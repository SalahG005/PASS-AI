package com.pass.ai_assistant_backend.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentOrchestratorReliabilityTest {

    @Test
    void messagesForModelCallCapsLongHistory() {
        var full = new java.util.ArrayList<com.pass.ai_assistant_backend.ai.dto.ChatHistoryItemDto>();
        full.add(new com.pass.ai_assistant_backend.ai.dto.ChatHistoryItemDto("system", "x".repeat(5000)));
        full.add(new com.pass.ai_assistant_backend.ai.dto.ChatHistoryItemDto("user", "design brief"));
        full.add(new com.pass.ai_assistant_backend.ai.dto.ChatHistoryItemDto("user", "build coffee shop"));
        for (int i = 0; i < 10; i++) {
            full.add(new com.pass.ai_assistant_backend.ai.dto.ChatHistoryItemDto("assistant", "y".repeat(8000)));
            full.add(new com.pass.ai_assistant_backend.ai.dto.ChatHistoryItemDto("user", "tool results " + "z".repeat(7000)));
        }
        var plan = new com.pass.ai_assistant_backend.ai.dto.ProjectPlanDto();
        plan.setSummary("Coffee shop landing page");
        var trimmed = invokeMessagesForModelCall(full, plan);
        assertTrue(trimmed.size() < full.size());
        int totalChars = trimmed.stream().mapToInt(m -> m.getContent().length()).sum();
        int fullChars = full.stream().mapToInt(m -> m.getContent().length()).sum();
        assertTrue(totalChars < fullChars / 2);
    }

    @Test
    void isScaffoldStubDetectsEmptyAndTinyFiles() {
        assertTrue(AgentOrchestratorReliabilityTestHelper.isScaffoldStub(""));
        assertTrue(AgentOrchestratorReliabilityTestHelper.isScaffoldStub("/* placeholder */"));
        assertFalse(AgentOrchestratorReliabilityTestHelper.isScaffoldStub(":root { --color-primary: #111; }\n".repeat(20)));
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<com.pass.ai_assistant_backend.ai.dto.ChatHistoryItemDto> invokeMessagesForModelCall(
            java.util.List<com.pass.ai_assistant_backend.ai.dto.ChatHistoryItemDto> full,
            com.pass.ai_assistant_backend.ai.dto.ProjectPlanDto plan
    ) {
        try {
            var m = AgentOrchestrator.class.getDeclaredMethod(
                    "messagesForModelCall",
                    java.util.List.class,
                    com.pass.ai_assistant_backend.ai.dto.ProjectPlanDto.class);
            m.setAccessible(true);
            return (java.util.List<com.pass.ai_assistant_backend.ai.dto.ChatHistoryItemDto>) m.invoke(null, full, plan);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

/** Test helper exposing package-private static methods via reflection wrapper. */
class AgentOrchestratorReliabilityTestHelper {
    static boolean isScaffoldStub(String content) {
        try {
            var m = AgentOrchestrator.class.getDeclaredMethod("isScaffoldStub", String.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, content);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
