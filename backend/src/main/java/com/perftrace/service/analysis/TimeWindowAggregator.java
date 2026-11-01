package com.perftrace.service.analysis;

import com.perftrace.entity.TelemetrySample;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates raw time-series telemetry samples into discrete time windows (default: 10s).
 */
@Component
public class TimeWindowAggregator {

    private final double windowSeconds;
    private final double maxGapThresholdSeconds;

    public TimeWindowAggregator(
        @Value("${perftrace.analysis.window-seconds:10}") double windowSeconds
    ) {
        this.windowSeconds = windowSeconds;
        this.maxGapThresholdSeconds = windowSeconds * 1.5; // Gap flagged if >15s between samples
    }

    public List<TimeWindow> aggregate(List<TelemetrySample> samples) {
        List<TimeWindow> windows = new ArrayList<>();
        if (samples.isEmpty()) return windows;

        double maxTime = samples.get(samples.size() - 1).getTimeOffsetSeconds();
        int totalWindows = (int) Math.ceil((maxTime + 0.001) / windowSeconds);
        if (totalWindows == 0) totalWindows = 1;

        for (int i = 0; i < totalWindows; i++) {
            double start = i * windowSeconds;
            double end = start + windowSeconds;
            windows.add(new TimeWindow(start, end));
        }

        int currentWindowIdx = 0;
        for (TelemetrySample sample : samples) {
            double offset = sample.getTimeOffsetSeconds();
            while (currentWindowIdx < windows.size() - 1 && offset >= windows.get(currentWindowIdx).getEndOffsetSeconds()) {
                currentWindowIdx++;
            }
            if (currentWindowIdx < windows.size()) {
                windows.get(currentWindowIdx).addSample(sample);
            }
        }

        // Keep all windows to preserve original time boundaries and data gaps
        List<TimeWindow> validWindows = new ArrayList<>();
        for (TimeWindow window : windows) {
            window.computeAggregates(maxGapThresholdSeconds);
            validWindows.add(window);
        }

        return validWindows;
    }
}
