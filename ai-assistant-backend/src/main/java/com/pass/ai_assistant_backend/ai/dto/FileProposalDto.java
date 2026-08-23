package com.pass.ai_assistant_backend.ai.dto;

public class FileProposalDto {
    private String path;
    private String action;
    private String content;
    /** Existing file body before overwrite (null for create). Used for Diff Apply UI. */
    private String previousContent;

    public FileProposalDto() {
    }

    public FileProposalDto(String path, String action, String content) {
        this.path = path;
        this.action = action;
        this.content = content;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getPreviousContent() {
        return previousContent;
    }

    public void setPreviousContent(String previousContent) {
        this.previousContent = previousContent;
    }
}
