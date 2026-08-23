package com.pass.ai_assistant_backend.ai.dto;

import java.util.ArrayList;
import java.util.List;

public class AgentResponseDto {
    private String reply;
    private String model;
    private String agentMode;
    private List<String> usedPaths = new ArrayList<>();
    private List<String> ragPaths = new ArrayList<>();
    private List<FileProposalDto> files = new ArrayList<>();
    private List<AgentToolStepDto> steps = new ArrayList<>();
    private List<ReviewFindingDto> findings = new ArrayList<>();
    /** Test Agent: summary of terminal test run (pass/fail). */
    private String testRunSummary;
    /** Scaffold / Composer: multi-file project plan. */
    private ProjectPlanDto projectPlan;
    /** Ollama chat calls made during this run (for diagnostics). */
    private int ollamaCallCount;
    /** Queued install commands — require explicit Install in UI. */
    private List<InstallProposalDto> installProposals = new ArrayList<>();
    /** Optional post-scaffold layout preview. */
    private ScaffoldPreviewDto scaffoldPreview;

    public AgentResponseDto() {
    }

    public AgentResponseDto(
            String reply,
            String model,
            List<String> usedPaths,
            List<String> ragPaths,
            List<FileProposalDto> files
    ) {
        this(reply, model, usedPaths, ragPaths, files, List.of());
    }

    public AgentResponseDto(
            String reply,
            String model,
            List<String> usedPaths,
            List<String> ragPaths,
            List<FileProposalDto> files,
            List<AgentToolStepDto> steps
    ) {
        this.reply = reply;
        this.model = model;
        this.usedPaths = usedPaths != null ? usedPaths : new ArrayList<>();
        this.ragPaths = ragPaths != null ? ragPaths : new ArrayList<>();
        this.files = files != null ? files : new ArrayList<>();
        this.steps = steps != null ? steps : new ArrayList<>();
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getAgentMode() {
        return agentMode;
    }

    public void setAgentMode(String agentMode) {
        this.agentMode = agentMode;
    }

    public List<String> getUsedPaths() {
        return usedPaths;
    }

    public void setUsedPaths(List<String> usedPaths) {
        this.usedPaths = usedPaths;
    }

    public List<String> getRagPaths() {
        return ragPaths;
    }

    public void setRagPaths(List<String> ragPaths) {
        this.ragPaths = ragPaths;
    }

    public List<FileProposalDto> getFiles() {
        return files;
    }

    public void setFiles(List<FileProposalDto> files) {
        this.files = files;
    }

    public List<AgentToolStepDto> getSteps() {
        return steps;
    }

    public void setSteps(List<AgentToolStepDto> steps) {
        this.steps = steps;
    }

    public List<ReviewFindingDto> getFindings() {
        return findings;
    }

    public void setFindings(List<ReviewFindingDto> findings) {
        this.findings = findings != null ? findings : new ArrayList<>();
    }

    public String getTestRunSummary() {
        return testRunSummary;
    }

    public void setTestRunSummary(String testRunSummary) {
        this.testRunSummary = testRunSummary;
    }

    public ProjectPlanDto getProjectPlan() {
        return projectPlan;
    }

    public void setProjectPlan(ProjectPlanDto projectPlan) {
        this.projectPlan = projectPlan;
    }

    public int getOllamaCallCount() {
        return ollamaCallCount;
    }

    public void setOllamaCallCount(int ollamaCallCount) {
        this.ollamaCallCount = ollamaCallCount;
    }

    public List<InstallProposalDto> getInstallProposals() {
        return installProposals;
    }

    public void setInstallProposals(List<InstallProposalDto> installProposals) {
        this.installProposals = installProposals != null ? installProposals : new ArrayList<>();
    }

    public ScaffoldPreviewDto getScaffoldPreview() {
        return scaffoldPreview;
    }

    public void setScaffoldPreview(ScaffoldPreviewDto scaffoldPreview) {
        this.scaffoldPreview = scaffoldPreview;
    }
}
