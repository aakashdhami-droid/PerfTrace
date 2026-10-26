package com.perftrace.dto;

import java.util.ArrayList;
import java.util.List;

public class RunComparisonDto {

    private AnalysisRunDto runA;
    private AnalysisRunDto runB;

    private List<MetricComparisonDto> metrics = new ArrayList<>();
    private List<String> sensorCoverageDiffs = new ArrayList<>();
    private List<String> comparabilityWarnings = new ArrayList<>();
    private String conclusionNote;

    public RunComparisonDto() {}

    public RunComparisonDto(AnalysisRunDto runA, AnalysisRunDto runB) {
        this.runA = runA;
        this.runB = runB;
    }

    public AnalysisRunDto getRunA() { return runA; }
    public void setRunA(AnalysisRunDto runA) { this.runA = runA; }

    public AnalysisRunDto getRunB() { return runB; }
    public void setRunB(AnalysisRunDto runB) { this.runB = runB; }

    public List<MetricComparisonDto> getMetrics() { return metrics; }
    public void setMetrics(List<MetricComparisonDto> metrics) { this.metrics = metrics; }

    public List<String> getSensorCoverageDiffs() { return sensorCoverageDiffs; }
    public void setSensorCoverageDiffs(List<String> sensorCoverageDiffs) { this.sensorCoverageDiffs = sensorCoverageDiffs; }

    public List<String> getComparabilityWarnings() { return comparabilityWarnings; }
    public void setComparabilityWarnings(List<String> comparabilityWarnings) { this.comparabilityWarnings = comparabilityWarnings; }

    public String getConclusionNote() { return conclusionNote; }
    public void setConclusionNote(String conclusionNote) { this.conclusionNote = conclusionNote; }
}
