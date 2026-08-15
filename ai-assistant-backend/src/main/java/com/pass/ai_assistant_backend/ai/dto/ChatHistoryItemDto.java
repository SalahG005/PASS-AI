package com.pass.ai_assistant_backend.ai.dto;

public class ChatHistoryItemDto {
    private String role;
    private String content;

    public ChatHistoryItemDto() {
    }

    public ChatHistoryItemDto(String role, String content) {
        this.role = role;
        this.content = content;
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
}
