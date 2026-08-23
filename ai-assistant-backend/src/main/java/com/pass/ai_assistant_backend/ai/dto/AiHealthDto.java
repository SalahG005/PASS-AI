package com.pass.ai_assistant_backend.ai.dto;

import java.util.ArrayList;
import java.util.List;

public class AiHealthDto {
    private boolean ok;
    private boolean ollamaReachable;
    private boolean chatModelPresent;
    private boolean embedModelPresent;
    private String baseUrl;
    private String chatModel;
    private String embedModel;
    private String message;
    private List<String> models = new ArrayList<>();

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public boolean isOllamaReachable() {
        return ollamaReachable;
    }

    public void setOllamaReachable(boolean ollamaReachable) {
        this.ollamaReachable = ollamaReachable;
    }

    public boolean isChatModelPresent() {
        return chatModelPresent;
    }

    public void setChatModelPresent(boolean chatModelPresent) {
        this.chatModelPresent = chatModelPresent;
    }

    public boolean isEmbedModelPresent() {
        return embedModelPresent;
    }

    public void setEmbedModelPresent(boolean embedModelPresent) {
        this.embedModelPresent = embedModelPresent;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getChatModel() {
        return chatModel;
    }

    public void setChatModel(String chatModel) {
        this.chatModel = chatModel;
    }

    public String getEmbedModel() {
        return embedModel;
    }

    public void setEmbedModel(String embedModel) {
        this.embedModel = embedModel;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<String> getModels() {
        return models;
    }

    public void setModels(List<String> models) {
        this.models = models;
    }
}
