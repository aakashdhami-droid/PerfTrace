package com.perftrace.service.analysis;

import com.perftrace.dto.*;
import com.perftrace.entity.*;
import com.perftrace.exception.InvalidFileException;
import com.perftrace.exception.ResourceNotFoundException;
import com.perftrace.repository.AnalysisRunRepository;
import com.perftrace.repository.DetectedSegmentRepository;
import com.perftrace.repository.TelemetrySampleRepository;
import com.perftrace.service.comparison.RunComparisonService;
import com.perftrace.service.csv.CsvIngestionService;
import com.perftrace.service.csv.CsvScanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class AnalysisRunService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRunService.class);

    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final int MAX_DESCRIPTION_LENGTH = 500;
    private static final int MAX_ERROR_LENGTH = 1000;
    private static final int MAX_WARNINGS_LENGTH = 2000;
    private static final int SAMPLE_BATCH_SIZE = 1000;
    private static final int DEFAULT_MAX_CHART_POINTS = 500;
    private static final int MIN_CHART_POINTS = 10;
    private static final int MAX_CHART_POINTS = 5000;

    private static final String SAMPLE_COLUMNS =
        "id, time_offset_seconds, cpu_total_load, cpu_max_thread_load, cpu_temp_c, cpu_clock_mhz, " +
        "gpu_load, gpu_temp_c, gpu_clock_mhz, cpu_throttle_flag, gpu_throttle_flag, " +
        "ram_usage_percent, cpu_power_w, gpu_power_w";

    /**
     * Downsampling happens in PostgreSQL/SQL rather than by loading every JPA entity.
     *
     * <p>Each bucket emits its lowest-stress and highest-stress sample. The stress score
     * normalizes loads, temperatures, clocks and power onto an approximate 0–100 scale so
     * short peaks are preserved instead of being averaged away by plain decimation.</p>
     */
    private static final String DOWNSAMPLE_SQL =
        "WITH bucketed AS (" +
        "  SELECT " + SAMPLE_COLUMNS + ", " +
        "    NTILE(?) OVER (ORDER BY time_offset_seconds) AS bucket_id, " +
        "    GREATEST(" +
        "      COALESCE(cpu_total_load, 0), " +
        "      COALESCE(cpu_max_thread_load, 0), " +
        "      COALESCE(gpu_load, 0), " +
        "      COALESCE(ram_usage_percent, 0), " +
        "      (COALESCE(cpu_temp_c, 0) - 40.0) * 2.0, " +
        "      (COALESCE(gpu_temp_c, 0) - 40.0) * 2.0, " +
        "      COALESCE(cpu_clock_mhz, 0) / 50.0, " +
        "      COALESCE(gpu_clock_mhz, 0) / 20.0, " +
        "      COALESCE(cpu_power_w, 0) / 2.0, " +
        "      COALESCE(gpu_power_w, 0) / 3.0" +
        "    ) AS stress " +
        "  FROM telemetry_samples WHERE run_id = ?" +
        "), ranked AS (" +
        "  SELECT * , " +
        "    ROW_NUMBER() OVER (PARTITION BY bucket_id ORDER BY stress DESC, time_offset_seconds ASC) AS max_rank, " +
        "    ROW_NUMBER() OVER (PARTITION BY bucket_id ORDER BY stress ASC, time_offset_seconds ASC) AS min_rank " +
        "  FROM bucketed" +
        ") " +
        "SELECT " + SAMPLE_COLUMNS + " FROM ranked " +
        "WHERE max_rank = 1 OR min_rank = 1 " +
        "ORDER BY time_offset_seconds";

    private final AnalysisRunRepository runRepository;
    private final TelemetrySampleRepository sampleRepository;
    private final DetectedSegmentRepository segmentRepository;
    private final CsvIngestionService csvIngestionService;
    private final TelemetryAnalysisService telemetryAnalysisService;
    private final RunComparisonService comparisonService;
    private final TimeWindowAggregator windowAggregator;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;

    public AnalysisRunService(
        AnalysisRunRepository runRepository,
        TelemetrySampleRepository sampleRepository,
        DetectedSegmentRepository segmentRepository,
        CsvIngestionService csvIngestionService,
        TelemetryAnalysisService telemetryAnalysisService,
        RunComparisonService comparisonService,
        TimeWindowAggregator windowAggregator,
        JdbcTemplate jdbcTemplate,
        PlatformTransactionManager transactionManager
    ) {
        this.runRepository = runRepository;
        this.sampleRepository = sampleRepository;
        this.segmentRepository = segmentRepository;
        this.csvIngestionService = csvIngestionService;
        this.telemetryAnalysisService = telemetryAnalysisService;
        this.comparisonService = comparisonService;
        this.windowAggregator = windowAggregator;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionManager = transactionManager;
    }

    public RunUploadResponse uploadAndProcess(MultipartFile file, String description, User user) {
        return uploadAndProcess(file, description, null, user);
    }

    /**
     * Parses and analyzes an uploaded CSV with bounded memory.
     *
     * <p>The upload is spooled to a temporary file, scanned once for header/timestamp
     * metadata, then streamed a second time straight into window aggregation and JDBC
     * batches. Deliberately not annotated with {@code @Transactional}: the run row is
     * created in its own committed transaction, file work happens outside any database
     * transaction, and only persistence is wrapped in a transaction. This guarantees the
     * processing transaction has fully rolled back (releasing its row lock) before the
     * FAILED status is written in a separate transaction.</p>
     */
    public RunUploadResponse uploadAndProcess(
        MultipartFile file,
        String description,
        String workloadType,
        User user
    ) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileException("Uploaded file is empty");
        }

        String originalFilename = file.getOriginalFilename();
        String fileName = sanitizeFileName(originalFilename);
        if (fileName == null) {
            throw new InvalidFileException("Uploaded file must have a .csv extension");
        }
        String normalizedDescription = normalizeDescription(description);
        WorkloadType normalizedWorkload = WorkloadType.fromString(workloadType);

        AnalysisRun run = new AnalysisRun();
        run.setUser(user);
        run.setFileName(fileName);
        run.setDescription(normalizedDescription);
        run.setWorkloadType(normalizedWorkload);
        run.setStatus(RunStatus.UPLOADING);
        run = createRun(run);

        Path spool = null;
        try {
            spool = Files.createTempFile("perftrace-upload-", ".csv");
            try (InputStream is = file.getInputStream()) {
                Files.copy(is, spool, StandardCopyOption.REPLACE_EXISTING);
            }

            // 1. First pass: header validation, row counts, timestamp baseline. No samples.
            CsvScanResult scan = csvIngestionService.scan(spool);
            applyScanMetadata(run, scan);

            // 2. Second pass: stream samples into windows + database inside one transaction,
            //    then analyze aggregate windows and persist segments. Failure rolls back both.
            List<String> warnings = persistAndAnalyze(run, spool, scan);

            RunUploadResponse response = new RunUploadResponse(
                run.getId(),
                run.getFileName(),
                run.getStatus(),
                "Telemetry log processed successfully: " + scan.getValidRows() + " valid samples analyzed."
            );
            response.setWorkloadType(run.getWorkloadType());
            response.setTotalRowsParsed(scan.getTotalRowsParsed());
            response.setValidRows(scan.getValidRows());
            response.setRejectedRows(scan.getRejectedRows());
            response.setTotalDurationSeconds(scan.getTotalDurationSeconds());
            return response;
        } catch (InvalidFileException e) {
            log.warn("Rejected telemetry CSV for run {}: {}", run.getId(), e.getMessage());
            persistFailedRun(run, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Failed processing telemetry CSV for run {}: {}", run.getId(), e.getMessage(), e);
            persistFailedRun(run, rootCauseMessage(e));
            throw new InvalidFileException(
                "Analysis processing failed for run #" + run.getId() + ". The run has been recorded as FAILED.");
        } finally {
            if (spool != null) {
                try {
                    Files.deleteIfExists(spool);
                } catch (IOException e) {
                    log.warn("Could not delete spool file {}: {}", spool, e.getMessage());
                }
            }
        }
    }

    private void applyScanMetadata(AnalysisRun run, CsvScanResult scan) {
        run.setTotalRows(scan.getTotalRowsParsed());
        run.setValidRows(scan.getValidRows());
        run.setRejectedRows(scan.getRejectedRows());
        run.setRecognizedSensors(String.join(", ", scan.getRecognizedSensors()));
        run.setMissingSensors(String.join(", ", scan.getMissingSensors()));
    }

    private List<String> persistAndAnalyze(AnalysisRun run, Path spool, CsvScanResult scan) {
        List<String> warnings = new ArrayList<>(scan.getWarnings());
        List<String> analysisWarnings = new ArrayList<>();

        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.executeWithoutResult(status -> {
            run.setStatus(RunStatus.ANALYZING);
            runRepository.save(run);

            TimeWindowAggregator.WindowCollector collector = windowAggregator.newCollector();
            SampleBatchWriter writer = new SampleBatchWriter(run.getId());
            try {
                csvIngestionService.streamSamples(spool, scan, sample -> {
                    collector.add(sample);
                    writer.add(sample);
                });
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to stream spooled CSV", e);
            }
            writer.flush();

            List<TimeWindow> windows = collector.finish();
            TelemetryAnalysisService.AnalysisResult analysis =
                telemetryAnalysisService.analyzeWindows(run, windows);
            analysisWarnings.addAll(analysis.getWarnings());

            warnings.addAll(analysis.getWarnings());
            run.setDataQualityWarnings(joinWarnings(warnings));

            for (DetectedSegment seg : analysis.getSegments()) {
                seg.setRun(run);
            }
            segmentRepository.saveAll(analysis.getSegments());

            run.setStatus(RunStatus.COMPLETED);
            run.setCompletedAt(Instant.now());
            run.setErrorMessage(null);
            runRepository.save(run);
        });

        return warnings;
    }

    private final class SampleBatchWriter {
        private final Long runId;
        private final List<Object[]> batch = new ArrayList<>(SAMPLE_BATCH_SIZE);

        private SampleBatchWriter(Long runId) {
            this.runId = runId;
        }

        private void add(TelemetrySample s) {
            batch.add(new Object[] {
                runId,
                s.getTimeOffsetSeconds(),
                s.getCpuTotalLoad(),
                s.getCpuMaxThreadLoad(),
                s.getCpuTempC(),
                s.getCpuClockMhz(),
                s.getGpuLoad(),
                s.getGpuTempC(),
                s.getGpuClockMhz(),
                s.getCpuThrottleFlag(),
                s.getGpuThrottleFlag(),
                s.getRamUsagePercent(),
                s.getCpuPowerW(),
                s.getGpuPowerW()
            });

            if (batch.size() == SAMPLE_BATCH_SIZE) {
                flush();
            }
        }

        private void flush() {
            if (batch.isEmpty()) return;
            String sql = "INSERT INTO telemetry_samples (" +
                "run_id, time_offset_seconds, cpu_total_load, cpu_max_thread_load, cpu_temp_c, cpu_clock_mhz, " +
                "gpu_load, gpu_temp_c, gpu_clock_mhz, cpu_throttle_flag, gpu_throttle_flag, ram_usage_percent, " +
                "cpu_power_w, gpu_power_w) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            jdbcTemplate.batchUpdate(sql, batch);
            batch.clear();
        }
    }

    @Transactional(readOnly = true)
    public Page<AnalysisRunDto> getUserRuns(Long userId, Pageable pageable) {
        return runRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
            .map(comparisonService::toDto);
    }

    @Transactional(readOnly = true)
    public AnalysisRunDto getRunById(Long runId, Long userId) {
        AnalysisRun run = runRepository.findById(runId)
            .orElseThrow(() -> new ResourceNotFoundException("Run not found (ID: " + runId + ")"));

        if (!run.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Run not found (ID: " + runId + ")");
        }

        return comparisonService.toDto(run);
    }

    @Transactional(readOnly = true)
    public List<DetectedSegmentDto> getRunSegments(Long runId, Long userId) {
        AnalysisRun run = runRepository.findById(runId)
            .orElseThrow(() -> new ResourceNotFoundException("Run not found (ID: " + runId + ")"));

        if (!run.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Run not found (ID: " + runId + ")");
        }

        List<DetectedSegment> segments = segmentRepository.findByRunIdOrderByStartOffsetSeconds(runId);
        List<DetectedSegmentDto> dtos = new ArrayList<>();
        for (DetectedSegment s : segments) {
            DetectedSegmentDto dto = new DetectedSegmentDto();
            dto.setId(s.getId());
            dto.setStartOffsetSeconds(s.getStartOffsetSeconds());
            dto.setEndOffsetSeconds(s.getEndOffsetSeconds());
            dto.setDurationSeconds(s.getDurationSeconds());
            dto.setClassification(s.getClassification());
            dto.setThrottleEvidence(s.getThrottleEvidence());
            dto.setEvidenceExplanation(s.getEvidenceExplanation());
            dto.setAvgCpuLoad(s.getAvgCpuLoad());
            dto.setAvgGpuLoad(s.getAvgGpuLoad());
            dto.setAvgCpuMaxThread(s.getAvgCpuMaxThread());
            dto.setAvgCpuTempC(s.getAvgCpuTempC());
            dto.setAvgGpuTempC(s.getAvgGpuTempC());
            dto.setAvgCpuClockMhz(s.getAvgCpuClockMhz());
            dto.setAvgGpuClockMhz(s.getAvgGpuClockMhz());
            dto.setPeakCpuTempC(s.getPeakCpuTempC());
            dto.setPeakGpuTempC(s.getPeakGpuTempC());
            dto.setSampleCount(s.getSampleCount());
            dtos.add(dto);
        }
        return dtos;
    }

    /**
     * Retrieves downsampled telemetry samples for rendering responsive time-series charts.
     * Min/max-per-bucket sampling happens in the database and preserves short peaks that
     * plain decimation would drop.
     */
    @Transactional(readOnly = true)
    public List<TelemetrySampleDto> getDownsampledSamples(Long runId, Long userId, int maxPoints) {
        AnalysisRun run = runRepository.findById(runId)
            .orElseThrow(() -> new ResourceNotFoundException("Run not found (ID: " + runId + ")"));

        if (!run.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Run not found (ID: " + runId + ")");
        }

        int limit = maxPoints <= 0 ? DEFAULT_MAX_CHART_POINTS : maxPoints;
        limit = Math.max(MIN_CHART_POINTS, Math.min(MAX_CHART_POINTS, limit));

        long total = sampleRepository.countByRunId(runId);
        if (total == 0) return List.of();

        RowMapper<TelemetrySampleDto> mapper = AnalysisRunService::mapSample;
        if (total <= limit) {
            return jdbcTemplate.query(
                "SELECT " + SAMPLE_COLUMNS + " FROM telemetry_samples WHERE run_id = ? " +
                    "ORDER BY time_offset_seconds LIMIT ?",
                mapper,
                runId,
                total
            );
        }

        int buckets = Math.max(1, limit / 2);
        return jdbcTemplate.query(DOWNSAMPLE_SQL, mapper, buckets, runId);
    }

    private static TelemetrySampleDto mapSample(ResultSet rs, int rowNum) throws SQLException {
        TelemetrySampleDto dto = new TelemetrySampleDto();
        dto.setId(rs.getLong("id"));
        dto.setTimeOffsetSeconds(rs.getDouble("time_offset_seconds"));
        dto.setCpuTotalLoad(nullableDouble(rs, "cpu_total_load"));
        dto.setCpuMaxThreadLoad(nullableDouble(rs, "cpu_max_thread_load"));
        dto.setCpuTempC(nullableDouble(rs, "cpu_temp_c"));
        dto.setCpuClockMhz(nullableDouble(rs, "cpu_clock_mhz"));
        dto.setGpuLoad(nullableDouble(rs, "gpu_load"));
        dto.setGpuTempC(nullableDouble(rs, "gpu_temp_c"));
        dto.setGpuClockMhz(nullableDouble(rs, "gpu_clock_mhz"));
        dto.setCpuThrottleFlag(nullableBoolean(rs, "cpu_throttle_flag"));
        dto.setGpuThrottleFlag(nullableBoolean(rs, "gpu_throttle_flag"));
        dto.setRamUsagePercent(nullableDouble(rs, "ram_usage_percent"));
        dto.setCpuPowerW(nullableDouble(rs, "cpu_power_w"));
        dto.setGpuPowerW(nullableDouble(rs, "gpu_power_w"));
        return dto;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    public AnalysisRun createRun(AnalysisRun run) {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tt.execute(status -> runRepository.save(run));
    }

    public void persistFailedRun(AnalysisRun run, String message) {
        try {
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tt.executeWithoutResult(status -> {
                run.setStatus(RunStatus.FAILED);
                run.setErrorMessage(truncate(message, MAX_ERROR_LENGTH));
                run.setCompletedAt(Instant.now());
                runRepository.save(run);
            });
        } catch (Exception persistenceError) {
            // Never mask the original processing failure with a failure to record it.
            log.error("Could not persist FAILED status for run {}", run.getId(), persistenceError);
        }
    }

    @Transactional
    public void deleteRun(Long runId, Long userId) {
        AnalysisRun run = runRepository.findById(runId)
            .orElseThrow(() -> new ResourceNotFoundException("Run not found (ID: " + runId + ")"));

        if (!run.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Run not found (ID: " + runId + ")");
        }

        // Delete children with set-based statements rather than Hibernate cascade. A run can
        // contain hundreds of thousands of samples, and loading every child entity just to
        // delete it would make large-run deletion slow and memory-hungry.
        sampleRepository.deleteByRunId(runId);
        segmentRepository.deleteByRunId(runId);
        runRepository.delete(run);
    }

    private String sanitizeFileName(String originalFilename) {
        if (originalFilename == null) return null;
        String name = originalFilename.replace('\\', '/');
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) name = name.substring(lastSlash + 1);
        name = name.trim();
        if (name.isEmpty() || !name.toLowerCase(Locale.ROOT).endsWith(".csv")) return null;
        if (name.length() > MAX_FILE_NAME_LENGTH) {
            name = name.substring(0, MAX_FILE_NAME_LENGTH - 4) + ".csv";
        }
        return name;
    }

    private String normalizeDescription(String description) {
        if (description == null) return null;
        String trimmed = description.trim();
        if (trimmed.length() > MAX_DESCRIPTION_LENGTH) {
            throw new InvalidFileException("Description must not exceed " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String joinWarnings(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) return null;
        Set<String> unique = new LinkedHashSet<>();
        for (String warning : warnings) {
            if (warning != null && !warning.isBlank()) unique.add(warning.trim());
        }
        if (unique.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        for (String warning : unique) {
            if (sb.length() > 0) sb.append("; ");
            if (sb.length() + warning.length() > MAX_WARNINGS_LENGTH - 20) {
                sb.append("... (truncated)");
                break;
            }
            sb.append(warning);
        }
        return sb.length() > MAX_WARNINGS_LENGTH
            ? sb.substring(0, MAX_WARNINGS_LENGTH)
            : sb.toString();
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            message = cause.getClass().getSimpleName();
        }
        return cause.getClass().getSimpleName() + ": " + message;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
