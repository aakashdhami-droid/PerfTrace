package com.perftrace.dto;

import com.perftrace.entity.RunStatus;
import com.perftrace.entity.WorkloadType;
import java.time.Instant;

public class AnalysisRunDto {

    private Long id;
    private String fileName;
    private String description;
    private WorkloadType workloadType;
    private RunStatus status;
    private String errorMessage;
    private Double totalDurationSeconds;
    private Double validDataDurationSeconds;

    // Classification Durations
    private Double cpuBoundSeconds;
    private Double gpuBoundSeconds;
    private Double balancedSeconds;
    private Double idleSeconds;
    private Double unknownSeconds;

    // Percentages (Denominator: Active Time = totalDuration - idleSeconds)
    private Double cpuBoundActivePercent;
    private Double gpuBoundActivePercent;
    private Double balancedActivePercent;
    private Double idleTotalPercent;

    // Throttle
    private Double throttleDurationSeconds;
    private Double hardwareFlagThrottleSeconds;
    private Double heuristicThrottleSeconds;
    private Double throttlePercent;

    // Performance & Sensors
    private Double peakCpuTempC;
    private Double peakGpuTempC;
    private Double avgCpuClockMhz;
    private Double avgGpuClockMhz;

    private Integer totalRows;
    private Integer validRows;
    private Integer rejectedRows;
    private String recognizedSensors;
    private String missingSensors;
    private String dataQualityWarnings;

    private Instant createdAt;
    private Instant completedAt;

    public AnalysisRunDto() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

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
    public void setTotalDurationSeconds(Double totalDurationSeconds) { this.totalDurationSeconds = totalDurationSeconds; }

    public Double getValidDataDurationSeconds() { return validDataDurationSeconds; }
    public void setValidDataDurationSeconds(Double validDataDurationSeconds) { this.validDataDurationSeconds = validDataDurationSeconds; }

    public Double getCpuBoundSeconds() { return cpuBoundSeconds; }
    public void setCpuBoundSeconds(Double cpuBoundSeconds) { this.cpuBoundSeconds = cpuBoundSeconds; }

    public Double getGpuBoundSeconds() { return gpuBoundSeconds; }
    public void setGpuBoundSeconds(Double gpuBoundSeconds) { this.gpuBoundSeconds = gpuBoundSeconds; }

    public Double getBalancedSeconds() { return balancedSeconds; }
    public void setBalancedSeconds(Double balancedSeconds) { this.balancedSeconds = balancedSeconds; }

    public Double getIdleSeconds() { return idleSeconds; }
    public void setIdleSeconds(Double idleSeconds) { this.idleSeconds = idleSeconds; }

    public Double getUnknownSeconds() { return unknownSeconds; }
    public void setUnknownSeconds(Double unknownSeconds) { this.unknownSeconds = unknownSeconds; }

    public Double getCpuBoundActivePercent() { return cpuBoundActivePercent; }
    public void setCpuBoundActivePercent(Double cpuBoundActivePercent) { this.cpuBoundActivePercent = cpuBoundActivePercent; }

    public Double getGpuBoundActivePercent() { return gpuBoundActivePercent; }
    public void setGpuBoundActivePercent(Double gpuBoundActivePercent) { this.gpuBoundActivePercent = gpuBoundActivePercent; }

    public Double getBalancedActivePercent() { return balancedActivePercent; }
    public void setBalancedActivePercent(Double balancedActivePercent) { this.balancedActivePercent = balancedActivePercent; }

    public Double getIdleTotalPercent() { return idleTotalPercent; }
    public void setIdleTotalPercent(Double idleTotalPercent) { this.idleTotalPercent = idleTotalPercent; }

    public Double getThrottleDurationSeconds() { return throttleDurationSeconds; }
    public void setThrottleDurationSeconds(Double throttleDurationSeconds) { this.throttleDurationSeconds = throttleDurationSeconds; }

    public Double getHardwareFlagThrottleSeconds() { return hardwareFlagThrottleSeconds; }
    public void setHardwareFlagThrottleSeconds(Double hardwareFlagThrottleSeconds) { this.hardwareFlagThrottleSeconds = hardwareFlagThrottleSeconds; }

    public Double getHeuristicThrottleSeconds() { return heuristicThrottleSeconds; }
    public void setHeuristicThrottleSeconds(Double heuristicThrottleSeconds) { this.heuristicThrottleSeconds = heuristicThrottleSeconds; }

    public Double getThrottlePercent() { return throttlePercent; }
    public void setThrottlePercent(Double throttlePercent) { this.throttlePercent = throttlePercent; }

    public Double getPeakCpuTempC() { return peakCpuTempC; }
    public void setPeakCpuTempC(Double peakCpuTempC) { this.peakCpuTempC = peakCpuTempC; }

    public Double getPeakGpuTempC() { return peakGpuTempC; }
    public void setPeakGpuTempC(Double peakGpuTempC) { this.peakGpuTempC = peakGpuTempC; }

    public Double getAvgCpuClockMhz() { return avgCpuClockMhz; }
    public void setAvgCpuClockMhz(Double avgCpuClockMhz) { this.avgCpuClockMhz = avgCpuClockMhz; }

    public Double getAvgGpuClockMhz() { return avgGpuClockMhz; }
    public void setAvgGpuClockMhz(Double avgGpuClockMhz) { this.avgGpuClockMhz = avgGpuClockMhz; }

    public Integer getTotalRows() { return totalRows; }
    public void setTotalRows(Integer totalRows) { this.totalRows = totalRows; }

    public Integer getValidRows() { return validRows; }
    public void setValidRows(Integer validRows) { this.validRows = validRows; }

    public Integer getRejectedRows() { return rejectedRows; }
    public void setRejectedRows(Integer rejectedRows) { this.rejectedRows = rejectedRows; }

    public String getRecognizedSensors() { return recognizedSensors; }
    public void setRecognizedSensors(String recognizedSensors) { this.recognizedSensors = recognizedSensors; }

    public String getMissingSensors() { return missingSensors; }
    public void setMissingSensors(String missingSensors) { this.missingSensors = missingSensors; }

    public String getDataQualityWarnings() { return dataQualityWarnings; }
    public void setDataQualityWarnings(String dataQualityWarnings) { this.dataQualityWarnings = dataQualityWarnings; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
