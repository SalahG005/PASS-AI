package com.pass.ai_assistant_backend.dto;

public class FileContentDto {
    private String path;
    private String content;

    public FileContentDto() {
    }

    public FileContentDto(String path, String content) {
        this.path = path;
        this.content = content;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
