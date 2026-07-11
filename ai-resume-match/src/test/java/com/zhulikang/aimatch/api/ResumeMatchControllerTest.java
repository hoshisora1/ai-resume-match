package com.zhulikang.aimatch.api;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.MatchReportView;
import com.zhulikang.aimatch.application.analysis.AnalysisListItem;
import com.zhulikang.aimatch.application.analysis.AnalysisPage;
import com.zhulikang.aimatch.application.analysis.AnalysisSummary;
import com.zhulikang.aimatch.application.analysis.AnalysisSubmission;
import com.zhulikang.aimatch.application.analysis.AnalysisTaskDetails;
import com.zhulikang.aimatch.application.analysis.CreateAnalysisSubmissionUseCase;
import com.zhulikang.aimatch.application.analysis.CreateAnalysisTaskUseCase;
import com.zhulikang.aimatch.application.analysis.GetAnalysisSummaryUseCase;
import com.zhulikang.aimatch.application.analysis.GetAnalysisTaskUseCase;
import com.zhulikang.aimatch.application.analysis.ListAnalysisTasksUseCase;
import com.zhulikang.aimatch.application.analysis.RetryAnalysisTaskUseCase;
import com.zhulikang.aimatch.application.job.CreateJobDescriptionUseCase;
import com.zhulikang.aimatch.application.report.GetMatchReportUseCase;
import com.zhulikang.aimatch.application.resume.UploadResumeUseCase;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.resume.Resume;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.nullValue;
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
    CreateAnalysisSubmissionUseCase createAnalysisSubmissionUseCase;
    @MockBean
    GetAnalysisTaskUseCase getAnalysisTaskUseCase;
    @MockBean
    ListAnalysisTasksUseCase listAnalysisTasksUseCase;
    @MockBean
    GetAnalysisSummaryUseCase getAnalysisSummaryUseCase;
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
    void createsAtomicAnalysisSubmissionFromMultipartRequest() throws Exception {
        MockMultipartFile file = resumeFile("resume.docx");
        AnalysisSubmission submission = submission("Backend Engineer", "resume.docx");
        when(createAnalysisSubmissionUseCase.create(file, "Backend Engineer", "Java Redis"))
            .thenReturn(submission);

        mockMvc.perform(analysisSubmissionRequest(file, "Backend Engineer", "Java Redis"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.taskId").value(30))
            .andExpect(jsonPath("$.resumeId").value(10))
            .andExpect(jsonPath("$.jobDescriptionId").value(20))
            .andExpect(jsonPath("$.jobTitle").value("Backend Engineer"))
            .andExpect(jsonPath("$.resumeFileName").value("resume.docx"))
            .andExpect(jsonPath("$.matchScore").value(nullValue()))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.attemptCount").value(0))
            .andExpect(jsonPath("$.maxAttempts").value(3))
            .andExpect(jsonPath("$.createdAt").exists())
            .andExpect(jsonPath("$.updatedAt").exists());

        verify(createAnalysisSubmissionUseCase).create(file, "Backend Engineer", "Java Redis");
    }

    @Test
    void returnsBadRequestWhenAnalysisSubmissionFileIsInvalid() throws Exception {
        MockMultipartFile file = resumeFile("resume.txt");
        when(createAnalysisSubmissionUseCase.create(file, "Backend Engineer", "Java Redis"))
            .thenThrow(new IllegalArgumentException("Only PDF and DOCX are supported"));

        mockMvc.perform(analysisSubmissionRequest(file, "Backend Engineer", "Java Redis"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value("Only PDF and DOCX are supported"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"file", "jobTitle", "jobContent"})
    void returnsInvalidRequestWhenRequiredAnalysisSubmissionPartIsMissing(String missingPart) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/analysis-submissions");
        request.header("X-API-Token", "test-token");
        if (!missingPart.equals("file")) {
            request.file(resumeFile("resume.pdf"));
        }
        if (!missingPart.equals("jobTitle")) {
            request.param("jobTitle", "Backend Engineer");
        }
        if (!missingPart.equals("jobContent")) {
            request.param("jobContent", "Java Redis");
        }

        mockMvc.perform(request)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid request"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"jobTitle", "jobContent"})
    void returnsInvalidRequestWhenAnalysisSubmissionTextPartIsBlank(String blankPart) throws Exception {
        String jobTitle = blankPart.equals("jobTitle") ? "  " : "Backend Engineer";
        String jobContent = blankPart.equals("jobContent") ? "  " : "Java Redis";

        mockMvc.perform(analysisSubmissionRequest(resumeFile("resume.pdf"), jobTitle, jobContent))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid request"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u3000", "\u00A0"})
    void returnsInvalidRequestWhenAnalysisSubmissionTitleIsUnicodeWhitespace(String jobTitle) throws Exception {
        mockMvc.perform(analysisSubmissionRequest(resumeFile("resume.pdf"), jobTitle, "Java Redis"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid request"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u3000", "\u00A0"})
    void returnsInvalidRequestWhenAnalysisSubmissionContentIsUnicodeWhitespace(String jobContent) throws Exception {
        mockMvc.perform(analysisSubmissionRequest(resumeFile("resume.pdf"), "Backend Engineer", jobContent))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid request"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void acceptsAnalysisSubmissionTitleWithMaximumCodePointLength() throws Exception {
        String title = "x".repeat(119) + "\uD83D\uDE80";
        MockMultipartFile file = resumeFile("resume.pdf");
        when(createAnalysisSubmissionUseCase.create(file, title, "Java Redis"))
            .thenReturn(submission(title, "resume.pdf"));

        mockMvc.perform(analysisSubmissionRequest(file, title, "Java Redis"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobTitle").value(title));
    }

    @Test
    void returnsInvalidRequestWhenAnalysisSubmissionTitleExceedsCodePointLimit() throws Exception {
        String title = "x".repeat(120) + "\uD83D\uDE80";

        mockMvc.perform(analysisSubmissionRequest(resumeFile("resume.pdf"), title, "Java Redis"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid request"));
    }

    @Test
    void acceptsAnalysisSubmissionContentWithMaximumCodePointLength() throws Exception {
        String jobContent = "x".repeat(19_999) + "\uD83D\uDE80";
        MockMultipartFile file = resumeFile("resume.pdf");
        when(createAnalysisSubmissionUseCase.create(file, "Backend Engineer", jobContent))
            .thenReturn(submission("Backend Engineer", "resume.pdf"));

        mockMvc.perform(analysisSubmissionRequest(file, "Backend Engineer", jobContent))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.taskId").value(30));
    }

    @Test
    void returnsInvalidRequestWhenAnalysisSubmissionContentExceedsCodePointLimit() throws Exception {
        String jobContent = "x".repeat(20_000) + "\uD83D\uDE80";

        mockMvc.perform(analysisSubmissionRequest(resumeFile("resume.pdf"), "Backend Engineer", jobContent))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid request"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void returnsAnalysisHistoryPage() throws Exception {
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 10, 9, 0);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 7, 10, 9, 5);
        LocalDateTime completedAt = LocalDateTime.of(2026, 7, 10, 9, 5);
        AnalysisListItem item = new AnalysisListItem(
            30L,
            "高级后端工程师",
            "resume.pdf",
            AnalysisTask.Status.SUCCESS,
            88,
            1,
            3,
            null,
            createdAt,
            updatedAt,
            completedAt
        );
        when(listAnalysisTasksUseCase.list(null, 0, 20)).thenReturn(new AnalysisPage(
            List.of(item),
            0,
            20,
            1,
            1
        ));

        mockMvc.perform(get("/api/analysis")
                .param("page", "0")
                .param("size", "20")
                .header("X-API-Token", "test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].taskId").value(30))
            .andExpect(jsonPath("$.items[0].jobTitle").value("高级后端工程师"))
            .andExpect(jsonPath("$.items[0].resumeFileName").value("resume.pdf"))
            .andExpect(jsonPath("$.items[0].status").value("SUCCESS"))
            .andExpect(jsonPath("$.items[0].matchScore").value(88))
            .andExpect(jsonPath("$.items[0].attemptCount").value(1))
            .andExpect(jsonPath("$.items[0].maxAttempts").value(3))
            .andExpect(jsonPath("$.items[0].failureCode").doesNotExist())
            .andExpect(jsonPath("$.items[0].createdAt").value("2026-07-10T09:00:00"))
            .andExpect(jsonPath("$.items[0].updatedAt").value("2026-07-10T09:05:00"))
            .andExpect(jsonPath("$.items[0].completedAt").value("2026-07-10T09:05:00"))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.totalPages").value(1));

        verify(listAnalysisTasksUseCase).list(null, 0, 20);
    }

    @Test
    void returnsNullMatchScoreWhenAnalysisHasNoReport() throws Exception {
        AnalysisListItem item = new AnalysisListItem(
            30L,
            "高级后端工程师",
            "resume.pdf",
            AnalysisTask.Status.RUNNING,
            null,
            1,
            3,
            null,
            LocalDateTime.of(2026, 7, 10, 9, 0),
            LocalDateTime.of(2026, 7, 10, 9, 5),
            null
        );
        when(listAnalysisTasksUseCase.list(null, 0, 20)).thenReturn(new AnalysisPage(
            List.of(item),
            0,
            20,
            1,
            1
        ));

        mockMvc.perform(get("/api/analysis")
                .header("X-API-Token", "test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].matchScore").value(nullValue()));
    }

    @Test
    void filtersAnalysisHistoryByStatus() throws Exception {
        when(listAnalysisTasksUseCase.list(AnalysisTask.Status.SUCCESS, 0, 20)).thenReturn(new AnalysisPage(
            List.of(),
            0,
            20,
            0,
            0
        ));

        mockMvc.perform(get("/api/analysis")
                .param("status", "SUCCESS")
                .param("page", "0")
                .param("size", "20")
                .header("X-API-Token", "test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty())
            .andExpect(jsonPath("$.totalElements").value(0));

        verify(listAnalysisTasksUseCase).list(AnalysisTask.Status.SUCCESS, 0, 20);
    }

    @Test
    void returnsAnalysisSummary() throws Exception {
        when(getAnalysisSummaryUseCase.get()).thenReturn(new AnalysisSummary(
            12,
            10,
            1,
            1,
            new BigDecimal("82.4")
        ));

        mockMvc.perform(get("/api/analysis/summary").header("X-API-Token", "test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(12))
            .andExpect(jsonPath("$.successCount").value(10))
            .andExpect(jsonPath("$.inProgressCount").value(1))
            .andExpect(jsonPath("$.retryableFailureCount").value(1))
            .andExpect(jsonPath("$.averageMatchScore").value(82.4));
    }

    @Test
    void returnsAnalysisTaskStatus() throws Exception {
        AnalysisTask task = new AnalysisTask(10L, 20L);
        ReflectionTestUtils.setField(task, "id", 30L);
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 4, 10, 15);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 7, 4, 10, 20);
        ReflectionTestUtils.setField(task, "createdAt", createdAt);
        ReflectionTestUtils.setField(task, "updatedAt", updatedAt);
        when(getAnalysisTaskUseCase.find(30L)).thenReturn(Optional.of(new AnalysisTaskDetails(
            task,
            "高级后端工程师",
            "resume.pdf",
            88
        )));

        mockMvc.perform(get("/api/analysis/30").header("X-API-Token", "test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.taskId").value(30))
            .andExpect(jsonPath("$.resumeId").value(10))
            .andExpect(jsonPath("$.jobDescriptionId").value(20))
            .andExpect(jsonPath("$.jobTitle").value("高级后端工程师"))
            .andExpect(jsonPath("$.resumeFileName").value("resume.pdf"))
            .andExpect(jsonPath("$.matchScore").value(88))
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

    @ParameterizedTest
    @ValueSource(strings = {"\u3000", "\u00A0"})
    void returnsInvalidRequestWhenJobContentIsUnicodeWhitespace(String content) throws Exception {
        mockMvc.perform(post("/api/jobs")
                .header("X-API-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"" + content + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid request"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void acceptsJobTitleWithMaximumCodePointLength() throws Exception {
        String title = "x".repeat(119) + "\uD83D\uDE80";
        JobDescription savedJob = new JobDescription(title, "Java Redis", "Java,Redis");
        ReflectionTestUtils.setField(savedJob, "id", 20L);
        when(createJobDescriptionUseCase.create(title, "Java Redis")).thenReturn(savedJob);

        mockMvc.perform(post("/api/jobs")
                .header("X-API-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"content\":\"Java Redis\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobDescriptionId").value(20))
            .andExpect(jsonPath("$.title").value(title));
    }

    @Test
    void returnsBadRequestWhenJobTitleExceedsMaximumLength() throws Exception {
        String title = "x".repeat(120) + "\uD83D\uDE80";

        mockMvc.perform(post("/api/jobs")
                .header("X-API-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"content\":\"Java Redis\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid request"));
    }

    @Test
    void acceptsJobContentWithMaximumCodePointLength() throws Exception {
        String content = "x".repeat(19_999) + "\uD83D\uDE80";
        JobDescription savedJob = new JobDescription("Backend Engineer", content, "Java");
        ReflectionTestUtils.setField(savedJob, "id", 20L);
        when(createJobDescriptionUseCase.create("Backend Engineer", content)).thenReturn(savedJob);

        mockMvc.perform(post("/api/jobs")
                .header("X-API-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Backend Engineer\",\"content\":\"" + content + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobDescriptionId").value(20));
    }

    @Test
    void returnsInvalidRequestWhenJobContentExceedsCodePointLimit() throws Exception {
        String content = "x".repeat(20_000) + "\uD83D\uDE80";

        mockMvc.perform(post("/api/jobs")
                .header("X-API-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Backend Engineer\",\"content\":\"" + content + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid request"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
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
    void returnsInvalidRequestWhenAnalysisStatusIsUnknown() throws Exception {
        mockMvc.perform(get("/api/analysis")
                .param("status", "UNKNOWN")
                .header("X-API-Token", "test-token"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid request"));
    }

    @Test
    void returnsBadRequestWhenAnalysisPageIsNegative() throws Exception {
        when(listAnalysisTasksUseCase.list(null, -1, 20))
            .thenThrow(new IllegalArgumentException("Page must not be negative"));

        mockMvc.perform(get("/api/analysis")
                .param("page", "-1")
                .header("X-API-Token", "test-token"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value("Page must not be negative"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void returnsBadRequestWhenAnalysisPageSizeIsOutOfRange(int size) throws Exception {
        when(listAnalysisTasksUseCase.list(null, 0, size))
            .thenThrow(new IllegalArgumentException("Size must be between 1 and 100"));

        mockMvc.perform(get("/api/analysis")
                .param("size", Integer.toString(size))
                .header("X-API-Token", "test-token"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value("Size must be between 1 and 100"));
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

    private MockMultipartHttpServletRequestBuilder analysisSubmissionRequest(
        MockMultipartFile file,
        String jobTitle,
        String jobContent
    ) {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/analysis-submissions");
        request.file(file);
        request.param("jobTitle", jobTitle);
        request.param("jobContent", jobContent);
        request.header("X-API-Token", "test-token");
        return request;
    }

    private MockMultipartFile resumeFile(String fileName) {
        return new MockMultipartFile("file", fileName, MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[] {1});
    }

    private AnalysisSubmission submission(String jobTitle, String resumeFileName) {
        AnalysisTask task = new AnalysisTask(10L, 20L);
        ReflectionTestUtils.setField(task, "id", 30L);
        return new AnalysisSubmission(task, jobTitle, resumeFileName);
    }
}
