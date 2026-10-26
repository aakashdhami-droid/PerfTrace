package com.perftrace.service.analysis;

import com.perftrace.entity.TelemetrySample;
import com.perftrace.entity.ThrottleEvidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DetectorsTest {

    private CpuBoundDetector cpuBoundDetector;
    private GpuBoundDetector gpuBoundDetector;
    private IdleDetector idleDetector;
    private ThermalThrottleDetector throttleDetector;

    @BeforeEach
    void setUp() {
        cpuBoundDetector = new CpuBoundDetector();
        gpuBoundDetector = new GpuBoundDetector();
        idleDetector = new IdleDetector();
        throttleDetector = new ThermalThrottleDetector();
    }

    private TimeWindow createWindowWithSample(
        Double cpuTotal, Double cpuMaxThread, Double gpuLoad,
        Double cpuTemp, Double cpuClock, Boolean cpuThrottle
    ) {
        TimeWindow w = new TimeWindow(0.0, 10.0);
        TelemetrySample s = new TelemetrySample();
        s.setTimeOffsetSeconds(5.0);
        s.setCpuTotalLoad(cpuTotal);
        s.setCpuMaxThreadLoad(cpuMaxThread);
        s.setGpuLoad(gpuLoad);
        s.setCpuTempC(cpuTemp);
        s.setCpuClockMhz(cpuClock);
        s.setCpuThrottleFlag(cpuThrottle);
        w.addSample(s);
        w.computeAggregates(15.0);
        return w;
    }

    @Nested
    @DisplayName("CpuBoundDetector Tests")
    class CpuBoundTests {

        @Test
        @DisplayName("Should detect CPU-bound when GPU < 90% and busiest thread > 90%")
        void testCpuBoundBusiestThread() {
            TimeWindow w = createWindowWithSample(65.0, 95.0, 70.0, 70.0, 4500.0, false);
            assertThat(cpuBoundDetector.isCpuBound(w)).isTrue();
            assertThat(cpuBoundDetector.buildEvidence(w)).contains("busiest CPU thread saturated at 95.0%");
        }

        @Test
        @DisplayName("Should detect CPU-bound when GPU < 90% and CPU total > 90%")
        void testCpuBoundTotalLoad() {
            TimeWindow w = createWindowWithSample(92.0, 88.0, 85.0, 75.0, 4400.0, false);
            assertThat(cpuBoundDetector.isCpuBound(w)).isTrue();
            assertThat(cpuBoundDetector.buildEvidence(w)).contains("total CPU utilization saturated at 92.0%");
        }

        @Test
        @DisplayName("Should NOT detect CPU-bound when GPU is saturated (>= 90%)")
        void testNotCpuBoundWhenGpuSaturated() {
            TimeWindow w = createWindowWithSample(95.0, 98.0, 95.0, 80.0, 4500.0, false);
            assertThat(cpuBoundDetector.isCpuBound(w)).isFalse();
        }

        @Test
        @DisplayName("Should NOT detect CPU-bound when CPU load is moderate (< 90%)")
        void testNotCpuBoundWhenCpuModerate() {
            TimeWindow w = createWindowWithSample(60.0, 75.0, 70.0, 65.0, 4500.0, false);
            assertThat(cpuBoundDetector.isCpuBound(w)).isFalse();
        }
    }

    @Nested
    @DisplayName("GpuBoundDetector Tests")
    class GpuBoundTests {

        @Test
        @DisplayName("Should detect GPU-bound when GPU >= 95% and busiest thread < 85%")
        void testGpuBoundPositive() {
            TimeWindow w = createWindowWithSample(40.0, 60.0, 98.0, 68.0, 4600.0, false);
            assertThat(gpuBoundDetector.isGpuBound(w)).isTrue();
            assertThat(gpuBoundDetector.buildEvidence(w)).contains("GPU load at 98.0%");
        }

        @Test
        @DisplayName("Should NOT detect GPU-bound when GPU < 95%")
        void testNotGpuBoundWhenGpuUnder95() {
            TimeWindow w = createWindowWithSample(40.0, 60.0, 92.0, 68.0, 4600.0, false);
            assertThat(gpuBoundDetector.isGpuBound(w)).isFalse();
        }

        @Test
        @DisplayName("Should NOT detect GPU-bound when CPU busiest thread is heavily loaded (>= 85%)")
        void testNotGpuBoundWhenCpuAlsoSaturated() {
            TimeWindow w = createWindowWithSample(80.0, 90.0, 96.0, 75.0, 4400.0, false);
            assertThat(gpuBoundDetector.isGpuBound(w)).isFalse();
        }
    }

    @Nested
    @DisplayName("IdleDetector Tests")
    class IdleTests {

        @Test
        @DisplayName("Should detect Idle when both CPU total and GPU load < 15%")
        void testIdlePositive() {
            TimeWindow w = createWindowWithSample(8.0, 10.0, 5.0, 45.0, 2000.0, false);
            assertThat(idleDetector.isIdle(w)).isTrue();
            assertThat(idleDetector.buildEvidence(w)).contains("Idle/Low activity");
        }

        @Test
        @DisplayName("Should NOT detect Idle when CPU >= 15%")
        void testNotIdleWhenCpuActive() {
            TimeWindow w = createWindowWithSample(25.0, 30.0, 5.0, 50.0, 3500.0, false);
            assertThat(idleDetector.isIdle(w)).isFalse();
        }

        @Test
        @DisplayName("Should NOT detect Idle when GPU >= 15%")
        void testNotIdleWhenGpuActive() {
            TimeWindow w = createWindowWithSample(8.0, 10.0, 35.0, 55.0, 3000.0, false);
            assertThat(idleDetector.isIdle(w)).isFalse();
        }
    }

    @Nested
    @DisplayName("ThermalThrottleDetector Tests")
    class ThermalThrottleTests {

        @Test
        @DisplayName("Should confirm throttling via explicit hardware flag")
        void testHardwareFlagDetection() {
            TimeWindow w = createWindowWithSample(85.0, 90.0, 80.0, 95.0, 3800.0, true);
            var res = throttleDetector.detect(w, 4500.0, 2000.0);

            assertThat(res.evidence()).isEqualTo(ThrottleEvidence.HARDWARE_FLAG);
            assertThat(res.explanation()).contains("Confirmed thermal throttling: Hardware register explicitly flagged");
        }

        @Test
        @DisplayName("Should detect throttling via conservative heuristic when flags are absent")
        void testHeuristicDetection() {
            // High temp (94C >= 90C), heavy load (85% >= 50%), clock depressed (3800 MHz vs baseline 4500 MHz, >12% drop)
            TimeWindow w = createWindowWithSample(85.0, 90.0, 80.0, 94.0, 3800.0, null);
            var res = throttleDetector.detect(w, 4500.0, 2000.0);

            assertThat(res.evidence()).isEqualTo(ThrottleEvidence.HEURISTIC_DETECTED);
            assertThat(res.explanation()).contains("Potential CPU thermal throttling inferred");
        }

        @Test
        @DisplayName("Safeguard: High temperature alone without clock depression does NOT trigger throttling")
        void testSafeguardHighTempAloneDoesNotThrottle() {
            // High temp (91C >= 90C), heavy load (85%), but clock remains high (4450 MHz vs baseline 4500 MHz, only ~1% drop)
            TimeWindow w = createWindowWithSample(85.0, 90.0, 80.0, 91.0, 4450.0, null);
            var res = throttleDetector.detect(w, 4500.0, 2000.0);

            assertThat(res.evidence()).isEqualTo(ThrottleEvidence.NONE);
            assertThat(res.explanation()).contains("Elevated temperature observed without evidence of frequency throttling");
        }

        @Test
        @DisplayName("Safeguard: Clock drop under idle workload does NOT trigger thermal throttling")
        void testSafeguardClockDropUnderIdleDoesNotThrottle() {
            // High temp (91C), but workload is idle (10%), clock low (1500 MHz) due to normal C-states
            TimeWindow w = createWindowWithSample(10.0, 12.0, 8.0, 91.0, 1500.0, null);
            var res = throttleDetector.detect(w, 4500.0, 2000.0);

            assertThat(res.evidence()).isEqualTo(ThrottleEvidence.NONE);
        }

        @Test
        @DisplayName("Should return INSUFFICIENT_DATA when temperature sensors are absent")
        void testInsufficientDataWhenTempMissing() {
            TimeWindow w = createWindowWithSample(80.0, 85.0, 80.0, null, 4200.0, null);
            var res = throttleDetector.detect(w, 4500.0, 2000.0);

            assertThat(res.evidence()).isEqualTo(ThrottleEvidence.INSUFFICIENT_DATA);
            assertThat(res.explanation()).contains("No CPU or GPU temperature sensors were available");
        }
    }
}
