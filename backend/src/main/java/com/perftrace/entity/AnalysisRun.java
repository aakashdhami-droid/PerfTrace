package com.perftrace.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An analysis run represents one uploaded CSV file and its processed results.
 * Tracks status through its lifecycle: UPLOADING → ANALYZING → COMPLETED / FAILED.
 */
@Entity
@Table(name = "analysis_runs", indexes = {
    @Index(name = "idx_run_user", columnList = "user_id"),
    @Index(name = "idx_run_status", columnList = "status")
})
public class AnalysisRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "description", length = 500)
    private String description;

    /** User-supplied workload category; never inferred automatically. */
    @Enumerated(EnumType.STRING)
    @Column(name = "workload_type", nullable = false, length = 30)
    private WorkloadType workloadType = WorkloadType.UNSPECIFIED;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RunStatus status = RunStatus.UPLOADING;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /** Total duration of the recording in seconds */
    @Column(name = "total_duration_seconds")
    private Double totalDurationSeconds;

    /** Duration with valid sensor data in seconds */
    @Column(name = "valid_data_duration_seconds")
    private Double validDataDurationSeconds;

    @Column(name = "cpu_bound_seconds")
    private Double cpuBoundSeconds;

    @Column(name = "gpu_bound_seconds")
    private Double gpuBoundSeconds;

    @Column(name = "balanced_seconds")
    private Double balancedSeconds;

    @Column(name = "idle_seconds")
    private Double idleSeconds;

    @Column(name = "unknown_seconds")
    private Double unknownSeconds;

    /** Throttle durations in seconds */
    @Column(name = "throttle_duration_seconds")
    private Double throttleDurationSeconds;

    @Column(name = "hardware_flag_throttle_seconds")
    private Double hardwareFlagThrottleSeconds;

    @Column(name = "heuristic_throttle_seconds")
    private Double heuristicThrottleSeconds;

    /** Peak temperatures (Celsius) — null when sensor unavailable */
    @Column(name = "peak_cpu_temp_c")
    private Double peakCpuTempC;

    @Column(name = "peak_gpu_temp_c")
    private Double peakGpuTempC;

    /** Average sustained clock speeds (MHz) — null when sensor unavailable */
    @Column(name = "avg_cpu_clock_mhz")
    private Double avgCpuClockMhz;

    @Column(name = "avg_gpu_clock_mhz")
    private Double avgGpuClockMhz;

    @Column(name = "total_rows")
    private Integer totalRows;

    @Column(name = "valid_rows")
    private Integer validRows;

    @Column(name = "rejected_rows")
    private Integer rejectedRows;

    /** Comma-separated list of recognized sensor names */
    @Column(name = "recognized_sensors", length = 2000)
    private String recognizedSensors;

    /** Comma-separated list of missing expected sensors */
    @Column(name = "missing_sensors", length = 2000)
    private String missingSensors;

    @Column(name = "data_quality_warnings", length = 2000)
    private String dataQualityWarnings;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("timeOffsetSeconds ASC")
    private List<TelemetrySample> samples = new ArrayList<>();

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("startOffsetSeconds ASC")
    private List<DetectedSegment> segments = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    // ── Getters and Setters ──

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public WorkloadType getWorkloadType() { return workloadType; }
    public void setWorkloadType(WorkloadType workloadType) { this.workloadType = workloadType; }

    public RunStatus getStatus() { return status; }
    public void setStatus(RunStatus status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Double getTotalDurationSeconds() { return totalDurationSeconds; }
    public void setTotalDurationSeconds(Double v) { this.totalDurationSeconds = v; }

    public Double getValidDataDurationSeconds() { return validDataDurationSeconds; }
    public void setValidDataDurationSeconds(Double v) { this.validDataDurationSeconds = v; }

    public Double getCpuBoundSeconds() { return cpuBoundSeconds; }
    public void setCpuBoundSeconds(Double v) { this.cpuBoundSeconds = v; }

    public Double getGpuBoundSeconds() { return gpuBoundSeconds; }
    public void setGpuBoundSeconds(Double v) { this.gpuBoundSeconds = v; }

    public Double getBalancedSeconds() { return balancedSeconds; }
    public void setBalancedSeconds(Double v) { this.balancedSeconds = v; }

    public Double getIdleSeconds() { return idleSeconds; }
    public void setIdleSeconds(Double v) { this.idleSeconds = v; }

    public Double getUnknownSeconds() { return unknownSeconds; }
    public void setUnknownSeconds(Double v) { this.unknownSeconds = v; }

    public Double getThrottleDurationSeconds() { return throttleDurationSeconds; }
    public void setThrottleDurationSeconds(Double v) { this.throttleDurationSeconds = v; }

    public Double getHardwareFlagThrottleSeconds() { return hardwareFlagThrottleSeconds; }
    public void setHardwareFlagThrottleSeconds(Double v) { this.hardwareFlagThrottleSeconds = v; }

    public Double getHeuristicThrottleSeconds() { return heuristicThrottleSeconds; }
    public void setHeuristicThrottleSeconds(Double v) { this.heuristicThrottleSeconds = v; }

    public Double getPeakCpuTempC() { return peakCpuTempC; }
    public void setPeakCpuTempC(Double v) { this.peakCpuTempC = v; }

    public Double getPeakGpuTempC() { return peakGpuTempC; }
    public void setPeakGpuTempC(Double v) { this.peakGpuTempC = v; }

    public Double getAvgCpuClockMhz() { return avgCpuClockMhz; }
    public void setAvgCpuClockMhz(Double v) { this.avgCpuClockMhz = v; }

    public Double getAvgGpuClockMhz() { return avgGpuClockMhz; }
    public void setAvgGpuClockMhz(Double v) { this.avgGpuClockMhz = v; }

    public Integer getTotalRows() { return totalRows; }
    public void setTotalRows(Integer v) { this.totalRows = v; }

    public Integer getValidRows() { return validRows; }
    public void setValidRows(Integer v) { this.validRows = v; }

    public Integer getRejectedRows() { return rejectedRows; }
    public void setRejectedRows(Integer v) { this.rejectedRows = v; }

    public String getRecognizedSensors() { return recognizedSensors; }
    public void setRecognizedSensors(String v) { this.recognizedSensors = v; }

    public String getMissingSensors() { return missingSensors; }
    public void setMissingSensors(String v) { this.missingSensors = v; }

    public String getDataQualityWarnings() { return dataQualityWarnings; }
    public void setDataQualityWarnings(String v) { this.dataQualityWarnings = v; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }

    public List<TelemetrySample> getSamples() { return samples; }
    public void setSamples(List<TelemetrySample> v) { this.samples = v; }

    public List<DetectedSegment> getSegments() { return segments; }
    public void setSegments(List<DetectedSegment> v) { this.segments = v; }
}
