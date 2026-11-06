package com.perftrace.service.comparison;

import com.perftrace.dto.AnalysisRunDto;
import com.perftrace.dto.MetricComparisonDto;
import com.perftrace.dto.RunComparisonDto;
import com.perftrace.entity.AnalysisRun;
import com.perftrace.entity.RunStatus;
import com.perftrace.entity.WorkloadType;
import com.perftrace.exception.InvalidFileException;
import com.perftrace.exception.ResourceNotFoundException;
import com.perftrace.repository.AnalysisRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class RunComparisonService {

    private final AnalysisRunRepository runRepository;

    public RunComparisonService(AnalysisRunRepository runRepository) {
        this.runRepository = runRepository;
    }

    @Transactional(readOnly = true)
    public RunComparisonDto compareRuns(Long runIdA, Long runIdB, Long userId) {
        if (runIdA == null || runIdB == null) {
            throw new IllegalArgumentException("Both run IDs 'a' and 'b' must be provided for comparison.");
        }
        if (runIdA.equals(runIdB)) {
            throw new IllegalArgumentException("Cannot compare a run with itself (ID: " + runIdA + "). Please select two distinct runs.");
        }

        AnalysisRun runA = runRepository.findByIdAndUserId(runIdA, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Run A not found or you do not have permission to view it (ID: " + runIdA + ")"));

        AnalysisRun runB = runRepository.findByIdAndUserId(runIdB, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Run B not found or you do not have permission to view it (ID: " + runIdB + ")"));

        if (runA.getStatus() != RunStatus.COMPLETED || runB.getStatus() != RunStatus.COMPLETED) {
            throw new InvalidFileException("Both runs must be in COMPLETED status before they can be compared.");
        }

        AnalysisRunDto dtoA = toDto(runA);
        AnalysisRunDto dtoB = toDto(runB);

        RunComparisonDto comparison = new RunComparisonDto(dtoA, dtoB);

        // 1. Metric Comparisons
        buildMetricComparisons(comparison, runA, runB);

        // 2. Sensor Coverage Differences
        buildSensorCoverageDiffs(comparison, runA, runB);

        // 3. Workload Comparability Warnings
        buildComparabilityWarnings(comparison, runA, runB);

        // 4. Diagnostic Conclusion Note
        comparison.setConclusionNote(
            "Important Diagnostic Limitation: This comparison highlights shifts in observed hardware telemetry. " +
            "A reduction in temperature does not inherently prove higher performance without corroborating sustained clock speeds and throughput. " +
            "Hardware telemetry explains rule-based resource saturation, not absolute microarchitectural efficiency."
        );

        return comparison;
    }

    private void buildMetricComparisons(RunComparisonDto comparison, AnalysisRun a, AnalysisRun b) {
        List<MetricComparisonDto> m = comparison.getMetrics();

        // Total Duration
        m.add(new MetricComparisonDto(
            "Total Run Duration", "Duration", "seconds",
            a.getTotalDurationSeconds(), b.getTotalDurationSeconds(),
            "Total elapsed recording time from first to last sample."
        ));

        // Active Time (Denominator for active percentages)
        double activeA = computeActiveTime(a);
        double activeB = computeActiveTime(b);
        m.add(new MetricComparisonDto(
            "Active Workload Duration", "Duration", "seconds",
            activeA, activeB,
            "Observed telemetry duration minus idle time. Used as the denominator for active classification percentages."
        ));

        // Classification percentages. With no active time there is no meaningful percentage,
        // so null is reported instead of a misleading 0%.
        m.add(new MetricComparisonDto(
            "CPU-Bound Active Time", "Classification", "% of active time",
            percentOf(a.getCpuBoundSeconds(), activeA), percentOf(b.getCpuBoundSeconds(), activeB),
            "Portion of active workload where GPU had headroom and CPU was heavily loaded."
        ));

        m.add(new MetricComparisonDto(
            "GPU-Bound Active Time", "Classification", "% of active time",
            percentOf(a.getGpuBoundSeconds(), activeA), percentOf(b.getGpuBoundSeconds(), activeB),
            "Portion of active workload where GPU utilization was >= 95% with CPU thread headroom."
        ));

        m.add(new MetricComparisonDto(
            "Balanced / Other Active Time", "Classification", "% of active time",
            percentOf(a.getBalancedSeconds(), activeA), percentOf(b.getBalancedSeconds(), activeB),
            "Portion of active workload where neither CPU nor GPU crossed limitation heuristics."
        ));

        // Idle Time (% of observed run data)
        double baseTotalA = observedDuration(a);
        double baseTotalB = observedDuration(b);
        m.add(new MetricComparisonDto(
            "Idle Time", "Activity", "% of observed data",
            percentOf(a.getIdleSeconds(), baseTotalA), percentOf(b.getIdleSeconds(), baseTotalB),
            "Percentage of observed telemetry spent below 15% CPU and GPU load."
        ));

        // Throttle Duration
        m.add(new MetricComparisonDto(
            "Thermal Throttling Duration", "Thermal", "seconds",
            a.getThrottleDurationSeconds(), b.getThrottleDurationSeconds(),
            "Total duration with either hardware throttle flags or high-temp clock reductions."
        ));

        // Peak Temperatures
        m.add(new MetricComparisonDto(
            "Peak CPU Temperature", "Thermal", "°C",
            a.getPeakCpuTempC(), b.getPeakCpuTempC(),
            "Maximum package temperature observed."
        ));

        m.add(new MetricComparisonDto(
            "Peak GPU Temperature", "Thermal", "°C",
            a.getPeakGpuTempC(), b.getPeakGpuTempC(),
            "Maximum GPU core temperature observed."
        ));

        // Average Sustained Clocks
        m.add(new MetricComparisonDto(
            "Average Sustained CPU Clock", "Clocks", "MHz",
            a.getAvgCpuClockMhz(), b.getAvgCpuClockMhz(),
            "Average CPU frequency under active workload."
        ));

        m.add(new MetricComparisonDto(
            "Average Sustained GPU Clock", "Clocks", "MHz",
            a.getAvgGpuClockMhz(), b.getAvgGpuClockMhz(),
            "Average GPU frequency under active workload."
        ));
    }

    private void buildSensorCoverageDiffs(RunComparisonDto comparison, AnalysisRun a, AnalysisRun b) {
        Set<String> sensorsA = parseSensorSet(a.getRecognizedSensors());
        Set<String> sensorsB = parseSensorSet(b.getRecognizedSensors());

        for (String s : sensorsA) {
            if (!sensorsB.contains(s)) {
                comparison.getSensorCoverageDiffs().add("Sensor present in Run A but absent in Run B: " + s);
            }
        }
        for (String s : sensorsB) {
            if (!sensorsA.contains(s)) {
                comparison.getSensorCoverageDiffs().add("Sensor present in Run B but absent in Run A: " + s);
            }
        }
    }

    private void buildComparabilityWarnings(RunComparisonDto comparison, AnalysisRun a, AnalysisRun b) {
        List<String> w = comparison.getComparabilityWarnings();

        // 0. Workload metadata. PerfTrace never guesses the workload type; if the user did
        // not declare it, the comparison cannot verify that the runs represent the same work.
        WorkloadType workloadA = a.getWorkloadType() != null ? a.getWorkloadType() : WorkloadType.UNSPECIFIED;
        WorkloadType workloadB = b.getWorkloadType() != null ? b.getWorkloadType() : WorkloadType.UNSPECIFIED;
        if (workloadA != workloadB) {
            if (workloadA == WorkloadType.UNSPECIFIED || workloadB == WorkloadType.UNSPECIFIED) {
                w.add(String.format(
                    "Workload type is unspecified for %s. PerfTrace does not infer workload type from telemetry; " +
                    "declare it at upload time to make this comparison reproducible.",
                    workloadA == WorkloadType.UNSPECIFIED ? "Run A" : "Run B"
                ));
            } else {
                w.add(String.format(
                    "Different declared workload types: Run A is %s and Run B is %s. " +
                    "Telemetry shifts between different workload categories may not be comparable.",
                    workloadA, workloadB
                ));
            }
        } else if (workloadA == WorkloadType.UNSPECIFIED) {
            w.add("Workload type was not specified for either run. This comparison cannot verify workload equivalence; " +
                "use the workload selector on upload to make future comparisons reproducible.");
        }

        Double durA = a.getTotalDurationSeconds();
        Double durB = b.getTotalDurationSeconds();

        // 1. Duration disparity warning
        if (durA != null && durB != null && durA > 0 && durB > 0) {
            double ratio = Math.max(durA, durB) / Math.min(durA, durB);
            if (ratio > 1.30) {
                w.add(String.format(
                    "Run duration disparity: Run A is %.0fs and Run B is %.0fs (ratio %.1fx > 1.3x). " +
                    "Comparing percentage distributions across vastly different session lengths may introduce sampling bias.",
                    durA, durB, ratio
                ));
            }
        }

        // 2. Idle disparity warning
        Double idleA = a.getIdleSeconds();
        Double idleB = b.getIdleSeconds();
        double baseA = observedDuration(a);
        double baseB = observedDuration(b);
        if (idleA != null && idleB != null && baseA > 0 && baseB > 0) {
            double idleRatioA = clampFraction(idleA / baseA);
            double idleRatioB = clampFraction(idleB / baseB);
            if (Math.abs(idleRatioA - idleRatioB) > 0.25) {
                w.add(String.format(
                    "Idle state disparity: Run A spent %.1f%% idle while Run B spent %.1f%% idle. " +
                    "Workloads with differing idle ratios may reflect pauses or menu browsing rather than operational workload differences.",
                    idleRatioA * 100.0, idleRatioB * 100.0
                ));
            }
        }

        // 3. Sensor parity warning
        if (!comparison.getSensorCoverageDiffs().isEmpty()) {
            w.add("Sensor coverage mismatch: The runs logged different sensor subsets. " +
                  "Metrics relying on missing sensors cannot be compared directly.");
        }
    }

    private double computeActiveTime(AnalysisRun r) {
        double idle = getOrZero(r.getIdleSeconds());
        return Math.max(0.0, observedDuration(r) - idle);
    }

    /**
     * Duration covered by windows that actually contained telemetry. Falls back to the raw
     * elapsed duration for older rows that predate valid-data tracking. Empty gap windows
     * are deliberately excluded so sampling outages are not mislabelled as idle time.
     */
    private double observedDuration(AnalysisRun r) {
        double validData = getOrZero(r.getValidDataDurationSeconds());
        return validData > 0 ? validData : getOrZero(r.getTotalDurationSeconds());
    }

    private Double percentOf(Double numerator, double denominator) {
        if (denominator <= 0) return null;
        return round(Math.min(100.0, getOrZero(numerator) / denominator * 100.0));
    }

    private double clampFraction(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.min(1.0, Math.max(0.0, value));
    }

    private double getOrZero(Double v) {
        return v != null ? v : 0.0;
    }

    private double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private Set<String> parseSensorSet(String sensorString) {
        if (sensorString == null || sensorString.isBlank()) return Collections.emptySet();
        String[] parts = sensorString.split(",");
        Set<String> set = new HashSet<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isBlank()) set.add(t);
        }
        return set;
    }

    public AnalysisRunDto toDto(AnalysisRun run) {
        AnalysisRunDto dto = new AnalysisRunDto();
        dto.setId(run.getId());
        dto.setFileName(run.getFileName());
        dto.setDescription(run.getDescription());
        dto.setWorkloadType(run.getWorkloadType());
        dto.setStatus(run.getStatus());
        dto.setErrorMessage(run.getErrorMessage());
        dto.setTotalDurationSeconds(run.getTotalDurationSeconds());
        dto.setValidDataDurationSeconds(run.getValidDataDurationSeconds());
        dto.setCpuBoundSeconds(run.getCpuBoundSeconds());
        dto.setGpuBoundSeconds(run.getGpuBoundSeconds());
        dto.setBalancedSeconds(run.getBalancedSeconds());
        dto.setIdleSeconds(run.getIdleSeconds());
        dto.setUnknownSeconds(run.getUnknownSeconds());

        double active = computeActiveTime(run);
        dto.setCpuBoundActivePercent(percentOf(run.getCpuBoundSeconds(), active));
        dto.setGpuBoundActivePercent(percentOf(run.getGpuBoundSeconds(), active));
        dto.setBalancedActivePercent(percentOf(run.getBalancedSeconds(), active));

        double baseTotal = observedDuration(run);
        dto.setIdleTotalPercent(percentOf(run.getIdleSeconds(), baseTotal));
        dto.setThrottlePercent(percentOf(run.getThrottleDurationSeconds(), baseTotal));

        dto.setThrottleDurationSeconds(run.getThrottleDurationSeconds());
        dto.setHardwareFlagThrottleSeconds(run.getHardwareFlagThrottleSeconds());
        dto.setHeuristicThrottleSeconds(run.getHeuristicThrottleSeconds());

        dto.setPeakCpuTempC(run.getPeakCpuTempC());
        dto.setPeakGpuTempC(run.getPeakGpuTempC());
        dto.setAvgCpuClockMhz(run.getAvgCpuClockMhz());
        dto.setAvgGpuClockMhz(run.getAvgGpuClockMhz());

        dto.setTotalRows(run.getTotalRows());
        dto.setValidRows(run.getValidRows());
        dto.setRejectedRows(run.getRejectedRows());
        dto.setRecognizedSensors(run.getRecognizedSensors());
        dto.setMissingSensors(run.getMissingSensors());
        dto.setDataQualityWarnings(run.getDataQualityWarnings());
        dto.setCreatedAt(run.getCreatedAt());
        dto.setCompletedAt(run.getCompletedAt());

        return dto;
    }
}
