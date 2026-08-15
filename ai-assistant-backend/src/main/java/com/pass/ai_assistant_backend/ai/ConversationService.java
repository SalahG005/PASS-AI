package com.pass.ai_assistant_backend.ai;

import com.pass.ai_assistant_backend.ai.dto.AppendMessagesRequest;
import com.pass.ai_assistant_backend.ai.dto.ConversationDetailDto;
import com.pass.ai_assistant_backend.ai.dto.ConversationMessageDto;
import com.pass.ai_assistant_backend.ai.dto.ConversationSummaryDto;
import com.pass.ai_assistant_backend.ai.dto.CreateConversationRequest;
import com.pass.ai_assistant_backend.ai.dto.SaveTurnRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class ConversationService {

    private final AiConversationRepository conversations;
    private final AiMessageRepository messages;

    public ConversationService(AiConversationRepository conversations, AiMessageRepository messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    @Transactional(readOnly = true)
    public List<ConversationSummaryDto> list(String email, String workspaceId) {
        String ws = requireWorkspaceId(workspaceId);
        List<AiConversation> list = conversations.findByEmailAndWorkspaceIdOrderByUpdatedAtDesc(email, ws);
        List<ConversationSummaryDto> out = new ArrayList<>();
        for (AiConversation c : list) {
            long count = messages.countByConversationId(c.getId());
            out.add(new ConversationSummaryDto(
                    c.getId(),
                    c.getTitle(),
                    c.getMode(),
                    c.getWorkspaceId(),
                    c.getCreatedAt(),
                    c.getUpdatedAt(),
                    count
            ));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public ConversationDetailDto get(String email, Long id, String workspaceId) {
        AiConversation c = requireOwnedInWorkspace(email, id, workspaceId);
        return toDetail(c);
    }

    @Transactional
    public ConversationDetailDto create(String email, String workspaceIdHeader, CreateConversationRequest request) {
        AiConversation c = new AiConversation();
        c.setEmail(email);
        String ws = request != null && request.getWorkspaceId() != null && !request.getWorkspaceId().isBlank()
                ? request.getWorkspaceId()
                : workspaceIdHeader;
        c.setWorkspaceId(requireWorkspaceId(ws));
        String mode = request != null && request.getMode() != null ? request.getMode().trim().toLowerCase(Locale.ROOT) : "chat";
        if (!mode.equals("agent") && !mode.equals("composer")) {
            mode = "chat";
        }
        if (mode.equals("composer")) {
            mode = "agent";
        }
        c.setMode(mode);
        String title = request != null && request.getTitle() != null && !request.getTitle().isBlank()
                ? truncate(request.getTitle().trim(), 120)
                : "New chat";
        c.setTitle(title);
        Instant now = Instant.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        conversations.save(c);
        return toDetail(c);
    }

    @Transactional
    public ConversationDetailDto rename(String email, Long id, String title, String workspaceId) {
        AiConversation c = requireOwnedInWorkspace(email, id, workspaceId);
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
        }
        c.setTitle(truncate(title.trim(), 120));
        c.setUpdatedAt(Instant.now());
        conversations.save(c);
        return toDetail(c);
    }

    @Transactional
    public ConversationDetailDto appendMessages(String email, Long id, AppendMessagesRequest request, String workspaceId) {
        AiConversation c = requireOwnedInWorkspace(email, id, workspaceId);
        if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messages required");
        }
        int next = (int) messages.countByConversationId(id);
        for (ConversationMessageDto dto : request.getMessages()) {
            if (dto == null || dto.getContent() == null || dto.getContent().isBlank()) {
                continue;
            }
            String role = normalizeRole(dto.getRole());
            AiMessage m = new AiMessage();
            m.setConversationId(id);
            m.setRole(role);
            m.setContent(dto.getContent());
            m.setSortOrder(next++);
            m.setCreatedAt(Instant.now());
            messages.save(m);
        }
        if (request.getTitle() != null && !request.getTitle().isBlank()
                && (c.getTitle().equals("New chat") || c.getTitle().startsWith("New "))) {
            c.setTitle(truncate(request.getTitle().trim(), 120));
        } else if ("New chat".equals(c.getTitle()) || "New agent".equals(c.getTitle())) {
            ConversationMessageDto firstUser = request.getMessages().stream()
                    .filter(m -> m != null && "user".equalsIgnoreCase(m.getRole()) && m.getContent() != null)
                    .findFirst()
                    .orElse(null);
            if (firstUser != null) {
                c.setTitle(truncate(firstUser.getContent().trim().replace('\n', ' '), 80));
            }
        }
        c.setUpdatedAt(Instant.now());
        conversations.save(c);
        return toDetail(c);
    }

    @Transactional
    public ConversationDetailDto saveTurn(String email, String workspaceIdHeader, SaveTurnRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body required");
        }
        String userMsg = request.getUserMessage() == null ? "" : request.getUserMessage().trim();
        String assistantMsg = request.getAssistantMessage() == null ? "" : request.getAssistantMessage().trim();
        if (userMsg.isBlank() && assistantMsg.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messages required");
        }

        AiConversation c;
        if (request.getConversationId() != null) {
            String ws = request.getWorkspaceId() != null && !request.getWorkspaceId().isBlank()
                    ? request.getWorkspaceId()
                    : workspaceIdHeader;
            c = requireOwnedInWorkspace(email, request.getConversationId(), ws);
        } else {
            c = new AiConversation();
            c.setEmail(email);
            String ws = request.getWorkspaceId() != null && !request.getWorkspaceId().isBlank()
                    ? request.getWorkspaceId()
                    : workspaceIdHeader;
            c.setWorkspaceId(requireWorkspaceId(ws));
            String mode = request.getMode() != null ? request.getMode().trim().toLowerCase(Locale.ROOT) : "chat";
            if (mode.equals("composer")) {
                mode = "agent";
            }
            if (!mode.equals("agent")) {
                mode = "chat";
            }
            c.setMode(mode);
            String titleSource = request.getTitle() != null && !request.getTitle().isBlank()
                    ? request.getTitle()
                    : (!userMsg.isBlank() ? userMsg : "New chat");
            c.setTitle(truncate(titleSource.replace('\n', ' ').trim(), 80));
            Instant now = Instant.now();
            c.setCreatedAt(now);
            c.setUpdatedAt(now);
            conversations.save(c);
        }

        int next = (int) messages.countByConversationId(c.getId());
        if (!userMsg.isBlank()) {
            AiMessage um = new AiMessage();
            um.setConversationId(c.getId());
            um.setRole("user");
            um.setContent(userMsg);
            um.setSortOrder(next++);
            um.setCreatedAt(Instant.now());
            messages.save(um);
        }
        if (!assistantMsg.isBlank()) {
            AiMessage am = new AiMessage();
            am.setConversationId(c.getId());
            am.setRole("assistant");
            am.setContent(assistantMsg);
            am.setSortOrder(next++);
            am.setCreatedAt(Instant.now());
            messages.save(am);
        }

        if ("New chat".equals(c.getTitle()) || "New agent".equals(c.getTitle())) {
            if (!userMsg.isBlank()) {
                c.setTitle(truncate(userMsg.replace('\n', ' ').trim(), 80));
            }
        }
        c.setUpdatedAt(Instant.now());
        conversations.save(c);
        // flush so toDetail sees new rows
        messages.flush();
        return toDetail(c);
    }

    @Transactional
    public void delete(String email, Long id, String workspaceId) {
        AiConversation c = requireOwnedInWorkspace(email, id, workspaceId);
        messages.deleteByConversationId(c.getId());
        conversations.delete(c);
    }

    private AiConversation requireOwnedInWorkspace(String email, Long id, String workspaceId) {
        AiConversation c = conversations.findByIdAndEmail(id, email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        String expected = requireWorkspaceId(workspaceId);
        String actual = c.getWorkspaceId() == null || c.getWorkspaceId().isBlank() ? "default" : c.getWorkspaceId();
        if (!expected.equals(actual)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found in this project");
        }
        return c;
    }

    private static String requireWorkspaceId(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Active project (workspaceId) is required");
        }
        return workspaceId.trim();
    }

    private ConversationDetailDto toDetail(AiConversation c) {
        ConversationDetailDto dto = new ConversationDetailDto();
        dto.setId(c.getId());
        dto.setTitle(c.getTitle());
        dto.setMode(c.getMode());
        dto.setWorkspaceId(c.getWorkspaceId());
        dto.setCreatedAt(c.getCreatedAt());
        dto.setUpdatedAt(c.getUpdatedAt());
        List<ConversationMessageDto> msgs = new ArrayList<>();
        for (AiMessage m : messages.findByConversationIdOrderBySortOrderAscIdAsc(c.getId())) {
            msgs.add(new ConversationMessageDto(m.getId(), m.getRole(), m.getContent(), m.getSortOrder(), m.getCreatedAt()));
        }
        dto.setMessages(msgs);
        return dto;
    }

    private static String normalizeRole(String role) {
        if (role == null) {
            return "user";
        }
        String r = role.trim().toLowerCase(Locale.ROOT);
        if (r.equals("assistant") || r.equals("system") || r.equals("user")) {
            return r;
        }
        return "user";
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 1) + "…";
    }
}
