package com.perftrace.service.analysis;

import com.perftrace.entity.AnalysisRun;
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

class TelemetryAnalysisServiceTest {

    private TelemetryAnalysisService service;
    private AnalysisRun run;

    @BeforeEach
    void setUp() {
        service = new TelemetryAnalysisService(
            new TimeWindowAggregator(10.0),
            new CpuBoundDetector(),
            new GpuBoundDetector(),
            new IdleDetector(),
            new ThermalThrottleDetector(),
            new SegmentMergingService(20.0)
        );
        run = new AnalysisRun();
        run.setFileName("test.csv");
    }

    private TelemetrySample sample(double offset, Double cpu, Double gpu) {
        TelemetrySample s = new TelemetrySample();
        s.setTimeOffsetSeconds(offset);
        s.setCpuTotalLoad(cpu);
        s.setCpuMaxThreadLoad(cpu == null ? null : Math.min(100.0, cpu + 5));
        s.setGpuLoad(gpu);
        s.setCpuTempC(65.0);
        s.setGpuTempC(60.0);
        s.setCpuClockMhz(4500.0);
        s.setGpuClockMhz(1900.0);
        s.setCpuThrottleFlag(false);
        s.setGpuThrottleFlag(false);
        return s;
    }

    private List<TelemetrySample> samples(double... offsets) {
        List<TelemetrySample> list = new ArrayList<>();
        for (double offset : offsets) {
            list.add(sample(offset, 50.0, 50.0));
        }
        return list;
    }

    @Test
    @DisplayName("Should classify CPU-bound windows and report the actual elapsed duration")
    void testCpuBoundClassification() {
        List<TelemetrySample> input = List.of(
            sample(0.0, 92.0, 60.0),
            sample(2.0, 93.0, 61.0),
            sample(4.0, 94.0, 62.0),
            sample(6.0, 92.5, 60.0)
        );

        TelemetryAnalysisService.AnalysisResult result = service.analyze(run, input);

        assertThat(result.getSegments()).hasSize(1);
        DetectedSegment segment = result.getSegments().get(0);
        assertThat(segment.getClassification()).isEqualTo(Classification.CPU_BOUND);
        assertThat(segment.getDurationSeconds()).isEqualTo(6.0);
        assertThat(segment.getSampleCount()).isEqualTo(4);

        assertThat(run.getCpuBoundSeconds()).isEqualTo(6.0);
        assertThat(run.getTotalDurationSeconds()).isEqualTo(6.0);
        assertThat(run.getValidDataDurationSeconds()).isEqualTo(6.0);
        assertThat(run.getGpuBoundSeconds()).isZero();
        assertThat(run.getUnknownSeconds()).isZero();
    }

