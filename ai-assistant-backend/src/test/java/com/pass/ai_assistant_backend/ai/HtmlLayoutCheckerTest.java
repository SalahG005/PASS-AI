package com.pass.ai_assistant_backend.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HtmlLayoutCheckerTest {

    @Test
    void detectsStackedFeatureCardsWithoutGridParent() {
        String html = """
                <!DOCTYPE html><html><head><script src="https://cdn.tailwindcss.com"></script></head><body>
                <main>
                  <section>
                    <div class="rounded-lg shadow p-6 bg-white">Feature 1</div>
                    <div class="rounded-lg shadow p-6 bg-white">Feature 2</div>
                    <div class="rounded-lg shadow p-6 bg-white">Feature 3</div>
                    <div class="rounded-lg shadow p-6 bg-white">Feature 4</div>
                  </section>
                </main>
                </body></html>
                """;
        List<String> warnings = HtmlLayoutChecker.analyze(html);
        assertFalse(warnings.isEmpty());
        assertTrue(warnings.get(0).toLowerCase().contains("grid/flex"));
    }

    @Test
    void passesWhenParentHasGrid() {
        String html = """
                <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
                  <div class="rounded-lg shadow p-6">A</div>
                  <div class="rounded-lg shadow p-6">B</div>
                  <div class="rounded-lg shadow p-6">C</div>
                </div>
                """;
        List<String> warnings = HtmlLayoutChecker.analyze(html);
        assertTrue(warnings.stream().noneMatch(w -> w.contains("stacked")));
    }
}
