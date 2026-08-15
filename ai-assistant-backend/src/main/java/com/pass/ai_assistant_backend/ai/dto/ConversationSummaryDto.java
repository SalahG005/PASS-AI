package com.pass.ai_assistant_backend.ai.dto;

import java.time.Instant;
import java.util.List;

public class ConversationSummaryDto {
    private Long id;
    private String title;
    private String mode;
    private String workspaceId;
    private Instant createdAt;
    private Instant updatedAt;
    private long messageCount;

    public ConversationSummaryDto() {
    }

    public ConversationSummaryDto(
            Long id,
            String title,
            String mode,
            String workspaceId,
            Instant createdAt,
            Instant updatedAt,
            long messageCount
    ) {
        this.id = id;
        this.title = title;
        this.mode = mode;
        this.workspaceId = workspaceId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.messageCount = messageCount;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
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

    public long getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(long messageCount) {
        this.messageCount = messageCount;
    }
}