    @Test
    @DisplayName("Should classify GPU-bound windows when GPU saturation and CPU thread headroom are present")
    void testGpuBoundClassification() {
        List<TelemetrySample> input = List.of(
            sample(0.0, 45.0, 98.0),
            sample(2.0, 46.0, 99.0),
            sample(4.0, 44.0, 97.5),
            sample(6.0, 47.0, 98.5)
        );

        TelemetryAnalysisService.AnalysisResult result = service.analyze(run, input);

        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).getClassification()).isEqualTo(Classification.GPU_BOUND);
        assertThat(run.getGpuBoundSeconds()).isEqualTo(6.0);
        assertThat(run.getCpuBoundSeconds()).isZero();
    }

    @Test
    @DisplayName("Should classify idle windows and exclude them from active time")
    void testIdleClassification() {
        List<TelemetrySample> input = List.of(
            sample(0.0, 5.0, 4.0),
            sample(2.0, 6.0, 5.0),
            sample(4.0, 7.0, 6.0)
        );

        TelemetryAnalysisService.AnalysisResult result = service.analyze(run, input);

        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).getClassification()).isEqualTo(Classification.IDLE);
        assertThat(run.getIdleSeconds()).isEqualTo(4.0);
        assertThat(run.getCpuBoundSeconds()).isZero();
    }

    @Test
    @DisplayName("Missing busiest-thread evidence must produce UNKNOWN, never BALANCED")
    void testMissingThreadEvidenceProducesUnknown() {
        List<TelemetrySample> input = new ArrayList<>();
        for (double offset : new double[]{0.0, 2.0, 4.0}) {
            TelemetrySample s = sample(offset, 70.0, 60.0);
            s.setCpuMaxThreadLoad(null);
            input.add(s);
        }

        TelemetryAnalysisService.AnalysisResult result = service.analyze(run, input);

        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).getClassification()).isEqualTo(Classification.UNKNOWN);
        assertThat(run.getBalancedSeconds()).isZero();
        assertThat(run.getUnknownSeconds()).isEqualTo(4.0);
        assertThat(result.getWarnings())
            .anyMatch(w -> w.contains("Busiest CPU thread load was unavailable"));
    }

    @Test
    @DisplayName("Missing GPU readings must produce UNKNOWN rather than an idle or balanced guess")
    void testMissingGpuLoadProducesUnknown() {
        List<TelemetrySample> input = List.of(
            sample(0.0, 90.0, null),
            sample(2.0, 91.0, null),
            sample(4.0, 92.0, null)
        );

        TelemetryAnalysisService.AnalysisResult result = service.analyze(run, input);

        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).getClassification()).isEqualTo(Classification.UNKNOWN);
        assertThat(run.getUnknownSeconds()).isEqualTo(4.0);
    }

    @Test
    @DisplayName("Should keep telemetry gaps as UNKNOWN segments and count them as warnings")
    void testGapWindowsBecomeUnknown() {
        TelemetryAnalysisService.AnalysisResult result = service.analyze(run, samples(0.0, 1.0, 2.0, 31.0, 32.0, 33.0));

        assertThat(result.getWarnings()).anyMatch(w -> w.contains("2 time window(s) without telemetry"));
        assertThat(run.getTotalDurationSeconds()).isEqualTo(33.0);
        assertThat(run.getValidDataDurationSeconds()).isEqualTo(13.0);
        assertThat(run.getUnknownSeconds()).isEqualTo(20.0);
        assertThat(run.getBalancedSeconds()).isEqualTo(13.0);
    }

    @Test
    @DisplayName("Thermal baseline must not collapse when most windows are already throttled")
    void testThermalBaselineExcludesMajorityThrottledWindows() {
        // 18 of 20 active windows are already compressed to 3000 MHz at 99 C. A plain
        // mean/P90 over every window would use ~3180 MHz as the reference and would hide
        // the regression. The preliminary P95 baseline is taken from the two healthy
        // windows, so all 18 compressed windows are excluded before the final P90.
        List<TelemetrySample> input = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            TelemetrySample s = sample(i * 10.0 + 5.0, 92.0, 60.0);
            s.setCpuTempC(99.0);
            s.setCpuClockMhz(3000.0);
            s.setCpuThrottleFlag(null);
            s.setGpuThrottleFlag(null);
            input.add(s);
        }
        for (int i = 18; i < 20; i++) {
            TelemetrySample s = sample(i * 10.0 + 5.0, 92.0, 60.0);
            s.setCpuTempC(70.0);
            s.setCpuClockMhz(4800.0);
            s.setCpuThrottleFlag(null);
            s.setGpuThrottleFlag(null);
            input.add(s);
        }

        TelemetryAnalysisService.AnalysisResult result = service.analyze(run, input);

        assertThat(result.getWarnings())
            .anyMatch(w -> w.contains("18 active window(s) were excluded from the thermal clock baseline"));
        assertThat(result.getSegments()).hasSize(2);
        DetectedSegment compressed = result.getSegments().get(0);
        assertThat(compressed.getThrottleEvidence()).isEqualTo(ThrottleEvidence.HEURISTIC_DETECTED);
        assertThat(compressed.getDurationSeconds()).isEqualTo(180.0);
        assertThat(compressed.getEvidenceExplanation()).contains("baseline 4800 MHz");
        assertThat(run.getHeuristicThrottleSeconds()).isEqualTo(180.0);
        assertThat(result.getSegments().get(1).getThrottleEvidence()).isEqualTo(ThrottleEvidence.NONE);
    }

    @Test
    @DisplayName("Throttle baseline should use the 90th percentile, not the mean, of non-idle clocks")
    void testRobustClockBaselineUsesPercentile() {
        // Clocks sorted: [4200, 4600, 4700, 4800, 4900]. The 90th percentile is 4800 MHz,
        // while the mean is 4640 MHz. At 4200 MHz the window is below 88% of the percentile
        // baseline but above 88% of the mean, so a mean baseline would hide the regression.
        List<TelemetrySample> input = new ArrayList<>();
        double[] clocks = {4200.0, 4600.0, 4700.0, 4800.0, 4900.0};
        for (int i = 0; i < clocks.length; i++) {
            TelemetrySample s = sample(i * 10.0 + 5.0, 90.0, 60.0);
            s.setCpuClockMhz(clocks[i]);
            s.setCpuTempC(i == 0 ? 95.0 : 70.0);
            s.setCpuThrottleFlag(null);
            s.setGpuThrottleFlag(null);
            input.add(s);
        }

        TelemetryAnalysisService.AnalysisResult result = service.analyze(run, input);

        List<DetectedSegment> segments = result.getSegments();
        assertThat(segments).isNotEmpty();
        assertThat(segments.get(0).getThrottleEvidence()).isEqualTo(ThrottleEvidence.HEURISTIC_DETECTED);
        assertThat(segments.get(0).getEvidenceExplanation()).contains("baseline 4800 MHz");
    }
}
