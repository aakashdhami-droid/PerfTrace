package com.perftrace.service.analysis;

import org.springframework.stereotype.Component;

/**
 * Detector for idle or near-idle periods.
 *
 * Conditions:
 * 1. CPU total utilization is below 15%.
 * 2. GPU load is below 15%.
 */
@Component
public class IdleDetector {

    public static final double CPU_IDLE_THRESHOLD = 15.0;
    public static final double GPU_IDLE_THRESHOLD = 15.0;

    public boolean isIdle(TimeWindow window) {
        Double cpuTotal = window.getAvgCpuTotalLoad();
        Double gpuLoad = window.getAvgGpuLoad();

        if (cpuTotal == null || gpuLoad == null) return false;

        return cpuTotal < CPU_IDLE_THRESHOLD && gpuLoad < GPU_IDLE_THRESHOLD;
    }

    public String buildEvidence(TimeWindow window) {
        return String.format(
            "Idle/Low activity: CPU total utilization is %.1f%% (< %.0f%%) and GPU load is %.1f%% (< %.0f%%). " +
            "Workload was likely paused, idling, or navigating menus.",
            window.getAvgCpuTotalLoad(), CPU_IDLE_THRESHOLD,
            window.getAvgGpuLoad(), GPU_IDLE_THRESHOLD
        );
    }
}
