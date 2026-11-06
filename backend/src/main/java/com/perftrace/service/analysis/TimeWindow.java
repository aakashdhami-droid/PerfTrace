package com.perftrace.service.analysis;

import com.perftrace.entity.TelemetrySample;

/**
 * Compact, aggregate-only representation of a telemetry window.
 *
 * <p>Deliberately does not retain the source {@link TelemetrySample} objects. The ingestion
 * pipeline can therefore process arbitrarily large CSVs with memory proportional to the
 * number of active generators (the window currently being filled and clock histograms), not
 * to the number of uploaded rows.</p>
 *
 * <p>Clock averages are calculated from active-workload samples only. CPU and GPU activity
 * are evaluated independently so that a GPU-bound game does not drag the CPU clock average
 * down with otherwise-idle CPU samples.</p>
 */
public class TimeWindow {

    private final double startOffsetSeconds;
    private double endOffsetSeconds;

    private int sampleCount = 0;
    private Double firstSampleOffsetSeconds;
    private Double lastSampleOffsetSeconds;
    private double maxSampleDeltaSeconds = 0.0;
    private boolean hasDataGap = false;

    private double sumCpuTotalLoad = 0.0;
    private int countCpuTotalLoad = 0;

    private double sumCpuMaxThreadLoad = 0.0;
    private int countCpuMaxThreadLoad = 0;

    private double sumGpuLoad = 0.0;
    private int countGpuLoad = 0;

    private double sumCpuTempC = 0.0;
    private int countCpuTempC = 0;
    private Double peakCpuTempC;

    private double sumGpuTempC = 0.0;
    private int countGpuTempC = 0;
    private Double peakGpuTempC;

    /** Clocks are summed only for samples that prove active CPU/GPU work. */
    private double sumActiveCpuClockMhz = 0.0;
    private int countActiveCpuClockMhz = 0;

    private double sumActiveGpuClockMhz = 0.0;
    private int countActiveGpuClockMhz = 0;

    private int cpuHardwareThrottleCount = 0;
    private int gpuHardwareThrottleCount = 0;
    private int throttleFlagObservationCount = 0;

    public TimeWindow(double startOffsetSeconds, double endOffsetSeconds) {
        this.startOffsetSeconds = startOffsetSeconds;
        this.endOffsetSeconds = Math.max(startOffsetSeconds, endOffsetSeconds);
    }

    public void addSample(TelemetrySample sample) {
        if (sample == null) return;

        double offset = Math.max(0.0, sample.getTimeOffsetSeconds());
        if (firstSampleOffsetSeconds == null) {
            firstSampleOffsetSeconds = offset;
        } else {
            maxSampleDeltaSeconds = Math.max(maxSampleDeltaSeconds, Math.abs(offset - lastSampleOffsetSeconds));
        }
        lastSampleOffsetSeconds = offset;
        sampleCount++;

        if (sample.getCpuTotalLoad() != null) {
            sumCpuTotalLoad += sample.getCpuTotalLoad();
            countCpuTotalLoad++;
        }
        if (sample.getCpuMaxThreadLoad() != null) {
            sumCpuMaxThreadLoad += sample.getCpuMaxThreadLoad();
            countCpuMaxThreadLoad++;
        }
        if (sample.getGpuLoad() != null) {
            sumGpuLoad += sample.getGpuLoad();
            countGpuLoad++;
        }

        if (sample.getCpuTempC() != null) {
            sumCpuTempC += sample.getCpuTempC();
            countCpuTempC++;
            peakCpuTempC = peakCpuTempC == null
                ? sample.getCpuTempC()
                : Math.max(peakCpuTempC, sample.getCpuTempC());
        }
        if (sample.getGpuTempC() != null) {
            sumGpuTempC += sample.getGpuTempC();
            countGpuTempC++;
            peakGpuTempC = peakGpuTempC == null
                ? sample.getGpuTempC()
                : Math.max(peakGpuTempC, sample.getGpuTempC());
        }

        if (sample.getCpuClockMhz() != null && isCpuActive(sample)) {
            sumActiveCpuClockMhz += sample.getCpuClockMhz();
            countActiveCpuClockMhz++;
        }
        if (sample.getGpuClockMhz() != null && isGpuActive(sample)) {
            sumActiveGpuClockMhz += sample.getGpuClockMhz();
            countActiveGpuClockMhz++;
        }

        if (sample.getCpuThrottleFlag() != null || sample.getGpuThrottleFlag() != null) {
            throttleFlagObservationCount++;
            if (Boolean.TRUE.equals(sample.getCpuThrottleFlag())) cpuHardwareThrottleCount++;
            if (Boolean.TRUE.equals(sample.getGpuThrottleFlag())) gpuHardwareThrottleCount++;
        }
    }

