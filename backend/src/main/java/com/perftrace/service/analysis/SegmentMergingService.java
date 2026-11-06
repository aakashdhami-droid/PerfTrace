package com.perftrace.service.analysis;

import com.perftrace.entity.Classification;
import com.perftrace.entity.DetectedSegment;
import com.perftrace.entity.TelemetrySample;
import com.perftrace.entity.ThrottleEvidence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Merges consecutive compatible time windows into continuous segments.
 * Applies short-segment smoothing (~20s) without suppressing thermal throttling transitions.
 */
@Service
public class SegmentMergingService {

    private final double smoothingSeconds;

    public SegmentMergingService(
        @Value("${perftrace.analysis.smoothing-seconds:20}") double smoothingSeconds
    ) {
        this.smoothingSeconds = smoothingSeconds;
    }

    public static class ClassifiedWindow {
        private final TimeWindow window;
        private final Classification classification;
        private final ThrottleEvidence throttleEvidence;
        private final String evidenceExplanation;

        public ClassifiedWindow(TimeWindow window, Classification classification,
                                ThrottleEvidence throttleEvidence, String evidenceExplanation) {
            this.window = window;
            this.classification = classification;
            this.throttleEvidence = throttleEvidence;
            this.evidenceExplanation = evidenceExplanation;
        }

        public TimeWindow getWindow() { return window; }
        public Classification getClassification() { return classification; }
        public ThrottleEvidence getThrottleEvidence() { return throttleEvidence; }
        public String getEvidenceExplanation() { return evidenceExplanation; }
    }

    public List<DetectedSegment> merge(List<ClassifiedWindow> windows) {
        List<DetectedSegment> result = new ArrayList<>();
        if (windows == null || windows.isEmpty()) return result;

        // Step 1: Initial contiguous merge into drafts that retain their source windows,
        // so aggregate metrics can be recomputed if smoothing later joins drafts.
        List<SegmentDraft> drafts = new ArrayList<>();
        SegmentDraft current = null;

        for (ClassifiedWindow cw : windows) {
            if (current == null || !canMerge(current, cw)) {
                if (current != null) drafts.add(current);
                current = new SegmentDraft(cw);
            } else {
                current.extend(cw);
            }
        }
        if (current != null) drafts.add(current);

        // Step 2: Short-segment smoothing
        List<SegmentDraft> smoothed = applySmoothing(drafts);

        // Step 3: Compute metrics once, after the final segment boundaries are known.
        for (SegmentDraft draft : smoothed) {
            finalizeSegmentMetrics(draft);
            result.add(draft.segment);
        }
        return result;
    }

    private boolean canMerge(SegmentDraft draft, ClassifiedWindow cw) {
        DetectedSegment segment = draft.segment;
        if (segment.getClassification() != cw.getClassification()) return false;
        // Temporal adjacency required
        if (Math.abs(segment.getEndOffsetSeconds() - cw.getWindow().getStartOffsetSeconds()) > 0.001) return false;
        return isThrottled(segment.getThrottleEvidence()) == isThrottled(cw.getThrottleEvidence());
    }

    private boolean isThrottled(ThrottleEvidence evidence) {
        return evidence == ThrottleEvidence.HARDWARE_FLAG || evidence == ThrottleEvidence.HEURISTIC_DETECTED;
    }

