package com.pass.ai_assistant_backend.ai.dto;

import java.util.ArrayList;
import java.util.List;

public class ApplyRequestDto {
    private List<FileProposalDto> files = new ArrayList<>();

    public List<FileProposalDto> getFiles() {
        return files;
    }

    public void setFiles(List<FileProposalDto> files) {
        this.files = files != null ? files : new ArrayList<>();
    }
}
