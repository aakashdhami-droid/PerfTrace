package com.perftrace.entity;

import java.util.Locale;

/**
 * User-supplied workload category. PerfTrace never infers this automatically from sensor
 * values; uploads may leave it {@link #UNSPECIFIED} and the comparison view will warn that
 * workload equivalence cannot be verified.
 */
public enum WorkloadType {
    UNSPECIFIED,
    GAMING,
    CPU_RENDER,
    GPU_RENDER,
    STREAMING,
    PRODUCTIVITY,
    SYNTHETIC_BENCHMARK,
    OTHER;

    public static WorkloadType fromString(String raw) {
        if (raw == null || raw.isBlank()) return UNSPECIFIED;
        String normalized = raw.trim().toUpperCase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_');
        return switch (normalized) {
            case "UNSPECIFIED", "UNKNOWN", "NONE" -> UNSPECIFIED;
            case "GAMING", "GAME" -> GAMING;
            case "CPU_RENDER", "CPU_RENDERING" -> CPU_RENDER;
            case "GPU_RENDER", "GPU_RENDERING" -> GPU_RENDER;
            case "STREAMING", "STREAM" -> STREAMING;
            case "PRODUCTIVITY", "OFFICE" -> PRODUCTIVITY;
            case "SYNTHETIC_BENCHMARK", "BENCHMARK", "SYNTHETIC" -> SYNTHETIC_BENCHMARK;
            case "OTHER" -> OTHER;
            default -> throw new IllegalArgumentException(
                "Unsupported workload type '" + raw + "'. Expected one of: UNSPECIFIED, GAMING, CPU_RENDER, " +
                "GPU_RENDER, STREAMING, PRODUCTIVITY, SYNTHETIC_BENCHMARK, OTHER.");
        };
    }
}
