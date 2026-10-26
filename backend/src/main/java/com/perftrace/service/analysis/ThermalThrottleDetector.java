package com.perftrace.service.analysis;

import com.perftrace.entity.ThrottleEvidence;
import org.springframework.stereotype.Component;

/**
 * Detects thermal throttling by prioritizing hardware-level flags,
 * or falling back to a conservative multi-sensor heuristic.
 *
 * Never claims throttling from temperature alone.
 */
@Component
public class ThermalThrottleDetector {

    public static final double CPU_TEMP_THRESHOLD_C = 90.0;
    public static final double GPU_TEMP_THRESHOLD_C = 83.0;
    public static final double ACTIVE_LOAD_THRESHOLD = 50.0;
    public static final double CLOCK_DROP_RATIO = 0.88; // >12% clock reduction under heat

    public record ThrottleResult(ThrottleEvidence evidence, String explanation) {}

    public ThrottleResult detect(TimeWindow window, Double baselineCpuClockMhz, Double baselineGpuClockMhz) {
        // Priority 1: Hardware-reported thermal throttle flag
        if (window.hasHardwareThrottle()) {
            StringBuilder sb = new StringBuilder("Confirmed thermal throttling: Hardware register explicitly flagged throttling (");
            if (window.getCpuHardwareThrottleCount() > 0) {
                sb.append("CPU thermal throttling registered ").append(window.getCpuHardwareThrottleCount()).append(" times; ");
            }
            if (window.getGpuHardwareThrottleCount() > 0) {
                sb.append("GPU thermal throttling registered ").append(window.getGpuHardwareThrottleCount()).append(" times; ");
            }
            sb.append(").");
            return new ThrottleResult(ThrottleEvidence.HARDWARE_FLAG, sb.toString());
        }

        // Check if hardware flags were monitored but never tripped
        if (window.getThrottleFlagObservationCount() > 0) {
            return new ThrottleResult(ThrottleEvidence.NONE, "No thermal throttling flagged by hardware sensors.");
        }

        // Priority 2: Conservative heuristic fallback when hardware flags are missing
        Double cpuTemp = window.getAvgCpuTempC();
        Double gpuTemp = window.getAvgGpuTempC();
        Double cpuLoad = window.getAvgCpuTotalLoad();
        Double gpuLoad = window.getAvgGpuLoad();
        Double cpuClock = window.getAvgCpuClockMhz();
        Double gpuClock = window.getAvgGpuClockMhz();

        // If no temperature sensors are present at all
        if (cpuTemp == null && gpuTemp == null) {
            return new ThrottleResult(ThrottleEvidence.INSUFFICIENT_DATA,
                "Thermal throttling status unknown: No CPU or GPU temperature sensors were available in log.");
        }

        boolean cpuThrottlingLikely = false;
        boolean gpuThrottlingLikely = false;
        StringBuilder heuristicEvidence = new StringBuilder();

        // CPU heuristic: High temp (>=90C) + Active workload (>=50%) + Clock depressed relative to baseline
        if (cpuTemp != null && cpuTemp >= CPU_TEMP_THRESHOLD_C && cpuLoad != null && cpuLoad >= ACTIVE_LOAD_THRESHOLD) {
            if (baselineCpuClockMhz != null && cpuClock != null && cpuClock < (baselineCpuClockMhz * CLOCK_DROP_RATIO)) {
                cpuThrottlingLikely = true;
                heuristicEvidence.append(String.format(
                    "Potential CPU thermal throttling inferred: Package temperature reached %.1f°C (>= %.0f°C) under %.1f%% load, " +
                    "with sustained clock speed depressed to %.0f MHz (baseline %.0f MHz, >12%% drop). ",
                    cpuTemp, CPU_TEMP_THRESHOLD_C, cpuLoad, cpuClock, baselineCpuClockMhz
                ));
            }
        }

        // GPU heuristic: High temp (>=83C) + Active workload (>=50%) + Clock depressed relative to baseline
        if (gpuTemp != null && gpuTemp >= GPU_TEMP_THRESHOLD_C && gpuLoad != null && gpuLoad >= ACTIVE_LOAD_THRESHOLD) {
            if (baselineGpuClockMhz != null && gpuClock != null && gpuClock < (baselineGpuClockMhz * CLOCK_DROP_RATIO)) {
                gpuThrottlingLikely = true;
                heuristicEvidence.append(String.format(
                    "Potential GPU thermal throttling inferred: Core temperature reached %.1f°C (>= %.0f°C) under %.1f%% load, " +
                    "with sustained clock depressed to %.0f MHz (baseline %.0f MHz). ",
                    gpuTemp, GPU_TEMP_THRESHOLD_C, gpuLoad, gpuClock, baselineGpuClockMhz
                ));
            }
        }

        if (cpuThrottlingLikely || gpuThrottlingLikely) {
            heuristicEvidence.append("Note: Inferred by heuristic rule; explicit hardware throttling flags were not logged.");
            return new ThrottleResult(ThrottleEvidence.HEURISTIC_DETECTED, heuristicEvidence.toString());
        }

        // If temperatures are high but clock was NOT depressed or workload was low:
        if ((cpuTemp != null && cpuTemp >= CPU_TEMP_THRESHOLD_C) || (gpuTemp != null && gpuTemp >= GPU_TEMP_THRESHOLD_C)) {
            return new ThrottleResult(ThrottleEvidence.NONE,
                "Elevated temperature observed without evidence of frequency throttling or active workload constraint.");
        }

        return new ThrottleResult(ThrottleEvidence.NONE, "No thermal throttling detected.");
    }
}
