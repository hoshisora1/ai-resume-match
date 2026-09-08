package com.zhulikang.aimatch.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ai")
public record AiProperties(
    @NotBlank
    @Pattern(regexp = "https?://\\S+", message = "must be an absolute HTTP(S) URL")
    String endpoint,
    @NotBlank String apiKey,
    @NotBlank String model
) {
}
