package com.perftrace.service.analysis;

import org.springframework.stereotype.Component;

/**
 * Heuristic detector for CPU-limited workload periods.
 *
 * Conditions:
 * 1. GPU load is below 90% (GPU has headroom).
 * 2. Either busiest CPU thread > 90% OR CPU total load > 90%.
 *
 * Explains that GPU is likely waiting for draw calls, simulation ticks, or thread completion.
 */
@Component
public class CpuBoundDetector {

    public static final double GPU_LOAD_THRESHOLD = 90.0;
    public static final double CPU_BUSIEST_THREAD_THRESHOLD = 90.0;
    public static final double CPU_TOTAL_THRESHOLD = 90.0;

    public boolean isCpuBound(TimeWindow window) {
        Double gpuLoad = window.getAvgGpuLoad();
        Double cpuMaxThread = window.getAvgCpuMaxThreadLoad();
        Double cpuTotal = window.getAvgCpuTotalLoad();

        if (gpuLoad == null) return false;
        if (gpuLoad >= GPU_LOAD_THRESHOLD) return false;

        boolean threadSaturated = (cpuMaxThread != null && cpuMaxThread >= CPU_BUSIEST_THREAD_THRESHOLD);
        boolean totalSaturated = (cpuTotal != null && cpuTotal >= CPU_TOTAL_THRESHOLD);

        return threadSaturated || totalSaturated;
    }

    public String buildEvidence(TimeWindow window) {
        Double gpuLoad = window.getAvgGpuLoad();
        Double cpuMaxThread = window.getAvgCpuMaxThreadLoad();
        Double cpuTotal = window.getAvgCpuTotalLoad();

        StringBuilder sb = new StringBuilder("CPU-limited heuristic: ");
        sb.append(String.format("GPU load at %.1f%% (< %.0f%%), ", gpuLoad, GPU_LOAD_THRESHOLD));

        if (cpuMaxThread != null && cpuMaxThread >= CPU_BUSIEST_THREAD_THRESHOLD) {
            sb.append(String.format("busiest CPU thread saturated at %.1f%% (>= %.0f%%). ", cpuMaxThread, CPU_BUSIEST_THREAD_THRESHOLD));
        } else if (cpuTotal != null && cpuTotal >= CPU_TOTAL_THRESHOLD) {
            sb.append(String.format("total CPU utilization saturated at %.1f%% (>= %.0f%%). ", cpuTotal, CPU_TOTAL_THRESHOLD));
        }

        sb.append("Readings suggest the graphics processor had spare capacity while CPU execution constrained frame throughput.");
        return sb.toString();
    }
}
