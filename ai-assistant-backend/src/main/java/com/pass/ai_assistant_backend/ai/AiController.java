package com.pass.ai_assistant_backend.ai;

import com.pass.ai_assistant_backend.ai.dto.AgentResponseDto;
import com.pass.ai_assistant_backend.ai.dto.AiHealthDto;
import com.pass.ai_assistant_backend.ai.dto.ApplyRequestDto;
import com.pass.ai_assistant_backend.ai.dto.ApplyResultDto;
import com.pass.ai_assistant_backend.ai.dto.ChatRequestDto;
import com.pass.ai_assistant_backend.ai.dto.ChatResponseDto;
import com.pass.ai_assistant_backend.ai.dto.IndexResultDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiService aiService;
    private final EmbeddingService embeddingService;
    private final OllamaProperties props;
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    public AiController(AiService aiService, EmbeddingService embeddingService, OllamaProperties props) {
        this.aiService = aiService;
        this.embeddingService = embeddingService;
        this.props = props;
    }

    private String email(Authentication authentication) {
        return authentication.getName();
    }

    @GetMapping("/health")
    public AiHealthDto health() {
        return aiService.health();
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(
            Authentication authentication,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @RequestBody ChatRequestDto body
    ) {
        try {
            ChatResponseDto response = aiService.chat(email(authentication), workspaceId, body);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Ollama error"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Interrupted while waiting for Ollama"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "AI chat failed"));
        }
    }

    @PostMapping("/agent")
    public ResponseEntity<?> agent(
            Authentication authentication,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @RequestBody ChatRequestDto body
    ) {
        try {
            AgentResponseDto response = aiService.agent(email(authentication), workspaceId, body);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Ollama error"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Interrupted while waiting for Ollama"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "AI agent failed"));
        }
    }

    /**
     * Specialized agents — additive; does not change /agent.
     * Modes: code | review | test | docs | research
     */
    @PostMapping("/agents/{mode}")
    public ResponseEntity<?> specializedAgent(
            Authentication authentication,
            @PathVariable String mode,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @RequestBody ChatRequestDto body
    ) {
        try {
            AgentMode agentMode = AgentMode.fromPath(mode);
            AgentResponseDto response = aiService.specializedAgent(
                    email(authentication), workspaceId, body, agentMode);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Ollama error"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Interrupted while waiting for Ollama"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Specialized agent failed"));
        }
    }

    @GetMapping("/agents")
    public List<Map<String, Object>> listAgents() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentMode m : AgentMode.values()) {
            out.add(Map.of(
                    "id", m.pathSegment(),
                    "name", switch (m) {
                        case CODE -> "Code Agent";
                        case REVIEW -> "Review Agent";
                        case TEST -> "Test Agent";
                        case DOCS -> "Documentation Agent";
                        case RESEARCH -> "Research Agent";
                        case SCAFFOLD -> "Scaffold (Composer)";
                    },
                    "tools", m.allowedTools(),
                    "writes", m.allowsWrites()
            ));
        }
        return out;
    }

    @PostMapping("/apply")
    public ResponseEntity<?> apply(
            Authentication authentication,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @RequestBody ApplyRequestDto body
    ) {
        try {
            ApplyResultDto result = aiService.applyFiles(email(authentication), workspaceId, body);
            if (!result.isOk() && result.getApplied().isEmpty()) {
                return ResponseEntity.badRequest().body(result);
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Apply failed"));
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            Authentication authentication,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @RequestBody ChatRequestDto body
    ) {
        SseEmitter emitter = new SseEmitter(props.getTimeoutSeconds() * 1000L);
        String user = email(authentication);
        streamExecutor.submit(() -> {
            try {
                aiService.chatStream(
                        user,
                        workspaceId,
                        body,
                        token -> {
                            try {
                                emitter.send(SseEmitter.event().name("token").data(token));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        },
                        done -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data(done));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        }
                );
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("error", e.getMessage() != null ? e.getMessage() : "stream failed")));
                } catch (IOException ignored) {
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @PostMapping("/index")
    public ResponseEntity<?> index(
            Authentication authentication,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) {
        try {
            IndexResultDto result = embeddingService.indexWorkspace(email(authentication), workspaceId);
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Index failed"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Interrupted during indexing"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Index failed"));
        }
    }

    @GetMapping("/index/status")
    public Map<String, Object> indexStatus(
            Authentication authentication,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) {
        long chunks = embeddingService.chunkCount(email(authentication), workspaceId);
        return Map.of(
                "chunks", chunks,
                "indexed", chunks > 0,
                "embedModel", props.getEmbedModel()
        );
    }
}
