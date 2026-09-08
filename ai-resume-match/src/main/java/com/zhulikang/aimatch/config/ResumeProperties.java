package com.zhulikang.aimatch.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("resume")
public record ResumeProperties(@NotNull @Valid Upload upload) {
    public record Upload(
        @NotNull DataSize maxFileSize,
        @NotNull @Positive Integer maxPdfPages,
        @NotNull @Positive Integer maxDocxEntries,
        @NotNull DataSize maxDocxEntrySize,
        @NotNull DataSize maxDocxUncompressedSize
    ) {
        @AssertTrue(message = "resume upload byte limits must be positive")
        public boolean isByteLimitsValid() {
            return isPositive(maxFileSize)
                && isPositive(maxDocxEntrySize)
                && isPositive(maxDocxUncompressedSize);
        }

        @AssertTrue(message = "max-docx-uncompressed-size must be at least max-docx-entry-size")
        public boolean isDocxAggregateLimitValid() {
            return maxDocxEntrySize != null
                && maxDocxUncompressedSize != null
                && maxDocxUncompressedSize.toBytes() >= maxDocxEntrySize.toBytes();
        }

        private static boolean isPositive(DataSize value) {
            return value != null && value.toBytes() > 0;
        }
    }
}
