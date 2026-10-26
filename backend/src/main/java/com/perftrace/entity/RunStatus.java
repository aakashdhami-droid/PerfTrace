package com.perftrace.entity;

/**
 * Tracks the lifecycle of a CSV upload and analysis.
 *
 * UPLOADING  → file is being received/parsed
 * ANALYZING  → window aggregation and detection in progress
 * COMPLETED  → analysis finished successfully
 * FAILED     → processing failed; errorMessage explains why
 *
 * A failed upload must not appear as COMPLETED.
 */
public enum RunStatus {
    UPLOADING,
    ANALYZING,
    COMPLETED,
    FAILED
}
