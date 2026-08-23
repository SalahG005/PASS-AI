package com.pass.ai_assistant_backend.ai.dto;

import java.util.ArrayList;
import java.util.List;

public class ApplyResultDto {
    private boolean ok;
    private List<String> applied = new ArrayList<>();
    private List<String> errors = new ArrayList<>();

    public ApplyResultDto() {
    }

    public ApplyResultDto(boolean ok, List<String> applied, List<String> errors) {
        this.ok = ok;
        this.applied = applied != null ? applied : new ArrayList<>();
        this.errors = errors != null ? errors : new ArrayList<>();
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public List<String> getApplied() {
        return applied;
    }

    public void setApplied(List<String> applied) {
        this.applied = applied;
    }

    public List<String> getErrors() {
        return errors;
    }

    public void setErrors(List<String> errors) {
        this.errors = errors;
    }
}
