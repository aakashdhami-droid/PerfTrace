package com.perftrace.entity;

import jakarta.persistence.*;

/**
 * A single row of parsed telemetry data from the CSV file.
 * Stores normalized sensor readings at a specific time offset.
 *
 * Null values mean the sensor was not available or the reading was invalid
 * — they are NOT treated as zero.
 */
@Entity
@Table(name = "telemetry_samples", indexes = {
    @Index(name = "idx_sample_run_time", columnList = "run_id, time_offset_seconds")
})
public class TelemetrySample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private AnalysisRun run;

    /** Seconds since the start of the recording */
    @Column(name = "time_offset_seconds", nullable = false)
    private double timeOffsetSeconds;

    // ── CPU Sensors ──

    /** CPU total utilization percentage (0–100) */
    @Column(name = "cpu_total_load")
    private Double cpuTotalLoad;

    /** Busiest single CPU thread/core utilization (0–100) */
    @Column(name = "cpu_max_thread_load")
    private Double cpuMaxThreadLoad;

    /** CPU package temperature in Celsius */
    @Column(name = "cpu_temp_c")
    private Double cpuTempC;

    /** CPU clock speed in MHz */
    @Column(name = "cpu_clock_mhz")
    private Double cpuClockMhz;

    // ── GPU Sensors ──

    /** GPU utilization percentage (0–100) */
    @Column(name = "gpu_load")
    private Double gpuLoad;

    /** GPU temperature in Celsius */
    @Column(name = "gpu_temp_c")
    private Double gpuTempC;

    /** GPU clock speed in MHz */
    @Column(name = "gpu_clock_mhz")
    private Double gpuClockMhz;

    // ── Thermal Throttling ──

    /** Hardware-reported thermal throttle flag (true = throttling active) */
    @Column(name = "cpu_throttle_flag")
    private Boolean cpuThrottleFlag;

    @Column(name = "gpu_throttle_flag")
    private Boolean gpuThrottleFlag;

    // ── Memory ──

    /** RAM usage percentage */
    @Column(name = "ram_usage_percent")
    private Double ramUsagePercent;

    // ── Power ──

    /** CPU package power in watts */
    @Column(name = "cpu_power_w")
    private Double cpuPowerW;

    /** GPU power in watts */
    @Column(name = "gpu_power_w")
    private Double gpuPowerW;

    // ── Getters and Setters ──

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public AnalysisRun getRun() { return run; }
    public void setRun(AnalysisRun run) { this.run = run; }

    public double getTimeOffsetSeconds() { return timeOffsetSeconds; }
    public void setTimeOffsetSeconds(double v) { this.timeOffsetSeconds = v; }

    public Double getCpuTotalLoad() { return cpuTotalLoad; }
    public void setCpuTotalLoad(Double v) { this.cpuTotalLoad = v; }

    public Double getCpuMaxThreadLoad() { return cpuMaxThreadLoad; }
    public void setCpuMaxThreadLoad(Double v) { this.cpuMaxThreadLoad = v; }

    public Double getCpuTempC() { return cpuTempC; }
    public void setCpuTempC(Double v) { this.cpuTempC = v; }

    public Double getCpuClockMhz() { return cpuClockMhz; }
    public void setCpuClockMhz(Double v) { this.cpuClockMhz = v; }

    public Double getGpuLoad() { return gpuLoad; }
    public void setGpuLoad(Double v) { this.gpuLoad = v; }

    public Double getGpuTempC() { return gpuTempC; }
    public void setGpuTempC(Double v) { this.gpuTempC = v; }

    public Double getGpuClockMhz() { return gpuClockMhz; }
    public void setGpuClockMhz(Double v) { this.gpuClockMhz = v; }

    public Boolean getCpuThrottleFlag() { return cpuThrottleFlag; }
    public void setCpuThrottleFlag(Boolean v) { this.cpuThrottleFlag = v; }

    public Boolean getGpuThrottleFlag() { return gpuThrottleFlag; }
    public void setGpuThrottleFlag(Boolean v) { this.gpuThrottleFlag = v; }

    public Double getRamUsagePercent() { return ramUsagePercent; }
    public void setRamUsagePercent(Double v) { this.ramUsagePercent = v; }

    public Double getCpuPowerW() { return cpuPowerW; }
    public void setCpuPowerW(Double v) { this.cpuPowerW = v; }

    public Double getGpuPowerW() { return gpuPowerW; }
    public void setGpuPowerW(Double v) { this.gpuPowerW = v; }
}
