package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.application.resume.PrepareResumeUseCase;
import com.zhulikang.aimatch.application.resume.PreparedResume;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CreateAnalysisSubmissionUseCase {
    private final PrepareResumeUseCase prepareResumeUseCase;
    private final PersistAnalysisSubmissionUseCase persistUseCase;
    private final PersistIdempotentAnalysisSubmissionUseCase idempotentPersistUseCase;

    public CreateAnalysisSubmissionUseCase(
        PrepareResumeUseCase prepareResumeUseCase,
        PersistAnalysisSubmissionUseCase persistUseCase,
        PersistIdempotentAnalysisSubmissionUseCase idempotentPersistUseCase
    ) {
        this.prepareResumeUseCase = prepareResumeUseCase;
        this.persistUseCase = persistUseCase;
        this.idempotentPersistUseCase = idempotentPersistUseCase;
    }

    public AnalysisSubmission create(MultipartFile file, String jobTitle, String jobContent) {
        return create(file, jobTitle, jobContent, null);
    }

    public AnalysisSubmission create(
        MultipartFile file,
        String jobTitle,
        String jobContent,
        String idempotencyKey
    ) {
        String keyHash = idempotencyKey == null
            ? null
            : AnalysisSubmissionIdempotencyKey.hash(idempotencyKey);
        if (keyHash == null) {
            PreparedResume preparedResume = prepareResumeUseCase.prepare(file);
            return persistUseCase.persist(preparedResume, jobTitle, jobContent);
        }

        AnalysisSubmissionIdempotencyContext context = new AnalysisSubmissionIdempotencyContext(
            keyHash,
            AnalysisSubmissionRequestFingerprint.create(file, jobTitle, jobContent)
        );
        var existing = idempotentPersistUseCase.findExisting(context);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }

        PreparedResume preparedResume = prepareResumeUseCase.prepare(file);
        try {
            return idempotentPersistUseCase.persist(preparedResume, jobTitle, jobContent, context);
        } catch (DataIntegrityViolationException race) {
            return idempotentPersistUseCase.findExisting(context).orElseThrow(() -> race);
        }
    }
}
