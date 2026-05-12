package com.zhulikang.aimatch.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;

import java.time.LocalDateTime;

@Entity
public class JobDescription {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Column(nullable = false)
    private String skillTags;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected JobDescription() {
    }

    public JobDescription(String content, String skillTags) {
        this.content = content;
        this.skillTags = skillTags;
    }

    public Long getId() {
        return id;
    }

    public String getContent() {
        return content;
    }

    public String getSkillTags() {
        return skillTags;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
