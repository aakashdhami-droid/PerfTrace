package com.perftrace.dto;

import com.perftrace.entity.RunStatus;
import com.perftrace.entity.WorkloadType;

public class RunUploadResponse {

    private Long runId;
    private String fileName;
    private WorkloadType workloadType;
    private RunStatus status;
    private String message;
    private Integer totalRowsParsed;
    private Integer validRows;
    private Integer rejectedRows;
    private Double totalDurationSeconds;

    public RunUploadResponse() {}

    public RunUploadResponse(Long runId, String fileName, RunStatus status, String message) {
        this.runId = runId;
        this.fileName = fileName;
        this.status = status;
        this.message = message;
    }

    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public WorkloadType getWorkloadType() { return workloadType; }
    public void setWorkloadType(WorkloadType workloadType) { this.workloadType = workloadType; }

    public RunStatus getStatus() { return status; }
    public void setStatus(RunStatus status) { this.status = status; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Integer getTotalRowsParsed() { return totalRowsParsed; }
    public void setTotalRowsParsed(Integer totalRowsParsed) { this.totalRowsParsed = totalRowsParsed; }

    public Integer getValidRows() { return validRows; }
    public void setValidRows(Integer validRows) { this.validRows = validRows; }

    public Integer getRejectedRows() { return rejectedRows; }
    public void setRejectedRows(Integer rejectedRows) { this.rejectedRows = rejectedRows; }

    public Double getTotalDurationSeconds() { return totalDurationSeconds; }
    public void setTotalDurationSeconds(Double totalDurationSeconds) { this.totalDurationSeconds = totalDurationSeconds; }
}
