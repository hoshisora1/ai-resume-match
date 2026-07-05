package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.ai.AiClient;
import com.zhulikang.aimatch.analysis.AnalysisFailureCode;
import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.analysis.AnalysisTaskService;
import com.zhulikang.aimatch.analysis.MatchReport;
import com.zhulikang.aimatch.analysis.ReportParser;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.observability.AnalysisMetrics;
import com.zhulikang.aimatch.rag.EmbeddingClient;
import com.zhulikang.aimatch.rag.InMemoryVectorStore;
import com.zhulikang.aimatch.rag.RagContextBuilder;
import com.zhulikang.aimatch.rag.TextChunker;
import com.zhulikang.aimatch.rag.VectorSearchResult;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class RunAnalysisUseCase {
    private static final Logger log = LoggerFactory.getLogger(RunAnalysisUseCase.class);

    private final AnalysisTaskService taskService;
    private final AnalysisTaskRepository taskRepository;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobRepository;
    private final TextChunker textChunker;
    private final EmbeddingClient embeddingClient;
    private final RagContextBuilder ragContextBuilder;
    private final AiClient aiClient;
    private final ReportParser reportParser;
    private final AnalysisMetrics metrics;

    public RunAnalysisUseCase(
        AnalysisTaskService taskService,
        AnalysisTaskRepository taskRepository,
        ResumeRepository resumeRepository,
        JobDescriptionRepository jobRepository,
        TextChunker textChunker,
        EmbeddingClient embeddingClient,
        RagContextBuilder ragContextBuilder,
        AiClient aiClient,
        ReportParser reportParser,
        AnalysisMetrics metrics
    ) {
        this.taskService = taskService;
        this.taskRepository = taskRepository;
        this.resumeRepository = resumeRepository;
        this.jobRepository = jobRepository;
        this.textChunker = textChunker;
        this.embeddingClient = embeddingClient;
        this.ragContextBuilder = ragContextBuilder;
        this.aiClient = aiClient;
        this.reportParser = reportParser;
        this.metrics = metrics;
    }

    public void run(Long taskId, boolean redelivered) {
        if (!taskService.tryStart(taskId, redelivered)) {
            log.info("event=analysis_task_skipped taskId={} redelivered={} reason=not_claimable", taskId, redelivered);
            return;
        }
        Timer.Sample sample = metrics.startTimer();
        log.info("event=analysis_task_started taskId={} redelivered={}", taskId, redelivered);
        try {
            AnalysisTask task = taskRepository.findById(taskId).orElseThrow();
            Resume resume = resumeRepository.findById(task.getResumeId()).orElseThrow();
            JobDescription job = jobRepository.findById(task.getJobDescriptionId()).orElseThrow();

            InMemoryVectorStore vectorStore = new InMemoryVectorStore(embeddingClient);
            vectorStore.addAll(textChunker.chunk(resume.getRawText()));
            List<String> retrievedChunks = vectorStore.search(job.getContent(), 3).stream()
                .map(VectorSearchResult::text)
                .toList();

            String prompt = ragContextBuilder.build(
                retrievedChunks,
                job.getContent(),
                Arrays.stream(job.getSkillTags().split(",")).filter(tag -> !tag.isBlank()).toList()
            );
            String report = aiClient.complete(prompt);
            taskService.completeSuccess(new MatchReport(task.getId(), reportParser.extractScore(report), report));
            metrics.taskSucceeded(sample);
            log.info(
                "event=analysis_task_succeeded taskId={} resumeId={} jobDescriptionId={} attempt={}",
                task.getId(),
                task.getResumeId(),
                task.getJobDescriptionId(),
                task.getAttemptCount()
            );
        } catch (NoSuchElementException ex) {
            taskService.markFinalFailure(
                taskId,
                AnalysisFailureCode.SOURCE_DATA_MISSING,
                "Analysis source data is missing"
            );
            metrics.taskFailed(AnalysisFailureCode.SOURCE_DATA_MISSING, sample);
            log.warn(
                "event=analysis_task_failed taskId={} failureCode={} retryable=false",
                taskId,
                AnalysisFailureCode.SOURCE_DATA_MISSING
            );
        } catch (IllegalArgumentException ex) {
            taskService.markFinalFailure(taskId, AnalysisFailureCode.REPORT_PARSE_FAILED, ex.getMessage());
            metrics.taskFailed(AnalysisFailureCode.REPORT_PARSE_FAILED, sample);
            log.warn(
                "event=analysis_task_failed taskId={} failureCode={} retryable=false",
                taskId,
                AnalysisFailureCode.REPORT_PARSE_FAILED
            );
        } catch (RuntimeException ex) {
            taskService.markRetryableFailure(taskId, AnalysisFailureCode.AI_UNAVAILABLE, ex.getMessage());
            metrics.taskFailed(AnalysisFailureCode.AI_UNAVAILABLE, sample);
            log.warn(
                "event=analysis_task_failed taskId={} failureCode={} retryable=true",
                taskId,
                AnalysisFailureCode.AI_UNAVAILABLE
            );
        }
    }
}
