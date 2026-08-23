package com.pass.ai_assistant_backend.ai.dto;

public class ProjectPlanFileDto {
    private String path;
    private String purpose;
    private String status; // pending | proposed | applied

    public ProjectPlanFileDto() {
    }

    public ProjectPlanFileDto(String path, String purpose) {
        this.path = path;
        this.purpose = purpose;
        this.status = "pending";
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
