package com.perftrace.service.csv;

public enum NormalizedSensorField {
    TIMESTAMP("Timestamp / Time"),
    DATE("Date"),
    CPU_TOTAL_LOAD("CPU Total Load (%)"),
    CPU_MAX_THREAD_LOAD("CPU Max Thread Load (%)"),
    CPU_TEMP("CPU Temperature (°C)"),
    CPU_CLOCK("CPU Clock (MHz)"),
    GPU_LOAD("GPU Load (%)"),
    GPU_TEMP("GPU Temperature (°C)"),
    GPU_CLOCK("GPU Clock (MHz)"),
    CPU_THROTTLE("CPU Thermal Throttle Flag"),
    GPU_THROTTLE("GPU Thermal Throttle Flag"),
    RAM_USAGE("RAM Usage (%)"),
    CPU_POWER("CPU Power (W)"),
    GPU_POWER("GPU Power (W)");

    private final String displayName;

    NormalizedSensorField(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
