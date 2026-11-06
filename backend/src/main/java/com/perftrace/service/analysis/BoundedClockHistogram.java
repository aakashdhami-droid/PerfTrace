package com.perftrace.service.analysis;

/**
 * Fixed-size clock histogram used to estimate percentiles without retaining every reading.
 *
 * <p>One 1 MHz bucket per possible clock in the inclusive range 0–20,000 MHz is allocated
 * (80 KB per histogram). This keeps memory bounded and deterministic, unlike a reservoir
 * sampler whose output can vary between runs.</p>
 */
final class BoundedClockHistogram {

    static final int MAX_CLOCK_MHZ = 20_000;

    private final int[] bins = new int[MAX_CLOCK_MHZ + 1];
    private long count = 0;

    void add(Double clockMhz) {
        if (clockMhz == null || !Double.isFinite(clockMhz)) return;
        if (clockMhz < 0.0 || clockMhz > MAX_CLOCK_MHZ) return;
        bins[(int) Math.round(clockMhz)]++;
        count++;
    }

    long count() {
        return count;
    }

    boolean isEmpty() {
        return count == 0;
    }

    /**
     * Returns the zero-based rank used by the original in-memory percentile calculation:
     * {@code floor(p * (n - 1))}. Histogram precision is 1 MHz.
     */
    Double percentile(double p) {
        if (count == 0) return null;
        long target = (long) Math.floor(clamp(p) * (count - 1));
        long seen = 0;
        for (int i = 0; i < bins.length; i++) {
            seen += bins[i];
            if (seen > target) return (double) i;
        }
        // Floating point edge case: return the highest populated bucket.
        for (int i = bins.length - 1; i >= 0; i--) {
            if (bins[i] > 0) return (double) i;
        }
        return null;
    }

    private double clamp(double p) {
        return Math.max(0.0, Math.min(1.0, p));
    }
}
