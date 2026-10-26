package com.perftrace.service.csv;

import com.perftrace.entity.TelemetrySample;

import java.util.ArrayList;
import java.util.List;

public class CsvParseResult {

    private List<TelemetrySample> samples = new ArrayList<>();
    private int totalRowsParsed;
    private int validRows;
    private int rejectedRows;
    private double totalDurationSeconds;
    private List<String> recognizedSensors = new ArrayList<>();
    private List<String> missingSensors = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();

    public CsvParseResult() {}

    public List<TelemetrySample> getSamples() { return samples; }
    public void setSamples(List<TelemetrySample> samples) { this.samples = samples; }

    public int getTotalRowsParsed() { return totalRowsParsed; }
    public void setTotalRowsParsed(int totalRowsParsed) { this.totalRowsParsed = totalRowsParsed; }

    public int getValidRows() { return validRows; }
    public void setValidRows(int validRows) { this.validRows = validRows; }

    public int getRejectedRows() { return rejectedRows; }
    public void setRejectedRows(int rejectedRows) { this.rejectedRows = rejectedRows; }

    public double getTotalDurationSeconds() { return totalDurationSeconds; }
    public void setTotalDurationSeconds(double totalDurationSeconds) { this.totalDurationSeconds = totalDurationSeconds; }

    public List<String> getRecognizedSensors() { return recognizedSensors; }
    public void setRecognizedSensors(List<String> recognizedSensors) { this.recognizedSensors = recognizedSensors; }

    public List<String> getMissingSensors() { return missingSensors; }
    public void setMissingSensors(List<String> missingSensors) { this.missingSensors = missingSensors; }

    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings; }
}
