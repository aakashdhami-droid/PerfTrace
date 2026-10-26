package com.perftrace.entity;

/**
 * Describes how thermal throttling was detected.
 *
 * NONE               — No throttling evidence found
 * HARDWARE_FLAG      — Explicit throttling flag from hardware (most reliable)
 * HEURISTIC_DETECTED — Inferred from temperature + clock speed reduction (less certain)
 * INSUFFICIENT_DATA  — Cannot determine; required sensors missing
 */
public enum ThrottleEvidence {
    NONE,
    HARDWARE_FLAG,
    HEURISTIC_DETECTED,
    INSUFFICIENT_DATA
}
