package com.perftrace.service.analysis;

import org.springframework.stereotype.Component;

/**
 * Heuristic detector for GPU-limited workload periods.
 *
 * Conditions:
 * 1. GPU load is at least 95%.
 * 2. Busiest CPU thread utilization is below 85% (CPU is keeping up).
 */
@Component
public class GpuBoundDetector {

    public static final double GPU_LOAD_THRESHOLD = 95.0;
    public static final double CPU_MAX_THREAD_THRESHOLD = 85.0;

    public boolean isGpuBound(TimeWindow window) {
        Double gpuLoad = window.getAvgGpuLoad();
        Double cpuMaxThread = window.getAvgCpuMaxThreadLoad();

        if (gpuLoad == null) return false;
        if (gpuLoad < GPU_LOAD_THRESHOLD) return false;

        // Require CPU headroom evidence; missing busiest-thread data means insufficient evidence.
        if (cpuMaxThread == null) return false;
        if (cpuMaxThread >= CPU_MAX_THREAD_THRESHOLD) return false;

        return true;
    }

    public String buildEvidence(TimeWindow window) {
        Double gpuLoad = window.getAvgGpuLoad();
        Double cpuMaxThread = window.getAvgCpuMaxThreadLoad();

        StringBuilder sb = new StringBuilder("GPU-limited heuristic: ");
        sb.append(String.format("GPU load at %.1f%% (>= %.0f%%), ", gpuLoad, GPU_LOAD_THRESHOLD));

        if (cpuMaxThread != null) {
            sb.append(String.format("busiest CPU thread at %.1f%% (< %.0f%%). ", cpuMaxThread, CPU_MAX_THREAD_THRESHOLD));
        }
        sb.append("Readings indicate the graphics pipeline was running near maximum capacity without severe CPU scheduling bottlenecks.");
        return sb.toString();
    }
}
