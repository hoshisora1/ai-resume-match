package com.zhulikang.aimatch.api;

import com.zhulikang.aimatch.analysis.AnalysisService;
import com.zhulikang.aimatch.document.DocumentTextExtractor;
import com.zhulikang.aimatch.job.JdTagExtractor;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResumeMatchController.class)
@TestPropertySource(properties = "api.token=test-token")
class ResumeMatchControllerTest {
    @Autowired
    MockMvc mockMvc;

    @MockBean
    DocumentTextExtractor extractor;
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
            .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsNotFoundWhenReportMissing() throws Exception {
        when(analysisService.findReport(1L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/analysis/1/report").header("X-API-Token", "test-token"))
            .andExpect(status().isNotFound());
    }

    @Test
    void returnsBadRequestWhenJobContentIsBlank() throws Exception {
        mockMvc.perform(post("/api/jobs")
                .header("X-API-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"  \"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void returnsBadRequestWhenAnalysisIdsAreMissing() throws Exception {
        mockMvc.perform(post("/api/analysis")
                .header("X-API-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void returnsNotFoundWhenResumeDoesNotExist() throws Exception {
        when(resumeRepository.existsById(1L)).thenReturn(false);

        mockMvc.perform(post("/api/analysis")
                .header("X-API-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resumeId\":1,\"jobDescriptionId\":2}"))
            .andExpect(status().isNotFound());
    }
}
