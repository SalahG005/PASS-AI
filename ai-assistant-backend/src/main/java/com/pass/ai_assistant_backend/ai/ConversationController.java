package com.pass.ai_assistant_backend.ai;

import com.pass.ai_assistant_backend.ai.dto.AppendMessagesRequest;
import com.pass.ai_assistant_backend.ai.dto.ConversationDetailDto;
import com.pass.ai_assistant_backend.ai.dto.ConversationSummaryDto;
import com.pass.ai_assistant_backend.ai.dto.CreateConversationRequest;
import com.pass.ai_assistant_backend.ai.dto.SaveTurnRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    private String email(Authentication authentication) {
        return authentication.getName();
    }

    @GetMapping
    public List<ConversationSummaryDto> list(
            Authentication authentication,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @RequestParam(value = "workspaceId", required = false) String workspaceIdParam
    ) {
        String ws = workspaceIdParam != null && !workspaceIdParam.isBlank() ? workspaceIdParam : workspaceId;
        return conversationService.list(email(authentication), ws);
    }

    @GetMapping("/{id}")
    public ConversationDetailDto get(
            Authentication authentication,
            @PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) {
        return conversationService.get(email(authentication), id, workspaceId);
    }

    @PostMapping
    public ConversationDetailDto create(
            Authentication authentication,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @RequestBody(required = false) CreateConversationRequest body
    ) {
        return conversationService.create(email(authentication), workspaceId, body);
    }

    @PostMapping("/turns")
    public ConversationDetailDto saveTurn(
            Authentication authentication,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @RequestBody SaveTurnRequest body
    ) {
        return conversationService.saveTurn(email(authentication), workspaceId, body);
    }

    @PatchMapping("/{id}")
    public ConversationDetailDto rename(
            Authentication authentication,
            @PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @RequestBody Map<String, String> body
    ) {
        return conversationService.rename(email(authentication), id, body != null ? body.get("title") : null, workspaceId);
    }

    @PostMapping("/{id}/messages")
    public ConversationDetailDto append(
            Authentication authentication,
            @PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @RequestBody AppendMessagesRequest body
    ) {
        return conversationService.appendMessages(email(authentication), id, body, workspaceId);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            Authentication authentication,
            @PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) {
        conversationService.delete(email(authentication), id, workspaceId);
        return ResponseEntity.noContent().build();
    }
}
