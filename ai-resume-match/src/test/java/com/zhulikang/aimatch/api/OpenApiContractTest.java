package com.zhulikang.aimatch.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "api.token=test-token",
    "analysis.scheduling.enabled=false"
})
class OpenApiContractTest {
    private static final Path SNAPSHOT = Path.of("api", "openapi.json");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    RabbitTemplate rabbitTemplate;

    @MockBean
    StringRedisTemplate redisTemplate;

    @Test
    void generatedOpenApiMatchesTheCommittedFrontendContract() throws Exception {
        String responseBody = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

        JsonNode generated = objectMapper.readTree(responseBody);
        ((ObjectNode) generated).remove("servers");
        generated = canonicalize(generated);
        String normalized = objectMapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(generated) + System.lineSeparator();

        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, normalized, StandardCharsets.UTF_8);
        }

        assertThat(SNAPSHOT)
            .as("Run mvn -Dopenapi.update=true -Dtest=OpenApiContractTest test after an intentional API change")
            .isRegularFile();
        assertThat(Files.readString(SNAPSHOT, StandardCharsets.UTF_8)).isEqualTo(normalized);

        assertThat(generated.at("/paths/~1api~1analysis/post/responses/202").isMissingNode())
            .isFalse();
        assertThat(generated.at("/paths/~1api~1analysis/post/responses/429").isMissingNode())
            .isFalse();
        assertThat(generated.at("/paths/~1api~1analysis/post/responses/503").isMissingNode())
            .isFalse();
        assertThat(generated.at(
            "/paths/~1api~1analysis~1{taskId}/delete/responses/204"
        ).isMissingNode()).isFalse();
        assertThat(generated.at(
            "/paths/~1api~1analysis-submissions/post/responses/202/headers/Location"
        ).isMissingNode()).isFalse();
        assertThat(generated.at(
            "/components/securitySchemes/apiToken/name"
        ).asText()).isEqualTo("X-API-Token");
        assertThat(generated.at(
            "/components/schemas/ApiProblemDetail/properties/requestId"
        ).isMissingNode()).isFalse();
        assertThat(generated.at(
            "/components/schemas/MatchReportResponse/properties/structuredReport/$ref"
        ).asText()).isEqualTo("#/components/schemas/StructuredMatchReport");
        assertThat(generated.at(
            "/components/schemas/MatchReportResponse/properties/provenance/$ref"
        ).asText()).isEqualTo("#/components/schemas/AnalysisProvenance");
        assertThat(generated.at(
            "/components/schemas/AnalysisProvenance/properties/runMetadata/$ref"
        ).asText()).isEqualTo("#/components/schemas/RunMetadata");
        assertThat(generated.at(
            "/components/schemas/RunMetadata/properties/inputFingerprint/pattern"
        ).asText()).isEqualTo("^[0-9a-f]{64}$");
        assertThat(generated.at("/components/schemas/JsonNode").isMissingNode()).isTrue();
        assertThat(textValues(generated.at(
            "/components/schemas/MatchReportResponse/required"
        ))).containsExactlyInAnyOrder(
            "taskId",
            "matchScore",
            "reportContent",
            "reportSchemaVersion",
            "structuredReport",
            "provenance",
            "createdAt"
        );
        assertThat(textValues(generated.at(
            "/components/schemas/RunMetadata/required"
        ))).contains(
            "schemaVersion",
            "requestSchemaVersion",
            "agentRuntimeVersion",
            "inputFingerprint",
            "chatProviderCalls",
            "totalDurationMs"
        );
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, JsonNode> sortedFields = new TreeMap<>();
            node.fields().forEachRemaining(entry ->
                sortedFields.put(entry.getKey(), canonicalize(entry.getValue()))
            );
            ObjectNode result = objectMapper.createObjectNode();
            sortedFields.forEach(result::set);
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(child -> result.add(canonicalize(child)));
            return result;
        }
        return node;
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new java.util.ArrayList<>();
        array.forEach(item -> values.add(item.asText()));
        return values;
    }
}
