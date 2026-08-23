package com.pass.ai_assistant_backend.project;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "user_projects", indexes = {
        @Index(name = "idx_project_email_opened", columnList = "email, lastOpenedAt"),
        @Index(name = "idx_project_public_id", columnList = "projectId", unique = true)
})
public class UserProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Public id used as X-Workspace-Id / conversation.workspaceId */
    @Column(nullable = false, unique = true, length = 64)
    private String projectId;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, length = 1024)
    private String absolutePath;

    /** APPDATA | LINKED | RELOCATED */
    @Column(nullable = false, length = 32)
    private String storageType = "APPDATA";

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(nullable = false)
    private Instant lastOpenedAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAbsolutePath() {
        return absolutePath;
    }

    public void setAbsolutePath(String absolutePath) {
        this.absolutePath = absolutePath;
    }

    public String getStorageType() {
        return storageType;
    }

    public void setStorageType(String storageType) {
        this.storageType = storageType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getLastOpenedAt() {
        return lastOpenedAt;
    }

    public void setLastOpenedAt(Instant lastOpenedAt) {
        this.lastOpenedAt = lastOpenedAt;
    }
}
