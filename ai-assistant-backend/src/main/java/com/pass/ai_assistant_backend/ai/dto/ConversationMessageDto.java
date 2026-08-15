package com.pass.ai_assistant_backend.ai.dto;

import java.time.Instant;

public class ConversationMessageDto {
    private Long id;
    private String role;
    private String content;
    private Integer sortOrder;
    private Instant createdAt;

    public ConversationMessageDto() {
    }

    public ConversationMessageDto(Long id, String role, String content, int sortOrder, Instant createdAt) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.sortOrder = sortOrder;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
