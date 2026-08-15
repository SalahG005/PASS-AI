package com.pass.ai_assistant_backend.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "No React, no Spring" used to be read as a request FOR React and Spring, because the stack
 * detector only did a substring match. These tests pin the negation handling.
 */
class ScaffoldConstraintsStackTest {

    private static final String DJANGO_REQUEST = """
            Create a full-stack Event Booking website.
            Use Django for the backend and plain HTML + CSS + JS for the frontend.
            Start with Backend API first, then Frontend.
            Obey this stack: Django backend + static frontend. No React, no Spring.
            """;

    @Test
    void negatedFrameworksAreNotTreatedAsRequested() {
        String constraints = AgentOrchestrator.scaffoldConstraints(DJANGO_REQUEST);

        assertTrue(constraints.contains("Django"), "Django was requested and must be in the stack");
        assertFalse(constraints.contains("Stack is fixed by the user: Spring Boot"),
                "Spring was excluded, it must not become the fixed stack");
        assertTrue(constraints.contains("EXCLUDED"), "an exclusion rule is expected");
        assertTrue(constraints.contains("Spring Boot") && constraints.contains("React"),
                "both excluded frameworks should be listed as banned");
    }

    @Test
    void plainHtmlFrontendStaysExplicitAlongsideDjango() {
        String constraints = AgentOrchestrator.scaffoldConstraints(DJANGO_REQUEST);
        assertTrue(constraints.toLowerCase().contains("static html")
                        || constraints.toLowerCase().contains("plain static html"),
                "a static HTML frontend must remain explicit so the model does not pick a framework");
    }

    @Test
    void backendFirstOrderIsRespected() {
        String constraints = AgentOrchestrator.scaffoldConstraints(DJANGO_REQUEST);
        assertTrue(constraints.contains("START WITH THE BACKEND"), "the requested order must be enforced");
    }

    @Test
    void positivelyNamedStackStillDetected() {
        String constraints = AgentOrchestrator.scaffoldConstraints(
                "Build a todo app with Spring Boot for the API and React for the UI");
        assertTrue(constraints.contains("Spring Boot"), "Spring was genuinely requested");
        assertTrue(constraints.contains("React"), "React was genuinely requested");
        assertFalse(constraints.contains("EXCLUDED"), "nothing was excluded here");
    }
}
