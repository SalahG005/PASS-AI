package com.pass.ai_assistant_backend.ai.dto;

import java.util.ArrayList;
import java.util.List;

/** One build phase for multi-step Composer scaffolds (e.g. backend → frontend). */
public class ProjectPlanPhaseDto {
    private String id;
    private String name;
    private String status; // pending | active | proposed | applied | done
    private List<ProjectPlanFileDto> files = new ArrayList<>();

    public ProjectPlanPhaseDto() {
    }

    public ProjectPlanPhaseDto(String id, String name) {
        this.id = id;
        this.name = name;
        this.status = "pending";
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<ProjectPlanFileDto> getFiles() {
        return files;
    }

    public void setFiles(List<ProjectPlanFileDto> files) {
        this.files = files != null ? files : new ArrayList<>();
    }
}
