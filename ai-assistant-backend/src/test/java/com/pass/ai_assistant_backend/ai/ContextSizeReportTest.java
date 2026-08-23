package com.pass.ai_assistant_backend.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Documents context-size savings for Stage 1 fix #1 reporting. */
class ContextSizeReportTest {

    @Test
    void reportsCoffeeShopContextReduction() {
        String msg = "Landing page for a coffee shop — static HTML only. Files: index.html, styles.css, script.js";
        String full = ScaffoldDesignGuide.lockedDesignSystem(msg, null);
        String reminder = ScaffoldDesignGuide.lockedDesignReminder(msg, "Coffee shop landing page");
        int scaffoldPrompt = AgentMode.SCAFFOLD.systemPrompt().length();
        int beforePerTurn = scaffoldPrompt + full.length();
        int afterPerTurn = scaffoldPrompt + reminder.length();

        // Simulate step 5: plan + 3 file writes + tool dumps (~8k each before cap)
        int toolDumpBefore = 8000;
        int toolDumpAfter = 2500; // compactMessage cap
        int beforeStep5 = beforePerTurn + 4 * (beforePerTurn + toolDumpBefore);
        int afterStep5 = beforePerTurn + full.length() // once after submit_plan
                + 4 * (afterPerTurn + toolDumpAfter);

        System.out.println("SCAFFOLD system prompt: " + scaffoldPrompt + " chars");
        System.out.println("lockedDesignSystem full: " + full.length() + " chars");
        System.out.println("lockedDesignReminder:    " + reminder.length() + " chars");
        System.out.println("Before ~per-turn payload:  " + beforePerTurn + " chars");
        System.out.println("After ~per-turn payload:   " + afterPerTurn + " chars");
        System.out.println("Before ~step-5 total:      ~" + beforeStep5 + " chars");
        System.out.println("After ~step-5 total:       ~" + afterStep5 + " chars");
        System.out.println("Reduction at step 5:       ~" + String.format("%.1f", (double) beforeStep5 / afterStep5) + "x");

        assertTrue(reminder.length() < full.length() / 3);
        assertTrue(afterStep5 < beforeStep5);
        assertTrue(afterPerTurn < beforePerTurn);
    }
}
