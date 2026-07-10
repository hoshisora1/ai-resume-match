package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.application.job.CreateJobDescriptionUseCase;
import com.zhulikang.aimatch.application.resume.PreparedResume;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersistAnalysisSubmissionUseCaseTest {
    @Test
    void persistsResumeJobAndTaskAsOneSubmission() {
        ResumeRepository resumeRepository = mock(ResumeRepository.class);
        CreateJobDescriptionUseCase createJobDescriptionUseCase = mock(CreateJobDescriptionUseCase.class);
        AnalysisTaskCreator taskCreator = mock(AnalysisTaskCreator.class);
        PersistAnalysisSubmissionUseCase useCase = new PersistAnalysisSubmissionUseCase(
            resumeRepository,
            createJobDescriptionUseCase,
            taskCreator
        );
        PreparedResume prepared = new PreparedResume("resume.pdf", "Java Redis", "Java Redis");
        Resume savedResume = new Resume("resume.pdf", "Java Redis", "Java Redis");
        ReflectionTestUtils.setField(savedResume, "id", 10L);
        JobDescription savedJob = new JobDescription("Backend Engineer", "Java Redis", "Java,Redis");
        ReflectionTestUtils.setField(savedJob, "id", 20L);
        AnalysisTask savedTask = new AnalysisTask(10L, 20L);
        ReflectionTestUtils.setField(savedTask, "id", 30L);
        when(resumeRepository.save(any(Resume.class))).thenReturn(savedResume);
        when(createJobDescriptionUseCase.create("Backend Engineer", "Java Redis")).thenReturn(savedJob);
        when(taskCreator.create(10L, 20L)).thenReturn(savedTask);

        AnalysisSubmission submission = useCase.persist(prepared, "Backend Engineer", "Java Redis");

        assertThat(submission.task()).isSameAs(savedTask);
        assertThat(submission.jobTitle()).isEqualTo("Backend Engineer");
        assertThat(submission.resumeFileName()).isEqualTo("resume.pdf");
        InOrder order = inOrder(resumeRepository, createJobDescriptionUseCase, taskCreator);
        order.verify(resumeRepository).save(any(Resume.class));
        order.verify(createJobDescriptionUseCase).create("Backend Engineer", "Java Redis");
        order.verify(taskCreator).create(10L, 20L);
    }
}
