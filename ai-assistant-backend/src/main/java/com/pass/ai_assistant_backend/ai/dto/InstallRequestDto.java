package com.pass.ai_assistant_backend.ai.dto;

import java.util.ArrayList;
import java.util.List;

public class InstallRequestDto {
    private List<InstallProposalDto> commands = new ArrayList<>();

    public List<InstallProposalDto> getCommands() {
        return commands;
    }

    public void setCommands(List<InstallProposalDto> commands) {
        this.commands = commands != null ? commands : new ArrayList<>();
    }
}
