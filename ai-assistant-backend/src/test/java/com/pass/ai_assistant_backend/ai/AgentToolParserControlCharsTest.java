package com.pass.ai_assistant_backend.ai;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Docs agent often writes README content as a real multi-line block (literal newlines) instead
 * of a single \n-escaped JSON string. That is invalid JSON and silently dropped the write_file,
 * while done{} still claimed success. These tests pin the control-char repair.
 */
class AgentToolParserControlCharsTest {

    private final AgentToolParser parser = new AgentToolParser(new ObjectMapper());

    @Test
    void writeFileWithRawNewlinesInContentIsRecovered() {
        String raw = "```pass-tool\n"
                + "{\"name\":\"write_file\",\"args\":{\"path\":\"README.md\",\"action\":\"write\",\"content\":\"# Event Booking Project\n\n"
                + "## Installation\n\n"
                + "1. Install dependencies:\n"
                + "   pip install -r requirements.txt\n\n"
                + "Then open index.html.\"}}\n"
                + "```\n"
                + "done{message=\"README.md has been updated.\"}";

        AgentToolParser.ParseResult result = parser.parse(raw);
        List<AgentToolParser.ToolCall> writes = result.tools().stream()
                .filter(t -> "write_file".equals(t.name()))
                .toList();

        assertEquals(1, writes.size(), "the write_file must be recovered despite raw newlines");
        AgentToolParser.ToolCall write = writes.get(0);
        assertEquals("README.md", write.path());
        assertNotNull(write.content());
        assertTrue(write.content().startsWith("# Event Booking Project"),
                "content should be preserved with its line breaks");
        assertTrue(write.content().contains("pip install -r requirements.txt"),
                "the full body must survive the repair");
    }

    @Test
    void readmeWithEmbeddedFencesAndStrayQuoteIsSalvaged() {
        // Reproduces the real Docs output: markdown with ```python/```bash fences, a stray extra
        // quote after the Booking __str__ f-string, raw newlines, and a trailing done{}.
        String raw = "```pass-tool\n"
                + "{\"name\":\"write_file\",\"args\":{\"path\":\"README.md\",\"action\":\"write\",\"content\":\"# Event Booking Project\n"
                + "\n"
                + "## Installation\n"
                + "\n"
                + "```bash\n"
                + "pip install -r requirements.txt\n"
                + "```\n"
                + "\n"
                + "## Models\n"
                + "\n"
                + "```python\n"
                + "class Booking(models.Model):\n"
                + "    def __str__(self):\n"
                + "        return f\\\"{self.name} - {self.event.title}\\\"\"\n"
                + "```\n"
                + "\n"
                + "Open index.html in your browser.\"}}\n"
                + "```\n"
                + "done{message=\"README.md has been updated.\"}";

        AgentToolParser.ParseResult result = parser.parse(raw);
        List<AgentToolParser.ToolCall> writes = result.tools().stream()
                .filter(t -> "write_file".equals(t.name()))
                .toList();

        assertEquals(1, writes.size(), "the write_file must be salvaged despite fences + stray quote");
        AgentToolParser.ToolCall write = writes.get(0);
        assertEquals("README.md", write.path());
        assertNotNull(write.content());
        assertTrue(write.content().startsWith("# Event Booking Project"), "content preserved from the start");
        assertTrue(write.content().contains("pip install -r requirements.txt"), "install step present");
        assertTrue(write.content().contains("Open index.html in your browser."),
                "content survives PAST the stray quote to the real end");
    }

    @Test
    void shorthandSearchIsExecutedNotEchoed() {
        AgentToolParser.ParseResult result = parser.parse("search{query=\"Event Booking project flow\"}");
        List<AgentToolParser.ToolCall> tools = result.tools();
        assertEquals(1, tools.size());
        assertEquals("search", tools.get(0).name());
        assertEquals("Event Booking project flow", tools.get(0).query());
    }

    @Test
    void shorthandReadFileWithQuotedKeyPrefersValueNotKey() {
        AgentToolParser.ParseResult result = parser.parse("read_file{\"path\":\"backend/models/Booking.java\"}");
        List<AgentToolParser.ToolCall> reads = result.tools().stream()
                .filter(t -> "read_file".equals(t.name()))
                .toList();
        assertEquals(1, reads.size());
        assertEquals("backend/models/Booking.java", reads.get(0).path());
    }

    @Test
    void shorthandDoneCarriesItsMessage() {
        AgentToolParser.ParseResult result = parser.parse("done{message=\"All good here.\"}");
        List<AgentToolParser.ToolCall> dones = result.tools().stream()
                .filter(t -> "done".equals(t.name()))
                .toList();
        assertEquals(1, dones.size());
        assertEquals("All good here.", dones.get(0).message());
    }

    @Test
    void jsonFenceStillWinsOverShorthand() {
        String raw = "```pass-tool\n{\"name\":\"search\",\"args\":{\"query\":\"booking\"}}\n```";
        AgentToolParser.ParseResult result = parser.parse(raw);
        assertEquals(1, result.tools().size());
        assertEquals("search", result.tools().get(0).name());
        assertEquals("booking", result.tools().get(0).query());
    }

    @Test
    void escaperLeavesValidJsonUntouched() {
        String valid = "{\"name\":\"done\",\"args\":{\"message\":\"line1\\nline2\"}}";
        assertEquals(valid, AgentToolParser.escapeControlCharsInStrings(valid),
                "already-valid JSON must not be altered");
    }
}
