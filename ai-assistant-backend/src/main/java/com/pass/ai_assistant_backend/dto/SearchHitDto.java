package com.pass.ai_assistant_backend.dto;

public class SearchHitDto {
    private String path;
    private int line;
    private String preview;

    public SearchHitDto() {
    }

    public SearchHitDto(String path, int line, String preview) {
        this.path = path;
        this.line = line;
        this.preview = preview;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    public String getPreview() {
        return preview;
    }

    public void setPreview(String preview) {
        this.preview = preview;
    }
}
