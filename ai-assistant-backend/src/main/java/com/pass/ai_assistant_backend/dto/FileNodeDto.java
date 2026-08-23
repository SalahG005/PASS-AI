package com.pass.ai_assistant_backend.dto;

import java.util.ArrayList;
import java.util.List;

public class FileNodeDto {
    private String name;
    private String path;
    private String type; // file | folder
    private List<FileNodeDto> children = new ArrayList<>();

    public FileNodeDto() {
    }

    public FileNodeDto(String name, String path, String type) {
        this.name = name;
        this.path = path;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<FileNodeDto> getChildren() {
        return children;
    }

    public void setChildren(List<FileNodeDto> children) {
        this.children = children;
    }
}
