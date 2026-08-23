package com.pass.ai_assistant_backend.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScaffoldDesignGuideTest {

    @Test
    void detectsStaticWebFromGymPrompt() {
        String msg = "Build a premium commercial gym website with hero, pricing, testimonials";
        assertTrue(ScaffoldDesignGuide.targetsStaticWeb(msg, null));
        assertFalse(ScaffoldDesignGuide.scaffoldDesignBrief(msg).isBlank());
        assertTrue(ScaffoldDesignGuide.scaffoldDesignBrief(msg).contains("Tailwind"));
        assertTrue(ScaffoldDesignGuide.scaffoldDesignBrief(msg).contains("--space-1: 8px"));
    }

    @Test
    void coffeeShopGetsWarmPalette() {
        String msg = "a landing page for a coffee shop";
        String design = ScaffoldDesignGuide.lockedDesignSystem(msg, null);
        assertTrue(design.contains("--color-primary: #6f4e37"));
        assertTrue(design.contains("cdn.tailwindcss.com"));
    }

    @Test
    void plainCssWhenRequested() {
        String msg = "plain CSS landing page for a coffee shop, no tailwind";
        assertTrue(ScaffoldDesignGuide.wantsPlainCssOnly(msg));
        String design = ScaffoldDesignGuide.lockedDesignSystem(msg, null);
        assertTrue(design.contains("Plain CSS"));
        assertFalse(design.contains("Tailwind CSS via CDN (DEFAULT"));
    }

    @Test
    void skipsDesignBriefForSpringOnly() {
        String msg = "Create a Spring Boot REST API for todos";
        assertFalse(ScaffoldDesignGuide.targetsStaticWeb(msg, "Spring Boot"));
        assertTrue(ScaffoldDesignGuide.scaffoldDesignBrief(msg).isBlank());
    }

    @Test
    void oneFileContextForHtmlIncludesExistingCss() {
        String css = ":root { --color-primary: #111; }";
        String locked = ScaffoldDesignGuide.lockedDesignSystem("coffee shop site", null);
        String ctx = ScaffoldDesignGuide.oneFileDesignContext("index.html", "coffee shop", css, locked);
        assertTrue(ctx.contains("EXISTING styles.css"));
        assertTrue(ctx.contains("--color-primary"));
    }

    @Test
    void lockedDesignReminderIsShortAndSpecific() {
        String msg = "a landing page for a coffee shop";
        String full = ScaffoldDesignGuide.lockedDesignSystem(msg, null);
        String reminder = ScaffoldDesignGuide.lockedDesignReminder(msg, null);
        assertFalse(reminder.isBlank());
        assertTrue(reminder.length() < full.length() / 3,
                "reminder should be much shorter than full design block");
        assertTrue(reminder.contains("coffee palette"));
        assertTrue(reminder.contains("Tailwind"));
        assertFalse(reminder.contains("--space-1: 8px"));
    }

    @Test
    void submitPlanRequiresDesignInSummary() {
        String hint = ScaffoldDesignGuide.submitPlanDesignField("build a website");
        assertTrue(hint.contains("Design:"));
        assertTrue(hint.contains("styles.css"));
    }
}
