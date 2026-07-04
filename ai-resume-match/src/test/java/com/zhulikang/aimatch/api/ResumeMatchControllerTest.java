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
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResumeMatchController.class)
@TestPropertySource(properties = "api.token=test-token")
class ResumeMatchControllerTest {
    @Autowired
    MockMvc mockMvc;

    @MockBean
    DocumentTextExtractor extractor;
    @MockBean
    ResumeFileValidator resumeFileValidator;
    @MockBean
    ResumeRepository resumeRepository;
    @MockBean
    JobDescriptionRepository jobRepository;
    @MockBean
    JdTagExtractor jdTagExtractor;
    @MockBean
    AnalysisService analysisService;

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
    void returnsNotFoundWhenReportMissing() throws Exception {
        when(analysisService.findReport(1L)).thenReturn(Optional.empty());

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
        when(extractor.extract(file)).thenReturn("Java Redis");
        when(resumeRepository.save(any(Resume.class))).thenReturn(savedResume);

        mockMvc.perform(multipart("/api/resumes")
                .file(file)
                .header("X-API-Token", "test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resumeId").value(10));

        verify(resumeFileValidator).validate(file);
    }

    @Test
    void createsJobAndReturnsJobDescriptionId() throws Exception {
        JobDescription savedJob = new JobDescription("Java Redis", "Java,Redis");
        ReflectionTestUtils.setField(savedJob, "id", 20L);
        when(jdTagExtractor.extractTags("Java Redis")).thenReturn(List.of("Java", "Redis"));
        when(jdTagExtractor.toStorageValue(List.of("Java", "Redis"))).thenReturn("Java,Redis");
        when(jobRepository.save(any(JobDescription.class))).thenReturn(savedJob);

        mockMvc.perform(post("/api/jobs")
                .header("X-API-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Java Redis\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobDescriptionId").value(20));
    }

    @Test
    void createsAnalysisAndReturnsTaskIdAndStatus() throws Exception {
        AnalysisTask savedTask = new AnalysisTask(10L, 20L);
        ReflectionTestUtils.setField(savedTask, "id", 30L);
        when(resumeRepository.existsById(10L)).thenReturn(true);
        when(jobRepository.existsById(20L)).thenReturn(true);
        when(analysisService.createTask(10L, 20L)).thenReturn(savedTask);

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
    void returnsReportBodyWhenReportExists() throws Exception {
        MatchReportView report = new MatchReportView(
            30L,
            88,
            "匹配分数：88",
            LocalDateTime.of(2026, 7, 4, 9, 30)
        );
        when(analysisService.findReport(30L)).thenReturn(Optional.of(report));

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
    void returnsBadRequestWhenUploadedResumeHasNoText() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "empty.pdf",
            "application/pdf",
            new byte[] {1, 2, 3}
        );
        when(extractor.extract(file)).thenReturn("   ");

        mockMvc.perform(multipart("/api/resumes")
                .file(file)
                .header("X-API-Token", "test-token"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value("Resume text must not be blank"));
    }

    @Test
    void returnsNotFoundWhenResumeDoesNotExist() throws Exception {
        when(resumeRepository.existsById(1L)).thenReturn(false);

        mockMvc.perform(post("/api/analysis")
                .header("X-API-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resumeId\":1,\"jobDescriptionId\":2}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Resume not found"));
    }
}
