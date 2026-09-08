package com.zhulikang.aimatch.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.MatchReportView;
import com.zhulikang.aimatch.api.validation.UnicodeNotBlank;
import com.zhulikang.aimatch.application.analysis.AnalysisSubmission;
import com.zhulikang.aimatch.application.analysis.AnalysisSubmissionIdempotencyKey;
import com.zhulikang.aimatch.application.analysis.CreateAnalysisSubmissionUseCase;
import com.zhulikang.aimatch.application.analysis.CreateAnalysisTaskUseCase;
import com.zhulikang.aimatch.application.analysis.DeleteAnalysisUseCase;
import com.zhulikang.aimatch.application.analysis.GetAnalysisSummaryUseCase;
import com.zhulikang.aimatch.application.analysis.GetAnalysisTaskUseCase;
import com.zhulikang.aimatch.application.analysis.ListAnalysisTasksUseCase;
import com.zhulikang.aimatch.application.analysis.RetryAnalysisTaskUseCase;
import com.zhulikang.aimatch.application.analysis.AnalysisRequestRateLimiter;
import com.zhulikang.aimatch.application.job.CreateJobDescriptionUseCase;
import com.zhulikang.aimatch.application.report.GetMatchReportUseCase;
import com.zhulikang.aimatch.application.resume.UploadResumeUseCase;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.config.OpenApiConfiguration;
import com.zhulikang.aimatch.security.RequestIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.CodePointLength;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;

import static com.zhulikang.aimatch.api.validation.JobDescriptionConstraints.CONTENT_MAX_CODE_POINTS;
import static com.zhulikang.aimatch.api.validation.JobDescriptionConstraints.TITLE_MAX_CODE_POINTS;

@RestController
@RequestMapping("/api")
@Validated
@SecurityRequirement(name = OpenApiConfiguration.API_TOKEN_SCHEME)
public class ResumeMatchController {
    private final UploadResumeUseCase uploadResumeUseCase;
    private final CreateJobDescriptionUseCase createJobDescriptionUseCase;
    private final CreateAnalysisTaskUseCase createAnalysisTaskUseCase;
    private final CreateAnalysisSubmissionUseCase createAnalysisSubmissionUseCase;
    private final GetAnalysisTaskUseCase getAnalysisTaskUseCase;
    private final ListAnalysisTasksUseCase listAnalysisTasksUseCase;
    private final GetAnalysisSummaryUseCase getAnalysisSummaryUseCase;
    private final GetMatchReportUseCase getMatchReportUseCase;
    private final RetryAnalysisTaskUseCase retryAnalysisTaskUseCase;
    private final DeleteAnalysisUseCase deleteAnalysisUseCase;
    private final ObjectMapper objectMapper;
    private final AnalysisRequestRateLimiter analysisRequestRateLimiter;

    public ResumeMatchController(
        UploadResumeUseCase uploadResumeUseCase,
        CreateJobDescriptionUseCase createJobDescriptionUseCase,
        CreateAnalysisTaskUseCase createAnalysisTaskUseCase,
        CreateAnalysisSubmissionUseCase createAnalysisSubmissionUseCase,
        GetAnalysisTaskUseCase getAnalysisTaskUseCase,
        ListAnalysisTasksUseCase listAnalysisTasksUseCase,
        GetAnalysisSummaryUseCase getAnalysisSummaryUseCase,
        GetMatchReportUseCase getMatchReportUseCase,
        RetryAnalysisTaskUseCase retryAnalysisTaskUseCase,
        DeleteAnalysisUseCase deleteAnalysisUseCase,
        ObjectMapper objectMapper,
        AnalysisRequestRateLimiter analysisRequestRateLimiter
    ) {
        this.uploadResumeUseCase = uploadResumeUseCase;
        this.createJobDescriptionUseCase = createJobDescriptionUseCase;
        this.createAnalysisTaskUseCase = createAnalysisTaskUseCase;
        this.createAnalysisSubmissionUseCase = createAnalysisSubmissionUseCase;
        this.getAnalysisTaskUseCase = getAnalysisTaskUseCase;
        this.listAnalysisTasksUseCase = listAnalysisTasksUseCase;
        this.getAnalysisSummaryUseCase = getAnalysisSummaryUseCase;
        this.getMatchReportUseCase = getMatchReportUseCase;
        this.retryAnalysisTaskUseCase = retryAnalysisTaskUseCase;
        this.deleteAnalysisUseCase = deleteAnalysisUseCase;
        this.objectMapper = objectMapper;
        this.analysisRequestRateLimiter = analysisRequestRateLimiter;
    }

    @PostMapping("/resumes")
    public ResumeUploadResponse uploadResume(@RequestParam("file") MultipartFile file) {
        Resume resume = uploadResumeUseCase.upload(file);
        return new ResumeUploadResponse(resume.getId());
    }

