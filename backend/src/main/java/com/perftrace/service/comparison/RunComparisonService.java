package com.perftrace.service.comparison;

import com.perftrace.dto.AnalysisRunDto;
import com.perftrace.dto.MetricComparisonDto;
import com.perftrace.dto.RunComparisonDto;
import com.perftrace.entity.AnalysisRun;
import com.perftrace.entity.RunStatus;
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
            "Total duration minus idle time. Used as denominator for active classification percentages."
        ));

        // CPU-bound
        double cpuPctA = activeA > 0 ? Math.min(100.0, getOrZero(a.getCpuBoundSeconds()) / activeA * 100.0) : 0.0;
        double cpuPctB = activeB > 0 ? Math.min(100.0, getOrZero(b.getCpuBoundSeconds()) / activeB * 100.0) : 0.0;
        m.add(new MetricComparisonDto(
            "CPU-Bound Active Time", "Classification", "% of active time",
            round(cpuPctA), round(cpuPctB),
            "Portion of active workload where GPU had headroom and CPU was heavily loaded."
        ));

        // GPU-bound
        double gpuPctA = activeA > 0 ? Math.min(100.0, getOrZero(a.getGpuBoundSeconds()) / activeA * 100.0) : 0.0;
        double gpuPctB = activeB > 0 ? Math.min(100.0, getOrZero(b.getGpuBoundSeconds()) / activeB * 100.0) : 0.0;
        m.add(new MetricComparisonDto(
            "GPU-Bound Active Time", "Classification", "% of active time",
            round(gpuPctA), round(gpuPctB),
            "Portion of active workload where GPU utilization was >= 95%."
        ));

        // Balanced/Other
        double balPctA = activeA > 0 ? Math.min(100.0, getOrZero(a.getBalancedSeconds()) / activeA * 100.0) : 0.0;
        double balPctB = activeB > 0 ? Math.min(100.0, getOrZero(b.getBalancedSeconds()) / activeB * 100.0) : 0.0;
        m.add(new MetricComparisonDto(
            "Balanced / Other Active Time", "Classification", "% of active time",
            round(balPctA), round(balPctB),
            "Portion of active workload where neither CPU nor GPU crossed limitation heuristics."
        ));

        // Idle Time (% of total run)
        double baseTotalA = Math.max(getOrZero(a.getValidDataDurationSeconds()), getOrZero(a.getTotalDurationSeconds()));
        double baseTotalB = Math.max(getOrZero(b.getValidDataDurationSeconds()), getOrZero(b.getTotalDurationSeconds()));
        double idlePctA = baseTotalA > 0 ? Math.min(100.0, getOrZero(a.getIdleSeconds()) / baseTotalA * 100.0) : 0.0;
        double idlePctB = baseTotalB > 0 ? Math.min(100.0, getOrZero(b.getIdleSeconds()) / baseTotalB * 100.0) : 0.0;
        m.add(new MetricComparisonDto(
            "Idle Time", "Activity", "% of total run",
            round(idlePctA), round(idlePctB),
            "Percentage of total session spent below 15% CPU and GPU load."
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
        if (idleA != null && idleB != null && durA != null && durB != null) {
            double idleRatioA = idleA / durA;
            double idleRatioB = idleB / durB;
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
        double validData = getOrZero(r.getValidDataDurationSeconds());
        double total = getOrZero(r.getTotalDurationSeconds());
        double base = validData > 0 ? validData : total;
        double idle = getOrZero(r.getIdleSeconds());
        return Math.max(0.0, base - idle);
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
        if (active > 0) {
            dto.setCpuBoundActivePercent(round(Math.min(100.0, getOrZero(run.getCpuBoundSeconds()) / active * 100.0)));
            dto.setGpuBoundActivePercent(round(Math.min(100.0, getOrZero(run.getGpuBoundSeconds()) / active * 100.0)));
            dto.setBalancedActivePercent(round(Math.min(100.0, getOrZero(run.getBalancedSeconds()) / active * 100.0)));
        }

        double baseTotal = Math.max(getOrZero(run.getValidDataDurationSeconds()), getOrZero(run.getTotalDurationSeconds()));
        if (baseTotal > 0) {
            dto.setIdleTotalPercent(round(Math.min(100.0, getOrZero(run.getIdleSeconds()) / baseTotal * 100.0)));
            dto.setThrottlePercent(round(Math.min(100.0, getOrZero(run.getThrottleDurationSeconds()) / baseTotal * 100.0)));
        }

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
