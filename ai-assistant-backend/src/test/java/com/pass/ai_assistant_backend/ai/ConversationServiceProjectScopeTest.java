package com.pass.ai_assistant_backend.ai;

import com.pass.ai_assistant_backend.ai.dto.ConversationDetailDto;
import com.pass.ai_assistant_backend.ai.dto.SaveTurnRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationServiceProjectScopeTest {

    @Mock
    AiConversationRepository conversations;
    @Mock
    AiMessageRepository messages;

    ConversationService service;

    @BeforeEach
    void setUp() {
        service = new ConversationService(conversations, messages);
    }

    @Test
    void listRequiresWorkspaceId() {
        assertThrows(ResponseStatusException.class, () -> service.list("a@pass-consulting.com", null));
        assertThrows(ResponseStatusException.class, () -> service.list("a@pass-consulting.com", "  "));
    }

    @Test
    void getRejectsConversationFromAnotherProject() {
        AiConversation c = conversation(9L, "a@pass-consulting.com", "proj_aaa");
        when(conversations.findByIdAndEmail(9L, "a@pass-consulting.com")).thenReturn(Optional.of(c));

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.get("a@pass-consulting.com", 9L, "proj_bbb")
        );
        assertTrue(ex.getReason() != null && ex.getReason().toLowerCase().contains("project"));
    }

    @Test
    void saveTurnCreatesConversationBoundToActiveProject() {
        when(conversations.save(any(AiConversation.class))).thenAnswer(inv -> {
            AiConversation c = inv.getArgument(0);
            c.setId(42L);
            return c;
        });
        when(messages.countByConversationId(42L)).thenReturn(0L);
        when(messages.findByConversationIdOrderBySortOrderAscIdAsc(42L)).thenReturn(List.of());

        SaveTurnRequest req = new SaveTurnRequest();
        req.setUserMessage("hello");
        req.setAssistantMessage("world");
        req.setMode("chat");

        ConversationDetailDto detail = service.saveTurn("a@pass-consulting.com", "proj_xyz", req);
        assertEquals(42L, detail.getId());

        ArgumentCaptor<AiConversation> captor = ArgumentCaptor.forClass(AiConversation.class);
        verify(conversations, atLeastOnce()).save(captor.capture());
        assertEquals("proj_xyz", captor.getValue().getWorkspaceId());
    }

    private AiConversation conversation(Long id, String email, String workspaceId) {
        AiConversation c = new AiConversation();
        c.setId(id);
        c.setEmail(email);
        c.setWorkspaceId(workspaceId);
        c.setTitle("Chat");
        c.setMode("chat");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return c;
    }
}
