package com.perftrace.entity;

/**
 * Classification assigned to a time window or segment.
 *
 * CPU_BOUND    — GPU underutilized while CPU is heavily loaded
 * GPU_BOUND    — GPU at high utilization, CPU relatively lighter
 * BALANCED     — No single-resource limitation identified (does NOT prove optimal)
 * IDLE         — Both CPU and GPU at very low utilization
 * UNKNOWN      — Insufficient sensor data to classify
 *
 * Important: BALANCED means "no implemented rule identified a clear limitation",
 * not that hardware is proven to be working optimally.
 */
public enum Classification {
    CPU_BOUND,
    GPU_BOUND,
    BALANCED,
    IDLE,
    UNKNOWN
}
