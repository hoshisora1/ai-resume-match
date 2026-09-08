package com.zhulikang.aimatch.resume;

import com.zhulikang.aimatch.security.OwnerId;
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

    @Column(nullable = false, length = 64, columnDefinition = "char(64)")
    private String ownerId;

    @Column(nullable = false)
    private String fileName;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String rawText;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected Resume() {
    }

    public Resume(String fileName, String rawText) {
        this(OwnerId.LEGACY, fileName, rawText);
    }

    public Resume(String ownerId, String fileName, String rawText) {
        this.ownerId = OwnerId.requireValid(ownerId);
        this.fileName = fileName;
        this.rawText = rawText;
    }

    public Long getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getRawText() {
        return rawText;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
