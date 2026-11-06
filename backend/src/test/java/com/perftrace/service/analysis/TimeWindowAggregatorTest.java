package com.perftrace.service.analysis;

import com.perftrace.entity.TelemetrySample;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimeWindowAggregatorTest {

    private TimeWindowAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new TimeWindowAggregator(10.0);
    }

    private TelemetrySample sample(double offset, Double cpu, Double gpu) {
        TelemetrySample s = new TelemetrySample();
        s.setTimeOffsetSeconds(offset);
        s.setCpuTotalLoad(cpu);
        s.setGpuLoad(gpu);
        return s;
    }

    @Test
    @DisplayName("Should not create a phantom window when the recording ends on a boundary")
    void testNoPhantomWindowAtExactBoundary() {
        List<TimeWindow> windows = aggregator.aggregate(List.of(
            sample(0.0, 50.0, 60.0),
            sample(10.0, 51.0, 61.0),
            sample(20.0, 52.0, 62.0)
        ));

        assertThat(windows).hasSize(2);
        assertThat(windows.get(0).getStartOffsetSeconds()).isEqualTo(0.0);
        assertThat(windows.get(0).getEndOffsetSeconds()).isEqualTo(10.0);
        assertThat(windows.get(1).getStartOffsetSeconds()).isEqualTo(10.0);
        assertThat(windows.get(1).getEndOffsetSeconds()).isEqualTo(20.0);
    }

    @Test
    @DisplayName("Should clip the final window to the last observed timestamp")
    void testFinalWindowClippedToLastSample() {
        List<TimeWindow> windows = aggregator.aggregate(List.of(
            sample(0.0, 50.0, 60.0),
            sample(4.0, 51.0, 61.0),
            sample(9.0, 52.0, 62.0)
        ));

        assertThat(windows).hasSize(1);
        assertThat(windows.get(0).getEndOffsetSeconds()).isEqualTo(9.0);
        assertThat(windows.get(0).getDurationSeconds()).isEqualTo(9.0);
    }

    @Test
    @DisplayName("Should sort out-of-order input before bucketing")
    void testSortsInputChronologically() {
        List<TimeWindow> windows = aggregator.aggregate(List.of(
            sample(25.0, 80.0, 90.0),
            sample(1.0, 20.0, 30.0),
            sample(12.0, 50.0, 60.0)
        ));

        assertThat(windows).hasSize(3);
        assertThat(windows.get(0).getAvgCpuTotalLoad()).isEqualTo(20.0);
        assertThat(windows.get(1).getAvgCpuTotalLoad()).isEqualTo(50.0);
        assertThat(windows.get(2).getAvgCpuTotalLoad()).isEqualTo(80.0);
        assertThat(windows.get(0).getStartOffsetSeconds()).isEqualTo(0.0);
        assertThat(windows.get(1).getStartOffsetSeconds()).isEqualTo(10.0);
        assertThat(windows.get(2).getStartOffsetSeconds()).isEqualTo(20.0);
    }

    @Test
    @DisplayName("Should retain empty windows so telemetry gaps stay visible as UNKNOWN time")
    void testRetainsEmptyGapWindows() {
        List<TimeWindow> windows = aggregator.aggregate(List.of(
            sample(0.0, 50.0, 60.0),
            sample(1.0, 51.0, 61.0),
            sample(2.0, 52.0, 62.0),
            sample(31.0, 50.0, 60.0),
            sample(32.0, 51.0, 61.0),
            sample(33.0, 52.0, 62.0)
        ));

        assertThat(windows).hasSize(4); // 0-10, 10-20, 20-30, 30-33
        assertThat(windows.get(0).getSampleCount()).isEqualTo(3);
        assertThat(windows.get(1).getSampleCount()).isZero();
        assertThat(windows.get(2).getSampleCount()).isZero();
        assertThat(windows.get(3).getSampleCount()).isEqualTo(3);
        assertThat(windows.get(3).getEndOffsetSeconds()).isEqualTo(33.0);
    }

    @Test
    @DisplayName("Should average only readings that are present, never treating null as zero")
    void testNullSafeAverages() {
        TelemetrySample first = sample(0.0, 80.0, 90.0);
        first.setCpuTempC(70.0);
        TelemetrySample second = sample(1.0, 100.0, null);
        second.setCpuTempC(null);

        List<TimeWindow> windows = aggregator.aggregate(List.of(first, second));

        TimeWindow window = windows.get(0);
        assertThat(window.getAvgCpuTotalLoad()).isEqualTo(90.0);
        assertThat(window.getAvgGpuLoad()).isEqualTo(90.0);
        assertThat(window.getAvgCpuTempC()).isEqualTo(70.0);
    }

    @Test
    @DisplayName("Streaming collector must produce the same boundary windows as the list API")
    void testStreamingCollectorMatchesListApiAtBoundaries() {
        TimeWindowAggregator.WindowCollector collector = aggregator.newCollector();
        collector.add(sample(0.0, 50.0, 60.0));
        collector.add(sample(10.0, 51.0, 61.0));
        collector.add(sample(20.0, 52.0, 62.0));
        List<TimeWindow> windows = collector.finish();

        assertThat(windows).hasSize(2);
        assertThat(windows.get(0).getEndOffsetSeconds()).isEqualTo(10.0);
        assertThat(windows.get(1).getEndOffsetSeconds()).isEqualTo(20.0);
        assertThat(windows.get(0).getSampleCount()).isEqualTo(1);
        assertThat(windows.get(1).getSampleCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Streaming collector must not retain raw samples and must expose only aggregates")
    void testStreamingCollectorEmitsEmptyGapWindows() {
        TimeWindowAggregator.WindowCollector collector = aggregator.newCollector();
        collector.add(sample(0.0, 50.0, 60.0));
        collector.add(sample(31.0, 50.0, 60.0));
        collector.add(sample(32.0, 50.0, 60.0));
        collector.add(sample(33.0, 50.0, 60.0));
        List<TimeWindow> windows = collector.finish();

        assertThat(windows).hasSize(4);
        assertThat(windows.get(1).getSampleCount()).isZero();
        assertThat(windows.get(2).getSampleCount()).isZero();
        assertThat(windows.get(1).isHasDataGap()).isTrue();
        assertThat(windows.get(3).getEndOffsetSeconds()).isEqualTo(33.0);
    }

    @Test
    @DisplayName("Clock averages must include active CPU/GPU samples independently and exclude idle ones")
    void testActiveClockAveragesExcludeIdleSamples() {
        TelemetrySample activeCpuIdleGpu = sample(0.0, 90.0, 2.0);
        activeCpuIdleGpu.setCpuClockMhz(4500.0);
        activeCpuIdleGpu.setGpuClockMhz(500.0);

        TelemetrySample idleCpuActiveGpu = sample(1.0, 3.0, 95.0);
        idleCpuActiveGpu.setCpuClockMhz(1200.0);
        idleCpuActiveGpu.setGpuClockMhz(2000.0);

        TimeWindow window = aggregator.aggregate(List.of(activeCpuIdleGpu, idleCpuActiveGpu)).get(0);

        assertThat(window.getAvgCpuClockMhz()).isEqualTo(4500.0);
        assertThat(window.getAvgGpuClockMhz()).isEqualTo(2000.0);
        assertThat(window.getActiveCpuClockSampleCount()).isEqualTo(1);
        assertThat(window.getActiveGpuClockSampleCount()).isEqualTo(1);
    }
}
