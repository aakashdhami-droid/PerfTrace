package com.perftrace.service.analysis;

import com.perftrace.entity.AnalysisRun;
import com.perftrace.entity.Classification;
import com.perftrace.entity.DetectedSegment;
import com.perftrace.entity.TelemetrySample;
import com.perftrace.entity.ThrottleEvidence;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TelemetryAnalysisService {

    private final TimeWindowAggregator windowAggregator;
    private final CpuBoundDetector cpuBoundDetector;
    private final GpuBoundDetector gpuBoundDetector;
    private final IdleDetector idleDetector;
    private final ThermalThrottleDetector thermalThrottleDetector;
    private final SegmentMergingService segmentMergingService;

    public TelemetryAnalysisService(
        TimeWindowAggregator windowAggregator,
        CpuBoundDetector cpuBoundDetector,
        GpuBoundDetector gpuBoundDetector,
        IdleDetector idleDetector,
        ThermalThrottleDetector thermalThrottleDetector,
        SegmentMergingService segmentMergingService
    ) {
        this.windowAggregator = windowAggregator;
        this.cpuBoundDetector = cpuBoundDetector;
        this.gpuBoundDetector = gpuBoundDetector;
        this.idleDetector = idleDetector;
        this.thermalThrottleDetector = thermalThrottleDetector;
        this.segmentMergingService = segmentMergingService;
    }

    public static class AnalysisResult {
        private final List<DetectedSegment> segments;
        private final List<String> warnings;

        public AnalysisResult(List<DetectedSegment> segments, List<String> warnings) {
            this.segments = segments;
            this.warnings = warnings;
        }

        public List<DetectedSegment> getSegments() { return segments; }
        public List<String> getWarnings() { return warnings; }
    }

    /**
     * Executes the complete analysis pipeline on parsed telemetry samples and populates run summaries.
     */
    public AnalysisResult analyze(AnalysisRun run, List<TelemetrySample> samples) {
        List<String> warnings = new ArrayList<>();

        if (samples.isEmpty()) {
            return new AnalysisResult(List.of(), List.of("No samples available to analyze."));
        }

        // Step 1: Divide into 10s windows
        List<TimeWindow> windows = windowAggregator.aggregate(samples);

        // Check for data gaps in windows
        long gapCount = windows.stream().filter(TimeWindow::isHasDataGap).count();
        if (gapCount > 0) {
            warnings.add("Detected " + gapCount + " telemetry sampling gaps (>15s pause between consecutive logs).");
        }

        // Step 2: Calculate initial baseline clocks across non-idle windows
        Double baselineCpuClock = computeBaselineClock(windows, true);
        Double baselineGpuClock = computeBaselineClock(windows, false);

        // Step 3: Classify each window and detect throttling
        List<SegmentMergingService.ClassifiedWindow> classifiedWindows = new ArrayList<>();

        for (TimeWindow window : windows) {
            Classification classification;
            String evidence;

            // Insufficient data safeguard
            if (window.getAvgCpuTotalLoad() == null && window.getAvgGpuLoad() == null) {
                classification = Classification.UNKNOWN;
                evidence = "Insufficient data: Both CPU and GPU load readings were missing during this window.";
            } else if (idleDetector.isIdle(window)) {
                classification = Classification.IDLE;
                evidence = idleDetector.buildEvidence(window);
            } else if (cpuBoundDetector.isCpuBound(window)) {
                classification = Classification.CPU_BOUND;
                evidence = cpuBoundDetector.buildEvidence(window);
            } else if (gpuBoundDetector.isGpuBound(window)) {
                classification = Classification.GPU_BOUND;
                evidence = gpuBoundDetector.buildEvidence(window);
            } else {
                // Balanced / Other: Active workload where neither CPU nor GPU hit our specific bottleneck heuristic
                classification = Classification.BALANCED;
                evidence = String.format(
                    "Balanced/Other: Active workload (CPU total: %.1f%%, GPU: %.1f%%) where no single resource " +
                    "crossed limitation thresholds. Note: This indicates balanced utilization, not proven optimal execution.",
                    window.getAvgCpuTotalLoad() != null ? window.getAvgCpuTotalLoad() : 0.0,
                    window.getAvgGpuLoad() != null ? window.getAvgGpuLoad() : 0.0
                );
            }

            ThermalThrottleDetector.ThrottleResult throttle =
                thermalThrottleDetector.detect(window, baselineCpuClock, baselineGpuClock);

            classifiedWindows.add(new SegmentMergingService.ClassifiedWindow(
                window,
                classification,
                throttle.evidence(),
                evidence + " " + throttle.explanation()
            ));
        }

        // Step 4: Merge windows into segments
        List<DetectedSegment> segments = segmentMergingService.merge(classifiedWindows);
        for (DetectedSegment seg : segments) {
            seg.setRun(run);
        }

        // Step 5: Populate Run-level metrics
        populateRunSummary(run, windows, segments, samples);

        if (run.getDataQualityWarnings() != null && !run.getDataQualityWarnings().isBlank()) {
            warnings.add(run.getDataQualityWarnings());
        }

        return new AnalysisResult(segments, warnings);
    }

    private void populateRunSummary(
        AnalysisRun run,
        List<TimeWindow> windows,
        List<DetectedSegment> segments,
        List<TelemetrySample> samples
    ) {
        double cpuBoundSec = 0;
        double gpuBoundSec = 0;
        double balancedSec = 0;
        double idleSec = 0;
        double unknownSec = 0;

        double hardwareThrottleSec = 0;
        double heuristicThrottleSec = 0;

        for (DetectedSegment s : segments) {
            double dur = s.getDurationSeconds();
            switch (s.getClassification()) {
                case CPU_BOUND -> cpuBoundSec += dur;
                case GPU_BOUND -> gpuBoundSec += dur;
                case BALANCED -> balancedSec += dur;
                case IDLE -> idleSec += dur;
                case UNKNOWN -> unknownSec += dur;
            }

            if (s.getThrottleEvidence() == ThrottleEvidence.HARDWARE_FLAG) {
                hardwareThrottleSec += dur;
            } else if (s.getThrottleEvidence() == ThrottleEvidence.HEURISTIC_DETECTED) {
                heuristicThrottleSec += dur;
            }
        }

        run.setCpuBoundSeconds(round(cpuBoundSec));
        run.setGpuBoundSeconds(round(gpuBoundSec));
        run.setBalancedSeconds(round(balancedSec));
        run.setIdleSeconds(round(idleSec));
        run.setUnknownSeconds(round(unknownSec));

        run.setHardwareFlagThrottleSeconds(round(hardwareThrottleSec));
        run.setHeuristicThrottleSeconds(round(heuristicThrottleSec));
        run.setThrottleDurationSeconds(round(hardwareThrottleSec + heuristicThrottleSec));

        double totalDur = samples.isEmpty() ? 0.0 : samples.get(samples.size() - 1).getTimeOffsetSeconds();
        run.setTotalDurationSeconds(round(totalDur));
        run.setValidDataDurationSeconds(round(cpuBoundSec + gpuBoundSec + balancedSec + idleSec + unknownSec));

        // Peak temperatures and average sustained clocks
        Double peakCpu = null;
        Double peakGpu = null;
        double sumCpuClock = 0; int countCpuClock = 0;
        double sumGpuClock = 0; int countGpuClock = 0;

        for (TelemetrySample s : samples) {
            if (s.getCpuTempC() != null) {
                peakCpu = (peakCpu == null) ? s.getCpuTempC() : Math.max(peakCpu, s.getCpuTempC());
            }
            if (s.getGpuTempC() != null) {
                peakGpu = (peakGpu == null) ? s.getGpuTempC() : Math.max(peakGpu, s.getGpuTempC());
            }
            if (s.getCpuClockMhz() != null && s.getCpuClockMhz() > 500) {
                sumCpuClock += s.getCpuClockMhz();
                countCpuClock++;
            }
            if (s.getGpuClockMhz() != null && s.getGpuClockMhz() > 200) {
                sumGpuClock += s.getGpuClockMhz();
                countGpuClock++;
            }
        }

        run.setPeakCpuTempC(peakCpu);
        run.setPeakGpuTempC(peakGpu);
        if (countCpuClock > 0) run.setAvgCpuClockMhz(round(sumCpuClock / countCpuClock));
        if (countGpuClock > 0) run.setAvgGpuClockMhz(round(sumGpuClock / countGpuClock));
    }

    private Double computeBaselineClock(List<TimeWindow> windows, boolean cpu) {
        double sum = 0;
        int count = 0;
        for (TimeWindow w : windows) {
            if (!idleDetector.isIdle(w)) {
                Double clk = cpu ? w.getAvgCpuClockMhz() : w.getAvgGpuClockMhz();
                if (clk != null) {
                    sum += clk;
                    count++;
                }
            }
        }
        return count > 0 ? (sum / count) : null;
    }

    private double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
