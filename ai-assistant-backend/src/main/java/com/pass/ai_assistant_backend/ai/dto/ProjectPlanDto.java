package com.pass.ai_assistant_backend.ai.dto;

import java.util.ArrayList;
import java.util.List;

public class ProjectPlanDto {
    private String title;
    private String stack;
    private String summary;
    /** Flat file list for the active phase (compat with existing UI/loop). */
    private List<ProjectPlanFileDto> files = new ArrayList<>();
    /** Optional multi-phase plan for full-stack / large projects. */
    private List<ProjectPlanPhaseDto> phases = new ArrayList<>();
    /** 0-based index of the phase currently being generated. */
    private int currentPhase;

    public ProjectPlanDto() {
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStack() {
        return stack;
    }

    public void setStack(String stack) {
        this.stack = stack;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<ProjectPlanFileDto> getFiles() {
        return files;
    }

    public void setFiles(List<ProjectPlanFileDto> files) {
        this.files = files != null ? files : new ArrayList<>();
    }

    public List<ProjectPlanPhaseDto> getPhases() {
        return phases;
    }

    public void setPhases(List<ProjectPlanPhaseDto> phases) {
        this.phases = phases != null ? phases : new ArrayList<>();
    }

    public int getCurrentPhase() {
        return currentPhase;
    }

    public void setCurrentPhase(int currentPhase) {
        this.currentPhase = Math.max(0, currentPhase);
    }

    public int pendingCount() {
        int n = 0;
        for (ProjectPlanFileDto f : files) {
            if (f != null && (f.getStatus() == null || "pending".equalsIgnoreCase(f.getStatus()))) {
                n++;
            }
        }
        return n;
    }

    public boolean hasPhases() {
        return phases != null && !phases.isEmpty();
    }

    public ProjectPlanPhaseDto activePhase() {
        if (!hasPhases()) {
            return null;
        }
        int i = Math.min(currentPhase, phases.size() - 1);
        return phases.get(i);
    }

    public boolean hasNextPhase() {
        return hasPhases() && currentPhase + 1 < phases.size();
    }

    /** Sync flat files from the active phase (or keep files if no phases). */
    public void syncFilesFromActivePhase() {
        if (!hasPhases()) {
            return;
        }
        ProjectPlanPhaseDto p = activePhase();
        if (p == null) {
            return;
        }
        for (int i = 0; i < phases.size(); i++) {
            ProjectPlanPhaseDto ph = phases.get(i);
            if (ph == null) {
                continue;
            }
            if (i < currentPhase) {
                ph.setStatus("done");
            } else if (i == currentPhase) {
                ph.setStatus("active");
            } else if (ph.getStatus() == null || ph.getStatus().isBlank()) {
                ph.setStatus("pending");
            }
        }
        List<ProjectPlanFileDto> list = p.getFiles() == null ? new ArrayList<>() : new ArrayList<>(p.getFiles());
        this.files = list;
    }
}
