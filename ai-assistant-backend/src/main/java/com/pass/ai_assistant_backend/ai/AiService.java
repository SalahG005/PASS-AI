package com.pass.ai_assistant_backend.ai;

import com.pass.ai_assistant_backend.ai.dto.AgentResponseDto;
import com.pass.ai_assistant_backend.ai.dto.AiHealthDto;
import com.pass.ai_assistant_backend.ai.dto.ApplyRequestDto;
import com.pass.ai_assistant_backend.ai.dto.ApplyResultDto;
import com.pass.ai_assistant_backend.ai.dto.ChatHistoryItemDto;
import com.pass.ai_assistant_backend.ai.dto.ChatRequestDto;
import com.pass.ai_assistant_backend.ai.dto.ChatResponseDto;
import com.pass.ai_assistant_backend.ai.dto.FileProposalDto;
import com.pass.ai_assistant_backend.ai.dto.InstallProposalDto;
import com.pass.ai_assistant_backend.ai.dto.InstallRequestDto;
import com.pass.ai_assistant_backend.ai.dto.InstallResultDto;
import com.pass.ai_assistant_backend.ai.dto.ScaffoldPreviewDto;
import com.pass.ai_assistant_backend.dto.FileContentDto;
import com.pass.ai_assistant_backend.service.WorkspaceService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiService {

    private static final Pattern MENTION = Pattern.compile("@([\\w./\\\\-]+)");
    private static final int MAX_FILE_CHARS = 16_000;
    private static final int MAX_HISTORY = 20;
    private static final int MAX_SELECTION_CHARS = 8_000;
    private static final int MAX_RULES_CHARS = 6_000;

    private final OllamaClient ollama;
    private final OllamaProperties props;
    private final WorkspaceService workspaceService;
    private final EmbeddingService embeddingService;
    private final AgentOrchestrator agentOrchestrator;
    private final StaticSitePreviewService previewService;

    public AiService(
            OllamaClient ollama,
            OllamaProperties props,
            WorkspaceService workspaceService,
            EmbeddingService embeddingService,
            AgentOrchestrator agentOrchestrator,
            StaticSitePreviewService previewService
    ) {
        this.ollama = ollama;
        this.props = props;
        this.workspaceService = workspaceService;
        this.embeddingService = embeddingService;
        this.agentOrchestrator = agentOrchestrator;
        this.previewService = previewService;
    }

    public AiHealthDto health() {
        AiHealthDto dto = new AiHealthDto();
        dto.setBaseUrl(props.getBaseUrl());
        dto.setChatModel(props.resolveChatModel());
        dto.setEmbedModel(props.getEmbedModel());
        try {
            List<String> models = ollama.listModels();
            dto.setOllamaReachable(true);
            dto.setModels(models);
            boolean chatOk = ollama.hasModel(props.resolveChatModel(), models)
                    || ollama.hasModel(props.getChatModelFallback(), models);
            boolean agentOk = ollama.hasModel(props.resolveAgentModel(), models)
                    || ollama.hasModel(props.getAgentModelFallback(), models);
            dto.setChatModelPresent(chatOk);
            dto.setEmbedModelPresent(ollama.hasModel(props.getEmbedModel(), models));
            dto.setOk(chatOk && agentOk);
            if (!chatOk) {
                dto.setMessage("Ollama is up but chat model missing. Run: ollama pull qwen2.5-coder:7b "
                        + "&& ollama create pass-ai-coder -f ollama/Modelfile.pass-ai-coder");
            } else if (!agentOk) {
                dto.setMessage("Chat ready. Create agent model: ollama create pass-ai-agent -f ollama/Modelfile.pass-ai-agent "
                        + "(or keep fallback " + props.getAgentModelFallback() + ")");
                dto.setOk(true);
            } else if (!dto.isEmbedModelPresent()) {
                dto.setMessage("Chat ready. Pull embeddings with: ollama pull " + props.getEmbedModel());
            } else {
                dto.setMessage("Ollama ready (" + props.resolveChatModel() + " / " + props.resolveAgentModel() + ")");
            }
        } catch (Exception e) {
            dto.setOk(false);
            dto.setOllamaReachable(false);
            dto.setMessage("Cannot reach Ollama at " + props.getBaseUrl()
                    + ". Start Ollama, then: ollama pull qwen2.5-coder:7b");
        }
        return dto;
    }

    public ChatResponseDto chat(String email, String workspaceId, ChatRequestDto request)
            throws IOException, InterruptedException {
        BuiltPrompt built = buildPrompt(email, workspaceId, request, false);
        String reply = ollama.chat(built.messages(), props.resolveChatModel());
        return new ChatResponseDto(reply, props.resolveChatModel(), built.usedPaths(), built.ragPaths());
    }

    public AgentResponseDto agent(String email, String workspaceId, ChatRequestDto request)
            throws IOException, InterruptedException {
        BuiltPrompt built = buildPrompt(email, workspaceId, request, true);
        AgentResponseDto response = agentOrchestrator.run(email, workspaceId, request, built.messages(), AgentMode.CODE);
        // Merge RAG / used context paths into response
        List<String> used = new ArrayList<>(built.usedPaths());
        if (response.getUsedPaths() != null) {
            for (String p : response.getUsedPaths()) {
                if (!used.contains(p)) {
                    used.add(p);
                }
            }
        }
        response.setUsedPaths(used);
        response.setRagPaths(built.ragPaths());
        response.setAgentMode(AgentMode.CODE.pathSegment());
        return response;
    }

    /** Specialized agents: POST /api/ai/agents/{mode} — does not change legacy /agent. */
    public AgentResponseDto specializedAgent(String email, String workspaceId, ChatRequestDto request, AgentMode mode)
            throws IOException, InterruptedException {
        if (mode == null) {
            mode = AgentMode.CODE;
        }
        BuiltPrompt built = buildPromptForMode(email, workspaceId, request, mode);
        AgentResponseDto response = agentOrchestrator.run(email, workspaceId, request, built.messages(), mode);
        finalizeAgentResponse(built, mode, response);
        enrichScaffoldResponse(email, workspaceId, request, mode, response);
        return response;
    }

    /** SSE stream — same as specializedAgent but emits progress events while running. */
    public AgentResponseDto specializedAgentStream(
            String email,
            String workspaceId,
            ChatRequestDto request,
            AgentMode mode,
            AgentProgressListener progress
    ) throws IOException, InterruptedException {
        if (mode == null) {
            mode = AgentMode.CODE;
        }
        BuiltPrompt built = buildPromptForMode(email, workspaceId, request, mode);
        AgentResponseDto response = agentOrchestrator.run(
                email, workspaceId, request, built.messages(), mode, progress);
        finalizeAgentResponse(built, mode, response);
        enrichScaffoldResponse(email, workspaceId, request, mode, response);
        return response;
    }

    public InstallResultDto installCommands(String email, String workspaceId, InstallRequestDto request) {
        List<String> executed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        StringBuilder output = new StringBuilder();
        if (request == null || request.getCommands() == null || request.getCommands().isEmpty()) {
            return new InstallResultDto(false, executed, List.of("No install commands"), output.toString());
        }
        for (InstallProposalDto cmd : request.getCommands()) {
            if (cmd == null || cmd.getCommand() == null || cmd.getCommand().isBlank()) {
                errors.add("Missing command");
                continue;
            }
            String command = cmd.getCommand().trim();
            try {
                List<String> lines = workspaceService.runDebugCommand(email, workspaceId, command);
                executed.add(command);
                output.append("$ ").append(command).append('\n');
                output.append(String.join("\n", lines)).append("\n\n");
            } catch (Exception e) {
                errors.add(command + ": " + (e.getMessage() != null ? e.getMessage() : "failed"));
            }
        }
        return new InstallResultDto(errors.isEmpty(), executed, errors, output.toString().trim());
    }

    private void finalizeAgentResponse(BuiltPrompt built, AgentMode mode, AgentResponseDto response) {
        List<String> used = new ArrayList<>(built.usedPaths());
        if (response.getUsedPaths() != null) {
            for (String p : response.getUsedPaths()) {
                if (!used.contains(p)) {
                    used.add(p);
                }
            }
        }
        response.setUsedPaths(used);
        response.setRagPaths(built.ragPaths());
        response.setAgentMode(mode.pathSegment());
    }

    private void enrichScaffoldResponse(
            String email,
            String workspaceId,
            ChatRequestDto request,
            AgentMode mode,
            AgentResponseDto response
    ) {
        if (mode != AgentMode.SCAFFOLD || response.getFiles() == null || response.getFiles().isEmpty()) {
            return;
        }
        if (request != null && !request.isScaffoldPreview()) {
            return;
        }
        try {
            ScaffoldPreviewDto preview = previewService.previewStaticSite(email, workspaceId, response.getFiles());
            response.setScaffoldPreview(preview);
        } catch (Exception ignored) {
        }
    }

    public ApplyResultDto applyFiles(String email, String workspaceId, ApplyRequestDto request) {
        List<String> applied = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (request == null || request.getFiles() == null || request.getFiles().isEmpty()) {
            return new ApplyResultDto(false, applied, List.of("No files to apply"));
        }
        for (FileProposalDto file : request.getFiles()) {
            if (file == null || file.getPath() == null || file.getPath().isBlank()) {
                errors.add("Missing path");
                continue;
            }
            String path = file.getPath().replace('\\', '/').trim();
            try {
                workspaceService.writeFile(email, workspaceId, path, file.getContent() == null ? "" : file.getContent());
                applied.add(path);
            } catch (Exception e) {
                errors.add(path + ": " + (e.getMessage() != null ? e.getMessage() : "write failed"));
            }
        }
        return new ApplyResultDto(errors.isEmpty(), applied, errors);
    }

    public void chatStream(
            String email,
            String workspaceId,
            ChatRequestDto request,
            Consumer<String> onToken,
            Consumer<ChatResponseDto> onDone
    ) throws IOException, InterruptedException {
        BuiltPrompt built = buildPrompt(email, workspaceId, request, false);
        StringBuilder full = new StringBuilder();
        ollama.chatStream(built.messages(), props.resolveChatModel(), token -> {
            full.append(token);
            onToken.accept(token);
        });
        onDone.accept(new ChatResponseDto(full.toString(), props.resolveChatModel(), built.usedPaths(), built.ragPaths()));
    }

    private BuiltPrompt buildPrompt(String email, String workspaceId, ChatRequestDto request, boolean agentMode)
            throws IOException, InterruptedException {
        if (request == null || request.getMessage() == null || request.getMessage().isBlank()) {
            throw new IllegalArgumentException("message is required");
        }

        Set<String> paths = new LinkedHashSet<>();
        if (request.getActivePath() != null && !request.getActivePath().isBlank()) {
            paths.add(normalizePath(request.getActivePath()));
        }
        if (request.getMentionPaths() != null) {
            for (String p : request.getMentionPaths()) {
                if (p != null && !p.isBlank()) {
                    paths.add(normalizePath(p));
                }
            }
        }
        if (request.getOpenPaths() != null) {
            int n = 0;
            for (String p : request.getOpenPaths()) {
                if (p != null && !p.isBlank() && n < 8) {
                    paths.add(normalizePath(p));
                    n++;
                }
            }
        }
        Matcher matcher = MENTION.matcher(request.getMessage());
        while (matcher.find()) {
            paths.add(normalizePath(matcher.group(1)));
        }

        StringBuilder context = new StringBuilder();

        // Project rules (auto-seed template once)
        try {
            ensureProjectRules(email, workspaceId);
            FileContentDto rules = workspaceService.readFile(email, workspaceId, ".passai/rules.md");
            if (rules.getContent() != null && !rules.getContent().isBlank()) {
                context.append("\n--- PROJECT RULES (.passai/rules.md) ---\n")
                        .append(truncate(rules.getContent(), MAX_RULES_CHARS)).append("\n");
            }
        } catch (Exception ignored) {
        }

        if (request.getOpenPaths() != null && !request.getOpenPaths().isEmpty()) {
            context.append("\n--- OPEN TABS ---\n");
            for (String p : request.getOpenPaths()) {
                if (p != null && !p.isBlank()) {
                    context.append("- ").append(normalizePath(p)).append('\n');
                }
            }
        }

        if (request.getSelection() != null && !request.getSelection().isBlank()) {
            context.append("\n--- CURRENT SELECTION");
            if (request.getActivePath() != null) {
                context.append(" in ").append(normalizePath(request.getActivePath()));
            }
            context.append(" ---\n")
                    .append(truncate(request.getSelection(), MAX_SELECTION_CHARS))
                    .append("\n");
        }

        List<String> usedPaths = new ArrayList<>();
        for (String path : paths) {
            try {
                FileContentDto file = workspaceService.readFile(email, workspaceId, path);
                String body = truncate(file.getContent(), MAX_FILE_CHARS);
                context.append("\n--- FILE: ").append(file.getPath()).append(" ---\n")
                        .append(body).append("\n");
                usedPaths.add(file.getPath());
            } catch (NoSuchFileException | SecurityException ignored) {
            } catch (IOException e) {
                context.append("\n--- FILE: ").append(path).append(" (unreadable) ---\n");
            }
        }

        List<String> ragPaths = new ArrayList<>();
        if (request.isUseRag()) {
            try {
                EmbeddingService.RagContext rag = embeddingService.retrieve(
                        email, workspaceId, request.getMessage(), request);
                if (rag.promptBlock() != null && !rag.promptBlock().isBlank()) {
                    context.append("\n--- RELEVANT WORKSPACE SNIPPETS ---\n")
                            .append(rag.promptBlock()).append("\n");
                    ragPaths.addAll(rag.paths());
                }
            } catch (Exception ignored) {
            }
        }

        List<ChatHistoryItemDto> messages = new ArrayList<>();
        messages.add(new ChatHistoryItemDto("system", agentMode ? agentSystemPrompt() : systemPrompt()));
        if (context.length() > 0) {
            messages.add(new ChatHistoryItemDto("system",
                    "Workspace context for this turn:\n" + context));
        }

        List<ChatHistoryItemDto> history = request.getHistory();
        if (history != null && !history.isEmpty()) {
            int from = Math.max(0, history.size() - MAX_HISTORY);
            for (int i = from; i < history.size(); i++) {
                ChatHistoryItemDto h = history.get(i);
                if (h == null || h.getContent() == null || h.getContent().isBlank()) {
                    continue;
                }
                messages.add(new ChatHistoryItemDto(h.getRole(), h.getContent()));
            }
        }
        messages.add(new ChatHistoryItemDto("user", request.getMessage()));
        return new BuiltPrompt(messages, usedPaths, ragPaths);
    }

    private BuiltPrompt buildPromptForMode(String email, String workspaceId, ChatRequestDto request, AgentMode mode)
            throws IOException, InterruptedException {
        BuiltPrompt built = buildPrompt(email, workspaceId, request, true);
        if (mode != null && !built.messages().isEmpty()) {
            built.messages().set(0, new ChatHistoryItemDto("system", mode.systemPrompt()));
        }
        return built;
    }

    private void ensureProjectRules(String email, String workspaceId) {
        try {
            workspaceService.readFile(email, workspaceId, ".passai/rules.md");
        } catch (NoSuchFileException e) {
            try {
                String template = """
                        # PASS AI project rules

                        ## Stack
                        - Prefer patterns already used in this workspace.
                        - Keep changes minimal and match existing style.

                        ## Static HTML/CSS sites
                        - Use styles.css with :root CSS variables (colors, spacing, fonts) — avoid default Arial + #4CAF50.
                        - Polished commercial layout: hero, sections, cards, CTA, footer; mobile-first responsive.
                        - Reuse the same design tokens across all pages/phases — do not reset the palette each phase.

                        ## Agent
                        - File changes go through Diff Apply — do not ask the user to paste files manually.
                        - Local Ollama only; no paid cloud models.
                        """;
                workspaceService.writeFile(email, workspaceId, ".passai/rules.md", template);
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
    }

    private static String systemPrompt() {
        return """
                You are PASS AI, a coding assistant inside a Cursor-like IDE.
                Help with Java/Spring, Angular/TypeScript, and general software engineering.
                Be concise and practical. Prefer concrete code when asked.
                When referencing workspace files, use their paths. If context is missing, say what you need.
                Obey PROJECT RULES when present.
                """;
    }

    private static String agentSystemPrompt() {
        return """
                You are PASS AI Agent. Solve tasks by emitting tool blocks. The IDE applies file writes after user review.

                Emit one or more:
                ```pass-tool
                {"name":"TOOL","args":{...}}
                ```

                Tools: read_file{path}, search{query}, write_file{path,action,content}, run_terminal{command}, done{message}.
                Legacy pass-file blocks are also accepted for writes.
                FORBIDDEN: telling the user to run shell commands to create files.
                Prefer write_file + done. Short explanations only inside done.message.
                """;
    }

    private static String normalizePath(String path) {
        return path.replace('\\', '/').replaceFirst("^@+", "").trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "\n…[truncated]";
    }

    private record BuiltPrompt(List<ChatHistoryItemDto> messages, List<String> usedPaths, List<String> ragPaths) {
    }
}
