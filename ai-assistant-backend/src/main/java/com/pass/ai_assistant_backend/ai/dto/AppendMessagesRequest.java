package com.pass.ai_assistant_backend.ai.dto;

import java.util.ArrayList;
import java.util.List;

public class AppendMessagesRequest {
    private List<ConversationMessageDto> messages = new ArrayList<>();
    private String title;

    public List<ConversationMessageDto> getMessages() {
        return messages;
    }

    public void setMessages(List<ConversationMessageDto> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
