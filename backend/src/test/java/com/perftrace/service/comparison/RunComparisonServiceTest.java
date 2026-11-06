package com.perftrace.service.comparison;

import com.perftrace.dto.RunComparisonDto;
import com.perftrace.entity.AnalysisRun;
import com.perftrace.entity.RunStatus;
import com.perftrace.entity.User;
import com.perftrace.entity.WorkloadType;
import com.perftrace.exception.ResourceNotFoundException;
import com.perftrace.repository.AnalysisRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunComparisonServiceTest {

    @Mock
    private AnalysisRunRepository runRepository;

    private RunComparisonService comparisonService;

    private User testUser;
    private AnalysisRun runA;
    private AnalysisRun runB;

    @BeforeEach
    void setUp() {
        comparisonService = new RunComparisonService(runRepository);

        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("testuser");

        runA = new AnalysisRun();
        runA.setId(10L);
        runA.setUser(testUser);
        runA.setFileName("runA.csv");
        runA.setStatus(RunStatus.COMPLETED);
        runA.setTotalDurationSeconds(300.0);
        runA.setCpuBoundSeconds(150.0);
        runA.setGpuBoundSeconds(50.0);
        runA.setBalancedSeconds(50.0);
        runA.setIdleSeconds(50.0);
        runA.setThrottleDurationSeconds(20.0);
        runA.setPeakCpuTempC(88.0);
        runA.setPeakGpuTempC(75.0);
        runA.setAvgCpuClockMhz(4400.0);
        runA.setAvgGpuClockMhz(1900.0);
        runA.setRecognizedSensors("CPU Total Load, GPU Load, CPU Temperature, GPU Temperature");

        runB = new AnalysisRun();
        runB.setId(20L);
        runB.setUser(testUser);
        runB.setFileName("runB.csv");
        runB.setStatus(RunStatus.COMPLETED);
        runB.setTotalDurationSeconds(320.0);
        runB.setCpuBoundSeconds(60.0);
        runB.setGpuBoundSeconds(180.0);
        runB.setBalancedSeconds(40.0);
        runB.setIdleSeconds(40.0);
        runB.setThrottleDurationSeconds(0.0);
        runB.setPeakCpuTempC(78.0);
        runB.setPeakGpuTempC(76.0);
        runB.setAvgCpuClockMhz(4500.0);
        runB.setAvgGpuClockMhz(1950.0);
        runB.setRecognizedSensors("CPU Total Load, GPU Load, CPU Temperature, GPU Temperature, RAM Usage");
    }

    @Test
    @DisplayName("Should reject comparing a run with itself")
    void testRejectIdenticalRunIds() {
        assertThatThrownBy(() -> comparisonService.compareRuns(10L, 10L, 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Cannot compare a run with itself");
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when run does not exist or belongs to another user")
    void testRejectNonexistentOrUnownedRun() {
        when(runRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(runA));
        when(runRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> comparisonService.compareRuns(10L, 99L, 1L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Run B not found or you do not have permission");
    }

    @Test
    @DisplayName("Should successfully compare two valid runs and compute differences")
    void testSuccessfulComparison() {
        when(runRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(runA));
        when(runRepository.findByIdAndUserId(20L, 1L)).thenReturn(Optional.of(runB));

        RunComparisonDto result = comparisonService.compareRuns(10L, 20L, 1L);

        assertThat(result.getRunA().getFileName()).isEqualTo("runA.csv");
        assertThat(result.getRunB().getFileName()).isEqualTo("runB.csv");
        assertThat(result.getMetrics()).isNotEmpty();

        // Check sensor coverage difference
        assertThat(result.getSensorCoverageDiffs())
            .anyMatch(s -> s.contains("RAM Usage"));

        // Check conclusion disclaimer
        assertThat(result.getConclusionNote())
            .contains("A reduction in temperature does not inherently prove higher performance");
    }

    @Test
    @DisplayName("Should warn when one run has no declared workload type")
    void testUnspecifiedWorkloadWarning() {
        runA.setWorkloadType(WorkloadType.GAMING);
        runB.setWorkloadType(WorkloadType.UNSPECIFIED);

        when(runRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(runA));
        when(runRepository.findByIdAndUserId(20L, 1L)).thenReturn(Optional.of(runB));

        RunComparisonDto result = comparisonService.compareRuns(10L, 20L, 1L);

        assertThat(result.getComparabilityWarnings())
            .anyMatch(w -> w.contains("Workload type is unspecified for Run B")
                && w.contains("does not infer workload type"));
    }

    @Test
    @DisplayName("Should warn when the two runs declare different workload types")
    void testDifferentWorkloadTypeWarning() {
        runA.setWorkloadType(WorkloadType.GAMING);
        runB.setWorkloadType(WorkloadType.GPU_RENDER);

        when(runRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(runA));
        when(runRepository.findByIdAndUserId(20L, 1L)).thenReturn(Optional.of(runB));

        RunComparisonDto result = comparisonService.compareRuns(10L, 20L, 1L);

        assertThat(result.getComparabilityWarnings())
            .anyMatch(w -> w.contains("Different declared workload types")
                && w.contains("Run A is GAMING")
                && w.contains("Run B is GPU_RENDER"));
    }

    @Test
    @DisplayName("Should not emit a workload mismatch warning when both runs declare the same type")
    void testMatchingWorkloadTypeProducesNoWorkloadWarning() {
        runA.setWorkloadType(WorkloadType.GAMING);
        runB.setWorkloadType(WorkloadType.GAMING);

        when(runRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(runA));
        when(runRepository.findByIdAndUserId(20L, 1L)).thenReturn(Optional.of(runB));

        RunComparisonDto result = comparisonService.compareRuns(10L, 20L, 1L);

        assertThat(result.getComparabilityWarnings())
            .noneMatch(w -> w.contains("workload type") || w.contains("Workload type"));
    }

    @Test
    @DisplayName("Should warn when run durations differ by more than 30%")
    void testDurationDisparityWarning() {
        runB.setTotalDurationSeconds(700.0); // 700s vs 300s (ratio 2.3x > 1.3x)

        when(runRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(runA));
        when(runRepository.findByIdAndUserId(20L, 1L)).thenReturn(Optional.of(runB));

        RunComparisonDto result = comparisonService.compareRuns(10L, 20L, 1L);

        assertThat(result.getComparabilityWarnings())
            .anyMatch(w -> w.contains("Run duration disparity"));
    }

    @Test
    @DisplayName("Should warn when idle ratios differ by more than 25 percentage points")
    void testIdleDisparityWarning() {
        runA.setIdleSeconds(50.0);   // 50 / 300 = 16.7%
        runB.setIdleSeconds(150.0);  // 150 / 320 = 46.9%

        when(runRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(runA));
        when(runRepository.findByIdAndUserId(20L, 1L)).thenReturn(Optional.of(runB));

        RunComparisonDto result = comparisonService.compareRuns(10L, 20L, 1L);

        assertThat(result.getComparabilityWarnings())
            .anyMatch(w -> w.contains("Idle state disparity"));
    }

    @Test
    @DisplayName("Should use observed data duration, not raw elapsed duration, for active time")
    void testActiveDurationUsesObservedData() {
        runA.setTotalDurationSeconds(1000.0);
        runA.setValidDataDurationSeconds(300.0);
        runA.setIdleSeconds(50.0);

        when(runRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(runA));
        when(runRepository.findByIdAndUserId(20L, 1L)).thenReturn(Optional.of(runB));

        RunComparisonDto result = comparisonService.compareRuns(10L, 20L, 1L);

        var activeMetric = result.getMetrics().stream()
            .filter(m -> m.getName().equals("Active Workload Duration"))
            .findFirst()
            .orElseThrow();

        assertThat(activeMetric.getValueA()).isEqualTo(250.0);
        assertThat(result.getRunA().getCpuBoundActivePercent()).isEqualTo(60.0); // 150 / 250
    }

    @Test
    @DisplayName("Should report N/A percentages instead of 0% when there is no active time")
    void testNoActiveTimeProducesNullPercentages() {
        runA.setTotalDurationSeconds(0.0);
        runA.setValidDataDurationSeconds(0.0);
        runA.setIdleSeconds(0.0);
        runA.setCpuBoundSeconds(0.0);
        runA.setGpuBoundSeconds(0.0);
        runA.setBalancedSeconds(0.0);

        runB.setTotalDurationSeconds(0.0);
        runB.setValidDataDurationSeconds(0.0);
        runB.setIdleSeconds(0.0);
        runB.setCpuBoundSeconds(0.0);
        runB.setGpuBoundSeconds(0.0);
        runB.setBalancedSeconds(0.0);

        when(runRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(runA));
        when(runRepository.findByIdAndUserId(20L, 1L)).thenReturn(Optional.of(runB));

        RunComparisonDto result = comparisonService.compareRuns(10L, 20L, 1L);

        var cpuMetric = result.getMetrics().stream()
            .filter(m -> m.getName().equals("CPU-Bound Active Time"))
            .findFirst()
            .orElseThrow();

        assertThat(cpuMetric.getValueA()).isNull();
        assertThat(cpuMetric.getValueB()).isNull();
        assertThat(cpuMetric.getDifference()).isNull();
        assertThat(result.getRunA().getCpuBoundActivePercent()).isNull();
        assertThat(result.getRunA().getIdleTotalPercent()).isNull();
    }
}
