package com.pass.ai_assistant_backend.ai.dto;

/** Queued package install — user must click Install (never auto-run). */
public class InstallProposalDto {
    private String command;
    private String reason;
    private String status = "queued";

    public InstallProposalDto() {
    }

    public InstallProposalDto(String command, String reason) {
        this.command = command;
        this.reason = reason;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
