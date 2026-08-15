package com.pass.ai_assistant_backend.ai.dto;

public class AgentToolStepDto {
    private String name;
    private String argsSummary;
    private String status;
    private String resultPreview;

    public AgentToolStepDto() {
    }

    public AgentToolStepDto(String name, String argsSummary, String status) {
        this(name, argsSummary, status, null);
    }

    public AgentToolStepDto(String name, String argsSummary, String status, String resultPreview) {
        this.name = name;
        this.argsSummary = argsSummary;
        this.status = status;
        this.resultPreview = resultPreview;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArgsSummary() {
        return argsSummary;
    }

    public void setArgsSummary(String argsSummary) {
        this.argsSummary = argsSummary;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getResultPreview() {
        return resultPreview;
    }

    public void setResultPreview(String resultPreview) {
        this.resultPreview = resultPreview;
    }
}
