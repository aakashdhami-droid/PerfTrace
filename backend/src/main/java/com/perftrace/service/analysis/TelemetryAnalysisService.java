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

    /**
     * Preliminary baseline uses a high percentile so sustained throttling cannot make the
     * baseline collapse to the throttled clock and hide its own regression.
     */
    private static final double PRELIMINARY_BASELINE_PERCENTILE = 0.95;

    /** Final baseline is the P90 of active windows that survived throttling screening. */
    private static final double FINAL_BASELINE_PERCENTILE = 0.90;

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
     * Executes the complete analysis pipeline on parsed telemetry samples. Retained for
     * unit tests and small callers; uploads use the streaming window path below so no raw
     * sample list is ever retained.
     */
    public AnalysisResult analyze(AnalysisRun run, List<TelemetrySample> samples) {
        if (samples == null || samples.isEmpty()) {
            return new AnalysisResult(List.of(), List.of("No samples available to analyze."));
        }
        return analyzeWindows(run, windowAggregator.aggregate(samples));
    }

    /**
     * Executes classification, thermal-baseline correction, segment merging and run-summary
     * population from aggregate windows only.
     */
    public AnalysisResult analyzeWindows(AnalysisRun run, List<TimeWindow> windows) {
        List<String> warnings = new ArrayList<>();

        if (windows == null || windows.isEmpty()) {
            return new AnalysisResult(List.of(), List.of("No samples available to analyze."));
        }

        long emptyWindows = windows.stream().filter(w -> w.getSampleCount() == 0).count();
        if (emptyWindows > 0) {
            warnings.add("Detected " + emptyWindows + " time window(s) without telemetry (sampling gaps).");
        }

        boolean hasThreadEvidence = windows.stream().anyMatch(w -> w.getAvgCpuMaxThreadLoad() != null);
        if (!hasThreadEvidence) {
            warnings.add("Busiest CPU thread load was unavailable. Windows that cannot be distinguished " +
                "between balanced and thread-limited are reported as UNKNOWN.");
        }

        // Step 1: Preliminary high-percentile baseline, used only to identify windows that
        // are likely already throttled. Those windows are then excluded from the final
        // baseline so a long throttled run cannot lower its own reference clock.
        ClockBaselines preliminary = computeBaselines(windows, PRELIMINARY_BASELINE_PERCENTILE, null);

        int excludedFromBaseline = 0;
        List<TimeWindow> eligible = new ArrayList<>(windows.size());
        for (TimeWindow window : windows) {
            if (window.getSampleCount() == 0 || idleDetector.isIdle(window) || window.hasHardwareThrottle()) {
                continue;
            }
            ThermalThrottleDetector.ThrottleResult preliminaryThrottle =
                thermalThrottleDetector.detect(window, preliminary.cpuClockMhz(), preliminary.gpuClockMhz());
            if (preliminaryThrottle.evidence() == ThrottleEvidence.HEURISTIC_DETECTED) {
                excludedFromBaseline++;
            } else {
                eligible.add(window);
            }
        }

        ClockBaselines finalBaselines = computeBaselines(eligible, FINAL_BASELINE_PERCENTILE, preliminary);
        if (excludedFromBaseline > 0) {
            warnings.add(excludedFromBaseline + " active window(s) were excluded from the thermal clock baseline " +
                "because their clocks were already depressed at high temperature.");
        }

        // Step 2: Classify each window and apply final thermal detection.
        List<SegmentMergingService.ClassifiedWindow> classifiedWindows = new ArrayList<>(windows.size());

        for (TimeWindow window : windows) {
            Classification classification;
            String evidence;

            Double cpuTotal = window.getAvgCpuTotalLoad();
            Double gpuLoad = window.getAvgGpuLoad();
            Double cpuMaxThread = window.getAvgCpuMaxThreadLoad();

            if (cpuTotal == null || gpuLoad == null) {
                classification = Classification.UNKNOWN;
                evidence = "Insufficient data: CPU total load and/or GPU load readings were missing during this window.";
            } else if (idleDetector.isIdle(window)) {
                classification = Classification.IDLE;
                evidence = idleDetector.buildEvidence(window);
            } else if (cpuBoundDetector.isCpuBound(window)) {
                classification = Classification.CPU_BOUND;
                evidence = cpuBoundDetector.buildEvidence(window);
            } else if (gpuBoundDetector.isGpuBound(window)) {
                classification = Classification.GPU_BOUND;
                evidence = gpuBoundDetector.buildEvidence(window);
            } else if (cpuMaxThread == null) {
                // GPU-bound requires proof of CPU headroom. Without the busiest-thread reading
                // we cannot distinguish "balanced" from a single-thread CPU limitation.
                classification = Classification.UNKNOWN;
                evidence = "Insufficient data: busiest CPU thread load is required to distinguish a balanced " +
                    "workload from a single-thread CPU limitation.";
            } else {
                // Balanced / Other: Active workload where neither CPU nor GPU hit our specific bottleneck heuristic.
                classification = Classification.BALANCED;
                evidence = String.format(
                    "Balanced/Other: Active workload (CPU total: %.1f%%, GPU: %.1f%%, busiest thread: %.1f%%) " +
                    "where no single resource crossed limitation thresholds. Note: This indicates balanced " +
                    "utilization, not proven optimal execution.",
                    cpuTotal, gpuLoad, cpuMaxThread
                );
            }

            ThermalThrottleDetector.ThrottleResult throttle =
                thermalThrottleDetector.detect(window, finalBaselines.cpuClockMhz(), finalBaselines.gpuClockMhz());

            classifiedWindows.add(new SegmentMergingService.ClassifiedWindow(
                window,
                classification,
                throttle.evidence(),
                evidence + " " + throttle.explanation()
            ));
        }

        // Step 3: Merge windows into segments.
        List<DetectedSegment> segments = segmentMergingService.merge(classifiedWindows);
        for (DetectedSegment seg : segments) {
            seg.setRun(run);
        }

        // Step 4: Populate run-level metrics from aggregate windows.
        populateRunSummary(run, windows, segments);

        return new AnalysisResult(segments, warnings);
    }

    /**
     * Computes a percentile baseline from active, non-hardware-throttled windows using a
     * fixed-size histogram. When {@code fallback} is supplied, empty histograms inherit its
     * values rather than leaving throttling detection without a reference.
     */
    private ClockBaselines computeBaselines(
        List<TimeWindow> windows,
        double percentile,
        ClockBaselines fallback
    ) {
        BoundedClockHistogram cpu = new BoundedClockHistogram();
        BoundedClockHistogram gpu = new BoundedClockHistogram();

        for (TimeWindow window : windows) {
            if (window.getSampleCount() == 0 || idleDetector.isIdle(window) || window.hasHardwareThrottle()) {
                continue;
            }
            cpu.add(window.getAvgCpuClockMhz());
            gpu.add(window.getAvgGpuClockMhz());
        }

        Double cpuBaseline = cpu.percentile(percentile);
        Double gpuBaseline = gpu.percentile(percentile);
        if (fallback != null) {
            if (cpuBaseline == null) cpuBaseline = fallback.cpuClockMhz();
            if (gpuBaseline == null) gpuBaseline = fallback.gpuClockMhz();
        }
        return new ClockBaselines(cpuBaseline, gpuBaseline);
    }

    private void populateRunSummary(
        AnalysisRun run,
        List<TimeWindow> windows,
        List<DetectedSegment> segments
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

        double totalDur = windows.get(windows.size() - 1).getEndOffsetSeconds();
        run.setTotalDurationSeconds(round(totalDur));

        // "Valid data duration" excludes empty gap windows; they carry no measurements.
        double validDur = windows.stream()
            .filter(w -> w.getSampleCount() > 0)
            .mapToDouble(TimeWindow::getDurationSeconds)
            .sum();
        run.setValidDataDurationSeconds(round(validDur));

        // Peak temperatures and active-workload clock averages. Weighting each window's
        // average by its active clock sample count keeps idle periods from diluting clocks.
        Double peakCpu = null;
        Double peakGpu = null;
        double sumCpuClock = 0; int countCpuClock = 0;
        double sumGpuClock = 0; int countGpuClock = 0;

        for (TimeWindow window : windows) {
            peakCpu = maxNullable(peakCpu, window.getPeakCpuTempC());
            peakGpu = maxNullable(peakGpu, window.getPeakGpuTempC());

            Double cpuClock = window.getAvgCpuClockMhz();
            if (cpuClock != null) {
                sumCpuClock += cpuClock * window.getActiveCpuClockSampleCount();
                countCpuClock += window.getActiveCpuClockSampleCount();
            }
            Double gpuClock = window.getAvgGpuClockMhz();
            if (gpuClock != null) {
                sumGpuClock += gpuClock * window.getActiveGpuClockSampleCount();
                countGpuClock += window.getActiveGpuClockSampleCount();
            }
        }

        run.setPeakCpuTempC(peakCpu);
        run.setPeakGpuTempC(peakGpu);
        if (countCpuClock > 0) run.setAvgCpuClockMhz(round(sumCpuClock / countCpuClock));
        if (countGpuClock > 0) run.setAvgGpuClockMhz(round(sumGpuClock / countGpuClock));
    }

    private static Double maxNullable(Double a, Double b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.max(a, b);
    }

    private double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private record ClockBaselines(Double cpuClockMhz, Double gpuClockMhz) {}
}
