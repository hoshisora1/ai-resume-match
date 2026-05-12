package com.zhulikang.aimatch.api;

import com.zhulikang.aimatch.analysis.AnalysisService;
import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.MatchReport;
import com.zhulikang.aimatch.document.DocumentTextExtractor;
import com.zhulikang.aimatch.job.JdTagExtractor;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ResumeMatchController {
    private final DocumentTextExtractor extractor;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobRepository;
    private final JdTagExtractor jdTagExtractor;
    private final AnalysisService analysisService;

    public ResumeMatchController(
        DocumentTextExtractor extractor,
        ResumeRepository resumeRepository,
        JobDescriptionRepository jobRepository,
        JdTagExtractor jdTagExtractor,
        AnalysisService analysisService
    ) {
        this.extractor = extractor;
        this.resumeRepository = resumeRepository;
        this.jobRepository = jobRepository;
        this.jdTagExtractor = jdTagExtractor;
        this.analysisService = analysisService;
    }

    @PostMapping("/resumes")
    public Map<String, Long> uploadResume(@RequestParam("file") MultipartFile file) {
        String rawText = extractor.extract(file);
        Resume resume = resumeRepository.save(new Resume(file.getOriginalFilename(), rawText, rawText));
        return Map.of("resumeId", resume.getId());
    }

    @PostMapping("/jobs")
    public Map<String, Long> createJob(@RequestBody Map<String, String> request) {
        String content = request.getOrDefault("content", "");
        String tags = jdTagExtractor.toStorageValue(jdTagExtractor.extractTags(content));
        JobDescription job = jobRepository.save(new JobDescription(content, tags));
        return Map.of("jobDescriptionId", job.getId());
    }

    @PostMapping("/analysis")
    public Map<String, Long> createAnalysis(@RequestBody Map<String, Long> request) {
        AnalysisTask task = analysisService.createTask(request.get("resumeId"), request.get("jobDescriptionId"));
        return Map.of("taskId", task.getId());
    }

    @GetMapping("/analysis/{taskId}/report")
    public ResponseEntity<MatchReport> report(@PathVariable Long taskId) {
        return analysisService.findReport(taskId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
