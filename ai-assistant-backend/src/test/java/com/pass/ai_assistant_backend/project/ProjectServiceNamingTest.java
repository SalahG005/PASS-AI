package com.pass.ai_assistant_backend.project;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectServiceNamingTest {

    @Test
    void sanitizeFolderNameRemovesIllegalWindowsChars() {
        String name = ProjectService.sanitizeFolderName("My:Project<>?/Test");
        assertFalse(name.contains(":"));
        assertFalse(name.contains("<"));
        assertFalse(name.contains(">"));
        assertFalse(name.contains("?"));
        assertFalse(name.contains("/"));
        assertTrue(name.length() > 0);
    }

    @Test
    void sanitizeFolderNameFallsBackForBlank() {
        assertEquals("Untitled Project", ProjectService.sanitizeFolderName("   "));
        assertEquals("Untitled Project", ProjectService.sanitizeFolderName(null));
    }

    @Test
    void newProjectIdMatchesWorkspaceHeaderPattern() {
        String id = ProjectService.newProjectId();
        assertTrue(id.matches("^[A-Za-z0-9_-]{4,64}$"));
        assertTrue(id.startsWith("proj_"));
    }

    @Test
    void passAiAppDataRootEndsWithPassAi() {
        Path root = ProjectService.passAiAppDataRoot();
        assertEquals("Pass-AI", root.getFileName().toString());
    }

    @Test
    void sanitizeProjectIdKeepsValidLegacyIds() {
        assertEquals("ws_abc123_xyz", ProjectService.sanitizeProjectId("ws_abc123_xyz"));
        String regenerated = ProjectService.sanitizeProjectId("bad id!");
        assertTrue(regenerated.matches("^[A-Za-z0-9_-]{4,64}$"));
    }
}
