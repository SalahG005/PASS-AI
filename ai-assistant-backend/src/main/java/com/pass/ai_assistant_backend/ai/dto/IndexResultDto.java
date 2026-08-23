package com.pass.ai_assistant_backend.ai.dto;

public class IndexResultDto {
    private boolean ok;
    private int filesIndexed;
    private int chunksStored;
    private String message;

    public IndexResultDto() {
    }

    public IndexResultDto(boolean ok, int filesIndexed, int chunksStored, String message) {
        this.ok = ok;
        this.filesIndexed = filesIndexed;
        this.chunksStored = chunksStored;
        this.message = message;
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public int getFilesIndexed() {
        return filesIndexed;
    }

    public void setFilesIndexed(int filesIndexed) {
        this.filesIndexed = filesIndexed;
    }

    public int getChunksStored() {
        return chunksStored;
    }

    public void setChunksStored(int chunksStored) {
        this.chunksStored = chunksStored;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
