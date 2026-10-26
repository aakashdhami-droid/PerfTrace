package com.perftrace.dto;

import com.perftrace.entity.Classification;
import com.perftrace.entity.ThrottleEvidence;

public class DetectedSegmentDto {

    private Long id;
    private double startOffsetSeconds;
    private double endOffsetSeconds;
    private double durationSeconds;
    private Classification classification;
    private ThrottleEvidence throttleEvidence;
    private String evidenceExplanation;

    private Double avgCpuLoad;
    private Double avgGpuLoad;
    private Double avgCpuMaxThread;
    private Double avgCpuTempC;
    private Double avgGpuTempC;
    private Double avgCpuClockMhz;
    private Double avgGpuClockMhz;
    private Double peakCpuTempC;
    private Double peakGpuTempC;
    private Integer sampleCount;

    public DetectedSegmentDto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public double getStartOffsetSeconds() { return startOffsetSeconds; }
    public void setStartOffsetSeconds(double startOffsetSeconds) { this.startOffsetSeconds = startOffsetSeconds; }

    public double getEndOffsetSeconds() { return endOffsetSeconds; }
    public void setEndOffsetSeconds(double endOffsetSeconds) { this.endOffsetSeconds = endOffsetSeconds; }

    public double getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(double durationSeconds) { this.durationSeconds = durationSeconds; }

    public Classification getClassification() { return classification; }
    public void setClassification(Classification classification) { this.classification = classification; }

    public ThrottleEvidence getThrottleEvidence() { return throttleEvidence; }
    public void setThrottleEvidence(ThrottleEvidence throttleEvidence) { this.throttleEvidence = throttleEvidence; }

    public String getEvidenceExplanation() { return evidenceExplanation; }
    public void setEvidenceExplanation(String evidenceExplanation) { this.evidenceExplanation = evidenceExplanation; }

    public Double getAvgCpuLoad() { return avgCpuLoad; }
    public void setAvgCpuLoad(Double avgCpuLoad) { this.avgCpuLoad = avgCpuLoad; }

    public Double getAvgGpuLoad() { return avgGpuLoad; }
    public void setAvgGpuLoad(Double avgGpuLoad) { this.avgGpuLoad = avgGpuLoad; }

    public Double getAvgCpuMaxThread() { return avgCpuMaxThread; }
    public void setAvgCpuMaxThread(Double avgCpuMaxThread) { this.avgCpuMaxThread = avgCpuMaxThread; }

    public Double getAvgCpuTempC() { return avgCpuTempC; }
    public void setAvgCpuTempC(Double avgCpuTempC) { this.avgCpuTempC = avgCpuTempC; }

    public Double getAvgGpuTempC() { return avgGpuTempC; }
    public void setAvgGpuTempC(Double avgGpuTempC) { this.avgGpuTempC = avgGpuTempC; }

    public Double getAvgCpuClockMhz() { return avgCpuClockMhz; }
    public void setAvgCpuClockMhz(Double avgCpuClockMhz) { this.avgCpuClockMhz = avgCpuClockMhz; }

    public Double getAvgGpuClockMhz() { return avgGpuClockMhz; }
    public void setAvgGpuClockMhz(Double avgGpuClockMhz) { this.avgGpuClockMhz = avgGpuClockMhz; }

    public Double getPeakCpuTempC() { return peakCpuTempC; }
    public void setPeakCpuTempC(Double peakCpuTempC) { this.peakCpuTempC = peakCpuTempC; }

    public Double getPeakGpuTempC() { return peakGpuTempC; }
    public void setPeakGpuTempC(Double peakGpuTempC) { this.peakGpuTempC = peakGpuTempC; }

    public Integer getSampleCount() { return sampleCount; }
    public void setSampleCount(Integer sampleCount) { this.sampleCount = sampleCount; }
}
