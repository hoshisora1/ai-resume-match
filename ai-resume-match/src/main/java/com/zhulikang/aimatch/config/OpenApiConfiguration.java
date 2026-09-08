package com.zhulikang.aimatch.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration
@OpenAPIDefinition(
    info = @Info(
        title = "AI Resume Match API",
        version = "v1",
        description = "Asynchronous resume and job matching API"
    )
)
@SecurityScheme(
    name = OpenApiConfiguration.API_TOKEN_SCHEME,
    type = SecuritySchemeType.APIKEY,
    in = SecuritySchemeIn.HEADER,
    paramName = "X-API-Token",
    description = "Application API token"
)
public class OpenApiConfiguration {
    public static final String API_TOKEN_SCHEME = "apiToken";
    private static final Set<String> RATE_LIMITED_OPERATIONS = Set.of(
        "createAnalysis",
        "createAnalysisSubmission",
        "retryAnalysis"
    );

    @Bean
    OperationCustomizer standardProblemResponses() {
        return (operation, handlerMethod) -> {
            operation.getResponses().addApiResponse(
                "400",
                problemResponse("Invalid request")
            );
            operation.getResponses().addApiResponse(
                "401",
                problemResponse("Missing or invalid API token")
            );
            if (RATE_LIMITED_OPERATIONS.contains(handlerMethod.getMethod().getName())) {
                operation.getResponses().addApiResponse(
                    "429",
                    problemResponse("Anonymous session analysis quota exceeded; see Retry-After")
                );
                operation.getResponses().addApiResponse(
                    "503",
                    problemResponse("Analysis quota enforcement is temporarily unavailable")
                );
            }
            return operation;
        };
    }

    private static ApiResponse problemResponse(String description) {
        Schema<?> schema = new Schema<>().$ref("#/components/schemas/ApiProblemDetail");
        Content content = new Content().addMediaType(
            org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            new io.swagger.v3.oas.models.media.MediaType().schema(schema)
        );
        return new ApiResponse().description(description).content(content);
    }
}
