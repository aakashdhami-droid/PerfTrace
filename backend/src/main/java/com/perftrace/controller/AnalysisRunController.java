package com.perftrace.controller;

import com.perftrace.dto.*;
import com.perftrace.entity.User;
import com.perftrace.security.CustomUserDetailsService;
import com.perftrace.service.analysis.AnalysisRunService;
import com.perftrace.service.comparison.RunComparisonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/runs")
@Tag(name = "Analysis Runs", description = "HWiNFO64 CSV telemetry analysis, timeline segments, and run comparison")
public class AnalysisRunController {

    private final AnalysisRunService analysisRunService;
    private final RunComparisonService runComparisonService;
    private final CustomUserDetailsService userDetailsService;

    public AnalysisRunController(
        AnalysisRunService analysisRunService,
        RunComparisonService runComparisonService,
        CustomUserDetailsService userDetailsService
    ) {
        this.analysisRunService = analysisRunService;
        this.runComparisonService = runComparisonService;
        this.userDetailsService = userDetailsService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload an HWiNFO64 CSV export to analyze hardware telemetry")
    public ResponseEntity<RunUploadResponse> upload(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "description", required = false) String description,
        @RequestParam(value = "workloadType", required = false) String workloadType,
        Principal principal
    ) {
        User user = userDetailsService.loadUserEntity(principal.getName());
        RunUploadResponse response = analysisRunService.uploadAndProcess(file, description, workloadType, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @Operation(summary = "List all analysis runs owned by the authenticated user")
    public ResponseEntity<Page<AnalysisRunDto>> listRuns(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        Principal principal
    ) {
        User user = userDetailsService.loadUserEntity(principal.getName());
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Page<AnalysisRunDto> runs = analysisRunService.getUserRuns(user.getId(), PageRequest.of(safePage, safeSize));
        return ResponseEntity.ok(runs);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get run summary and classification breakdown by ID")
    public ResponseEntity<AnalysisRunDto> getRun(@PathVariable Long id, Principal principal) {
        User user = userDetailsService.loadUserEntity(principal.getName());
        AnalysisRunDto run = analysisRunService.getRunById(id, user.getId());
        return ResponseEntity.ok(run);
    }

    @GetMapping("/{id}/segments")
    @Operation(summary = "Get detected timeline segments and supporting evidence for a run")
    public ResponseEntity<List<DetectedSegmentDto>> getSegments(@PathVariable Long id, Principal principal) {
        User user = userDetailsService.loadUserEntity(principal.getName());
        List<DetectedSegmentDto> segments = analysisRunService.getRunSegments(id, user.getId());
        return ResponseEntity.ok(segments);
    }

    @GetMapping("/{id}/samples")
    @Operation(summary = "Get downsampled telemetry points for interactive time-series charts")
    public ResponseEntity<List<TelemetrySampleDto>> getSamples(
        @PathVariable Long id,
        @RequestParam(defaultValue = "500") int maxPoints,
        Principal principal
    ) {
        User user = userDetailsService.loadUserEntity(principal.getName());
        List<TelemetrySampleDto> samples = analysisRunService.getDownsampledSamples(id, user.getId(), maxPoints);
        return ResponseEntity.ok(samples);
    }

    @GetMapping("/compare")
    @Operation(summary = "Compare two completed runs side by side with shift metrics and comparability warnings")
    public ResponseEntity<RunComparisonDto> compareRuns(
        @RequestParam("a") Long runIdA,
        @RequestParam("b") Long runIdB,
        Principal principal
    ) {
        User user = userDetailsService.loadUserEntity(principal.getName());
        RunComparisonDto comparison = runComparisonService.compareRuns(runIdA, runIdB, user.getId());
        return ResponseEntity.ok(comparison);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an analysis run and its associated telemetry data")
    public ResponseEntity<Void> deleteRun(@PathVariable Long id, Principal principal) {
        User user = userDetailsService.loadUserEntity(principal.getName());
        analysisRunService.deleteRun(id, user.getId());
        return ResponseEntity.noContent().build();
    }
}