    private List<SegmentDraft> applySmoothing(List<SegmentDraft> drafts) {
        if (drafts.size() <= 2) return new ArrayList<>(drafts);

        List<SegmentDraft> smoothed = new ArrayList<>();
        int i = 0;

        while (i < drafts.size()) {
            SegmentDraft curr = drafts.get(i);

            // Check if current segment is brief (< smoothingSeconds)
            // AND surrounded by identical classification on both sides
            // AND not carrying a critical thermal throttling flag
            if (i > 0 && i < drafts.size() - 1 && curr.segment.getDurationSeconds() < smoothingSeconds) {
                SegmentDraft prev = smoothed.get(smoothed.size() - 1);
                SegmentDraft next = drafts.get(i + 1);

                boolean currThrottled = isThrottled(curr.segment.getThrottleEvidence());
                boolean prevThrottled = isThrottled(prev.segment.getThrottleEvidence());
                boolean nextThrottled = isThrottled(next.segment.getThrottleEvidence());

                // Temporal adjacency between prev->curr and curr->next
                boolean adjacent = Math.abs(prev.segment.getEndOffsetSeconds() - curr.segment.getStartOffsetSeconds()) < 0.001
                    && Math.abs(curr.segment.getEndOffsetSeconds() - next.segment.getStartOffsetSeconds()) < 0.001;

                if (!currThrottled && !prevThrottled && !nextThrottled
                        && prev.segment.getClassification() == next.segment.getClassification()
                        && adjacent
                        && prev.segment.getThrottleEvidence() == next.segment.getThrottleEvidence()) {
                    // Absorb curr and next into prev, retaining all source windows for metrics.
                    prev.absorb(curr, next);
                    i += 2;
                    continue;
                }
            }

            smoothed.add(curr);
            i++;
        }

        return smoothed;
    }

    /**
     * Mutable merge draft. Keeps one aggregate window instead of the source sample rows,
     * so averages, peaks and sample counts always describe the complete final segment
     * while memory remains independent of CSV row count.
     */
    private static final class SegmentDraft {
        private final DetectedSegment segment;
        private final TimeWindow metrics;

        private SegmentDraft(ClassifiedWindow cw) {
            this.segment = new DetectedSegment();
            this.segment.setStartOffsetSeconds(cw.getWindow().getStartOffsetSeconds());
            this.segment.setEndOffsetSeconds(cw.getWindow().getEndOffsetSeconds());
            this.segment.setDurationSeconds(round(cw.getWindow().getDurationSeconds()));
            this.segment.setClassification(cw.getClassification());
            this.segment.setThrottleEvidence(cw.getThrottleEvidence());
            this.segment.setEvidenceExplanation(cw.getEvidenceExplanation());
            this.metrics = new TimeWindow(
                cw.getWindow().getStartOffsetSeconds(),
                cw.getWindow().getEndOffsetSeconds()
            );
            this.metrics.addWindow(cw.getWindow());
        }

        private void extend(ClassifiedWindow cw) {
            setEnd(cw.getWindow().getEndOffsetSeconds());
            this.metrics.addWindow(cw.getWindow());
        }

        private void absorb(SegmentDraft... absorbed) {
            for (SegmentDraft draft : absorbed) {
                setEnd(draft.segment.getEndOffsetSeconds());
                this.metrics.addWindow(draft.metrics);
            }
        }

        private void setEnd(double endOffsetSeconds) {
            this.segment.setEndOffsetSeconds(endOffsetSeconds);
            this.segment.setDurationSeconds(round(endOffsetSeconds - this.segment.getStartOffsetSeconds()));
        }
    }

    private static void finalizeSegmentMetrics(SegmentDraft draft) {
        DetectedSegment segment = draft.segment;
        TimeWindow metrics = draft.metrics;

        segment.setSampleCount(metrics.getSampleCount());
        segment.setAvgCpuLoad(metrics.getAvgCpuTotalLoad());
        segment.setAvgGpuLoad(metrics.getAvgGpuLoad());
        segment.setAvgCpuMaxThread(metrics.getAvgCpuMaxThreadLoad());
        segment.setAvgCpuTempC(metrics.getAvgCpuTempC());
        segment.setAvgGpuTempC(metrics.getAvgGpuTempC());
        // Active-only clocks: idle samples must not drag the segment average down.
        segment.setAvgCpuClockMhz(metrics.getAvgCpuClockMhz());
        segment.setAvgGpuClockMhz(metrics.getAvgGpuClockMhz());
        segment.setPeakCpuTempC(metrics.getPeakCpuTempC());
        segment.setPeakGpuTempC(metrics.getPeakGpuTempC());
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
