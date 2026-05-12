package com.zhulikang.aimatch.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiCompatibleClientTest {
    @Test
    void sendsOpenAiCompatibleRequestAndReturnsMessageContent() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
            restTemplate,
            "https://example.test/v1/chat/completions",
            "test-key",
            "test-model"
        );
        server.expect(requestTo("https://example.test/v1/chat/completions"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
            .andRespond(withSuccess("""
                {"choices":[{"message":{"content":"匹配分数：88"}}]}
                """, MediaType.APPLICATION_JSON));

        String result = client.complete("请分析");

        assertThat(result).isEqualTo("匹配分数：88");
        server.verify();
    }
}
