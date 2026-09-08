package com.zhulikang.aimatch.api;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.zhulikang.aimatch.observability.RequestCorrelation;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.util.Locale;

@JsonPropertyOrder({
    "type",
    "title",
    "status",
    "detail",
    "instance",
    "code",
    "requestId",
    "message"
})
@Schema(
    name = "ApiProblemDetail",
    description = "RFC 9457 problem details with stable application error code and request ID",
    requiredProperties = {
        "type", "title", "status", "detail", "instance", "code", "requestId", "message"
    }
)
public record ApiProblemDetail(
    URI type,
    String title,
    int status,
    String detail,
    URI instance,
    String code,
    @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) String requestId,
    String message
) {
    public static ApiProblemDetail of(
        HttpStatus status,
        String code,
        String detail,
        String requestUri
    ) {
        String safeDetail = detail == null || detail.isBlank()
            ? status.getReasonPhrase()
            : detail;
        return new ApiProblemDetail(
            URI.create("urn:ai-resume-match:problem:" + problemSlug(code)),
            status.getReasonPhrase(),
            status.value(),
            safeDetail,
            URI.create(requestUri),
            code,
            RequestCorrelation.currentRequestId(),
            safeDetail
        );
    }

    private static String problemSlug(String code) {
        return code.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
