package com.perftrace.service.analysis;

import com.perftrace.entity.TelemetrySample;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Aggregates raw time-series telemetry samples into discrete time windows (default: 10s).
 *
 * <p>The final window is clipped to the timestamp of the last sample so that segment
 * durations never claim more elapsed time than the recording actually contains.
 * Empty windows are retained deliberately: they represent telemetry gaps and must
 * remain visible as {@code UNKNOWN} periods instead of being silently removed.</p>
 *
 * <p>Uploads use {@link #newCollector()} so memory is proportional to the number of
 * time windows (bounded by {@link #MAX_WINDOW_COUNT}) and not to the number of CSV rows.
 * The list-based {@link #aggregate(List)} entry point is retained for tests and small
 * in-memory callers.</p>
 */
@Component
public class TimeWindowAggregator {

    private static final double EPSILON = 1e-9;

    /**
     * Safety valve against a malformed timestamp producing millions of empty gap windows.
     * 200,000 windows at 10 seconds covers more than 23 days of continuous recording.
     */
    static final int MAX_WINDOW_COUNT = 200_000;

    private final double windowSeconds;
    private final double maxGapThresholdSeconds;

    public TimeWindowAggregator(
        @Value("${perftrace.analysis.window-seconds:10}") double windowSeconds
    ) {
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("perftrace.analysis.window-seconds must be greater than zero");
        }
        this.windowSeconds = windowSeconds;
        this.maxGapThresholdSeconds = windowSeconds * 1.5;
    }

    public List<TimeWindow> aggregate(List<TelemetrySample> samples) {
        if (samples == null || samples.isEmpty()) return new ArrayList<>();

        // Analysis must not depend on the caller supplying rows in chronological order.
        // Streaming ingestion already guarantees monotonic timestamps; this copy exists only
        // for the legacy list-based API.
        List<TelemetrySample> ordered = new ArrayList<>(samples);
        ordered.sort(Comparator.comparingDouble(TelemetrySample::getTimeOffsetSeconds));

        WindowCollector collector = newCollector();
        for (TelemetrySample sample : ordered) {
            collector.add(sample);
        }
        return collector.finish();
    }

    /**
     * Creates a stateful, single-pass collector. Samples must arrive in non-decreasing
     * timestamp order, which the CSV streaming pipeline guarantees.
     */
    public WindowCollector newCollector() {
        return new WindowCollector();
    }

    public double getWindowSeconds() {
        return windowSeconds;
    }

    public double getMaxGapThresholdSeconds() {
        return maxGapThresholdSeconds;
    }

    public final class WindowCollector {

        private final List<TimeWindow> windows = new ArrayList<>();
        private TimeWindow current = new TimeWindow(0.0, windowSeconds);
        private double lastOffset = Double.NEGATIVE_INFINITY;
        private double lastObservedOffset = 0.0;
        private boolean finished = false;

        private WindowCollector() {}

        /**
         * Adds one sample. Calling this after {@link #finish()} is a programming error.
         */
        public void add(TelemetrySample sample) {
            if (finished) {
                throw new IllegalStateException("Cannot add samples to a finished window collector");
            }
            if (sample == null) return;

            double offset = Math.max(0.0, sample.getTimeOffsetSeconds());
            // Preserve chronological processing without retaining or sorting rows. The CSV
            // parser already clamps out-of-order timestamps, so a backward offset here is
            // defensive only.
            if (!Double.isInfinite(lastOffset) && offset < lastOffset) {
                offset = lastOffset;
            }
            lastOffset = offset;
            lastObservedOffset = Math.max(lastObservedOffset, offset);

            while (offset >= current.getEndOffsetSeconds() - EPSILON) {
                windows.add(current);
                if (windows.size() > MAX_WINDOW_COUNT) {
                    throw new IllegalArgumentException(
                        "Recording spans too many analysis windows (limit " + MAX_WINDOW_COUNT +
                        "). Check for malformed timestamps or split the recording.");
                }
                double nextStart = current.getEndOffsetSeconds();
                current = new TimeWindow(nextStart, nextStart + windowSeconds);
            }

            current.addSample(sample);
        }

        /**
         * Finalizes the window list. The last window is clipped to the last observed
         * timestamp. A zero-length trailing window (created when the recording ends exactly
         * on a window boundary) is folded back into the previous window so boundary samples
         * are not reported as a phantom extra window.
         */
        public List<TimeWindow> finish() {
            if (finished) {
                return List.copyOf(windows);
            }
            finished = true;

            current.clipEndTo(lastObservedOffset);
            if (current.getDurationSeconds() <= EPSILON && !windows.isEmpty()) {
                TimeWindow previous = windows.remove(windows.size() - 1);
                previous.addWindow(current);
                previous.clipEndTo(lastObservedOffset);
                windows.add(previous);
            } else {
                windows.add(current);
            }

            for (TimeWindow window : windows) {
                window.computeAggregates(maxGapThresholdSeconds);
            }
            return List.copyOf(windows);
        }
    }
}
