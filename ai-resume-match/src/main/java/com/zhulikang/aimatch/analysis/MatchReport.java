package com.zhulikang.aimatch.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;

import java.time.LocalDateTime;

@Entity
public class MatchReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long taskId;

    @Column(nullable = false)
    private int matchScore;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String reportContent;

    @Column(nullable = false, length = 32)
    private String reportSchemaVersion;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String structuredReportJson;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String provenanceJson;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected MatchReport() {
    }

    public MatchReport(Long taskId, int matchScore, String reportContent) {
        this(taskId, matchScore, reportContent, "markdown-v1", null, null);
    }

    public MatchReport(
        Long taskId,
        int matchScore,
        String reportContent,
        String reportSchemaVersion,
        String structuredReportJson,
        String provenanceJson
    ) {
        this.taskId = taskId;
        this.matchScore = matchScore;
        this.reportContent = reportContent;
        this.reportSchemaVersion = reportSchemaVersion;
        this.structuredReportJson = structuredReportJson;
        this.provenanceJson = provenanceJson;
    }

    public Long getId() {
        return id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public int getMatchScore() {
        return matchScore;
    }

    public String getReportContent() {
        return reportContent;
    }

    public String getReportSchemaVersion() {
        return reportSchemaVersion;
    }

    public String getStructuredReportJson() {
        return structuredReportJson;
    }

    public String getProvenanceJson() {
        return provenanceJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
