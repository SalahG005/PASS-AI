package com.pass.ai_assistant_backend.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Test agent once wrote a tests.py whose entire body was the literal words "FULL TEST FILE",
 * copied straight out of the reminder prompt. write_file must refuse that.
 */
class PlaceholderContentTest {

    @Test
    void literalPlaceholderFromThePromptIsRejected() {
        assertTrue(AgentOrchestrator.looksLikePlaceholderContent("library/catalog/tests.py", "FULL TEST FILE"));
        assertTrue(AgentOrchestrator.looksLikePlaceholderContent("a/b/File.java", "FULL FILE"));
        assertTrue(AgentOrchestrator.looksLikePlaceholderContent("x.py", "  your code here  "));
    }

    @Test
    void realSourceCodeIsAccepted() {
        String real = "from django.test import TestCase\nfrom .models import Book\n\n"
                + "class BookModelTests(TestCase):\n    def test_str(self):\n        pass\n";
        assertFalse(AgentOrchestrator.looksLikePlaceholderContent("library/catalog/tests.py", real));
    }

    @Test
    void shortButValidOneLinerIsAccepted() {
        assertFalse(AgentOrchestrator.looksLikePlaceholderContent("x.py", "from .models import Book"));
        assertFalse(AgentOrchestrator.looksLikePlaceholderContent("v.js", "export const a = 1;"));
    }

    @Test
    void deliberatelyEmptyFileIsAccepted() {
        assertFalse(AgentOrchestrator.looksLikePlaceholderContent("catalog/__init__.py", ""));
    }

    @Test
    void plainTextFilesAreNotJudgedAsCode() {
        assertFalse(AgentOrchestrator.looksLikePlaceholderContent("requirements.txt", "Django==4.2"));
    }
}
