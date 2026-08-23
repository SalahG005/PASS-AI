package com.pass.ai_assistant_backend.ai.dto;

import java.util.ArrayList;
import java.util.List;

public class InstallResultDto {
    private boolean ok;
    private List<String> executed = new ArrayList<>();
    private List<String> errors = new ArrayList<>();
    private String output = "";

    public InstallResultDto() {
    }

    public InstallResultDto(boolean ok, List<String> executed, List<String> errors, String output) {
        this.ok = ok;
        this.executed = executed != null ? executed : new ArrayList<>();
        this.errors = errors != null ? errors : new ArrayList<>();
        this.output = output == null ? "" : output;
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public List<String> getExecuted() {
        return executed;
    }

    public void setExecuted(List<String> executed) {
        this.executed = executed;
    }

    public List<String> getErrors() {
        return errors;
    }

    public void setErrors(List<String> errors) {
        this.errors = errors;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }
}
