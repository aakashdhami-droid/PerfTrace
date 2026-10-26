package com.perftrace.service.analysis;

import com.perftrace.dto.*;
import com.perftrace.entity.*;
import com.perftrace.exception.InvalidFileException;
import com.perftrace.exception.ResourceNotFoundException;
import com.perftrace.exception.UnauthorizedException;
import com.perftrace.repository.AnalysisRunRepository;
import com.perftrace.repository.DetectedSegmentRepository;
import com.perftrace.repository.TelemetrySampleRepository;
import com.perftrace.service.comparison.RunComparisonService;
import com.perftrace.service.csv.CsvIngestionService;
import com.perftrace.service.csv.CsvParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class AnalysisRunService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRunService.class);

    private final AnalysisRunRepository runRepository;
    private final TelemetrySampleRepository sampleRepository;
    private final DetectedSegmentRepository segmentRepository;
    private final CsvIngestionService csvIngestionService;
    private final TelemetryAnalysisService telemetryAnalysisService;
    private final RunComparisonService comparisonService;
    private final JdbcTemplate jdbcTemplate;

    public AnalysisRunService(
        AnalysisRunRepository runRepository,
        TelemetrySampleRepository sampleRepository,
        DetectedSegmentRepository segmentRepository,
        CsvIngestionService csvIngestionService,
        TelemetryAnalysisService telemetryAnalysisService,
        RunComparisonService comparisonService,
        JdbcTemplate jdbcTemplate
    ) {
        this.runRepository = runRepository;
        this.sampleRepository = sampleRepository;
        this.segmentRepository = segmentRepository;
        this.csvIngestionService = csvIngestionService;
        this.telemetryAnalysisService = telemetryAnalysisService;
        this.comparisonService = comparisonService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public RunUploadResponse uploadAndProcess(MultipartFile file, String description, User user) {
        if (file.isEmpty()) {
            throw new InvalidFileException("Uploaded file is empty");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".csv")) {
            throw new InvalidFileException("Uploaded file must have a .csv extension");
        }

        AnalysisRun run = new AnalysisRun();
        run.setUser(user);
        run.setFileName(originalFilename);
        run.setDescription(description);
        run.setStatus(RunStatus.UPLOADING);
        run = runRepository.save(run);

        try {
            run.setStatus(RunStatus.ANALYZING);
            runRepository.save(run);

            // 1. Streaming CSV parse
            CsvParseResult parseResult;
            try (InputStream is = file.getInputStream()) {
                parseResult = csvIngestionService.parse(is);
            }

            run.setTotalRows(parseResult.getTotalRowsParsed());
            run.setValidRows(parseResult.getValidRows());
            run.setRejectedRows(parseResult.getRejectedRows());
            run.setRecognizedSensors(String.join(", ", parseResult.getRecognizedSensors()));
            run.setMissingSensors(String.join(", ", parseResult.getMissingSensors()));

            if (!parseResult.getWarnings().isEmpty()) {
                run.setDataQualityWarnings(String.join("; ", parseResult.getWarnings()));
            }

            // 2. Perform Telemetry Analysis & Segment Detection
            TelemetryAnalysisService.AnalysisResult analysisResult =
                telemetryAnalysisService.analyze(run, parseResult.getSamples());

            // 3. Batch insert samples using JDBC batch insert for high performance
            batchInsertSamples(run.getId(), parseResult.getSamples());

            // 4. Save detected segments
            for (DetectedSegment seg : analysisResult.getSegments()) {
                seg.setRun(run);
            }
            segmentRepository.saveAll(analysisResult.getSegments());

            // 5. Finalize status
            run.setStatus(RunStatus.COMPLETED);
            run.setCompletedAt(Instant.now());
            runRepository.save(run);

            RunUploadResponse response = new RunUploadResponse(
                run.getId(),
                run.getFileName(),
                run.getStatus(),
                "Telemetry log processed successfully: " + parseResult.getValidRows() + " valid samples analyzed."
            );
            response.setTotalRowsParsed(parseResult.getTotalRowsParsed());
            response.setValidRows(parseResult.getValidRows());
            response.setRejectedRows(parseResult.getRejectedRows());
            response.setTotalDurationSeconds(run.getTotalDurationSeconds());

            return response;

        } catch (Exception e) {
            log.error("Failed processing telemetry CSV for run {}: {}", run.getId(), e.getMessage(), e);
            run.setStatus(RunStatus.FAILED);
            run.setErrorMessage(e.getMessage());
            runRepository.save(run);
            throw new InvalidFileException("Analysis processing failed: " + e.getMessage());
        }
    }

    private void batchInsertSamples(Long runId, List<TelemetrySample> samples) {
        String sql = "INSERT INTO telemetry_samples (" +
            "run_id, time_offset_seconds, cpu_total_load, cpu_max_thread_load, cpu_temp_c, cpu_clock_mhz, " +
            "gpu_load, gpu_temp_c, gpu_clock_mhz, cpu_throttle_flag, gpu_throttle_flag, ram_usage_percent, " +
            "cpu_power_w, gpu_power_w) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        final int batchSize = 1000;
        List<Object[]> batchArgs = new ArrayList<>(batchSize);

        for (TelemetrySample s : samples) {
            batchArgs.add(new Object[] {
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

            if (batchArgs.size() == batchSize) {
                jdbcTemplate.batchUpdate(sql, batchArgs);
                batchArgs.clear();
            }
        }

        if (!batchArgs.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batchArgs);
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
            throw new UnauthorizedException("You do not have permission to view run " + runId);
        }

        return comparisonService.toDto(run);
    }

    @Transactional(readOnly = true)
    public List<DetectedSegmentDto> getRunSegments(Long runId, Long userId) {
        AnalysisRun run = runRepository.findById(runId)
            .orElseThrow(() -> new ResourceNotFoundException("Run not found (ID: " + runId + ")"));

        if (!run.getUser().getId().equals(userId)) {
            throw new UnauthorizedException("You do not have permission to view segments for run " + runId);
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
     * Prevents browser UI stalls when rendering runs with 50,000+ points.
     */
    @Transactional(readOnly = true)
    public List<TelemetrySampleDto> getDownsampledSamples(Long runId, Long userId, int maxPoints) {
        AnalysisRun run = runRepository.findById(runId)
            .orElseThrow(() -> new ResourceNotFoundException("Run not found (ID: " + runId + ")"));

        if (!run.getUser().getId().equals(userId)) {
            throw new UnauthorizedException("You do not have permission to view telemetry for run " + runId);
        }

        List<TelemetrySample> allSamples = sampleRepository.findAllByRunId(runId);
        List<TelemetrySampleDto> result = new ArrayList<>();

        if (allSamples.isEmpty()) return result;

        int step = Math.max(1, (int) Math.ceil((double) allSamples.size() / maxPoints));

        for (int i = 0; i < allSamples.size(); i += step) {
            TelemetrySample s = allSamples.get(i);
            TelemetrySampleDto dto = new TelemetrySampleDto();
            dto.setId(s.getId());
            dto.setTimeOffsetSeconds(s.getTimeOffsetSeconds());
            dto.setCpuTotalLoad(s.getCpuTotalLoad());
            dto.setCpuMaxThreadLoad(s.getCpuMaxThreadLoad());
            dto.setCpuTempC(s.getCpuTempC());
            dto.setCpuClockMhz(s.getCpuClockMhz());
            dto.setGpuLoad(s.getGpuLoad());
            dto.setGpuTempC(s.getGpuTempC());
            dto.setGpuClockMhz(s.getGpuClockMhz());
            dto.setCpuThrottleFlag(s.getCpuThrottleFlag());
            dto.setGpuThrottleFlag(s.getGpuThrottleFlag());
            dto.setRamUsagePercent(s.getRamUsagePercent());
            dto.setCpuPowerW(s.getCpuPowerW());
            dto.setGpuPowerW(s.getGpuPowerW());
            result.add(dto);
        }

        return result;
    }

    @Transactional
    public void deleteRun(Long runId, Long userId) {
        AnalysisRun run = runRepository.findById(runId)
            .orElseThrow(() -> new ResourceNotFoundException("Run not found (ID: " + runId + ")"));

        if (!run.getUser().getId().equals(userId)) {
            throw new UnauthorizedException("You do not have permission to delete run " + runId);
        }

        runRepository.delete(run);
    }
}
