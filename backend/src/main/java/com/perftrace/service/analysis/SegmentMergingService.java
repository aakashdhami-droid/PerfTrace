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
        List<DetectedSegment> rawSegments = new ArrayList<>();
        if (windows.isEmpty()) return rawSegments;

        // Step 1: Initial contiguous merge
        DetectedSegment current = null;
        List<TimeWindow> segmentWindows = new ArrayList<>();

        for (ClassifiedWindow cw : windows) {
            if (current == null) {
                current = createSegment(cw);
                segmentWindows.add(cw.getWindow());
            } else if (canMerge(current, cw)) {
                // Extend current segment
                current.setEndOffsetSeconds(cw.getWindow().getEndOffsetSeconds());
                current.setDurationSeconds(Math.round((current.getEndOffsetSeconds() - current.getStartOffsetSeconds()) * 100.0) / 100.0);
                segmentWindows.add(cw.getWindow());
            } else {
                finalizeSegmentMetrics(current, segmentWindows);
                rawSegments.add(current);

                current = createSegment(cw);
                segmentWindows.clear();
                segmentWindows.add(cw.getWindow());
            }
        }

        if (current != null) {
            finalizeSegmentMetrics(current, segmentWindows);
            rawSegments.add(current);
        }

        // Step 2: Short-segment smoothing
        return applySmoothing(rawSegments);
    }

    private boolean canMerge(DetectedSegment segment, ClassifiedWindow cw) {
        if (segment.getClassification() != cw.getClassification()) return false;
        // Temporal adjacency required
        if (Math.abs(segment.getEndOffsetSeconds() - cw.getWindow().getStartOffsetSeconds()) > 0.001) return false;
        // Throttle compatibility
        boolean segmentThrottled = (segment.getThrottleEvidence() == ThrottleEvidence.HARDWARE_FLAG ||
                                    segment.getThrottleEvidence() == ThrottleEvidence.HEURISTIC_DETECTED);
        boolean windowThrottled = (cw.getThrottleEvidence() == ThrottleEvidence.HARDWARE_FLAG ||
                                   cw.getThrottleEvidence() == ThrottleEvidence.HEURISTIC_DETECTED);
        return segmentThrottled == windowThrottled;
    }

    private DetectedSegment createSegment(ClassifiedWindow cw) {
        DetectedSegment seg = new DetectedSegment();
        seg.setStartOffsetSeconds(cw.getWindow().getStartOffsetSeconds());
        seg.setEndOffsetSeconds(cw.getWindow().getEndOffsetSeconds());
        seg.setDurationSeconds(Math.round(cw.getWindow().getDurationSeconds() * 100.0) / 100.0);
        seg.setClassification(cw.getClassification());
        seg.setThrottleEvidence(cw.getThrottleEvidence());
        seg.setEvidenceExplanation(cw.getEvidenceExplanation());
        return seg;
    }

    private void finalizeSegmentMetrics(DetectedSegment segment, List<TimeWindow> windows) {
        List<TelemetrySample> allSamples = new ArrayList<>();
        for (TimeWindow w : windows) {
            allSamples.addAll(w.getSamples());
        }

        segment.setSampleCount(allSamples.size());
        if (allSamples.isEmpty()) return;

        double sumCpuLoad = 0; int countCpuLoad = 0;
        double sumGpuLoad = 0; int countGpuLoad = 0;
        double sumCpuThread = 0; int countCpuThread = 0;
        double sumCpuTemp = 0; int countCpuTemp = 0;
        double sumGpuTemp = 0; int countGpuTemp = 0;
        double sumCpuClock = 0; int countCpuClock = 0;
        double sumGpuClock = 0; int countGpuClock = 0;
        Double peakCpu = null;
        Double peakGpu = null;

        for (TelemetrySample s : allSamples) {
            if (s.getCpuTotalLoad() != null) { sumCpuLoad += s.getCpuTotalLoad(); countCpuLoad++; }
            if (s.getGpuLoad() != null) { sumGpuLoad += s.getGpuLoad(); countGpuLoad++; }
            if (s.getCpuMaxThreadLoad() != null) { sumCpuThread += s.getCpuMaxThreadLoad(); countCpuThread++; }
            if (s.getCpuTempC() != null) {
                sumCpuTemp += s.getCpuTempC(); countCpuTemp++;
                peakCpu = (peakCpu == null) ? s.getCpuTempC() : Math.max(peakCpu, s.getCpuTempC());
            }
            if (s.getGpuTempC() != null) {
                sumGpuTemp += s.getGpuTempC(); countGpuTemp++;
                peakGpu = (peakGpu == null) ? s.getGpuTempC() : Math.max(peakGpu, s.getGpuTempC());
            }
            if (s.getCpuClockMhz() != null) { sumCpuClock += s.getCpuClockMhz(); countCpuClock++; }
            if (s.getGpuClockMhz() != null) { sumGpuClock += s.getGpuClockMhz(); countGpuClock++; }
        }

        if (countCpuLoad > 0) segment.setAvgCpuLoad(round(sumCpuLoad / countCpuLoad));
        if (countGpuLoad > 0) segment.setAvgGpuLoad(round(sumGpuLoad / countGpuLoad));
        if (countCpuThread > 0) segment.setAvgCpuMaxThread(round(sumCpuThread / countCpuThread));
        if (countCpuTemp > 0) segment.setAvgCpuTempC(round(sumCpuTemp / countCpuTemp));
        if (countGpuTemp > 0) segment.setAvgGpuTempC(round(sumGpuTemp / countGpuTemp));
        if (countCpuClock > 0) segment.setAvgCpuClockMhz(round(sumCpuClock / countCpuClock));
        if (countGpuClock > 0) segment.setAvgGpuClockMhz(round(sumGpuClock / countGpuClock));
        segment.setPeakCpuTempC(peakCpu);
        segment.setPeakGpuTempC(peakGpu);
    }

    private List<DetectedSegment> applySmoothing(List<DetectedSegment> segments) {
        if (segments.size() <= 2) return segments;

        List<DetectedSegment> smoothed = new ArrayList<>();
        int i = 0;

        while (i < segments.size()) {
            DetectedSegment curr = segments.get(i);

            // Check if current segment is brief (< smoothingSeconds)
            // AND surrounded by identical classification on both sides
            // AND not carrying a critical thermal throttling flag
            if (i > 0 && i < segments.size() - 1 && curr.getDurationSeconds() < smoothingSeconds) {
                DetectedSegment prev = smoothed.get(smoothed.size() - 1);
                DetectedSegment next = segments.get(i + 1);

                boolean currThrottled = (curr.getThrottleEvidence() == ThrottleEvidence.HARDWARE_FLAG ||
                                        curr.getThrottleEvidence() == ThrottleEvidence.HEURISTIC_DETECTED);
                boolean prevThrottled = (prev.getThrottleEvidence() == ThrottleEvidence.HARDWARE_FLAG ||
                                        prev.getThrottleEvidence() == ThrottleEvidence.HEURISTIC_DETECTED);
                boolean nextThrottled = (next.getThrottleEvidence() == ThrottleEvidence.HARDWARE_FLAG ||
                                        next.getThrottleEvidence() == ThrottleEvidence.HEURISTIC_DETECTED);

                // Temporal adjacency between prev->curr and curr->next
                boolean adjacent = Math.abs(prev.getEndOffsetSeconds() - curr.getStartOffsetSeconds()) < 0.001
                    && Math.abs(curr.getEndOffsetSeconds() - next.getStartOffsetSeconds()) < 0.001;

                if (!currThrottled && !prevThrottled && !nextThrottled
                        && prev.getClassification() == next.getClassification()
                        && adjacent
                        && prev.getThrottleEvidence() == next.getThrottleEvidence()) {
                    // Absorb curr and next into prev
                    prev.setEndOffsetSeconds(next.getEndOffsetSeconds());
                    prev.setDurationSeconds(Math.round((prev.getEndOffsetSeconds() - prev.getStartOffsetSeconds()) * 100.0) / 100.0);
                    prev.setSampleCount(prev.getSampleCount() + curr.getSampleCount() + next.getSampleCount());
                    i += 2;
                    continue;
                }
            }

            smoothed.add(curr);
            i++;
        }

        return smoothed;
    }

    private double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
