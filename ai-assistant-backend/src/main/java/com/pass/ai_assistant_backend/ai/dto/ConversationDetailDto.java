package com.pass.ai_assistant_backend.ai.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class ConversationDetailDto {
    private Long id;
    private String title;
    private String mode;
    private String workspaceId;
    private Instant createdAt;
    private Instant updatedAt;
    private List<ConversationMessageDto> messages = new ArrayList<>();

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

    public List<ConversationMessageDto> getMessages() {
        return messages;
    }

    public void setMessages(List<ConversationMessageDto> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
    }
}
