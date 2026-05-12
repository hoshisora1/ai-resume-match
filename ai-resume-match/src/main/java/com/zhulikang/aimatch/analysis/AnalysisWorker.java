package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.ai.AiClient;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.rag.EmbeddingClient;
import com.zhulikang.aimatch.rag.InMemoryVectorStore;
import com.zhulikang.aimatch.rag.RagContextBuilder;
import com.zhulikang.aimatch.rag.TextChunker;
import com.zhulikang.aimatch.rag.VectorSearchResult;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AnalysisWorker {
    private static final Logger log = LoggerFactory.getLogger(AnalysisWorker.class);
    private static final Pattern SCORE_PATTERN = Pattern.compile("匹配分数\\s*[:：]\\s*(\\d{1,3})");

    private final AnalysisTaskService taskService;
    private final AnalysisTaskRepository taskRepository;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobRepository;
    private final TextChunker textChunker;
    private final EmbeddingClient embeddingClient;
    private final RagContextBuilder ragContextBuilder;
    private final AiClient aiClient;

    public AnalysisWorker(
        AnalysisTaskService taskService,
        AnalysisTaskRepository taskRepository,
        ResumeRepository resumeRepository,
        JobDescriptionRepository jobRepository,
        TextChunker textChunker,
        EmbeddingClient embeddingClient,
        RagContextBuilder ragContextBuilder,
        AiClient aiClient
    ) {
        this.taskService = taskService;
        this.taskRepository = taskRepository;
        this.resumeRepository = resumeRepository;
        this.jobRepository = jobRepository;
        this.textChunker = textChunker;
        this.embeddingClient = embeddingClient;
        this.ragContextBuilder = ragContextBuilder;
        this.aiClient = aiClient;
    }

    public void handle(Long taskId) {
        handle(taskId, false);
    }

    @RabbitListener(queues = RabbitConfig.ANALYSIS_QUEUE)
    public void handle(Long taskId, @Header(name = AmqpHeaders.REDELIVERED, required = false) Boolean redelivered) {
        boolean isRedelivered = Boolean.TRUE.equals(redelivered);
        if (!taskService.tryStart(taskId, isRedelivered)) {
            log.info("Skip analysis task {} because it is not pending", taskId);
            return;
        }
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
            taskService.completeSuccess(new MatchReport(task.getId(), extractScore(report), report));
        } catch (RuntimeException ex) {
            taskService.markFailed(taskId);
            log.warn("Analysis task {} failed: {}", taskId, ex.getMessage());
        }
    }

    int extractScore(String report) {
        Matcher matcher = SCORE_PATTERN.matcher(report == null ? "" : report);
        if (!matcher.find()) {
            throw new IllegalArgumentException("AI report does not contain 匹配分数");
        }
        int score = Integer.parseInt(matcher.group(1));
        return Math.max(0, Math.min(100, score));
    }
}
