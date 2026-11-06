package com.perftrace.service.analysis;

import com.perftrace.entity.Classification;
import com.perftrace.entity.DetectedSegment;
import com.perftrace.entity.TelemetrySample;
import com.perftrace.entity.ThrottleEvidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentMergingServiceTest {

    private SegmentMergingService mergingService;

    @BeforeEach
    void setUp() {
        mergingService = new SegmentMergingService(20.0); // 20s smoothing threshold
    }

    private SegmentMergingService.ClassifiedWindow makeWindow(
        double start, double end, Classification classification, ThrottleEvidence throttle
    ) {
        TimeWindow w = new TimeWindow(start, end);
        TelemetrySample s = new TelemetrySample();
        s.setTimeOffsetSeconds((start + end) / 2.0);
        s.setCpuTotalLoad(50.0);
        s.setGpuLoad(50.0);
        w.addSample(s);
        w.computeAggregates(15.0);
        return new SegmentMergingService.ClassifiedWindow(w, classification, throttle, "Evidence for " + classification);
    }

    @Test
    @DisplayName("Should merge consecutive contiguous windows with identical classification and throttle status")
    void testMergeContiguousIdenticalWindows() {
        List<SegmentMergingService.ClassifiedWindow> windows = List.of(
            makeWindow(0.0, 10.0, Classification.CPU_BOUND, ThrottleEvidence.NONE),
            makeWindow(10.0, 20.0, Classification.CPU_BOUND, ThrottleEvidence.NONE),
            makeWindow(20.0, 30.0, Classification.CPU_BOUND, ThrottleEvidence.NONE)
        );

        List<DetectedSegment> segments = mergingService.merge(windows);

        assertThat(segments).hasSize(1);
        DetectedSegment seg = segments.get(0);
        assertThat(seg.getStartOffsetSeconds()).isEqualTo(0.0);
        assertThat(seg.getEndOffsetSeconds()).isEqualTo(30.0);
        assertThat(seg.getDurationSeconds()).isEqualTo(30.0);
        assertThat(seg.getClassification()).isEqualTo(Classification.CPU_BOUND);
    }

    @Test
    @DisplayName("Should split segments when classification changes")
    void testSplitOnClassificationChange() {
        List<SegmentMergingService.ClassifiedWindow> windows = List.of(
            makeWindow(0.0, 10.0, Classification.CPU_BOUND, ThrottleEvidence.NONE),
            makeWindow(10.0, 20.0, Classification.CPU_BOUND, ThrottleEvidence.NONE),
            makeWindow(20.0, 30.0, Classification.GPU_BOUND, ThrottleEvidence.NONE),
            makeWindow(30.0, 40.0, Classification.GPU_BOUND, ThrottleEvidence.NONE)
        );

        List<DetectedSegment> segments = mergingService.merge(windows);

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).getClassification()).isEqualTo(Classification.CPU_BOUND);
        assertThat(segments.get(0).getDurationSeconds()).isEqualTo(20.0);
        assertThat(segments.get(1).getClassification()).isEqualTo(Classification.GPU_BOUND);
        assertThat(segments.get(1).getDurationSeconds()).isEqualTo(20.0);
    }

    @Test
    @DisplayName("Should smooth a brief unthrottled segment (< 20s) into surrounding dominant classification")
    void testSmoothingShortSegment() {
        // CPU_BOUND (30s) -> BALANCED (10s brief blip) -> CPU_BOUND (30s)
        List<SegmentMergingService.ClassifiedWindow> windows = List.of(
            makeWindow(0.0, 10.0, Classification.CPU_BOUND, ThrottleEvidence.NONE),
            makeWindow(10.0, 20.0, Classification.CPU_BOUND, ThrottleEvidence.NONE),
            makeWindow(20.0, 30.0, Classification.CPU_BOUND, ThrottleEvidence.NONE),
            makeWindow(30.0, 40.0, Classification.BALANCED, ThrottleEvidence.NONE), // 10s < 20s
            makeWindow(40.0, 50.0, Classification.CPU_BOUND, ThrottleEvidence.NONE),
            makeWindow(50.0, 60.0, Classification.CPU_BOUND, ThrottleEvidence.NONE),
            makeWindow(60.0, 70.0, Classification.CPU_BOUND, ThrottleEvidence.NONE)
        );

        List<DetectedSegment> segments = mergingService.merge(windows);

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).getClassification()).isEqualTo(Classification.CPU_BOUND);
        assertThat(segments.get(0).getEndOffsetSeconds()).isEqualTo(70.0);
    }

    @Test
    @DisplayName("Metrics after smoothing must describe all absorbed windows, not just the first one")
    void testSmoothingRecomputesAggregateMetrics() {
        List<SegmentMergingService.ClassifiedWindow> windows = List.of(
            makeWindowWithSample(0.0, 10.0, Classification.CPU_BOUND, ThrottleEvidence.NONE,
                90.0, 95.0, 60.0, 70.0, 4000.0),
            makeWindowWithSample(10.0, 20.0, Classification.CPU_BOUND, ThrottleEvidence.NONE,
                70.0, 75.0, 50.0, 80.0, 5000.0),
            makeWindowWithSample(20.0, 30.0, Classification.BALANCED, ThrottleEvidence.NONE,
                10.0, 20.0, 90.0, 60.0, 3000.0),
            makeWindowWithSample(30.0, 40.0, Classification.CPU_BOUND, ThrottleEvidence.NONE,
                50.0, 55.0, 40.0, 75.0, 4500.0),
            makeWindowWithSample(40.0, 50.0, Classification.CPU_BOUND, ThrottleEvidence.NONE,
                40.0, 45.0, 30.0, 65.0, 4200.0)
        );

        List<DetectedSegment> segments = mergingService.merge(windows);

        assertThat(segments).hasSize(1);
        DetectedSegment seg = segments.get(0);
        assertThat(seg.getDurationSeconds()).isEqualTo(50.0);
        assertThat(seg.getSampleCount()).isEqualTo(5);
        assertThat(seg.getAvgCpuLoad()).isEqualTo(52.0);
        assertThat(seg.getAvgGpuLoad()).isEqualTo(54.0);
        assertThat(seg.getAvgCpuMaxThread()).isEqualTo(58.0);
        assertThat(seg.getAvgCpuTempC()).isEqualTo(70.0);
        assertThat(seg.getPeakCpuTempC()).isEqualTo(80.0);
        // Every window has either CPU total load >= 15% or busiest-thread load >= 15%,
        // so all five clocks contribute to the active-workload average.
        assertThat(seg.getAvgCpuClockMhz()).isEqualTo(4140.0);
    }

    @Test
    @DisplayName("Segment clock averages must exclude idle samples and keep CPU/GPU activity independent")
    void testSegmentAverageClocksExcludeIdleSamples() {
        List<SegmentMergingService.ClassifiedWindow> windows = List.of(
            makeWindowWithClocks(0.0, 10.0, Classification.CPU_BOUND, ThrottleEvidence.NONE,
                92.0, 97.0, 10.0, 4500.0, 500.0),
            makeWindowWithClocks(10.0, 20.0, Classification.CPU_BOUND, ThrottleEvidence.NONE,
                5.0, 5.0, 95.0, 1200.0, 2000.0)
        );

        List<DetectedSegment> segments = mergingService.merge(windows);

        assertThat(segments).hasSize(1);
        DetectedSegment segment = segments.get(0);
        assertThat(segment.getSampleCount()).isEqualTo(2);
        // The second window's CPU clock (1200 MHz) is idle and must not dilute the CPU
        // average; its GPU work is real, so the GPU clock is averaged independently.
        assertThat(segment.getAvgCpuClockMhz()).isEqualTo(4500.0);
        assertThat(segment.getAvgGpuClockMhz()).isEqualTo(2000.0);
    }

    private SegmentMergingService.ClassifiedWindow makeWindowWithClocks(
        double start, double end, Classification classification, ThrottleEvidence throttle,
        double cpuLoad, double maxThread, double gpuLoad, double cpuClock, double gpuClock
    ) {
        TimeWindow w = new TimeWindow(start, end);
        TelemetrySample s = new TelemetrySample();
        s.setTimeOffsetSeconds((start + end) / 2.0);
        s.setCpuTotalLoad(cpuLoad);
        s.setCpuMaxThreadLoad(maxThread);
        s.setGpuLoad(gpuLoad);
        s.setCpuClockMhz(cpuClock);
        s.setGpuClockMhz(gpuClock);
        w.addSample(s);
        w.computeAggregates(15.0);
        return new SegmentMergingService.ClassifiedWindow(w, classification, throttle, "Evidence");
    }

    private SegmentMergingService.ClassifiedWindow makeWindowWithSample(
        double start, double end, Classification classification, ThrottleEvidence throttle,
        double cpuLoad, double maxThread, double gpuLoad, double cpuTemp, double cpuClock
    ) {
        TimeWindow w = new TimeWindow(start, end);
        TelemetrySample s = new TelemetrySample();
        s.setTimeOffsetSeconds((start + end) / 2.0);
        s.setCpuTotalLoad(cpuLoad);
        s.setCpuMaxThreadLoad(maxThread);
        s.setGpuLoad(gpuLoad);
        s.setCpuTempC(cpuTemp);
        s.setCpuClockMhz(cpuClock);
        w.addSample(s);
        w.computeAggregates(15.0);
        return new SegmentMergingService.ClassifiedWindow(w, classification, throttle, "Evidence");
    }

    @Test
    @DisplayName("Safeguard: Should NEVER smooth away a thermal throttling transition even if brief (<20s)")
    void testSafeguardNeverSmoothThermalThrottleTransition() {
        // CPU_BOUND (30s, no throttle) -> CPU_BOUND (10s, HARDWARE_FLAG) -> CPU_BOUND (30s, no throttle)
        List<SegmentMergingService.ClassifiedWindow> windows = List.of(
            makeWindow(0.0, 10.0, Classification.CPU_BOUND, ThrottleEvidence.NONE),
            makeWindow(10.0, 20.0, Classification.CPU_BOUND, ThrottleEvidence.NONE),
            makeWindow(20.0, 30.0, Classification.CPU_BOUND, ThrottleEvidence.NONE),
            makeWindow(30.0, 40.0, Classification.CPU_BOUND, ThrottleEvidence.HARDWARE_FLAG), // 10s, but throttled!
            makeWindow(40.0, 50.0, Classification.CPU_BOUND, ThrottleEvidence.NONE),
            makeWindow(50.0, 60.0, Classification.CPU_BOUND, ThrottleEvidence.NONE),
            makeWindow(60.0, 70.0, Classification.CPU_BOUND, ThrottleEvidence.NONE)
        );

        List<DetectedSegment> segments = mergingService.merge(windows);

        // Throttle transition MUST be preserved as its own segment!
        assertThat(segments).hasSize(3);
        assertThat(segments.get(1).getThrottleEvidence()).isEqualTo(ThrottleEvidence.HARDWARE_FLAG);
        assertThat(segments.get(1).getDurationSeconds()).isEqualTo(10.0);
    }
}
