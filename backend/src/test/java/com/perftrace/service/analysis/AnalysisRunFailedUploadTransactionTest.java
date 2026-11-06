package com.perftrace.service.analysis;

import com.perftrace.entity.AnalysisRun;
import com.perftrace.entity.DetectedSegment;
import com.perftrace.entity.RunStatus;
import com.perftrace.entity.User;
import com.perftrace.exception.InvalidFileException;
import com.perftrace.repository.AnalysisRunRepository;
import com.perftrace.repository.TelemetrySampleRepository;
import com.perftrace.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Proves that a database failure while persisting analysis results rolls back the batch of
 * samples/segments and still records the run as FAILED in a separate transaction. The test
 * uses the real CSV streaming pipeline and the real repositories so the failure comes from
 * an actual database constraint rather than a mocked repository throwing.
 */
@SpringBootTest
class AnalysisRunFailedUploadTransactionTest {

    @Autowired
    private AnalysisRunService analysisRunService;

    @Autowired
    private AnalysisRunRepository runRepository;

    @Autowired
    private TelemetrySampleRepository sampleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private TelemetryAnalysisService telemetryAnalysisService;

    @BeforeEach
    void cleanDatabase() {
        runRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @Timeout(20)
    void failedUploadPersistsFailedRunAndRollsBackPartialData() throws Exception {
        User user = new User();
        user.setUsername("txuser");
        user.setEmail("txuser@example.com");
        user.setPasswordHash("hash");
        final User savedUser = userRepository.save(user);

        // A segment with no classification violates detected_segments.classification NOT NULL.
        // This fails inside the persistence transaction after the sample batch has been inserted,
        // which is exactly the partial-write state the rollback must clean up.
        DetectedSegment invalidSegment = new DetectedSegment();
        invalidSegment.setStartOffsetSeconds(0.0);
        invalidSegment.setEndOffsetSeconds(4.0);
        invalidSegment.setDurationSeconds(4.0);
        invalidSegment.setClassification(null);

        TelemetryAnalysisService.AnalysisResult analysisResult =
            new TelemetryAnalysisService.AnalysisResult(List.of(invalidSegment), List.of());
        when(telemetryAnalysisService.analyzeWindows(any(), anyList())).thenReturn(analysisResult);

        String csv = """
            Date,Time,"Total CPU Usage [%]","GPU Core Load [%]"
            26.10.2026,12:00:00,92.0,60.0
            26.10.2026,12:00:02,93.0,61.0
            26.10.2026,12:00:04,94.0,62.0
            """;
        MultipartFile file = new MockMultipartFile(
            "file", "x.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> analysisRunService.uploadAndProcess(file, "desc", savedUser))
            .isInstanceOf(InvalidFileException.class)
            .hasMessageContaining("recorded as FAILED");

        assertThat(runRepository.count()).isEqualTo(1);
        AnalysisRun run = runRepository.findAll().get(0);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorMessage()).isNotBlank();
        assertThat(run.getCompletedAt()).isNotNull();

        // The failed transaction must not leave partially inserted child rows behind.
        assertThat(sampleRepository.count()).isZero();
        Integer segCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM detected_segments", Integer.class);
        assertThat(segCount).isZero();
    }
}
