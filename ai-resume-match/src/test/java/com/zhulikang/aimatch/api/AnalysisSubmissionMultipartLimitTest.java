package com.zhulikang.aimatch.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.application.analysis.AnalysisSubmission;
import com.zhulikang.aimatch.application.analysis.CreateAnalysisSubmissionUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.support.AllEncompassingFormHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalysisSubmissionMultipartLimitTest {
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_JOB_CONTENT_CODE_POINTS = 20_000;
    private static final PropertySource<?> PRODUCTION_APPLICATION_PROPERTIES = loadProductionApplicationProperties();

    @DynamicPropertySource
    static void configureProductionMultipartLimits(DynamicPropertyRegistry registry) {
        registry.add(
            "spring.servlet.multipart.max-file-size",
            () -> productionProperty("spring.servlet.multipart.max-file-size")
        );
        registry.add(
            "spring.servlet.multipart.max-request-size",
            () -> productionProperty("spring.servlet.multipart.max-request-size")
        );
    }

    @LocalServerPort
    int port;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    MultipartProperties multipartProperties;
    @MockBean
    CreateAnalysisSubmissionUseCase createAnalysisSubmissionUseCase;
    @MockBean
    RabbitTemplate rabbitTemplate;
    @MockBean
    StringRedisTemplate redisTemplate;

    @Test
    void acceptsFiveMebibyteFileWithMaximumUtf8JobContent() throws Exception {
        assertThat(multipartProperties.getMaxFileSize().toBytes()).isEqualTo(MAX_FILE_BYTES + 1L);
        assertThat(multipartProperties.getMaxRequestSize().toBytes()).isEqualTo(6L * 1024 * 1024);
        String jobTitle = "Backend Engineer";
        String jobContent = "\uD83D\uDE80".repeat(MAX_JOB_CONTENT_CODE_POINTS);
        AnalysisTask task = new AnalysisTask(10L, 20L);
        ReflectionTestUtils.setField(task, "id", 30L);
        when(createAnalysisSubmissionUseCase.create(any(MultipartFile.class), eq(jobTitle), eq(jobContent)))
            .thenReturn(new AnalysisSubmission(task, jobTitle, "resume.pdf"));

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.APPLICATION_PDF);
        HttpEntity<ByteArrayResource> filePart = new HttpEntity<>(resumeResource(), fileHeaders);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart);
        body.add("jobTitle", jobTitle);
        body.add("jobContent", jobContent);

        MockHttpOutputMessage encodedRequest = new MockHttpOutputMessage();
        new AllEncompassingFormHttpMessageConverter()
            .write(body, MediaType.MULTIPART_FORM_DATA, encodedRequest);
        byte[] requestBody = encodedRequest.getBodyAsBytes();
        assertThat(requestBody.length).isLessThan(6 * 1024 * 1024);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/analysis-submissions"))
            .header(HttpHeaders.CONTENT_TYPE, encodedRequest.getHeaders().getContentType().toString())
            .header("X-API-Token", "test-token")
            .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
            .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            request,
            HttpResponse.BodyHandlers.ofString()
        );

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.required("taskId").asLong()).isEqualTo(30L);
        verify(createAnalysisSubmissionUseCase).create(any(MultipartFile.class), eq(jobTitle), eq(jobContent));
    }

    private ByteArrayResource resumeResource() {
        return new ByteArrayResource(new byte[MAX_FILE_BYTES]) {
            @Override
            public String getFilename() {
                return "resume.pdf";
            }
        };
    }

    private static PropertySource<?> loadProductionApplicationProperties() {
        try {
            return new YamlPropertySourceLoader()
                .load("production-application", new FileSystemResource("src/main/resources/application.yml"))
                .getFirst();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Object productionProperty(String name) {
        Object value = PRODUCTION_APPLICATION_PROPERTIES.getProperty(name);
        if (value == null) {
            throw new IllegalStateException("Missing production property: " + name);
        }
        return value;
    }
}
