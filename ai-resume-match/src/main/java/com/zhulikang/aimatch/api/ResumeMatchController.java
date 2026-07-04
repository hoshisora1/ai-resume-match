package com.zhulikang.aimatch.api;

import com.zhulikang.aimatch.analysis.AnalysisService;
import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.MatchReportView;
import com.zhulikang.aimatch.document.DocumentTextExtractor;
import com.zhulikang.aimatch.document.ResumeFileValidator;
import com.zhulikang.aimatch.job.JdTagExtractor;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
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
    private final DocumentTextExtractor extractor;
    private final ResumeFileValidator resumeFileValidator;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobRepository;
    private final JdTagExtractor jdTagExtractor;
    private final AnalysisService analysisService;

    public ResumeMatchController(
        DocumentTextExtractor extractor,
        ResumeFileValidator resumeFileValidator,
        ResumeRepository resumeRepository,
        JobDescriptionRepository jobRepository,
        JdTagExtractor jdTagExtractor,
        AnalysisService analysisService
    ) {
        this.extractor = extractor;
        this.resumeFileValidator = resumeFileValidator;
        this.resumeRepository = resumeRepository;
        this.jobRepository = jobRepository;
        this.jdTagExtractor = jdTagExtractor;
        this.analysisService = analysisService;
    }

    @PostMapping("/resumes")
    public ResumeUploadResponse uploadResume(@RequestParam("file") MultipartFile file) {
        resumeFileValidator.validate(file);
        String rawText = extractor.extract(file);
        if (rawText.isBlank()) {
            throw new IllegalArgumentException("Resume text must not be blank");
        }
        Resume resume = resumeRepository.save(new Resume(file.getOriginalFilename(), rawText, rawText));
        return new ResumeUploadResponse(resume.getId());
    }

    @PostMapping("/jobs")
    public JobDescriptionResponse createJob(@Valid @RequestBody CreateJobRequest request) {
        String tags = jdTagExtractor.toStorageValue(jdTagExtractor.extractTags(request.content()));
        JobDescription job = jobRepository.save(new JobDescription(request.content(), tags));
        return new JobDescriptionResponse(job.getId());
    }

    @PostMapping("/analysis")
    public AnalysisTaskResponse createAnalysis(@Valid @RequestBody CreateAnalysisRequest request) {
        if (!resumeRepository.existsById(request.resumeId())) {
            throw new ResourceNotFoundException("Resume not found");
        }
        if (!jobRepository.existsById(request.jobDescriptionId())) {
            throw new ResourceNotFoundException("Job description not found");
        }
        AnalysisTask task = analysisService.createTask(request.resumeId(), request.jobDescriptionId());
        return AnalysisTaskResponse.from(task);
    }

    @GetMapping("/analysis/{taskId}")
    public ResponseEntity<AnalysisTaskResponse> analysisTask(@PathVariable Long taskId) {
        return analysisService.findTask(taskId)
            .map(AnalysisTaskResponse::from)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/analysis/{taskId}/report")
    public ResponseEntity<MatchReportView> report(@PathVariable Long taskId) {
        return analysisService.findReport(taskId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
