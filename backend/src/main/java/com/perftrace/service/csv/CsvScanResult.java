package com.perftrace.service.csv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Metadata produced by the first streaming pass over a CSV file.
 *
 * <p>It deliberately contains no telemetry samples. The second pass re-reads the spooled
 * file and emits normalized samples one at a time, so memory use does not grow with the
 * number of CSV rows.</p>
 */
public class CsvScanResult {

    private Map<NormalizedSensorField, Integer> columnMap = new EnumMap<>(NormalizedSensorField.class);
    private List<String> recognizedSensors = new ArrayList<>();
    private List<String> missingSensors = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();

    private int totalRowsParsed;
    private int validRows;
    private int rejectedRows;
    private double totalDurationSeconds;

    private boolean hasTimestampColumns;
    private boolean hasDateColumn;
    private boolean firstTimestampFound;
    private int firstParsedIndex;
    private double fallbackIntervalSeconds = 1.0;
    private double baselineAbsoluteSeconds;

    public Map<NormalizedSensorField, Integer> getColumnMap() {
        return Collections.unmodifiableMap(columnMap);
    }

    public void setColumnMap(Map<NormalizedSensorField, Integer> columnMap) {
        this.columnMap = new EnumMap<>(columnMap);
    }

    public List<String> getRecognizedSensors() { return recognizedSensors; }
    public void setRecognizedSensors(List<String> recognizedSensors) { this.recognizedSensors = recognizedSensors; }

    public List<String> getMissingSensors() { return missingSensors; }
    public void setMissingSensors(List<String> missingSensors) { this.missingSensors = missingSensors; }

    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings; }

    public int getTotalRowsParsed() { return totalRowsParsed; }
    public void setTotalRowsParsed(int totalRowsParsed) { this.totalRowsParsed = totalRowsParsed; }

    public int getValidRows() { return validRows; }
    public void setValidRows(int validRows) { this.validRows = validRows; }

    public int getRejectedRows() { return rejectedRows; }
    public void setRejectedRows(int rejectedRows) { this.rejectedRows = rejectedRows; }

    public double getTotalDurationSeconds() { return totalDurationSeconds; }
    public void setTotalDurationSeconds(double totalDurationSeconds) { this.totalDurationSeconds = totalDurationSeconds; }

    public boolean isHasTimestampColumns() { return hasTimestampColumns; }
    public void setHasTimestampColumns(boolean hasTimestampColumns) { this.hasTimestampColumns = hasTimestampColumns; }

    public boolean isHasDateColumn() { return hasDateColumn; }
    public void setHasDateColumn(boolean hasDateColumn) { this.hasDateColumn = hasDateColumn; }

    public boolean isFirstTimestampFound() { return firstTimestampFound; }
    public void setFirstTimestampFound(boolean firstTimestampFound) { this.firstTimestampFound = firstTimestampFound; }

    public int getFirstParsedIndex() { return firstParsedIndex; }
    public void setFirstParsedIndex(int firstParsedIndex) { this.firstParsedIndex = firstParsedIndex; }

    public double getFallbackIntervalSeconds() { return fallbackIntervalSeconds; }
    public void setFallbackIntervalSeconds(double fallbackIntervalSeconds) { this.fallbackIntervalSeconds = fallbackIntervalSeconds; }

    public double getBaselineAbsoluteSeconds() { return baselineAbsoluteSeconds; }
    public void setBaselineAbsoluteSeconds(double baselineAbsoluteSeconds) { this.baselineAbsoluteSeconds = baselineAbsoluteSeconds; }
}
