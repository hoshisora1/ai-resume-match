package com.zhulikang.aimatch.resume;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;

import java.time.LocalDateTime;

@Entity
public class Resume {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fileName;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String rawText;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String structuredSummary;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected Resume() {
    }

    public Resume(String fileName, String rawText, String structuredSummary) {
        this.fileName = fileName;
        this.rawText = rawText;
        this.structuredSummary = structuredSummary;
    }

    public Long getId() {
        return id;
    }

    public String getFileName() {
        return fileName;
    }

    public String getRawText() {
        return rawText;
    }

    public String getStructuredSummary() {
        return structuredSummary;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