    /**
     * Combines another aggregate window into this one. Used by segment merging without
     * retaining any raw samples.
     */
    public void addWindow(TimeWindow other) {
        if (other == null) return;

        if (firstSampleOffsetSeconds == null) {
            firstSampleOffsetSeconds = other.firstSampleOffsetSeconds;
        } else if (other.firstSampleOffsetSeconds != null) {
            firstSampleOffsetSeconds = Math.min(firstSampleOffsetSeconds, other.firstSampleOffsetSeconds);
        }
        if (lastSampleOffsetSeconds == null) {
            lastSampleOffsetSeconds = other.lastSampleOffsetSeconds;
        } else if (other.lastSampleOffsetSeconds != null) {
            lastSampleOffsetSeconds = Math.max(lastSampleOffsetSeconds, other.lastSampleOffsetSeconds);
        }

        sampleCount += other.sampleCount;
        maxSampleDeltaSeconds = Math.max(maxSampleDeltaSeconds, other.maxSampleDeltaSeconds);
        hasDataGap = hasDataGap || other.hasDataGap;

        sumCpuTotalLoad += other.sumCpuTotalLoad;
        countCpuTotalLoad += other.countCpuTotalLoad;
        sumCpuMaxThreadLoad += other.sumCpuMaxThreadLoad;
        countCpuMaxThreadLoad += other.countCpuMaxThreadLoad;
        sumGpuLoad += other.sumGpuLoad;
        countGpuLoad += other.countGpuLoad;

        sumCpuTempC += other.sumCpuTempC;
        countCpuTempC += other.countCpuTempC;
        peakCpuTempC = maxNullable(peakCpuTempC, other.peakCpuTempC);

        sumGpuTempC += other.sumGpuTempC;
        countGpuTempC += other.countGpuTempC;
        peakGpuTempC = maxNullable(peakGpuTempC, other.peakGpuTempC);

        sumActiveCpuClockMhz += other.sumActiveCpuClockMhz;
        countActiveCpuClockMhz += other.countActiveCpuClockMhz;
        sumActiveGpuClockMhz += other.sumActiveGpuClockMhz;
        countActiveGpuClockMhz += other.countActiveGpuClockMhz;

        cpuHardwareThrottleCount += other.cpuHardwareThrottleCount;
        gpuHardwareThrottleCount += other.gpuHardwareThrottleCount;
        throttleFlagObservationCount += other.throttleFlagObservationCount;
    }

    /**
     * Finalizes gap status. Kept for compatibility with the original public API; aggregate
     * values are available immediately after {@link #addSample(TelemetrySample)}.
     */
    public void computeAggregates(double maxGapThresholdSeconds) {
        if (sampleCount == 0) {
            hasDataGap = true;
            return;
        }
        if (maxSampleDeltaSeconds > maxGapThresholdSeconds) {
            hasDataGap = true;
        }
    }

    /**
     * Clips the window's end to the last observed timestamp. The streaming aggregator
     * cannot know the final sample time while it is filling the window, so the final
     * window is trimmed when the recording ends.
     */
    public void clipEndTo(double endOffsetSeconds) {
        this.endOffsetSeconds = Math.max(this.startOffsetSeconds, endOffsetSeconds);
    }

    private boolean isCpuActive(TelemetrySample sample) {
        return (sample.getCpuTotalLoad() != null && sample.getCpuTotalLoad() >= IdleDetector.CPU_IDLE_THRESHOLD)
            || (sample.getCpuMaxThreadLoad() != null && sample.getCpuMaxThreadLoad() >= IdleDetector.CPU_IDLE_THRESHOLD);
    }

    private boolean isGpuActive(TelemetrySample sample) {
        return sample.getGpuLoad() != null && sample.getGpuLoad() >= IdleDetector.GPU_IDLE_THRESHOLD;
    }

    private static Double maxNullable(Double a, Double b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.max(a, b);
    }

    private double average(double sum, int count) {
        return count == 0 ? 0.0 : round(sum / count);
    }

    private double round(double val) {
        return Math.round(val * 10.0) / 10.0;
    }

    public double getStartOffsetSeconds() { return startOffsetSeconds; }
    public double getEndOffsetSeconds() { return endOffsetSeconds; }
    public double getDurationSeconds() { return endOffsetSeconds - startOffsetSeconds; }
    public int getSampleCount() { return sampleCount; }

    public Double getFirstSampleOffsetSeconds() { return firstSampleOffsetSeconds; }
    public Double getLastSampleOffsetSeconds() { return lastSampleOffsetSeconds; }

    public Double getAvgCpuTotalLoad() {
        return countCpuTotalLoad == 0 ? null : average(sumCpuTotalLoad, countCpuTotalLoad);
    }

    public Double getAvgCpuMaxThreadLoad() {
        return countCpuMaxThreadLoad == 0 ? null : average(sumCpuMaxThreadLoad, countCpuMaxThreadLoad);
    }

    public Double getAvgGpuLoad() {
        return countGpuLoad == 0 ? null : average(sumGpuLoad, countGpuLoad);
    }

    public Double getAvgCpuTempC() {
        return countCpuTempC == 0 ? null : average(sumCpuTempC, countCpuTempC);
    }

    public Double getAvgGpuTempC() {
        return countGpuTempC == 0 ? null : average(sumGpuTempC, countGpuTempC);
    }

    public Double getPeakCpuTempC() { return peakCpuTempC; }
    public Double getPeakGpuTempC() { return peakGpuTempC; }

    /** Average of valid clocks from samples with independently proven CPU activity. */
    public Double getAvgCpuClockMhz() {
        return countActiveCpuClockMhz == 0 ? null : average(sumActiveCpuClockMhz, countActiveCpuClockMhz);
    }

    /** Average of valid clocks from samples with independently proven GPU activity. */
    public Double getAvgGpuClockMhz() {
        return countActiveGpuClockMhz == 0 ? null : average(sumActiveGpuClockMhz, countActiveGpuClockMhz);
    }

    public int getActiveCpuClockSampleCount() { return countActiveCpuClockMhz; }
    public int getActiveGpuClockSampleCount() { return countActiveGpuClockMhz; }

    public int getCpuHardwareThrottleCount() { return cpuHardwareThrottleCount; }
    public int getGpuHardwareThrottleCount() { return gpuHardwareThrottleCount; }
    public int getThrottleFlagObservationCount() { return throttleFlagObservationCount; }
    public boolean hasHardwareThrottle() { return cpuHardwareThrottleCount > 0 || gpuHardwareThrottleCount > 0; }
    public boolean isHasDataGap() { return hasDataGap; }
}
