package com.pass.ai_assistant_backend.ai;

import com.pass.ai_assistant_backend.ai.dto.InstallProposalDto;
import com.pass.ai_assistant_backend.ai.dto.AgentResponseDto;
import com.pass.ai_assistant_backend.ai.dto.AgentToolStepDto;
import com.pass.ai_assistant_backend.ai.dto.ChatHistoryItemDto;
import com.pass.ai_assistant_backend.ai.dto.ChatRequestDto;
import com.pass.ai_assistant_backend.ai.dto.FileProposalDto;
import com.pass.ai_assistant_backend.ai.dto.ProjectPlanDto;
import com.pass.ai_assistant_backend.ai.dto.ProjectPlanFileDto;
import com.pass.ai_assistant_backend.ai.dto.ProjectPlanPhaseDto;
import com.pass.ai_assistant_backend.ai.dto.ReviewFindingDto;
import com.pass.ai_assistant_backend.dto.FileContentDto;
import com.pass.ai_assistant_backend.dto.SearchHitDto;
import com.pass.ai_assistant_backend.service.WorkspaceService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Multi-step agent loop shared by all specialized modes.
 * Tool execution is gated by {@link AgentMode#allows(String)} — not prompt-only.
 */
@Service
public class AgentOrchestrator {

    private static final Pattern DANGEROUS = Pattern.compile(
            "(?i)(\\brm\\s+-rf\\b|\\bdel\\s+/[sf]|\\bformat\\s+|\\bmkfs\\b|\\bRemove-Item\\b.*-Recurse|"
                    + "\\bshutdown\\b|\\breg\\s+delete\\b|\\bcurl\\s+.*\\|\\s*sh\\b)"
    );

    private static final Pattern INSTALL_CMD = Pattern.compile(
            "(?i)(\\bnpm\\s+install\\b|\\bnpm\\s+i\\b|\\bnpx\\s+[^&|;]*\\binstall\\b|"
                    + "\\bpip3?\\s+install\\b|\\bpnpm\\s+(add|install)\\b|\\byarn\\s+(add|install)\\b|"
                    + "\\bmvn\\s+[^&|;]*dependency:resolve\\b)"
    );

    private static final Pattern ALLOWED_CMD = Pattern.compile(
            "(?i)^(dir|ls|pwd|cd\\s+[^&|;]+|"
                    + "git\\s+(status|diff|log|branch|show|rev-parse)(\\s.*)?|"
                    + "mvn(\\s.*)?|gradlew?(\\s.*)?|"
                    + "npm(\\s+(test|run|ci|ls|outdated|start)(\\s.*)?)?|"
                    + "npx(\\s.*)?|"
                    + "python(\\s+-m\\s+(pytest|http\\.server)(\\s+\\d+)?)?(\\s.*)?|"
                    + "python(\\s+[^&|;]*manage\\.py(\\s.*)?)|"
                    + "py(\\s+-m\\s+(pytest|http\\.server|manage)(\\s.*)?)?|"
                    + "py(\\s+[^&|;]*manage\\.py(\\s.*)?)|"
                    + "javac?(\\s.*)?|"
                    + "type\\s+[^&|;]+|Get-ChildItem(\\s.*)?|Get-Content\\s+[^&|;]+)$"
    );

    private final OllamaClient ollama;
    private final OllamaProperties props;
    private final WorkspaceService workspaceService;
    private final AgentToolParser toolParser;
    private final AgentTraceService traceService;

    public AgentOrchestrator(
            OllamaClient ollama,
            OllamaProperties props,
            WorkspaceService workspaceService,
            ObjectMapper mapper,
            AgentTraceService traceService
    ) {
        this.ollama = ollama;
        this.props = props;
        this.workspaceService = workspaceService;
        this.toolParser = new AgentToolParser(mapper);
        this.traceService = traceService;
    }

    /** Legacy /api/ai/agent — behaves as Code Agent. */
    public AgentResponseDto run(String email, String workspaceId, ChatRequestDto request, List<ChatHistoryItemDto> seedMessages)
            throws IOException, InterruptedException {
        return run(email, workspaceId, request, seedMessages, AgentMode.CODE);
    }

    public AgentResponseDto run(
            String email,
            String workspaceId,
            ChatRequestDto request,
            List<ChatHistoryItemDto> seedMessages,
            AgentMode mode
    ) throws IOException, InterruptedException {
        return run(email, workspaceId, request, seedMessages, mode, null);
    }

    public AgentResponseDto run(
            String email,
            String workspaceId,
            ChatRequestDto request,
            List<ChatHistoryItemDto> seedMessages,
            AgentMode mode,
            AgentProgressListener progress
    ) throws IOException, InterruptedException {
        if (mode == null) {
            mode = AgentMode.CODE;
        }
        List<ChatHistoryItemDto> messages = new ArrayList<>(seedMessages);
        // Ensure system prompt matches mode (replace first system if present)
        if (!messages.isEmpty() && "system".equalsIgnoreCase(messages.get(0).getRole())) {
            messages.set(0, new ChatHistoryItemDto("system", mode.systemPrompt()));
        } else {
            messages.add(0, new ChatHistoryItemDto("system", mode.systemPrompt()));
        }

        Map<String, FileProposalDto> proposals = new LinkedHashMap<>();
        List<AgentToolStepDto> steps = new ArrayList<>();
        List<ReviewFindingDto> findings = new ArrayList<>();
        // Files whose content the model has actually seen this run (read_file or a prior edit/write).
        // Overwriting an existing file the model has NOT read is refused — this is what prevents
        // "regenerate from memory" mistakes like the wrong class name / javax vs jakarta import.
        Set<String> seenFiles = new HashSet<>();
        ProjectPlanDto projectPlan = null;
        List<String> usedPaths = new ArrayList<>();
        StringBuilder testRunLog = new StringBuilder();
        String finalReply = "";
        boolean finished = false;
        // Read-only agents must actually look at the code before finishing — a bare
        // done{"Exploration complete"} with zero reads is a non-answer and gets rejected.
        int explorationCalls = 0;
        int readFileCalls = 0;
        int doneRejections = 0;
        int writeOrEditCalls = 0;
        int terminalCalls = 0;
        int maxSteps = Math.max(mode.preferredMaxSteps(), Math.max(1, props.getMaxToolSteps()));
        if (mode == AgentMode.SCAFFOLD) {
            maxSteps = Math.max(maxSteps, props.getMaxScaffoldToolSteps());
        }
        String model = props.resolveAgentModel();
        String lockedDesign = "";
        List<InstallProposalDto> installProposals = new ArrayList<>();
        int ollamaCallCount = 0;

        if (mode == AgentMode.SCAFFOLD) {
            String constraints = scaffoldConstraints(request.getMessage());
            if (!constraints.isBlank()) {
                messages.add(new ChatHistoryItemDto("user", constraints));
            }
            lockedDesign = ScaffoldDesignGuide.scaffoldDesignBrief(request.getMessage());
            if (!lockedDesign.isBlank()) {
                messages.add(new ChatHistoryItemDto("user", lockedDesign));
            }
            publishStatus(progress, "Scaffold started — waiting for model (plan + files)…");
        }

        for (int step = 0; step < maxSteps && !finished; step++) {
            publishStatus(progress, "Step " + (step + 1) + "/" + maxSteps + ": waiting for model…");
            ollamaCallCount++;
            String raw = ollama.chat(messagesForModelCall(messages, projectPlan), model);
            AgentToolParser.ParseResult parsed = toolParser.parse(raw);
            messages.add(new ChatHistoryItemDto("assistant", raw));

            if (parsed.tools().isEmpty()) {
                finalReply = !parsed.replyText().isBlank() ? parsed.replyText() : raw;
                // Scaffold: keep nudging — 7B models often dump Markdown instead of pass-tool
                if (mode == AgentMode.SCAFFOLD && step < 3) {
                    messages.add(new ChatHistoryItemDto("user", forcedScaffoldReminder()));
                    publishProgress(progress, steps, projectPlan, proposals);
                    continue;
                }
                // Test/Docs must act through tools. A prose reply here is a fabricated result
                // ("Applied 1 file(s)", "1 test passed") — never accept it on the first tries.
                if ((mode == AgentMode.TEST || mode == AgentMode.DOCS) && step < 3) {
                    messages.add(new ChatHistoryItemDto("user",
                            mode == AgentMode.TEST ? forcedTestReminder() : forcedToolReminder(mode)));
                    continue;
                }
                boolean mistypedTools = raw != null && raw.toLowerCase(Locale.ROOT).contains("write_file");
                if (step < 2 && mode.allowsWrites()
                        && (looksLikeFileTask(request.getMessage()) || mistypedTools
                        || (request.getMessage() != null && request.getMessage().startsWith("[FIX ERROR]")))) {
                    messages.add(new ChatHistoryItemDto("user", forcedToolReminder(mode)));
                    continue;
                }
                if (step == 0 && (mode == AgentMode.REVIEW || mode == AgentMode.RESEARCH)) {
                    messages.add(new ChatHistoryItemDto("user", forcedReadOnlyReminder(mode)));
                    continue;
                }
                break;
            }

            StringBuilder toolResults = new StringBuilder();
            for (AgentToolParser.ToolCall call : parsed.tools()) {
                String name = call.name();
                steps.add(new AgentToolStepDto(name, summarizeArgs(call), "running"));

                if (!mode.allows(name)) {
                    toolResults.append("TOOL_RESULT ").append(name)
                            .append(": DENIED — tool not available in ").append(mode.pathSegment())
                            .append(" agent. Allowed: ").append(String.join(", ", mode.allowedTools()))
                            .append("\n\n");
                    steps.get(steps.size() - 1).setStatus("denied");
                    steps.get(steps.size() - 1).setResultPreview("not allowed in " + mode.pathSegment());
                    continue;
                }

                switch (name) {
                    case "read_file" -> {
                        String result = execRead(email, workspaceId, call.path(), usedPaths);
                        if (!result.startsWith("ERROR")) {
                            seenFiles.add(normalizePlanPath(call.path()));
                            explorationCalls++;
                            readFileCalls++;
                        }
                        toolResults.append("TOOL_RESULT read_file path=").append(call.path()).append("\n")
                                .append(result).append("\n\n");
                        steps.get(steps.size() - 1).setStatus("ok");
                        steps.get(steps.size() - 1).setResultPreview(truncate(result, 200));
                    }
                    case "search" -> {
                        String result = execSearch(email, workspaceId, call.query());
                        explorationCalls++;
                        toolResults.append("TOOL_RESULT search query=").append(call.query()).append("\n")
                                .append(result).append("\n\n");
                        steps.get(steps.size() - 1).setStatus("ok");
                        steps.get(steps.size() - 1).setResultPreview(truncate(result, 200));
                        if (mode == AgentMode.RESEARCH && readFileCalls == 0) {
                            if (result.startsWith("(no matches)") || result.startsWith("ERROR")) {
                                toolResults.append("HINT: that query found nothing. Search SHORT keywords from the code "
                                        + "(a model name, a function name), then answer from the files.\n\n");
                            } else {
                                // 7B models keep re-searching instead of reading. Open the top hits
                                // for them so the answer can be grounded in real file contents.
                                try {
                                    LinkedHashSet<String> hitPaths = new LinkedHashSet<>();
                                    for (SearchHitDto h : workspaceService.search(email, workspaceId, call.query())) {
                                        if (h.getPath() != null && !h.getPath().isBlank()) {
                                            hitPaths.add(h.getPath());
                                        }
                                        if (hitPaths.size() >= 3) {
                                            break;
                                        }
                                    }
                                    for (String p : hitPaths) {
                                        String content = execRead(email, workspaceId, p, usedPaths);
                                        if (content.startsWith("ERROR")) {
                                            continue;
                                        }
                                        readFileCalls++;
                                        explorationCalls++;
                                        seenFiles.add(normalizePlanPath(p));
                                        steps.add(new AgentToolStepDto("read_file", p + " (auto)", "ok",
                                                truncate(content, 200)));
                                        toolResults.append("TOOL_RESULT read_file path=").append(p)
                                                .append(" (opened automatically from your search hits)\n")
                                                .append(content).append("\n\n");
                                    }
                                } catch (Exception ignored) {
                                    // Auto-read is best-effort; the model can still read_file itself.
                                }
                                if (readFileCalls > 0) {
                                    toolResults.append("You now HAVE the file contents above — do NOT search again. ")
                                            .append("Call done now: message = the full end-to-end explanation, ")
                                            .append("citing these file paths.\n\n");
                                }
                            }
                        }
                    }
                    case "write_file" -> {
                        FileProposalDto proposal = toProposal(email, workspaceId, call);
                        if (proposal == null) {
                            toolResults.append("TOOL_RESULT write_file: FAILED missing path\n\n");
                            steps.get(steps.size() - 1).setStatus("error");
                            break;
                        }
                        String norm = normalizePlanPath(proposal.getPath());
                        if (looksLikePlaceholderContent(proposal.getPath(), proposal.getContent())) {
                            toolResults.append("TOOL_RESULT write_file: REJECTED — args.content is a placeholder (\"")
                                    .append(truncate(proposal.getContent(), 60))
                                    .append("\"), not real code. Emit the COMPLETE file source in args.content, ")
                                    .append("with \\n between lines. Never copy the words from an example.\n\n");
                            steps.get(steps.size() - 1).setStatus("rejected");
                            steps.get(steps.size() - 1).setResultPreview("placeholder content");
                            break;
                        }
                        boolean existing = proposal.getPreviousContent() != null;
                        boolean alreadyProposed = proposals.containsKey(proposal.getPath());
                        if (existing && !seenFiles.contains(norm) && !alreadyProposed) {
                            if (mode == AgentMode.SCAFFOLD && isScaffoldStub(proposal.getPreviousContent())) {
                                // Tiny/empty stub on disk — allow overwrite without read_file round-trip
                            } else if (mode == AgentMode.SCAFFOLD) {
                                String content = execRead(email, workspaceId, proposal.getPath(), usedPaths);
                                seenFiles.add(norm);
                                readFileCalls++;
                                steps.add(new AgentToolStepDto("read_file", proposal.getPath() + " (auto)", "ok",
                                        truncate(content, 200)));
                                toolResults.append("TOOL_RESULT read_file path=").append(proposal.getPath())
                                        .append(" (auto-opened — file exists on disk)\n")
                                        .append(content).append("\n\n");
                                toolResults.append("TOOL_RESULT write_file: REFUSED — ")
                                        .append(proposal.getPath())
                                        .append(" already exists (content above). ")
                                        .append("Call write_file with action overwrite and the COMPLETE new file. ")
                                        .append("Do NOT use edit_file for whole-file scaffold rewrites.\n\n");
                                steps.get(steps.size() - 1).setStatus("refused");
                                steps.get(steps.size() - 1).setResultPreview("read then overwrite");
                                break;
                            } else {
                                toolResults.append("TOOL_RESULT write_file: REFUSED — ")
                                        .append(proposal.getPath())
                                        .append(" already exists and you have not read it this turn. ")
                                        .append("Call read_file{path} first, or use edit_file for a small change. ")
                                        .append("Do not regenerate a whole file from memory.\n\n");
                                steps.get(steps.size() - 1).setStatus("refused");
                                steps.get(steps.size() - 1).setResultPreview("read before overwrite");
                                break;
                            }
                        }
                        proposals.put(proposal.getPath(), proposal);
                        seenFiles.add(norm);
                        markPlanFileProposed(projectPlan, proposal.getPath());
                        writeOrEditCalls++;
                        boolean wroteDisk = false;
                        if (mode == AgentMode.TEST) {
                            try {
                                workspaceService.writeFile(email, workspaceId, proposal.getPath(), proposal.getContent());
                                wroteDisk = true;
                            } catch (Exception e) {
                                toolResults.append("TOOL_RESULT write_file: disk write FAILED — ")
                                        .append(e.getMessage() != null ? e.getMessage() : "io error")
                                        .append("\n\n");
                                steps.get(steps.size() - 1).setStatus("error");
                                steps.get(steps.size() - 1).setResultPreview("disk write failed");
                                break;
                            }
                        }
                        toolResults.append("TOOL_RESULT write_file: ")
                                .append(wroteDisk ? "written to disk and " : "")
                                .append("queued proposal for ")
                                .append(proposal.getPath())
                                .append(" (").append(proposal.getAction())
                                .append(")")
                                .append(wroteDisk ? " so run_terminal can execute tests now.\n\n"
                                        : ". User must Apply in the IDE.\n\n");
                        if (mode == AgentMode.TEST && terminalCalls == 0) {
                            toolResults.append("NEXT REQUIRED: call run_terminal to execute the tests "
                                    + "(e.g. python library/manage.py test catalog). "
                                    + "Do NOT call done with invented pass/fail counts.\n\n");
                        }
                        steps.get(steps.size() - 1).setStatus(wroteDisk ? "applied" : "queued");
                        steps.get(steps.size() - 1).setResultPreview(proposal.getPath());
                    }
                    case "edit_file" -> {
                        EditResult edit = applyEdit(email, workspaceId, call, proposals);
                        if (edit.proposal() != null) {
                            proposals.put(edit.proposal().getPath(), edit.proposal());
                            seenFiles.add(normalizePlanPath(edit.proposal().getPath()));
                            markPlanFileProposed(projectPlan, edit.proposal().getPath());
                            writeOrEditCalls++;
                            boolean wroteDisk = false;
                            if (mode == AgentMode.TEST) {
                                try {
                                    workspaceService.writeFile(email, workspaceId,
                                            edit.proposal().getPath(), edit.proposal().getContent());
                                    wroteDisk = true;
                                } catch (Exception e) {
                                    toolResults.append("TOOL_RESULT edit_file: disk write FAILED — ")
                                            .append(e.getMessage() != null ? e.getMessage() : "io error")
                                            .append("\n\n");
                                    steps.get(steps.size() - 1).setStatus("error");
                                    steps.get(steps.size() - 1).setResultPreview("disk write failed");
                                    break;
                                }
                            }
                            toolResults.append("TOOL_RESULT edit_file: ")
                                    .append(wroteDisk ? "written to disk and " : "")
                                    .append("queued edit for ")
                                    .append(edit.proposal().getPath())
                                    .append(wroteDisk ? ".\n\n" : ". User must Apply in the IDE.\n\n");
                            steps.get(steps.size() - 1).setStatus(wroteDisk ? "applied" : "queued");
                            steps.get(steps.size() - 1).setResultPreview(edit.proposal().getPath());
                        } else {
                            toolResults.append("TOOL_RESULT edit_file: FAILED — ")
                                    .append(edit.error()).append("\n\n");
                            steps.get(steps.size() - 1).setStatus("error");
                            steps.get(steps.size() - 1).setResultPreview(truncate(edit.error(), 200));
                        }
                    }
                    case "run_terminal" -> {
                        String cmd = call.command() == null ? "" : call.command().trim();
                        if (INSTALL_CMD.matcher(cmd).find()) {
                            toolResults.append("TOOL_RESULT run_terminal: BLOCKED — install commands must use propose_install ")
                                    .append("(user clicks Install in the IDE). Command was: ")
                                    .append(truncate(cmd, 120)).append("\n\n");
                            steps.get(steps.size() - 1).setStatus("blocked");
                            steps.get(steps.size() - 1).setResultPreview("use propose_install");
                            break;
                        }
                        String result = execTerminal(email, workspaceId, cmd);
                        toolResults.append("TOOL_RESULT run_terminal\n").append(result).append("\n\n");
                        if (mode == AgentMode.TEST || mode == AgentMode.SCAFFOLD) {
                            testRunLog.append("$ ").append(call.command()).append('\n').append(result).append("\n\n");
                        }
                        terminalCalls++;
                        boolean ok = !result.startsWith("BLOCKED");
                        steps.get(steps.size() - 1).setStatus(ok ? "ok" : "blocked");
                        steps.get(steps.size() - 1).setResultPreview(truncate(result, 200));
                    }
                    case "propose_install" -> {
                        String cmd = call.command() == null ? "" : call.command().trim();
                        if (cmd.isBlank()) {
                            toolResults.append("TOOL_RESULT propose_install: FAILED — command required\n\n");
                            steps.get(steps.size() - 1).setStatus("error");
                            break;
                        }
                        if (!INSTALL_CMD.matcher(cmd).find()) {
                            toolResults.append("TOOL_RESULT propose_install: REJECTED — not a recognized install command: ")
                                    .append(truncate(cmd, 120))
                                    .append("\nUse npm install, pip install, pnpm add, yarn add, etc.\n\n");
                            steps.get(steps.size() - 1).setStatus("rejected");
                            break;
                        }
                        InstallProposalDto install = new InstallProposalDto(cmd,
                                call.message() == null ? "" : call.message());
                        installProposals.add(install);
                        toolResults.append("TOOL_RESULT propose_install: queued \"")
                                .append(truncate(cmd, 160))
                                .append("\" — user must click Install in the IDE (not auto-run).\n\n");
                        steps.get(steps.size() - 1).setStatus("queued");
                        steps.get(steps.size() - 1).setResultPreview(truncate(cmd, 120));
                        if (progress != null) {
                            progress.onEvent("installs", new ArrayList<>(installProposals));
                        }
                    }
                    case "submit_findings" -> {
                        if (call.findings() != null) {
                            findings.clear();
                            findings.addAll(call.findings());
                        }
                        toolResults.append("TOOL_RESULT submit_findings: accepted ")
                                .append(findings.size()).append(" finding(s).\n\n");
                        steps.get(steps.size() - 1).setStatus("ok");
                        steps.get(steps.size() - 1).setResultPreview(findings.size() + " findings");
                    }
                    case "submit_plan" -> {
                        ProjectPlanDto incoming = call.plan() != null ? call.plan() : new ProjectPlanDto();
                        boolean advancing = projectPlan != null && projectPlan.hasPhases()
                                && looksLikeNextPhaseRequest(request.getMessage());
                        if (projectPlan != null && projectPlan.getFiles() != null && !projectPlan.getFiles().isEmpty()
                                && mode == AgentMode.SCAFFOLD && !advancing) {
                            toolResults.append("TOOL_RESULT submit_plan: IGNORED — plan already set. ")
                                    .append(remainingPlanHint(projectPlan, proposals))
                                    .append("\nEmit write_file for ONE pending path next.\n\n");
                            steps.get(steps.size() - 1).setStatus("ignored");
                            steps.get(steps.size() - 1).setResultPreview("plan already set");
                            break;
                        }
                        if (advancing && projectPlan != null && projectPlan.hasPhases()) {
                            // Merge: keep prior phases, adopt new files into next slot
                            int nextIdx = Math.min(projectPlan.getCurrentPhase() + 1, projectPlan.getPhases().size() - 1);
                            if (incoming.hasPhases() && incoming.getPhases().size() > nextIdx) {
                                projectPlan = incoming;
                                projectPlan.setCurrentPhase(nextIdx);
                            } else if (incoming.getFiles() != null && !incoming.getFiles().isEmpty()) {
                                ProjectPlanPhaseDto slot = projectPlan.getPhases().get(nextIdx);
                                slot.setFiles(new ArrayList<>(incoming.getFiles()));
                                slot.setStatus("active");
                                projectPlan.setCurrentPhase(nextIdx);
                            } else {
                                projectPlan.setCurrentPhase(nextIdx);
                            }
                            projectPlan.syncFilesFromActivePhase();
                        } else {
                            projectPlan = incoming;
                            if (projectPlan.getFiles() == null) {
                                projectPlan.setFiles(new ArrayList<>());
                            }
                            if (projectPlan.getFiles().size() > 20) {
                                projectPlan.setFiles(new ArrayList<>(projectPlan.getFiles().subList(0, 20)));
                            }
                            if (!projectPlan.hasPhases() && !projectPlan.getFiles().isEmpty()) {
                                ProjectPlanPhaseDto only = new ProjectPlanPhaseDto("1", "MVP");
                                only.setStatus("active");
                                only.setFiles(new ArrayList<>(projectPlan.getFiles()));
                                projectPlan.setPhases(new ArrayList<>(List.of(only)));
                                projectPlan.setCurrentPhase(0);
                            } else {
                                projectPlan.syncFilesFromActivePhase();
                            }
                        }
                        for (ProjectPlanFileDto f : projectPlan.getFiles()) {
                            if (f.getStatus() == null || f.getStatus().isBlank()) {
                                f.setStatus("pending");
                            }
                            if (f.getPath() != null && proposals.containsKey(normalizePlanPath(f.getPath()))) {
                                f.setStatus("proposed");
                            }
                        }
                        String phaseLabel = projectPlan.activePhase() != null
                                ? projectPlan.activePhase().getName()
                                : "MVP";
                        toolResults.append("TOOL_RESULT submit_plan: accepted \"")
                                .append(projectPlan.getTitle() == null ? "project" : projectPlan.getTitle())
                                .append("\" phase \"").append(phaseLabel).append("\" with ")
                                .append(projectPlan.getFiles().size())
                                .append(" file(s). write_file EACH active-phase path — ONE file per response.\n");
                        if (ScaffoldDesignGuide.targetsStaticWeb(request.getMessage(), projectPlan.getStack())) {
                            lockedDesign = ScaffoldDesignGuide.lockedDesignSystem(
                                    request.getMessage(), projectPlan.getSummary());
                            if (!lockedDesign.isBlank()) {
                                toolResults.append('\n').append(lockedDesign).append('\n');
                            }
                            toolResults.append("Write styles.css FIRST (design tokens), then index.html, then other files.\n");
                        }
                        toolResults.append(remainingPlanHint(projectPlan, proposals)).append("\n\n");
                        steps.get(steps.size() - 1).setStatus("ok");
                        steps.get(steps.size() - 1).setResultPreview(projectPlan.getFiles().size() + " files · " + phaseLabel);
                    }
                    case "done" -> {
                        if (mode == AgentMode.SCAFFOLD && projectPlan != null) {
                            List<String> missing = missingPlanPaths(projectPlan, proposals);
                            if (!missing.isEmpty()) {
                                toolResults.append("TOOL_RESULT done: REJECTED — still missing write_file for: ")
                                        .append(String.join(", ", missing))
                                        .append("\nWrite those files next, then done.\n\n");
                                steps.get(steps.size() - 1).setStatus("rejected");
                                steps.get(steps.size() - 1).setResultPreview(missing.size() + " files missing");
                                break;
                            }
                        }
                        if (mode == AgentMode.SCAFFOLD && projectPlan == null) {
                            toolResults.append("TOOL_RESULT done: REJECTED — call submit_plan first, then write files.\n\n");
                            steps.get(steps.size() - 1).setStatus("rejected");
                            break;
                        }
                        if ((mode == AgentMode.RESEARCH || mode == AgentMode.REVIEW) && doneRejections < 3) {
                            String msg = call.message() == null ? "" : call.message().trim();
                            boolean noReads = mode == AgentMode.RESEARCH && readFileCalls == 0;
                            boolean noExploration = explorationCalls == 0;
                            boolean statusLineOnly = mode == AgentMode.RESEARCH && msg.length() < 120;
                            if (noReads || noExploration || statusLineOnly) {
                                doneRejections++;
                                String reason;
                                if (noReads) {
                                    reason = "you only searched — you have not opened ANY file with read_file. "
                                            + "Pick concrete paths from the search hits "
                                            + "and call read_file{path} on each, THEN write the full explanation in done.message. ";
                                } else if (noExploration) {
                                    reason = "you have not read ANY code yet. Call search then read_file first. ";
                                } else {
                                    reason = "your message is a status line, not an answer. ";
                                }
                                toolResults.append("TOOL_RESULT done: REJECTED — ")
                                        .append(reason)
                                        .append("done.message must be the FULL step-by-step explanation with file paths cited. ")
                                        .append("Never finish with 'Exploration complete' or 'Plan submitted'.\n\n");
                                steps.get(steps.size() - 1).setStatus("rejected");
                                steps.get(steps.size() - 1).setResultPreview(
                                        noReads ? "search without read_file"
                                                : (noExploration ? "done with no exploration" : "status line instead of answer"));
                                break;
                            }
                        }
                        if (mode == AgentMode.TEST && doneRejections < 3) {
                            String msg = call.message() == null ? "" : call.message().toLowerCase(Locale.ROOT);
                            boolean noTestsWritten = writeOrEditCalls == 0 && proposals.isEmpty();
                            boolean noRun = terminalCalls == 0 || testRunLog.length() == 0;
                            boolean inventsPass = noRun && (msg.contains("passed") || msg.contains("pass/fail")
                                    || msg.contains("tests failed") || msg.contains("test passed"));
                            if (noTestsWritten || noRun || inventsPass) {
                                doneRejections++;
                                String reason;
                                if (noTestsWritten) {
                                    reason = "you have not written any test file. "
                                            + "Call search/read_file, then write_file the tests "
                                            + "(Django → catalog/tests.py), THEN run_terminal. ";
                                } else if (noRun || inventsPass) {
                                    reason = "you have not run the tests via run_terminal yet "
                                            + "(or you invented a pass/fail count). "
                                            + "Call run_terminal with the real command "
                                            + "(Django → python manage.py test catalog), "
                                            + "THEN put the ACTUAL counts from that output in done.message. ";
                                } else {
                                    reason = "incomplete test workflow. ";
                                }
                                toolResults.append("TOOL_RESULT done: REJECTED — ")
                                        .append(reason)
                                        .append("Never claim 'Applied N file(s)' or 'N test passed' without tools. ")
                                        .append("Never tell the user to run tests in a markdown bash block — YOU must call run_terminal.\n\n");
                                steps.get(steps.size() - 1).setStatus("rejected");
                                steps.get(steps.size() - 1).setResultPreview(
                                        noTestsWritten ? "done with no test file"
                                                : "done without running tests");
                                break;
                            }
                        }
                        finalReply = call.message() != null && !call.message().isBlank()
                                ? call.message()
                                : (!parsed.replyText().isBlank() ? parsed.replyText() : "Done.");
                        finished = true;
                        steps.get(steps.size() - 1).setStatus("ok");
                        steps.get(steps.size() - 1).setResultPreview(truncate(finalReply, 200));
                    }
                    default -> {
                        toolResults.append("TOOL_RESULT unknown tool: ").append(name).append("\n\n");
                        steps.get(steps.size() - 1).setStatus("error");
                    }
                }
            }

            publishProgress(progress, steps, projectPlan, proposals);

            if (!finished && toolResults.length() > 0) {
                if (mode == AgentMode.SCAFFOLD && projectPlan != null) {
                    List<String> stillMissing = missingPlanPaths(projectPlan, proposals);
                    if (stillMissing.isEmpty() && !proposals.isEmpty()) {
                        finished = true;
                        if (projectPlan.hasPhases()) {
                            ProjectPlanPhaseDto ap = projectPlan.activePhase();
                            if (ap != null) {
                                ap.setStatus("proposed");
                                // mirror statuses onto phase files
                                if (ap.getFiles() != null) {
                                    for (ProjectPlanFileDto f : ap.getFiles()) {
                                        if (f.getPath() != null && proposals.containsKey(normalizePlanPath(f.getPath()))) {
                                            f.setStatus("proposed");
                                        }
                                    }
                                }
                            }
                        }
                        if (finalReply.isBlank()) {
                            if (projectPlan.hasNextPhase()) {
                                finalReply = "Phase \""
                                        + (projectPlan.activePhase() != null ? projectPlan.activePhase().getName() : "current")
                                        + "\" ready (" + proposals.size()
                                        + " file(s)). Apply all, then click Next phase.";
                            } else {
                                finalReply = "Scaffold ready: " + proposals.size()
                                        + " file(s) proposed. Review the plan and Apply all.";
                            }
                        }
                        steps.add(new AgentToolStepDto("done", "auto", "ok", "active phase files proposed"));
                        publishProgress(progress, steps, projectPlan, proposals);
                        continue;
                    }
                    // Push one-file-at-a-time instructions for 7B reliability
                    if (!stillMissing.isEmpty()) {
                        String designReminder = ScaffoldDesignGuide.lockedDesignReminder(
                                request.getMessage(), null);
                        if (!designReminder.isBlank()) {
                            toolResults.append('\n').append(designReminder).append('\n');
                        }
                        toolResults.append(oneFileWritePrompt(stillMissing.get(0))).append('\n');
                    } else {
                        toolResults.append(remainingPlanHint(projectPlan, proposals)).append('\n');
                    }
                }
                if (mode == AgentMode.RESEARCH) {
                    if (readFileCalls == 0) {
                        toolResults.append("You still have not called read_file. Open at least one real source file, ")
                                .append("then put the FULL answer in done.message (with paths). Do not repeat the same search.\n");
                    } else {
                        toolResults.append("You have enough code. Call done now — done.message must be the full explanation ")
                                .append("citing the paths you read (not a status line).\n");
                    }
                } else if (mode == AgentMode.TEST) {
                    if (writeOrEditCalls == 0 && proposals.isEmpty()) {
                        toolResults.append("Write the test file first (write_file), then run_terminal, then done with real counts.\n");
                    } else if (terminalCalls == 0) {
                        toolResults.append("Tests are proposed. Call run_terminal NOW (python …/manage.py test …). ")
                                .append("done is forbidden until you have real terminal output.\n");
                    } else {
                        toolResults.append("Copy the ACTUAL pass/fail counts from the terminal output into done.message.\n");
                    }
                } else {
                    toolResults.append("Continue with allowed tools only (")
                            .append(String.join(", ", mode.allowedTools()))
                            .append("), or finish with done.");
                }
                messages.add(new ChatHistoryItemDto("user", toolResults.toString()));
            }
        }

        // Scaffold reliability: dedicated one-file LLM calls for anything still missing
        if (mode == AgentMode.SCAFFOLD && projectPlan != null) {
            List<String> beforeFill = missingPlanPaths(projectPlan, proposals);
            if (!beforeFill.isEmpty()) {
                int[] counter = new int[] { ollamaCallCount };
                fillScaffoldFilesOneByOne(
                        messages, model, projectPlan, proposals, steps, email, workspaceId,
                        request.getMessage(), lockedDesign, progress, counter);
                ollamaCallCount = counter[0];
            }
            if (!proposals.isEmpty() && missingPlanPaths(projectPlan, proposals).isEmpty()) {
                finished = true;
                if (finalReply.isBlank() || finalReply.toLowerCase(Locale.ROOT).contains("missing")) {
                    finalReply = "Scaffold ready: " + proposals.size()
                            + " file(s) proposed. Review diffs and Apply all.";
                }
            } else if (!beforeFill.isEmpty()) {
                int left = missingPlanPaths(projectPlan, proposals).size();
                finalReply = "Scaffold proposed " + proposals.size() + " file(s); "
                        + left + " still pending — client will auto-continue.";
            }
        }

        if (finalReply.isBlank() && mode == AgentMode.RESEARCH && !usedPaths.isEmpty()) {
            try {
                List<ChatHistoryItemDto> synth = new ArrayList<>();
                synth.add(new ChatHistoryItemDto("system", """
                        You already read project files. Answer the user's question now in plain prose.
                        Cite the file paths you used. No tools, no status lines — only the explanation.
                        """));
                synth.add(new ChatHistoryItemDto("user",
                        "Question: " + request.getMessage()
                                + "\nFiles you read: " + String.join(", ", usedPaths)
                                + "\nWrite the end-to-end explanation now."));
                String raw = ollama.chat(synth, model);
                AgentToolParser.ParseResult parsed = toolParser.parse(raw);
                String candidate = parsed.tools().stream()
                        .filter(t -> "done".equals(t.name()) && t.message() != null && !t.message().isBlank())
                        .map(AgentToolParser.ToolCall::message)
                        .findFirst()
                        .orElse(!parsed.replyText().isBlank() ? parsed.replyText() : raw);
                if (candidate != null && candidate.trim().length() >= 80
                        && !candidate.toLowerCase(Locale.ROOT).contains("exploration complete")) {
                    finalReply = candidate.trim();
                    steps.add(new AgentToolStepDto("done", "synthesis", "ok", truncate(finalReply, 200)));
                }
            } catch (Exception ignored) {
                // fall through to the generic Research fallback below
            }
        }

        if (finalReply.isBlank()) {
            if (mode == AgentMode.REVIEW && !findings.isEmpty()) {
                finalReply = "Review complete: " + findings.size() + " finding(s).";
            } else if (mode == AgentMode.SCAFFOLD && !proposals.isEmpty()) {
                int pending = projectPlan != null ? missingPlanPaths(projectPlan, proposals).size() : 0;
                finalReply = "Scaffold proposed " + proposals.size() + " file(s)."
                        + (pending > 0 ? " " + pending + " planned file(s) still missing — click Continue or re-run Scaffold." : " Review diffs and Apply all.");
            } else if (!proposals.isEmpty()) {
                finalReply = "Proposed " + proposals.size() + " file change(s). Review the diffs and Apply.";
            } else if (mode == AgentMode.RESEARCH) {
                finalReply = readFileCalls == 0
                        ? "Research searched the project but could not open any file. Try again with short code keywords (a model or function name)."
                        : "Research finished without a summary. Try asking again more specifically.";
            } else {
                finalReply = mode.pathSegment() + " agent finished. Try a more specific request.";
            }
        }

        // Never let the Test agent report results it did not produce. If no test command actually
        // ran, any "N test passed" / "Applied N file(s)" text is fabricated — replace it.
        if (mode == AgentMode.TEST && terminalCalls == 0) {
            String lower = finalReply.toLowerCase(Locale.ROOT);
            boolean claimsResults = lower.contains("test passed") || lower.contains("tests passed")
                    || lower.contains("tests failed") || lower.contains("pass/fail")
                    || lower.contains("applied ") || lower.contains("all tests");
            if (claimsResults || proposals.isEmpty()) {
                StringBuilder honest = new StringBuilder();
                honest.append("No tests were executed. The agent did not call run_terminal, so any pass/fail count would be invented.\n");
                if (proposals.isEmpty()) {
                    honest.append("It also did not write a test file.\n");
                } else {
                    honest.append("Test file(s) written: ")
                            .append(String.join(", ", proposals.keySet()))
                            .append(" — but never run.\n");
                }
                honest.append("Try again, or run it yourself in the terminal: python library/manage.py test catalog");
                finalReply = honest.toString();
            }
        }

        if (!proposals.isEmpty() && props.isAgentVerify() && mode.allowsWrites()) {
            StringBuilder verify = new StringBuilder("VERIFY:\n");
            for (FileProposalDto p : proposals.values()) {
                verify.append("- ").append(p.getPath()).append(" (").append(p.getAction()).append(")\n");
            }
            steps.add(new AgentToolStepDto("verify", "proposals", "ok", truncate(verify.toString(), 300)));
        }

        // Read-only modes must never return file proposals even if model hallucinated pass-file
        List<FileProposalDto> files = mode.allowsWrites()
                ? new ArrayList<>(proposals.values())
                : List.of();

        AgentResponseDto response = new AgentResponseDto(
                finalReply,
                model,
                usedPaths,
                List.of(),
                files,
                steps
        );
        response.setAgentMode(mode.pathSegment());
        response.setFindings(findings);
        response.setProjectPlan(projectPlan);
        response.setOllamaCallCount(ollamaCallCount);
        response.setInstallProposals(installProposals);
        if (mode == AgentMode.TEST && testRunLog.length() > 0) {
            response.setTestRunSummary(truncate(testRunLog.toString().trim(), 4000));
        } else if (mode == AgentMode.TEST) {
            // No real run — never echo the model's claimed results into the TEST RUN panel.
            response.setTestRunSummary("No test command was executed (run_terminal was never called).");
        } else if (mode == AgentMode.SCAFFOLD && testRunLog.length() > 0) {
            response.setTestRunSummary(truncate(testRunLog.toString().trim(), 4000));
        }

        try {
            traceService.record(email, workspaceId, request.getMessage(), steps, files, finalReply, model);
        } catch (Exception ignored) {
        }
        return response;
    }

    private FileProposalDto toProposal(String email, String workspaceId, AgentToolParser.ToolCall call) {
        if (call.path() == null || call.path().isBlank()) {
            return null;
        }
        String path = call.path().replace('\\', '/').trim();
        String action = call.action() == null || call.action().isBlank() ? "overwrite" : call.action();
        String content = call.content() == null ? "" : call.content();
        String previous = null;
        try {
            FileContentDto existing = workspaceService.readFile(email, workspaceId, path);
            previous = existing.getContent();
            if ("create".equalsIgnoreCase(action) && previous != null) {
                action = "overwrite";
            }
        } catch (NoSuchFileException e) {
            action = "create";
        } catch (Exception ignored) {
        }
        FileProposalDto dto = new FileProposalDto(path, action, content);
        dto.setPreviousContent(previous);
        return dto;
    }

    /**
     * Applies an exact-match {@code old_string -> new_string} edit and fails loudly when the
     * anchor is missing or ambiguous. Edits against an already-queued proposal if present, else disk.
     */
    private EditResult applyEdit(String email, String workspaceId, AgentToolParser.ToolCall call,
                                 Map<String, FileProposalDto> proposals) {
        if (call.path() == null || call.path().isBlank()) {
            return EditResult.fail("missing path");
        }
        String path = call.path().replace('\\', '/').trim();
        String oldStr = call.oldString();
        String newStr = call.newString() == null ? "" : call.newString();
        if (oldStr == null || oldStr.isEmpty()) {
            return EditResult.fail("old_string is required (the exact text to replace)");
        }

        String base;
        String previous;
        FileProposalDto queued = proposals.get(path);
        if (queued != null && queued.getContent() != null) {
            base = queued.getContent();
            previous = queued.getPreviousContent();
        } else {
            try {
                FileContentDto existing = workspaceService.readFile(email, workspaceId, path);
                base = existing.getContent() == null ? "" : existing.getContent();
                previous = base;
            } catch (NoSuchFileException e) {
                return EditResult.fail(path + " does not exist. Use write_file to create it.");
            } catch (Exception e) {
                return EditResult.fail("could not read " + path + ": "
                        + (e.getMessage() != null ? e.getMessage() : "read failed"));
            }
        }

        int first = base.indexOf(oldStr);
        if (first < 0) {
            return EditResult.fail("old_string not found in " + path
                    + ". Read the file and copy the exact text (including whitespace). "
                    + anchorHint(base, oldStr));
        }
        if (base.indexOf(oldStr, first + 1) >= 0) {
            int count = 0;
            int idx = 0;
            while ((idx = base.indexOf(oldStr, idx)) >= 0) {
                count++;
                idx += oldStr.length();
            }
            return EditResult.fail("old_string is not unique in " + path + " (" + count
                    + " matches). Add surrounding lines so it matches exactly once.");
        }

        String updated = base.substring(0, first) + newStr + base.substring(first + oldStr.length());
        String action = previous == null ? "create" : "edit";
        FileProposalDto dto = new FileProposalDto(path, action, updated);
        dto.setPreviousContent(previous);
        return EditResult.ok(dto);
    }

    private record EditResult(FileProposalDto proposal, String error) {
        static EditResult ok(FileProposalDto proposal) {
            return new EditResult(proposal, null);
        }

        static EditResult fail(String error) {
            return new EditResult(null, error);
        }
    }

    private String execRead(String email, String workspaceId, String path, List<String> usedPaths) {
        if (path == null || path.isBlank()) {
            return "ERROR: path required";
        }
        try {
            FileContentDto file = workspaceService.readFile(email, workspaceId, path.replace('\\', '/'));
            usedPaths.add(file.getPath());
            String body = file.getContent() == null ? "" : file.getContent();
            if (body.length() > 16_000) {
                body = body.substring(0, 16_000) + "\n…[truncated]";
            }
            return body;
        } catch (Exception e) {
            return "ERROR: " + (e.getMessage() != null ? e.getMessage() : "read failed");
        }
    }

    private String execSearch(String email, String workspaceId, String query) {
        if (query == null || query.isBlank()) {
            return "ERROR: query required";
        }
        try {
            List<SearchHitDto> hits = workspaceService.search(email, workspaceId, query);
            if (hits.isEmpty()) {
                return "(no matches)";
            }
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (SearchHitDto h : hits) {
                sb.append(h.getPath()).append(':').append(h.getLine()).append(": ").append(h.getPreview()).append('\n');
                if (++n >= 40) {
                    sb.append("…[truncated]\n");
                    break;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "ERROR: " + (e.getMessage() != null ? e.getMessage() : "search failed");
        }
    }

    private String execTerminal(String email, String workspaceId, String command) {
        if (command == null || command.isBlank()) {
            return "ERROR: command required";
        }
        String cmd = command.trim();
        if (DANGEROUS.matcher(cmd).find()) {
            return "BLOCKED: dangerous command refused";
        }
        if (!ALLOWED_CMD.matcher(cmd).matches()) {
            return "BLOCKED: command not in allowlist.";
        }
        try {
            List<String> lines = workspaceService.runDebugCommand(email, workspaceId, cmd);
            return truncate(String.join("\n", lines), 8_000);
        } catch (Exception e) {
            return "ERROR: " + (e.getMessage() != null ? e.getMessage() : "run failed");
        }
    }

    private void fillScaffoldFilesOneByOne(
            List<ChatHistoryItemDto> messages,
            String model,
            ProjectPlanDto projectPlan,
            Map<String, FileProposalDto> proposals,
            List<AgentToolStepDto> steps,
            String email,
            String workspaceId,
            String userMessage,
            String lockedDesign,
            AgentProgressListener progress,
            int[] ollamaCallCounter
    ) throws IOException, InterruptedException {
        List<String> missing = missingPlanPaths(projectPlan, proposals);
        int budget = Math.min(Math.max(missing.size(), 1), 20);
        publishStatus(progress, "Generating " + missing.size() + " missing file(s) one-by-one…");
        boolean fullDesignSent = false;
        for (int i = 0; i < budget; i++) {
            missing = prioritizeStylesCss(missingPlanPaths(projectPlan, proposals));
            if (missing.isEmpty()) {
                return;
            }
            String path = missing.get(0);
            publishStatus(progress, "One-by-one: writing " + path + "…");
            String purpose = purposeFor(projectPlan, path);
            String existingStyles = readWorkspaceStylesCss(email, workspaceId, proposals);
            List<ChatHistoryItemDto> turn = new ArrayList<>();
            turn.add(new ChatHistoryItemDto("system", """
                    You write ONE source file for a scaffold. Reply with exactly one ```pass-tool write_file block.
                    No markdown tutorials. No done. No submit_plan. Full file contents in args.content.
                    """));
            String designCtx;
            if (!fullDesignSent && lockedDesign != null && !lockedDesign.isBlank()) {
                designCtx = ScaffoldDesignGuide.oneFileDesignContext(
                        path, userMessage, existingStyles, lockedDesign);
                fullDesignSent = true;
            } else {
                designCtx = ScaffoldDesignGuide.oneFileDesignContextShort(
                        path, userMessage, existingStyles,
                        ScaffoldDesignGuide.lockedDesignReminder(userMessage, null));
            }
            turn.add(new ChatHistoryItemDto("user", oneFileWritePrompt(path)
                    + (purpose.isBlank() ? "" : "\nPurpose: " + purpose)
                    + "\nProject: " + (projectPlan.getTitle() == null ? "app" : projectPlan.getTitle())
                    + (projectPlan.getStack() == null ? "" : " (" + projectPlan.getStack() + ")")
                    + (designCtx.isBlank() ? "" : "\n\n" + designCtx)));

            ollamaCallCounter[0]++;
            String raw = ollama.chat(turn, model);
            messages.add(new ChatHistoryItemDto("assistant", raw));
            AgentToolParser.ParseResult parsed = toolParser.parse(raw);
            boolean wrote = false;
            for (AgentToolParser.ToolCall call : parsed.tools()) {
                if (!"write_file".equals(call.name())) {
                    continue;
                }
                AgentToolParser.ToolCall forced = AgentToolParser.ToolCall.writeFile(
                        path, call.action() == null ? "create" : call.action(), call.content());
                FileProposalDto proposal = toProposal(email, workspaceId, forced);
                if (proposal != null && proposal.getContent() != null && !proposal.getContent().isBlank()) {
                    proposals.put(normalizePlanPath(proposal.getPath()), proposal);
                    markPlanFileProposed(projectPlan, proposal.getPath());
                    steps.add(new AgentToolStepDto("write_file", proposal.getPath(), "queued", "one-by-one"));
                    wrote = true;
                    publishProgress(progress, steps, projectPlan, proposals);
                    break;
                }
            }
            if (!wrote) {
                steps.add(new AgentToolStepDto("write_file", path, "error", "model did not emit write_file"));
                // stop burning tokens if model fails twice in a row — still try next once
                if (i >= 1 && missingPlanPaths(projectPlan, proposals).size() == missing.size()) {
                    // failed this path; skip to next by marking a stub? better leave pending
                    continue;
                }
            }
        }
    }

    private static List<String> prioritizeStylesCss(List<String> paths) {
        if (paths == null || paths.size() < 2) {
            return paths == null ? List.of() : paths;
        }
        List<String> sorted = new ArrayList<>(paths);
        sorted.sort((a, b) -> {
            boolean aCss = ScaffoldDesignGuide.isStyleFile(a);
            boolean bCss = ScaffoldDesignGuide.isStyleFile(b);
            if (aCss && !bCss) {
                return -1;
            }
            if (!aCss && bCss) {
                return 1;
            }
            boolean aHtml = ScaffoldDesignGuide.isMarkupFile(a);
            boolean bHtml = ScaffoldDesignGuide.isMarkupFile(b);
            if (aHtml && !bHtml && !bCss) {
                return -1;
            }
            if (!aHtml && bHtml && !aCss) {
                return 1;
            }
            return 0;
        });
        return sorted;
    }

    private String readWorkspaceStylesCss(
            String email,
            String workspaceId,
            Map<String, FileProposalDto> proposals
    ) {
        for (String key : List.of("styles.css", "style.css", "css/styles.css", "assets/styles.css")) {
            FileProposalDto p = proposals.get(key);
            if (p != null && p.getContent() != null && !p.getContent().isBlank()) {
                return p.getContent();
            }
        }
        if (email == null || workspaceId == null) {
            return "";
        }
        for (String key : List.of("styles.css", "style.css", "css/styles.css", "assets/styles.css")) {
            try {
                FileContentDto file = workspaceService.readFile(email, workspaceId, key);
                if (file.getContent() != null && !file.getContent().isBlank()) {
                    return file.getContent();
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static String purposeFor(ProjectPlanDto plan, String path) {
        if (plan == null || plan.getFiles() == null || path == null) {
            return "";
        }
        String norm = normalizePlanPath(path);
        for (ProjectPlanFileDto f : plan.getFiles()) {
            if (f.getPath() != null && normalizePlanPath(f.getPath()).equals(norm)) {
                return f.getPurpose() == null ? "" : f.getPurpose();
            }
        }
        return "";
    }

    private static String oneFileWritePrompt(String path) {
        String norm = path == null ? "" : path.replace("\\", "/");
        String designNote = "";
        if (ScaffoldDesignGuide.isStyleFile(norm)) {
            designNote = """
                    
                    This is styles.css — include :root design tokens, typography, layout utilities, and component styles.
                    """;
        } else if (ScaffoldDesignGuide.isMarkupFile(norm)) {
            designNote = """
                    
                    This is HTML — use semantic sections, link styles.css, apply the shared design system (not bare unstyled blocks).
                    """;
        }
        return """
                WRITE ONLY THIS FILE NOW (one pass-tool block):
                ```pass-tool
                {"name":"write_file","args":{"path":"%s","action":"create","content":"...full file..."}}
                ```
                If this path already exists on disk, use read_file first then write_file with action overwrite.
                Prefer write_file (full file) over edit_file for scaffold pages. Do not wrap inside done.%s
                """.formatted(norm, designNote);
    }

    private static String summarizeArgs(AgentToolParser.ToolCall call) {
        return switch (call.name()) {
            case "read_file" -> call.path();
            case "search" -> call.query();
            case "write_file" -> call.path() + " (" + call.action() + ")";
            case "edit_file" -> call.path() + " (edit)";
            case "run_terminal" -> call.command();
            case "propose_install" -> call.command();
            case "submit_findings" -> (call.findings() == null ? 0 : call.findings().size()) + " items";
            case "submit_plan" -> call.plan() == null || call.plan().getFiles() == null
                    ? "0 files"
                    : call.plan().getFiles().size() + " files";
            case "done" -> call.message();
            default -> call.name();
        };
    }

    private static void markPlanFileProposed(ProjectPlanDto plan, String path) {
        if (plan == null || plan.getFiles() == null || path == null) {
            return;
        }
        String norm = normalizePlanPath(path);
        for (ProjectPlanFileDto f : plan.getFiles()) {
            if (f.getPath() != null && normalizePlanPath(f.getPath()).equals(norm)) {
                f.setStatus("proposed");
            }
        }
    }

    private static List<String> missingPlanPaths(ProjectPlanDto plan, Map<String, FileProposalDto> proposals) {
        List<String> missing = new ArrayList<>();
        if (plan == null || plan.getFiles() == null) {
            return missing;
        }
        for (ProjectPlanFileDto f : plan.getFiles()) {
            if (f == null || f.getPath() == null || f.getPath().isBlank()) {
                continue;
            }
            String norm = normalizePlanPath(f.getPath());
            if (!proposals.containsKey(norm) && !proposals.containsKey(f.getPath())) {
                missing.add(norm);
            }
        }
        return missing;
    }

    private static String remainingPlanHint(ProjectPlanDto plan, Map<String, FileProposalDto> proposals) {
        List<String> missing = missingPlanPaths(plan, proposals);
        if (missing.isEmpty()) {
            return "All planned files have write_file proposals. You may call done.";
        }
        StringBuilder sb = new StringBuilder("PENDING FILES (" + missing.size() + "): ");
        int n = 0;
        for (String p : missing) {
            if (n++ > 0) {
                sb.append(", ");
            }
            sb.append(p);
            if (n >= 12) {
                sb.append("…");
                break;
            }
        }
        return sb.toString();
    }

    private static String normalizePlanPath(String path) {
        return path.replace('\\', '/').trim();
    }

    private static boolean looksLikeNextPhaseRequest(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("next phase") || m.contains("begin phase") || m.contains("start phase")
                || m.contains("phase 2") || m.contains("phase 3") || m.contains("continue to phase");
    }

    private static boolean looksLikeFullStackRequest(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("full stack") || m.contains("fullstack") || m.contains("full-stack")
                || (m.contains("backend") && m.contains("frontend"))
                || (m.contains("spring") && (m.contains("angular") || m.contains("react")))
                || m.contains("monorepo");
    }

    /**
     * Turns the user's own words into hard constraints so the plan matches what was asked
     * (named stack, and which side to build first) instead of a default template.
     */
    static String scaffoldConstraints(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String m = message.toLowerCase(Locale.ROOT);
        List<String> rules = new ArrayList<>();

        String stack = requestedStack(m);
        if (stack != null) {
            rules.add("- Stack is fixed by the user: " + stack + ". Do not substitute another stack.");
        }

        String banned = bannedStacks(m);
        if (banned != null) {
            rules.add("- The user explicitly EXCLUDED: " + banned
                    + ". Generate NO files for these — no pom.xml, no package.json, no components for them.");
        }

        // "Start with Backend API first, then Frontend" marks BOTH areas, so the one mentioned
        // earliest wins instead of cancelling each other out.
        int frontendAt = firstOrderedMention(m, "frontend", "front-end", "front end", "front", "ui",
                "interface", "client");
        int backendAt = firstOrderedMention(m, "backend", "back-end", "back end", "api", "server", "serveur");
        boolean frontendFirst = frontendAt >= 0 && (backendAt < 0 || frontendAt < backendAt);
        boolean backendFirst = backendAt >= 0 && (frontendAt < 0 || backendAt < frontendAt);
        if (frontendFirst && !backendFirst) {
            rules.add("- The user asked to START WITH THE FRONTEND. Phase 1 MUST be the frontend "
                    + "(pages/components/styles). Put any backend in a LATER phase.");
        } else if (backendFirst && !frontendFirst) {
            rules.add("- The user asked to START WITH THE BACKEND. Phase 1 MUST be the backend/API.");
        }

        if (looksLikeFullStackRequest(message)) {
            rules.add("- This is a FULL-STACK project: submit_plan with 2–4 phases, "
                    + "and generate ONLY phase 1 files in this turn.");
        }

        if (rules.isEmpty()) {
            return "";
        }
        String designPlan = ScaffoldDesignGuide.submitPlanDesignField(message);
        return "USER CONSTRAINTS (must obey, they override your defaults and examples):\n"
                + String.join("\n", rules)
                + designPlan
                + "\nRestate nothing — just submit_plan that satisfies these, then write_file the phase 1 files.";
    }

    private static String requestedStack(String m) {
        boolean plainHtml = wants(m, "html");
        boolean named = wants(m, "spring") || wants(m, "react") || wants(m, "angular") || wants(m, "vue")
                || wants(m, "next.js") || wants(m, "nextjs") || wants(m, "django")
                || wants(m, "flask") || wants(m, "express") || wants(m, "node");
        if (plainHtml && !named) {
            return "plain static HTML + CSS + JavaScript (index.html, styles.css, script.js) — "
                    + "no framework, no build tool, no Spring/Node project files";
        }
        List<String> picked = new ArrayList<>();
        if (wants(m, "spring")) {
            picked.add("Spring Boot");
        }
        if (wants(m, "react") || wants(m, "next.js") || wants(m, "nextjs")) {
            picked.add("React");
        }
        if (wants(m, "angular")) {
            picked.add("Angular");
        }
        if (wants(m, "vue")) {
            picked.add("Vue");
        }
        if (wants(m, "django")) {
            picked.add("Django");
        }
        if (wants(m, "flask")) {
            picked.add("Flask");
        }
        if (wants(m, "express") || (wants(m, "node") && !m.contains("nodemon"))) {
            picked.add("Node/Express");
        }
        // "Django backend + plain HTML frontend": keep the static frontend explicit so the model
        // does not reach for a framework it was never asked for.
        boolean frontendFramework = picked.contains("React") || picked.contains("Angular") || picked.contains("Vue");
        if (plainHtml && !frontendFramework) {
            picked.add("a plain static HTML + CSS + JS frontend (index.html, styles.css, script.js — no framework)");
        }
        return picked.isEmpty() ? null : String.join(" + ", picked);
    }

    private static final String[] TECH_KEYWORDS = {
            "spring", "react", "angular", "vue", "next.js", "nextjs",
            "django", "flask", "express", "node"
    };

    private static final String[] NEGATION_WORDS = {
            "no", "not", "non", "without", "avoid", "sans", "aucun", "never",
            "dont", "don't", "doesnt", "exclude", "excluding", "skip", "pas"
    };

    /** Technologies the user explicitly ruled out ("no React, no Spring"). */
    private static String bannedStacks(String m) {
        List<String> banned = new ArrayList<>();
        for (String tech : TECH_KEYWORDS) {
            if (m.contains(tech) && isNegated(m, tech)) {
                String label = switch (tech) {
                    case "spring" -> "Spring Boot";
                    case "react", "next.js", "nextjs" -> "React";
                    case "node", "express" -> "Node/Express";
                    default -> Character.toUpperCase(tech.charAt(0)) + tech.substring(1);
                };
                if (!banned.contains(label)) {
                    banned.add(label);
                }
            }
        }
        return banned.isEmpty() ? null : String.join(", ", banned);
    }

    /** The user asked for this technology: it is mentioned and not every mention is negated. */
    private static boolean wants(String m, String keyword) {
        return m.contains(keyword) && !isNegated(m, keyword);
    }

    /**
     * True when EVERY mention of the keyword is preceded by a negation, so "no React" is not
     * mistaken for a request for React.
     */
    private static boolean isNegated(String m, String keyword) {
        int at = m.indexOf(keyword);
        boolean seen = false;
        while (at >= 0) {
            seen = true;
            String before = m.substring(Math.max(0, at - 24), at)
                    .replaceAll("[^a-z' ]", " ")
                    .trim();
            boolean negatedHere = false;
            for (String word : NEGATION_WORDS) {
                if (before.equals(word) || before.endsWith(" " + word) || before.endsWith(word + " ")) {
                    negatedHere = true;
                    break;
                }
            }
            if (!negatedHere) {
                return false;
            }
            at = m.indexOf(keyword, at + 1);
        }
        return seen;
    }

    /** Position of the first "start with &lt;area&gt;" style mention, or -1 when there is none. */
    private static int firstOrderedMention(String m, String... aliases) {
        String[] leads = {
                "start with", "starts with", "start by", "begin with", "begin by", "first",
                "commence par", "commencer par", "commençons par", "commencons par", "d'abord",
                "lets start with", "let's start with", "start from"
        };
        int best = -1;
        for (String alias : aliases) {
            int at = m.indexOf(alias);
            while (at >= 0) {
                String before = m.substring(Math.max(0, at - 40), at);
                for (String lead : leads) {
                    if (before.contains(lead)) {
                        if (best < 0 || at < best) {
                            best = at;
                        }
                        break;
                    }
                }
                at = m.indexOf(alias, at + 1);
            }
        }
        return best;
    }

    /** True when the message asks to begin with the given area. */
    private static boolean mentionsFirst(String m, String... aliases) {
        String[] leads = {
                "start with", "starts with", "start by", "begin with", "begin by", "first",
                "commence par", "commencer par", "commençons par", "commencons par", "d'abord",
                "lets start with", "let's start with", "start from"
        };
        for (String alias : aliases) {
            int at = m.indexOf(alias);
            while (at >= 0) {
                String before = m.substring(Math.max(0, at - 40), at);
                for (String lead : leads) {
                    if (before.contains(lead)) {
                        return true;
                    }
                }
                at = m.indexOf(alias, at + 1);
            }
        }
        return false;
    }

    private static final Set<String> PLACEHOLDER_CONTENTS = Set.of(
            "full test file", "full file", "full file contents", "full file content",
            "full source", "full source code", "file content", "file contents",
            "your code here", "code here", "test file", "todo", "tbd",
            "content", "complete file", "the complete file"
    );

    /**
     * True when args.content is an example placeholder copied from a prompt rather than real code.
     * Small models reproduce whatever literal string they were shown, producing a one-line file.
     */
    static boolean looksLikePlaceholderContent(String path, String content) {
        if (content == null) {
            return true;
        }
        String trimmed = content.strip();
        if (trimmed.isEmpty()) {
            // An intentionally empty file (e.g. __init__.py) is legitimate.
            return false;
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (PLACEHOLDER_CONTENTS.contains(normalized)) {
            return true;
        }
        // A single short line with no code punctuation is never a real source file.
        boolean codePath = path != null && path.matches("(?i).*\\.(py|java|js|ts|tsx|jsx|css|html|xml|json|md)$");
        return codePath
                && !trimmed.contains("\n")
                && trimmed.length() < 60
                && !trimmed.matches(".*[=(){}:;<>\\[\\]].*")
                && !normalized.contains("import");
    }

    private static String forcedTestReminder() {
        return """
                REJECTED: prose does not create or run tests. You wrote a description, not tool calls.
                Never say "Applied N file(s)" or "N test passed" in text — the IDE ignores it.
                args.content must be the COMPLETE real source code, \\n-escaped. Never write a
                placeholder like FULL TEST FILE — that is rejected.
                Do this now, one ```pass-tool fence per step:
                ```pass-tool
                {"name":"write_file","args":{"path":"library/catalog/tests.py","action":"create","content":"from django.test import TestCase\\nfrom .models import Book\\n\\nclass BookModelTests(TestCase):\\n    def test_str(self):\\n        b = Book.objects.create(title='T', author='A', isbn='1', year=2020)\\n        self.assertEqual(str(b), 'T')\\n"}}
                ```
                Extend that file so it really covers Book, Loan and the list-books view.
                Then actually execute them yourself:
                ```pass-tool
                {"name":"run_terminal","args":{"command":"python library/manage.py test catalog"}}
                ```
                then done{message} with the REAL counts copied from that terminal output.
                """;
    }

    private static String forcedScaffoldReminder() {
        return """
                REJECTED: Markdown / Step lists / ```java dumps do NOT create files in this IDE.
                Emit ONLY ```pass-tool blocks. Start NOW with submit_plan, then write_file for each path, then done.
                Example:
                ```pass-tool
                {"name":"submit_plan","args":{"title":"Todo API","stack":"Spring Boot","files":[{"path":"pom.xml","purpose":"Maven build"},{"path":"src/main/java/com/example/demo/TodoApplication.java","purpose":"entry"},{"path":"README.md","purpose":"docs"}]}}
                ```
                Then one write_file per path with full content in args.content.
                """;
    }

    private static String forcedToolReminder(AgentMode mode) {
        String tools = mode.allowedTools().stream().sorted().collect(Collectors.joining(", "));
        if (mode.allowsWrites()) {
            return """
                    STOP. Your previous reply was NOT applied. Use this exact shape:
                    ```pass-tool
                    {"name":"write_file","args":{"path":"relative/path.ext","action":"overwrite","content":"line one\\nline two\\n"}}
                    ```
                    Fence label must be pass-tool (not json). Use name+args — not {"tool":...,"path":...} at the root.
                    Spring Boot 3: jakarta.persistence. Java class name must match the file name.
                    Allowed: %s. Emit write_file now, then done.
                    """.formatted(tools);
        }
        return "STOP. Emit pass-tool blocks only. Allowed tools for this agent: " + tools
                + ". Finish with done.";
    }

    private static String forcedReadOnlyReminder(AgentMode mode) {
        if (mode == AgentMode.REVIEW) {
            return """
                    Use read_file/search, then submit_findings with a JSON findings array, then done.
                    Example submit_findings args: {"findings":[{"path":"A.java","line":10,"severity":"warning","message":"..."}]}
                    """;
        }
        return """
                STOP — use tools, not a chat reply.
                1) search{query="Booking"} or similar SHORT keyword
                2) read_file{path="..."} on the hits (frontend form + backend controller/view + Booking model)
                3) done{message="...full explanation with paths..."}
                Searching alone is not enough. done.message must be the answer, never "Exploration complete".
                """;
    }

    static boolean looksLikeFileTask(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        boolean verb = m.contains("create") || m.contains("cré") || m.contains("cree") || m.contains("add file")
                || m.contains("write") || m.contains("écris") || m.contains("ecris") || m.contains("generate")
                || m.contains("make a file") || m.contains("new file") || m.contains("implement")
                || m.contains("modifier") || m.contains("update file") || m.contains("overwrite")
                || m.contains("fix") || m.contains("refactor");
        boolean fileHint = m.contains(".txt") || m.contains(".java") || m.contains(".ts") || m.contains(".js")
                || m.contains(".py") || m.contains(".json") || m.contains(".html") || m.contains(".css")
                || m.contains(".md") || m.contains("fichier") || m.contains("file") || m.contains("code");
        return verb && (fileHint || m.contains("hello"));
    }

    private static void publishStatus(AgentProgressListener progress, String message) {
        if (progress != null && message != null && !message.isBlank()) {
            progress.onEvent("status", Map.of("message", message));
        }
    }

    private static void publishProgress(
            AgentProgressListener progress,
            List<AgentToolStepDto> steps,
            ProjectPlanDto projectPlan,
            Map<String, FileProposalDto> proposals
    ) {
        if (progress == null) {
            return;
        }
        progress.onEvent("steps", new ArrayList<>(steps));
        if (projectPlan != null) {
            progress.onEvent("plan", projectPlan);
        }
        if (proposals != null && !proposals.isEmpty()) {
            progress.onEvent("files", new ArrayList<>(proposals.values()));
        }
    }

    /** Cap tool-loop context: system + pinned scaffold setup + plan + last 2 exchanges. */
    private static List<ChatHistoryItemDto> messagesForModelCall(
            List<ChatHistoryItemDto> full,
            ProjectPlanDto projectPlan
    ) {
        if (full == null || full.size() <= 8) {
            return full;
        }
        List<ChatHistoryItemDto> out = new ArrayList<>();
        out.add(full.get(0));
        int pinUntil = 1;
        for (int i = 1; i < full.size() && i < 6; i++) {
            ChatHistoryItemDto m = full.get(i);
            if (m == null) {
                continue;
            }
            if ("assistant".equalsIgnoreCase(m.getRole())) {
                break;
            }
            out.add(compactMessage(m));
            pinUntil = i + 1;
        }
        if (projectPlan != null && projectPlan.getSummary() != null && !projectPlan.getSummary().isBlank()) {
            out.add(new ChatHistoryItemDto("user", "PLAN SUMMARY (locked): " + truncate(projectPlan.getSummary(), 800)));
        } else if (projectPlan != null && projectPlan.getTitle() != null) {
            out.add(new ChatHistoryItemDto("user", "PLAN: " + projectPlan.getTitle()
                    + (projectPlan.getStack() != null ? " (" + projectPlan.getStack() + ")" : "")));
        }
        int tailStart = Math.max(pinUntil, full.size() - 4);
        for (int i = tailStart; i < full.size(); i++) {
            out.add(compactMessage(full.get(i)));
        }
        return out;
    }

    private static ChatHistoryItemDto compactMessage(ChatHistoryItemDto m) {
        if (m == null || m.getContent() == null) {
            return m;
        }
        String role = m.getRole() == null ? "user" : m.getRole();
        String content = m.getContent();
        if ("assistant".equalsIgnoreCase(role) && content.length() > 2500) {
            content = truncate(content, 1200) + "\n…[assistant output truncated for context cap]";
        } else if ("user".equalsIgnoreCase(role) && content.length() > 6000) {
            content = truncate(content, 3000) + "\n…[tool results truncated for context cap]";
        }
        return new ChatHistoryItemDto(role, content);
    }

    private static boolean isScaffoldStub(String content) {
        if (content == null) {
            return true;
        }
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        return trimmed.length() < 512
                && !trimmed.contains("<html")
                && !trimmed.contains(":root");
    }

    private static String anchorHint(String base, String oldStr) {
        if (base == null || oldStr == null || oldStr.isBlank()) {
            return "";
        }
        String firstLine = oldStr.lines().findFirst().orElse(oldStr).trim();
        int idx = base.indexOf(firstLine.length() >= 4 ? firstLine : oldStr.trim());
        if (idx < 0) {
            return "Hint: file starts with: \"" + truncate(base.trim(), 120).replace('\n', ' ') + "\"";
        }
        int from = Math.max(0, idx - 40);
        int to = Math.min(base.length(), idx + Math.min(oldStr.length(), 80) + 40);
        return "Near match context: \""
                + truncate(base.substring(from, to).replace('\n', ' '), 160)
                + "\" — copy old_string EXACTLY from read_file output.";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
