package com.pass.ai_assistant_backend.dto;

public class ProblemDto {
    private String severity;
    private String message;
    private String file;
    private int line;

    public ProblemDto() {
    }

    public ProblemDto(String severity, String message, String file, int line) {
        this.severity = severity;
        this.message = message;
        this.file = file;
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

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }
}