    @PostMapping("/jobs")
    public JobDescriptionResponse createJob(@Valid @RequestBody CreateJobRequest request) {
        JobDescription job = createJobDescriptionUseCase.create(request.title(), request.content());
        return JobDescriptionResponse.from(job);
    }

    @PostMapping("/analysis")
    @Operation(summary = "Create an asynchronous analysis task from existing resources")
    @ApiResponse(
        responseCode = "202",
        description = "Analysis accepted",
        headers = @Header(
            name = "Location",
            description = "Canonical task status resource",
            schema = @Schema(type = "string")
        ),
        content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = AnalysisTaskResponse.class)
        )
    )
    public ResponseEntity<AnalysisTaskResponse> createAnalysis(
        @Valid @RequestBody CreateAnalysisRequest request
    ) {
        consumeAnalysisQuota();
        AnalysisTask task = createAnalysisTaskUseCase.create(request.resumeId(), request.jobDescriptionId());
        return accepted(AnalysisTaskResponse.from(task));
    }

    @PostMapping(value = "/analysis-submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Atomically upload a resume, create a job and accept an analysis task")
    @ApiResponses({
        @ApiResponse(
            responseCode = "202",
            description = "Analysis accepted",
            headers = @Header(
                name = "Location",
                description = "Canonical task status resource",
                schema = @Schema(type = "string")
            ),
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = AnalysisTaskResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "409",
            description = "Idempotency key conflict",
            content = @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ApiProblemDetail.class)
            )
        )
    })
    public ResponseEntity<AnalysisTaskResponse> createAnalysisSubmission(
        @RequestParam("file") MultipartFile file,
        @RequestParam("jobTitle") @UnicodeNotBlank @CodePointLength(max = TITLE_MAX_CODE_POINTS) String jobTitle,
        @RequestParam("jobContent") @UnicodeNotBlank @CodePointLength(max = CONTENT_MAX_CODE_POINTS) String jobContent,
        @RequestHeader(name = "Idempotency-Key", required = false)
        @Size(max = AnalysisSubmissionIdempotencyKey.MAX_LENGTH)
        @Pattern(regexp = AnalysisSubmissionIdempotencyKey.SAFE_PATTERN)
        String idempotencyKey
    ) {
        consumeAnalysisQuota();
        AnalysisSubmission submission = idempotencyKey == null
            ? createAnalysisSubmissionUseCase.create(file, jobTitle, jobContent)
            : createAnalysisSubmissionUseCase.create(file, jobTitle, jobContent, idempotencyKey);
        return accepted(AnalysisTaskResponse.from(submission));
    }

    @GetMapping("/analysis")
    public AnalysisPageResponse analyses(
        @RequestParam(required = false) AnalysisTask.Status status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return AnalysisPageResponse.from(listAnalysisTasksUseCase.list(status, page, size));
    }

    @GetMapping("/analysis/summary")
    public AnalysisSummaryResponse analysisSummary() {
        return AnalysisSummaryResponse.from(getAnalysisSummaryUseCase.get());
    }

    @GetMapping("/analysis/{taskId}")
    public ResponseEntity<AnalysisTaskResponse> analysisTask(@PathVariable Long taskId) {
        AnalysisTaskResponse response = getAnalysisTaskUseCase.find(taskId)
            .map(AnalysisTaskResponse::from)
            .orElseThrow(() -> new ResourceNotFoundException("Analysis task not found"));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/analysis/{taskId}/retry")
    public AnalysisTaskResponse retryAnalysis(@PathVariable Long taskId) {
        consumeAnalysisQuota();
        return AnalysisTaskResponse.from(retryAnalysisTaskUseCase.retry(taskId));
    }

    @GetMapping("/analysis/{taskId}/report")
    public ResponseEntity<MatchReportResponse> report(@PathVariable Long taskId) {
        MatchReportView report = getMatchReportUseCase.find(taskId)
            .orElseThrow(() -> new ResourceNotFoundException("Match report not found"));
        return ResponseEntity.ok(MatchReportResponse.from(report, objectMapper));
    }

    @DeleteMapping("/analysis/{taskId}")
    @Operation(summary = "Delete an owned analysis and its source data")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Analysis data deleted"),
        @ApiResponse(
            responseCode = "404",
            description = "Analysis does not exist or belongs to another session",
            content = @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ApiProblemDetail.class)
            )
        )
    })
    public ResponseEntity<Void> deleteAnalysis(@PathVariable Long taskId) {
        deleteAnalysisUseCase.delete(taskId);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<AnalysisTaskResponse> accepted(AnalysisTaskResponse response) {
        return ResponseEntity.accepted()
            .location(URI.create("/api/analysis/" + response.taskId()))
            .body(response);
    }

    private void consumeAnalysisQuota() {
        analysisRequestRateLimiter.consume(RequestIdentity.currentOwnerId());
    }
}
