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

    @Column(nullable = false, length = 120)
    private String title;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Column(nullable = false)
    private String skillTags;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected JobDescription() {
    }

    public JobDescription(String title, String content, String skillTags) {
        this.title = title;
        this.content = content;
        this.skillTags = skillTags;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
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
