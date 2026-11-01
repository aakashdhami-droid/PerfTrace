package com.perftrace.service.analysis;

import com.perftrace.entity.TelemetrySample;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents aggregated telemetry metrics over a discrete time window (typically 10 seconds).
 * Averages do NOT treat missing measurements as zero.
 */
public class TimeWindow {

    private final double startOffsetSeconds;
    private final double endOffsetSeconds;
    private final List<TelemetrySample> samples = new ArrayList<>();

    // Aggregates
    private Double avgCpuTotalLoad;
    private Double avgCpuMaxThreadLoad;
    private Double avgGpuLoad;

    private Double avgCpuTempC;
    private Double avgGpuTempC;
    private Double peakCpuTempC;
    private Double peakGpuTempC;

    private Double avgCpuClockMhz;
    private Double avgGpuClockMhz;

    private int cpuHardwareThrottleCount = 0;
    private int gpuHardwareThrottleCount = 0;
    private int throttleFlagObservationCount = 0;

    private boolean hasDataGap = false;

    public TimeWindow(double startOffsetSeconds, double endOffsetSeconds) {
        this.startOffsetSeconds = startOffsetSeconds;
        this.endOffsetSeconds = endOffsetSeconds;
    }

    public void addSample(TelemetrySample sample) {
        samples.add(sample);
    }

    public void computeAggregates(double maxGapThresholdSeconds) {
        if (samples.isEmpty()) {
            this.hasDataGap = true;
            return;
        }

        // Check for data gaps between consecutive samples in this window
        for (int i = 1; i < samples.size(); i++) {
            double delta = samples.get(i).getTimeOffsetSeconds() - samples.get(i - 1).getTimeOffsetSeconds();
            if (delta > maxGapThresholdSeconds) {
                this.hasDataGap = true;
                break;
            }
        }

        double sumCpuTotal = 0; int countCpuTotal = 0;
        double sumCpuThread = 0; int countCpuThread = 0;
        double sumGpuLoad = 0; int countGpuLoad = 0;

        double sumCpuTemp = 0; int countCpuTemp = 0;
        double sumGpuTemp = 0; int countGpuTemp = 0;
        Double maxCpuTemp = null;
        Double maxGpuTemp = null;

        double sumCpuClock = 0; int countCpuClock = 0;
        double sumGpuClock = 0; int countGpuClock = 0;

        for (TelemetrySample s : samples) {
            if (s.getCpuTotalLoad() != null) {
                sumCpuTotal += s.getCpuTotalLoad();
                countCpuTotal++;
            }
            if (s.getCpuMaxThreadLoad() != null) {
                sumCpuThread += s.getCpuMaxThreadLoad();
                countCpuThread++;
            }
            if (s.getGpuLoad() != null) {
                sumGpuLoad += s.getGpuLoad();
                countGpuLoad++;
            }

            if (s.getCpuTempC() != null) {
                sumCpuTemp += s.getCpuTempC();
                countCpuTemp++;
                maxCpuTemp = (maxCpuTemp == null) ? s.getCpuTempC() : Math.max(maxCpuTemp, s.getCpuTempC());
            }
            if (s.getGpuTempC() != null) {
                sumGpuTemp += s.getGpuTempC();
                countGpuTemp++;
                maxGpuTemp = (maxGpuTemp == null) ? s.getGpuTempC() : Math.max(maxGpuTemp, s.getGpuTempC());
            }

            if (s.getCpuClockMhz() != null) {
                sumCpuClock += s.getCpuClockMhz();
                countCpuClock++;
            }
            if (s.getGpuClockMhz() != null) {
                sumGpuClock += s.getGpuClockMhz();
                countGpuClock++;
            }

            if (s.getCpuThrottleFlag() != null || s.getGpuThrottleFlag() != null) {
                throttleFlagObservationCount++;
                if (Boolean.TRUE.equals(s.getCpuThrottleFlag())) cpuHardwareThrottleCount++;
                if (Boolean.TRUE.equals(s.getGpuThrottleFlag())) gpuHardwareThrottleCount++;
            }
        }

        if (countCpuTotal > 0) avgCpuTotalLoad = round(sumCpuTotal / countCpuTotal);
        if (countCpuThread > 0) avgCpuMaxThreadLoad = round(sumCpuThread / countCpuThread);
        if (countGpuLoad > 0) avgGpuLoad = round(sumGpuLoad / countGpuLoad);

        if (countCpuTemp > 0) avgCpuTempC = round(sumCpuTemp / countCpuTemp);
        if (countGpuTemp > 0) avgGpuTempC = round(sumGpuTemp / countGpuTemp);
        peakCpuTempC = maxCpuTemp;
        peakGpuTempC = maxGpuTemp;

        if (countCpuClock > 0) avgCpuClockMhz = round(sumCpuClock / countCpuClock);
        if (countGpuClock > 0) avgGpuClockMhz = round(sumGpuClock / countGpuClock);
    }

    private double round(double val) {
        return Math.round(val * 10.0) / 10.0;
    }

    public double getStartOffsetSeconds() { return startOffsetSeconds; }
    public double getEndOffsetSeconds() { return endOffsetSeconds; }
    public double getDurationSeconds() { return endOffsetSeconds - startOffsetSeconds; }
    public List<TelemetrySample> getSamples() { return samples; }
    public int getSampleCount() { return samples.size(); }

    public Double getAvgCpuTotalLoad() { return avgCpuTotalLoad; }
    public Double getAvgCpuMaxThreadLoad() { return avgCpuMaxThreadLoad; }
    public Double getAvgGpuLoad() { return avgGpuLoad; }

    public Double getAvgCpuTempC() { return avgCpuTempC; }
    public Double getAvgGpuTempC() { return avgGpuTempC; }
    public Double getPeakCpuTempC() { return peakCpuTempC; }
    public Double getPeakGpuTempC() { return peakGpuTempC; }

    public Double getAvgCpuClockMhz() { return avgCpuClockMhz; }
    public Double getAvgGpuClockMhz() { return avgGpuClockMhz; }

    public int getCpuHardwareThrottleCount() { return cpuHardwareThrottleCount; }
    public int getGpuHardwareThrottleCount() { return gpuHardwareThrottleCount; }
    public int getThrottleFlagObservationCount() { return throttleFlagObservationCount; }
    public boolean hasHardwareThrottle() { return cpuHardwareThrottleCount > 0 || gpuHardwareThrottleCount > 0; }
    public boolean isHasDataGap() { return hasDataGap; }
}
