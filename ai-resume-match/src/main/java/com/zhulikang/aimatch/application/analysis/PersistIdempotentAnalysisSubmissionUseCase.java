package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisSubmissionIdempotencyRecord;
import com.zhulikang.aimatch.analysis.AnalysisSubmissionIdempotencyRepository;
import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.application.resume.PreparedResume;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class PersistIdempotentAnalysisSubmissionUseCase {
    private final AnalysisSubmissionIdempotencyRepository idempotencyRepository;
    private final PersistAnalysisSubmissionUseCase persistUseCase;
    private final AnalysisTaskRepository taskRepository;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobRepository;

    public PersistIdempotentAnalysisSubmissionUseCase(
        AnalysisSubmissionIdempotencyRepository idempotencyRepository,
        PersistAnalysisSubmissionUseCase persistUseCase,
        AnalysisTaskRepository taskRepository,
        ResumeRepository resumeRepository,
        JobDescriptionRepository jobRepository
    ) {
        this.idempotencyRepository = idempotencyRepository;
        this.persistUseCase = persistUseCase;
        this.taskRepository = taskRepository;
        this.resumeRepository = resumeRepository;
        this.jobRepository = jobRepository;
    }

    @Transactional
    public AnalysisSubmission persist(
        PreparedResume preparedResume,
        String jobTitle,
        String jobContent,
        AnalysisSubmissionIdempotencyContext context
    ) {
        Optional<AnalysisSubmissionIdempotencyRecord> existing =
            idempotencyRepository.findByIdempotencyKeyHash(context.keyHash());
        if (existing.isPresent()) {
            return resolve(existing.orElseThrow(), context.requestFingerprint());
        }

        AnalysisSubmissionIdempotencyRecord record = idempotencyRepository.saveAndFlush(
            new AnalysisSubmissionIdempotencyRecord(context.keyHash(), context.requestFingerprint())
        );
        AnalysisSubmission submission = persistUseCase.persist(preparedResume, jobTitle, jobContent);
        record.complete(submission.task().getId());
        idempotencyRepository.flush();
        return submission;
    }

    @Transactional(readOnly = true)
    public Optional<AnalysisSubmission> findExisting(AnalysisSubmissionIdempotencyContext context) {
        return idempotencyRepository.findByIdempotencyKeyHash(context.keyHash())
            .map(record -> resolve(record, context.requestFingerprint()));
    }

    private AnalysisSubmission resolve(
        AnalysisSubmissionIdempotencyRecord record,
        String requestFingerprint
    ) {
        if (!record.getRequestFingerprint().equals(requestFingerprint)) {
            throw new IdempotencyConflictException();
        }
        if (record.getTaskId() == null) {
            throw new IllegalStateException("Idempotency record is incomplete");
        }
        AnalysisTask task = taskRepository.findById(record.getTaskId())
            .orElseThrow(() -> new IllegalStateException("Idempotency record references a missing task"));
        Resume resume = resumeRepository.findById(task.getResumeId())
            .orElseThrow(() -> new IllegalStateException("Idempotency task references a missing resume"));
        JobDescription job = jobRepository.findById(task.getJobDescriptionId())
            .orElseThrow(() -> new IllegalStateException("Idempotency task references a missing job"));
        return new AnalysisSubmission(task, job.getTitle(), resume.getFileName());
    }
}
