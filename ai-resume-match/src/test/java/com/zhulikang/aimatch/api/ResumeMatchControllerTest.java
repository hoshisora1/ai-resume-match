package com.zhulikang.aimatch.api;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.MatchReportView;
import com.zhulikang.aimatch.application.analysis.CreateAnalysisTaskUseCase;
import com.zhulikang.aimatch.application.analysis.GetAnalysisTaskUseCase;
import com.zhulikang.aimatch.application.analysis.RetryAnalysisTaskUseCase;
import com.zhulikang.aimatch.application.job.CreateJobDescriptionUseCase;
import com.zhulikang.aimatch.application.report.GetMatchReportUseCase;
import com.zhulikang.aimatch.application.resume.UploadResumeUseCase;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.resume.Resume;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResumeMatchController.class)
@TestPropertySource(properties = "api.token=test-token")
class ResumeMatchControllerTest {
    @Autowired
    MockMvc mockMvc;

    @MockBean
    UploadResumeUseCase uploadResumeUseCase;
    @MockBean
    CreateJobDescriptionUseCase createJobDescriptionUseCase;
    @MockBean
    CreateAnalysisTaskUseCase createAnalysisTaskUseCase;
    @MockBean
    GetAnalysisTaskUseCase getAnalysisTaskUseCase;
    @MockBean
    GetMatchReportUseCase getMatchReportUseCase;
    @MockBean
    RetryAnalysisTaskUseCase retryAnalysisTaskUseCase;

