package com.pass.ai_assistant_backend.project.dto;

import java.time.Instant;

public class ProjectDto {
    private String projectId;
    private String name;
    private String absolutePath;
    private String storageType;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastOpenedAt;

    public ProjectDto() {
    }

    public ProjectDto(
            String projectId,
            String name,
            String absolutePath,
            String storageType,
            Instant createdAt,
            Instant updatedAt,
            Instant lastOpenedAt
    ) {
        this.projectId = projectId;
        this.name = name;
        this.absolutePath = absolutePath;
        this.storageType = storageType;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastOpenedAt = lastOpenedAt;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
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
