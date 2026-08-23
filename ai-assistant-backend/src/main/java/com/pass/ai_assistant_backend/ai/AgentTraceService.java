package com.pass.ai_assistant_backend.ai;

import com.pass.ai_assistant_backend.ai.dto.AgentToolStepDto;
import com.pass.ai_assistant_backend.ai.dto.FileProposalDto;
import com.pass.ai_assistant_backend.service.WorkspaceService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Appends successful agent turns to workspace .passai/traces/YYYY-MM-DD.jsonl for later LoRA.
 */
@Service
public class AgentTraceService {

    private final WorkspaceService workspaceService;
    private final ObjectMapper mapper;

    public AgentTraceService(WorkspaceService workspaceService, ObjectMapper mapper) {
        this.workspaceService = workspaceService;
        this.mapper = mapper;
    }

    public void record(
            String email,
            String workspaceId,
            String userMessage,
            List<AgentToolStepDto> steps,
            List<FileProposalDto> files,
            String reply,
            String model
    ) {
        if (userMessage == null || userMessage.isBlank()) {
            return;
        }
        try {
            Path root = workspaceService.resolveUserRoot(email, workspaceId);
            Path dir = root.resolve(".passai").resolve("traces");
            Files.createDirectories(dir);
            String day = LocalDate.now(ZoneOffset.UTC).toString();
            Path file = dir.resolve(day + ".jsonl");

            ObjectNode row = mapper.createObjectNode();
            row.put("ts", Instant.now().toString());
            row.put("model", model == null ? "" : model);
            row.put("user", userMessage);
            row.put("reply", reply == null ? "" : reply);
            ArrayNode stepArr = row.putArray("steps");
            if (steps != null) {
                for (AgentToolStepDto s : steps) {
                    ObjectNode sn = stepArr.addObject();
                    sn.put("name", s.getName());
                    sn.put("args", s.getArgsSummary());
                    sn.put("status", s.getStatus());
                }
            }
            ArrayNode fileArr = row.putArray("files");
            if (files != null) {
                for (FileProposalDto f : files) {
                    ObjectNode fn = fileArr.addObject();
                    fn.put("path", f.getPath());
                    fn.put("action", f.getAction());
                    fn.put("content", f.getContent() == null ? "" : f.getContent());
                }
            }

            // Instruction-tuning shape for LoRA export
            ArrayNode messages = row.putArray("messages");
            messages.addObject().put("role", "user").put("content", userMessage);
            StringBuilder assistant = new StringBuilder();
            if (files != null) {
                for (FileProposalDto f : files) {
                    assistant.append("```pass-tool\n")
                            .append("{\"name\":\"write_file\",\"args\":{")
                            .append("\"path\":").append(mapper.writeValueAsString(f.getPath())).append(',')
                            .append("\"action\":").append(mapper.writeValueAsString(f.getAction())).append(',')
                            .append("\"content\":").append(mapper.writeValueAsString(f.getContent() == null ? "" : f.getContent()))
                            .append("}}\n```\n");
                }
            }
            assistant.append("```pass-tool\n{\"name\":\"done\",\"args\":{\"message\":")
                    .append(mapper.writeValueAsString(reply == null ? "" : reply))
                    .append("}}\n```");
            messages.addObject().put("role", "assistant").put("content", assistant.toString());

            Files.writeString(
                    file,
                    mapper.writeValueAsString(row) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (Exception ignored) {
            // Tracing must never break the agent
        }
    }
}