    @Test
    void rejectsRequestWithoutApiToken() throws Exception {
        mockMvc.perform(get("/api/analysis/1/report"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").value("Unauthorized"));
    }

    @Test
    void rejectsRequestWithInvalidApiToken() throws Exception {
        mockMvc.perform(get("/api/analysis/1/report").header("X-API-Token", "wrong-token"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").value("Unauthorized"));
    }

    @Test
    void addsGeneratedRequestIdToUnauthorizedErrorResponse() throws Exception {
        mockMvc.perform(get("/api/analysis/1/report"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().exists("X-Request-Id"))
            .andExpect(header().exists("X-Correlation-Id"))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").value("Unauthorized"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void reusesIncomingRequestAndCorrelationIds() throws Exception {
        when(getAnalysisTaskUseCase.find(404L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/analysis/404")
                .header("X-API-Token", "test-token")
                .header("X-Request-Id", "client-request-1")
                .header("X-Correlation-Id", "client-correlation-1"))
            .andExpect(status().isNotFound())
            .andExpect(header().string("X-Request-Id", "client-request-1"))
            .andExpect(header().string("X-Correlation-Id", "client-correlation-1"))
            .andExpect(jsonPath("$.requestId").value("client-request-1"));
    }

    @Test
    void returnsNotFoundWhenReportMissing() throws Exception {
        when(getMatchReportUseCase.find(1L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/analysis/1/report").header("X-API-Token", "test-token"))
            .andExpect(status().isNotFound());
    }

    @Test
    void uploadsResumeAndReturnsResumeId() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "resume.pdf",
            "application/pdf",
            new byte[] {1, 2, 3}
        );
        Resume savedResume = new Resume("resume.pdf", "Java Redis", "Java Redis");
        ReflectionTestUtils.setField(savedResume, "id", 10L);
        when(uploadResumeUseCase.upload(file)).thenReturn(savedResume);

        mockMvc.perform(multipart("/api/resumes")
                .file(file)
                .header("X-API-Token", "test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resumeId").value(10));

        verify(uploadResumeUseCase).upload(file);
    }

    @Test
    void returnsBadRequestWhenUploadValidatorRejectsFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "resume.txt",
            "text/plain",
            new byte[] {1, 2, 3}
        );
        doThrow(new IllegalArgumentException("Only PDF and DOCX are supported"))
            .when(uploadResumeUseCase).upload(file);

        mockMvc.perform(multipart("/api/resumes")
                .file(file)
                .header("X-API-Token", "test-token"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value("Only PDF and DOCX are supported"));
    }

    @Test
    void createsJobWithExplicitTitleAndReturnsIt() throws Exception {
        JobDescription savedJob = new JobDescription("Backend Engineer", "Java Redis", "Java,Redis");
        ReflectionTestUtils.setField(savedJob, "id", 20L);
        when(createJobDescriptionUseCase.create("Backend Engineer", "Java Redis")).thenReturn(savedJob);

        mockMvc.perform(post("/api/jobs")
                .header("X-API-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Backend Engineer\",\"content\":\"Java Redis\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobDescriptionId").value(20))
            .andExpect(jsonPath("$.title").value("Backend Engineer"));
    }

    @Test
    void createsJobWithoutTitleAndReturnsTitleDerivedFromFirstJdLine() throws Exception {
        JobDescription savedJob = new JobDescription("Java Redis", "Java Redis\nBuild platform", "Java,Redis");
        ReflectionTestUtils.setField(savedJob, "id", 20L);
        when(createJobDescriptionUseCase.create(null, "Java Redis\nBuild platform")).thenReturn(savedJob);

        mockMvc.perform(post("/api/jobs")
                .header("X-API-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Java Redis\\nBuild platform\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobDescriptionId").value(20))
            .andExpect(jsonPath("$.title").value("Java Redis"));

        verify(createJobDescriptionUseCase).create(null, "Java Redis\nBuild platform");
    }

    @Test
    void createsAnalysisAndReturnsTaskIdAndStatus() throws Exception {
        AnalysisTask savedTask = new AnalysisTask(10L, 20L);
        ReflectionTestUtils.setField(savedTask, "id", 30L);
        when(createAnalysisTaskUseCase.create(10L, 20L)).thenReturn(savedTask);

        mockMvc.perform(post("/api/analysis")
                .header("X-API-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resumeId\":10,\"jobDescriptionId\":20}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.taskId").value(30))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.resumeId").value(10))
            .andExpect(jsonPath("$.jobDescriptionId").value(20));
    }

    @Test
    void returnsAnalysisTaskStatus() throws Exception {
        AnalysisTask task = new AnalysisTask(10L, 20L);
        ReflectionTestUtils.setField(task, "id", 30L);
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 4, 10, 15);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 7, 4, 10, 20);
        ReflectionTestUtils.setField(task, "createdAt", createdAt);
        ReflectionTestUtils.setField(task, "updatedAt", updatedAt);
        when(getAnalysisTaskUseCase.find(30L)).thenReturn(Optional.of(task));

        mockMvc.perform(get("/api/analysis/30").header("X-API-Token", "test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.taskId").value(30))
            .andExpect(jsonPath("$.resumeId").value(10))
            .andExpect(jsonPath("$.jobDescriptionId").value(20))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.attemptCount").value(0))
            .andExpect(jsonPath("$.maxAttempts").value(3))
            .andExpect(jsonPath("$.failureCode").doesNotExist())
            .andExpect(jsonPath("$.failureMessage").doesNotExist())
            .andExpect(jsonPath("$.createdAt").value("2026-07-04T10:15:00"))
            .andExpect(jsonPath("$.updatedAt").value("2026-07-04T10:20:00"));
    }

    @Test
    void returnsNotFoundWhenAnalysisTaskDoesNotExist() throws Exception {
        when(getAnalysisTaskUseCase.find(404L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/analysis/404").header("X-API-Token", "test-token"))
            .andExpect(status().isNotFound());
    }

    @Test
    void retriesAnalysisTask() throws Exception {
        AnalysisTask task = new AnalysisTask(10L, 20L);
        ReflectionTestUtils.setField(task, "id", 30L);
        when(retryAnalysisTaskUseCase.retry(30L)).thenReturn(task);

        mockMvc.perform(post("/api/analysis/30/retry").header("X-API-Token", "test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.taskId").value(30))
            .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void returnsBadRequestWhenRetryIsRejected() throws Exception {
        when(retryAnalysisTaskUseCase.retry(30L))
            .thenThrow(new IllegalArgumentException("Only retryable failed analysis tasks can be retried"));

        mockMvc.perform(post("/api/analysis/30/retry").header("X-API-Token", "test-token"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value("Only retryable failed analysis tasks can be retried"));
    }

    @Test
    void returnsReportBodyWhenReportExists() throws Exception {
        MatchReportView report = new MatchReportView(
            30L,
            88,
            "匹配分数：88",
            LocalDateTime.of(2026, 7, 4, 9, 30)
        );
        when(getMatchReportUseCase.find(30L)).thenReturn(Optional.of(report));

        mockMvc.perform(get("/api/analysis/30/report").header("X-API-Token", "test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.taskId").value(30))
            .andExpect(jsonPath("$.matchScore").value(88))
            .andExpect(jsonPath("$.reportContent").value("匹配分数：88"))
            .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void returnsBadRequestWhenJobContentIsBlank() throws Exception {
        mockMvc.perform(post("/api/jobs")
                .header("X-API-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"  \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid request"));
    }

    @Test
    void returnsBadRequestWhenJobTitleExceedsMaximumLength() throws Exception {
        mockMvc.perform(post("/api/jobs")
                .header("X-API-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + "x".repeat(121) + "\",\"content\":\"Java Redis\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid request"));
    }

    @Test
    void returnsInvalidRequestWhenJobJsonIsMalformed() throws Exception {
        mockMvc.perform(post("/api/jobs")
                .header("X-API-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid request"));
    }

    @Test
    void returnsBadRequestWhenAnalysisIdsAreMissing() throws Exception {
        mockMvc.perform(post("/api/analysis")
                .header("X-API-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid request"));
    }

    @Test
    void returnsBadRequestWhenAnalysisIdsAreNotPositive() throws Exception {
        mockMvc.perform(post("/api/analysis")
                .header("X-API-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resumeId\":0,\"jobDescriptionId\":-1}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid request"));
    }

    @Test
    void returnsInvalidRequestWhenAnalysisTaskIdIsNotNumeric() throws Exception {
        mockMvc.perform(get("/api/analysis/not-a-number").header("X-API-Token", "test-token"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid request"));
    }

    @Test
    void returnsInvalidRequestWhenResumeUploadFileIsMissing() throws Exception {
        mockMvc.perform(multipart("/api/resumes").header("X-API-Token", "test-token"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid request"));
    }

    @Test
    void returnsBadRequestWhenUploadedResumeHasNoText() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "empty.pdf",
            "application/pdf",
            new byte[] {1, 2, 3}
        );
        when(uploadResumeUseCase.upload(file)).thenThrow(new IllegalArgumentException("Resume text must not be blank"));

        mockMvc.perform(multipart("/api/resumes")
                .file(file)
                .header("X-API-Token", "test-token"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value("Resume text must not be blank"));
    }

    @Test
    void returnsNotFoundWhenResumeDoesNotExist() throws Exception {
        when(createAnalysisTaskUseCase.create(1L, 2L)).thenThrow(new ResourceNotFoundException("Resume not found"));

        mockMvc.perform(post("/api/analysis")
                .header("X-API-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resumeId\":1,\"jobDescriptionId\":2}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Resume not found"));
    }
}
