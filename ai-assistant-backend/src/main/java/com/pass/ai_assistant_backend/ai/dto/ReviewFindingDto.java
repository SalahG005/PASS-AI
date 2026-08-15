package com.pass.ai_assistant_backend.ai.dto;

public class ReviewFindingDto {
    private String path;
    private Integer line;
    private String severity;
    private String message;

    public ReviewFindingDto() {
    }

    public ReviewFindingDto(String path, Integer line, String severity, String message) {
        this.path = path;
        this.line = line;
        this.severity = severity;
        this.message = message;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Integer getLine() {
        return line;
    }

    public void setLine(Integer line) {
        this.line = line;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
