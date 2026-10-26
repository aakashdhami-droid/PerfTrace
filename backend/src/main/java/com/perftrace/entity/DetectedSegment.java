package com.perftrace.entity;

import jakarta.persistence.*;

/**
 * A detected segment — a contiguous period with the same classification
 * and compatible throttle state, produced by merging adjacent time windows.
 */
@Entity
@Table(name = "detected_segments", indexes = {
    @Index(name = "idx_segment_run", columnList = "run_id"),
    @Index(name = "idx_segment_run_offset", columnList = "run_id, start_offset_seconds")
})
public class DetectedSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private AnalysisRun run;

    @Column(name = "start_offset_seconds", nullable = false)
    private double startOffsetSeconds;

    @Column(name = "end_offset_seconds", nullable = false)
    private double endOffsetSeconds;

    @Column(name = "duration_seconds", nullable = false)
    private double durationSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification", nullable = false, length = 20)
    private Classification classification;

    @Enumerated(EnumType.STRING)
    @Column(name = "throttle_evidence", nullable = false, length = 30)
    private ThrottleEvidence throttleEvidence = ThrottleEvidence.NONE;

    /** Human-readable explanation of why this classification was assigned */
    @Column(name = "evidence_explanation", length = 2000)
    private String evidenceExplanation;

    // ── Supporting metrics for this segment ──

    @Column(name = "avg_cpu_load")
    private Double avgCpuLoad;

    @Column(name = "avg_gpu_load")
    private Double avgGpuLoad;

    @Column(name = "avg_cpu_max_thread")
    private Double avgCpuMaxThread;

    @Column(name = "avg_cpu_temp_c")
    private Double avgCpuTempC;

    @Column(name = "avg_gpu_temp_c")
    private Double avgGpuTempC;

    @Column(name = "avg_cpu_clock_mhz")
    private Double avgCpuClockMhz;

    @Column(name = "avg_gpu_clock_mhz")
    private Double avgGpuClockMhz;

    @Column(name = "peak_cpu_temp_c")
    private Double peakCpuTempC;

    @Column(name = "peak_gpu_temp_c")
    private Double peakGpuTempC;

    /** Number of valid samples in this segment */
    @Column(name = "sample_count")
    private Integer sampleCount;

    // ── Getters and Setters ──

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public AnalysisRun getRun() { return run; }
    public void setRun(AnalysisRun run) { this.run = run; }

    public double getStartOffsetSeconds() { return startOffsetSeconds; }
    public void setStartOffsetSeconds(double v) { this.startOffsetSeconds = v; }

    public double getEndOffsetSeconds() { return endOffsetSeconds; }
    public void setEndOffsetSeconds(double v) { this.endOffsetSeconds = v; }

    public double getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(double v) { this.durationSeconds = v; }

    public Classification getClassification() { return classification; }
    public void setClassification(Classification v) { this.classification = v; }

    public ThrottleEvidence getThrottleEvidence() { return throttleEvidence; }
    public void setThrottleEvidence(ThrottleEvidence v) { this.throttleEvidence = v; }

    public String getEvidenceExplanation() { return evidenceExplanation; }
    public void setEvidenceExplanation(String v) { this.evidenceExplanation = v; }

    public Double getAvgCpuLoad() { return avgCpuLoad; }
    public void setAvgCpuLoad(Double v) { this.avgCpuLoad = v; }

    public Double getAvgGpuLoad() { return avgGpuLoad; }
    public void setAvgGpuLoad(Double v) { this.avgGpuLoad = v; }

    public Double getAvgCpuMaxThread() { return avgCpuMaxThread; }
    public void setAvgCpuMaxThread(Double v) { this.avgCpuMaxThread = v; }

    public Double getAvgCpuTempC() { return avgCpuTempC; }
    public void setAvgCpuTempC(Double v) { this.avgCpuTempC = v; }

    public Double getAvgGpuTempC() { return avgGpuTempC; }
    public void setAvgGpuTempC(Double v) { this.avgGpuTempC = v; }

    public Double getAvgCpuClockMhz() { return avgCpuClockMhz; }
    public void setAvgCpuClockMhz(Double v) { this.avgCpuClockMhz = v; }

    public Double getAvgGpuClockMhz() { return avgGpuClockMhz; }
    public void setAvgGpuClockMhz(Double v) { this.avgGpuClockMhz = v; }

    public Double getPeakCpuTempC() { return peakCpuTempC; }
    public void setPeakCpuTempC(Double v) { this.peakCpuTempC = v; }

    public Double getPeakGpuTempC() { return peakGpuTempC; }
    public void setPeakGpuTempC(Double v) { this.peakGpuTempC = v; }

    public Integer getSampleCount() { return sampleCount; }
    public void setSampleCount(Integer v) { this.sampleCount = v; }
}
