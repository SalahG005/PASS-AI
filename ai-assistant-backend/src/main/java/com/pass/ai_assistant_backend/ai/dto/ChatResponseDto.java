package com.pass.ai_assistant_backend.ai.dto;

import java.util.ArrayList;
import java.util.List;

public class ChatResponseDto {
    private String reply;
    private String model;
    private List<String> usedPaths = new ArrayList<>();
    private List<String> ragPaths = new ArrayList<>();

    public ChatResponseDto() {
    }

    public ChatResponseDto(String reply, String model, List<String> usedPaths, List<String> ragPaths) {
        this.reply = reply;
        this.model = model;
        this.usedPaths = usedPaths != null ? usedPaths : new ArrayList<>();
        this.ragPaths = ragPaths != null ? ragPaths : new ArrayList<>();
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<String> getUsedPaths() {
        return usedPaths;
    }

    public void setUsedPaths(List<String> usedPaths) {
        this.usedPaths = usedPaths;
    }

    public List<String> getRagPaths() {
        return ragPaths;
    }

    public void setRagPaths(List<String> ragPaths) {
        this.ragPaths = ragPaths;
    }
}
