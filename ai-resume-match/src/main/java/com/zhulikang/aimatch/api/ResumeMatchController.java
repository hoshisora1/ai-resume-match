package com.zhulikang.aimatch.api;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.MatchReportView;
import com.zhulikang.aimatch.application.analysis.CreateAnalysisTaskUseCase;
import com.zhulikang.aimatch.application.analysis.GetAnalysisTaskUseCase;
import com.zhulikang.aimatch.application.job.CreateJobDescriptionUseCase;
import com.zhulikang.aimatch.application.report.GetMatchReportUseCase;
import com.zhulikang.aimatch.application.resume.UploadResumeUseCase;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.resume.Resume;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ResumeMatchController {
    private final UploadResumeUseCase uploadResumeUseCase;
    private final CreateJobDescriptionUseCase createJobDescriptionUseCase;
    private final CreateAnalysisTaskUseCase createAnalysisTaskUseCase;
    private final GetAnalysisTaskUseCase getAnalysisTaskUseCase;
    private final GetMatchReportUseCase getMatchReportUseCase;

    public ResumeMatchController(
        UploadResumeUseCase uploadResumeUseCase,
        CreateJobDescriptionUseCase createJobDescriptionUseCase,
        CreateAnalysisTaskUseCase createAnalysisTaskUseCase,
        GetAnalysisTaskUseCase getAnalysisTaskUseCase,
        GetMatchReportUseCase getMatchReportUseCase
    ) {
        this.uploadResumeUseCase = uploadResumeUseCase;
        this.createJobDescriptionUseCase = createJobDescriptionUseCase;
        this.createAnalysisTaskUseCase = createAnalysisTaskUseCase;
        this.getAnalysisTaskUseCase = getAnalysisTaskUseCase;
        this.getMatchReportUseCase = getMatchReportUseCase;
    }

    @PostMapping("/resumes")
    public ResumeUploadResponse uploadResume(@RequestParam("file") MultipartFile file) {
        Resume resume = uploadResumeUseCase.upload(file);
        return new ResumeUploadResponse(resume.getId());
    }

    @PostMapping("/jobs")
    public JobDescriptionResponse createJob(@Valid @RequestBody CreateJobRequest request) {
        JobDescription job = createJobDescriptionUseCase.create(request.content());
        return new JobDescriptionResponse(job.getId());
    }

    @PostMapping("/analysis")
    public AnalysisTaskResponse createAnalysis(@Valid @RequestBody CreateAnalysisRequest request) {
        AnalysisTask task = createAnalysisTaskUseCase.create(request.resumeId(), request.jobDescriptionId());
        return AnalysisTaskResponse.from(task);
    }

    @GetMapping("/analysis/{taskId}")
    public ResponseEntity<AnalysisTaskResponse> analysisTask(@PathVariable Long taskId) {
        return getAnalysisTaskUseCase.find(taskId)
            .map(AnalysisTaskResponse::from)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/analysis/{taskId}/report")
    public ResponseEntity<MatchReportView> report(@PathVariable Long taskId) {
        return getMatchReportUseCase.find(taskId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
