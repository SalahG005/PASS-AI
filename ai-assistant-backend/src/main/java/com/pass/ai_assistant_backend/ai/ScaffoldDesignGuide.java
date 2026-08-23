package com.pass.ai_assistant_backend.ai;

import java.util.Locale;

/**
 * Opinionated design defaults for static HTML/CSS scaffold output.
 * Local 7B models default to Arial + #4CAF50 without this; Tailwind CDN raises the quality ceiling.
 *
 * Note: PASS AI's "no cloud APIs" rule applies to AI inference only — loading Tailwind or Google Fonts
 * from a CDN for static preview HTML is allowed (the site may be offline after Apply if CDN is blocked).
 */
final class ScaffoldDesignGuide {

    static final String TAILWIND_CDN = "<script src=\"https://cdn.tailwindcss.com\"></script>";

    private ScaffoldDesignGuide() {
    }

    static boolean targetsStaticWeb(String message, String stack) {
        if (message == null) {
            message = "";
        }
        String m = message.toLowerCase(Locale.ROOT);
        if (stack != null) {
            String s = stack.toLowerCase(Locale.ROOT);
            if (s.contains("html") || s.contains("css") || s.contains("static") || s.contains("plain")) {
                return true;
            }
        }
        return m.contains("website") || m.contains("web site") || m.contains("site web")
                || m.contains("landing page") || m.contains("homepage") || m.contains("webpage")
                || m.contains("html") || m.contains("css") || m.contains("frontend")
                || m.contains("ui/ux") || m.contains("commercial") || m.contains("premium")
                || m.contains("coffee") || m.contains("shop") || m.contains("restaurant");
    }

    /** User explicitly wants hand-written CSS only (no Tailwind). */
    static boolean wantsPlainCssOnly(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("plain css") || m.contains("vanilla css") || m.contains("no tailwind")
                || m.contains("without tailwind") || m.contains("hand-written css") || m.contains("handwritten css")
                || m.contains("raw css") || m.contains("css only") || m.contains("sans tailwind");
    }

    static boolean isStyleFile(String path) {
        if (path == null) {
            return false;
        }
        String p = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        return p.endsWith("styles.css") || p.endsWith("style.css") || p.endsWith(".css");
    }

    static boolean isMarkupFile(String path) {
        if (path == null) {
            return false;
        }
        String p = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        return p.endsWith(".html") || p.endsWith(".htm");
    }

    /** Full design brief injected at scaffold start. */
    static String scaffoldDesignBrief(String userMessage) {
        if (userMessage == null || userMessage.isBlank() || !targetsStaticWeb(userMessage, null)) {
            return "";
        }
        return lockedDesignSystem(userMessage, null);
    }

    /** Locked design block — reused in every phase and one-file generation. */
    static String lockedDesignSystem(String userMessage, String planSummary) {
        if (!targetsStaticWeb(userMessage, null)) {
            return "";
        }
        boolean plainCss = wantsPlainCssOnly(userMessage);
        String palette = paletteFor(userMessage);
        StringBuilder sb = new StringBuilder();
        sb.append("""
                DESIGN SYSTEM — LOCKED for this project (every phase, every HTML/CSS file MUST follow):
                """);
        if (!plainCss) {
            sb.append("""
                MODE: Tailwind CSS via CDN (DEFAULT — use unless user asked for plain CSS only).
                - In EVERY index.html / page HTML: include in <head>:
                    <script src="https://cdn.tailwindcss.com"></script>
                  (Cloud AI is forbidden; static asset CDNs like Tailwind/Google Fonts ARE allowed for preview.)
                - Build layout with Tailwind utilities (flex, grid, gap-*, p-*, max-w-*, rounded-*, shadow-*).
                - Keep styles.css small: :root tokens + a few custom overrides only — do NOT rewrite Tailwind in raw CSS.
                """);
        } else {
            sb.append("""
                MODE: Plain CSS (user requested — no Tailwind).
                - styles.css MUST define ALL styling. index.html links styles.css only.
                """);
        }
        sb.append("""
                
                TYPOGRAPHY (not browser default):
                - Link ONE Google Font in <head>, e.g.:
                    <link rel="preconnect" href="https://fonts.googleapis.com">
                    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;600;700&display=swap" rel="stylesheet">
                - Body stack: 'Inter', system-ui, -apple-system, 'Segoe UI', sans-serif
                - Headings: font-bold, text-3xl/4xl/5xl (Tailwind) or clamp(1.75rem, 4vw, 3rem) (plain CSS)
                - line-height: 1.5–1.7 for body text
                
                COLOR PALETTE — define ONCE in styles.css :root (use these exact roles):
                """);
        sb.append(palette);
        sb.append("""

                
                SPACING SCALE — use ONLY these steps (8px base), never random px like 13px or 37px:
                - --space-1: 8px   --space-2: 16px   --space-3: 24px   --space-4: 32px   --space-5: 48px   --space-6: 64px
                (Tailwind: prefer p-2/p-4/p-6/p-8/gap-4/gap-6 which map to this scale)
                
                LAYOUT (no floats):
                - max-width container: max-w-6xl mx-auto px-4 (Tailwind) or max-width: 72rem; margin: 0 auto; padding: 0 1rem;
                - Sections: semantic <header> <nav> <main> <section> <footer>
                - Hero + feature grid + CTA + footer; cards use rounded corners + subtle shadow
                
                RESPONSIVE — at least one breakpoint:
                - Mobile-first; stack columns on small screens; @media (min-width: 768px) for tablet/desktop
                - Tailwind: md: and lg: prefixes on grid/flex/typography
                
                CONTENT QUALITY:
                - NO "Lorem ipsum" walls. Write short realistic copy for the business (tagline, 3 features, CTA).
                - NO unstyled default browser look (bare <h1> on white with Times New Roman).
                - NEVER default to Arial + #4CAF50 green — that is forbidden.
                
                PHASE CONSISTENCY:
                - Establish styles.css (or Tailwind config in first HTML) in phase 1 BEFORE other pages.
                - Later phases: read/reuse the SAME palette, font, and spacing — do not invent a new theme.
                """);
        if (planSummary != null && !planSummary.isBlank()) {
            sb.append("\nPlan design notes: ").append(truncate(planSummary, 500)).append('\n');
        }
        return sb.toString();
    }

    /**
     * Short reminder for tool-loop turns after the full design brief was sent once at scaffold start.
     * Avoids re-sending ~2.5k chars on every write step.
     */
    static String lockedDesignReminder(String userMessage, String planSummary) {
        if (!targetsStaticWeb(userMessage, null)) {
            return "";
        }
        String mode = wantsPlainCssOnly(userMessage) ? "plain CSS" : "Tailwind CDN + styles.css :root tokens";
        StringBuilder sb = new StringBuilder();
        sb.append("DESIGN REMINDER — reuse the locked design from scaffold start (same palette, font, spacing): ")
                .append(mode).append(", Inter font, ").append(paletteHintShort(userMessage)).append('.');
        if (planSummary != null && !planSummary.isBlank()) {
            sb.append(" Plan notes: ").append(truncate(planSummary, 200));
        }
        return sb.toString();
    }

    /** Instruct submit_plan to record design in summary. */
    static String submitPlanDesignField(String userMessage) {
        if (!targetsStaticWeb(userMessage, null)) {
            return "";
        }
        String mode = wantsPlainCssOnly(userMessage) ? "plain CSS" : "Tailwind CDN + styles.css tokens";
        return """
                
                submit_plan REQUIRED for static sites: put a "design" line in summary, e.g.
                summary: "Design: %s | Font: Inter | Palette: primary/background/text/accent | Spacing: 8/16/24/32px scale"
                Phase 1 files MUST include styles.css (tokens) and index.html (%s) before any other pages.
                """.formatted(mode, wantsPlainCssOnly(userMessage) ? "plain CSS" : "Tailwind CDN in head");
    }

    static String oneFileDesignContext(String path, String userMessage, String existingStylesCss, String lockedDesign) {
        if (!isStyleFile(path) && !isMarkupFile(path)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (lockedDesign != null && !lockedDesign.isBlank()) {
            sb.append(lockedDesign);
        } else {
            sb.append(scaffoldDesignBrief(userMessage));
        }
        if (isMarkupFile(path) && existingStylesCss != null && !existingStylesCss.isBlank()) {
            sb.append("\nEXISTING styles.css — reuse these :root tokens (do NOT change palette):\n")
                    .append(truncate(existingStylesCss, 3500))
                    .append('\n');
        }
        if (isStyleFile(path)) {
            sb.append(wantsPlainCssOnly(userMessage)
                    ? plainCssFileChecklist()
                    : tailwindCompanionCssChecklist());
        }
        if (isMarkupFile(path)) {
            sb.append(wantsPlainCssOnly(userMessage)
                    ? plainHtmlChecklist()
                    : tailwindHtmlChecklist());
        }
        return sb.toString();
    }

    /** Short design reminder for one-by-one fill after the first file (avoids repeating full locked block). */
    static String oneFileDesignContextShort(
            String path,
            String userMessage,
            String existingStylesCss,
            String designReminder
    ) {
        if (!isStyleFile(path) && !isMarkupFile(path)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (designReminder != null && !designReminder.isBlank()) {
            sb.append(designReminder).append('\n');
        }
        if (isMarkupFile(path) && existingStylesCss != null && !existingStylesCss.isBlank()) {
            sb.append("EXISTING styles.css tokens (reuse):\n")
                    .append(truncate(existingStylesCss, 1200))
                    .append('\n');
        }
        if (isStyleFile(path)) {
            sb.append(wantsPlainCssOnly(userMessage)
                    ? plainCssFileChecklist()
                    : tailwindCompanionCssChecklist());
        } else if (isMarkupFile(path)) {
            sb.append(wantsPlainCssOnly(userMessage)
                    ? plainHtmlChecklist()
                    : tailwindHtmlChecklist());
        }
        return sb.toString();
    }

    static String phaseDesignContinuity(String lockedDesign, String stylesCssSnippet) {
        StringBuilder sb = new StringBuilder();
        if (lockedDesign != null && !lockedDesign.isBlank()) {
            sb.append(lockedDesign);
        } else {
            sb.append("""
                    DESIGN CONTINUITY: Reuse phase-1 palette, font, and spacing. Do NOT introduce a new theme.
                    """);
        }
        if (stylesCssSnippet != null && !stylesCssSnippet.isBlank()) {
            sb.append("\nCurrent styles.css (:root tokens — keep identical):\n")
                    .append(truncate(stylesCssSnippet, 2500))
                    .append('\n');
        }
        return sb.toString();
    }

    /** Old minimal prompt (for comparison docs/tests). */
    static String legacyMinimalDesignHint() {
        return "Create index.html, styles.css, script.js for the requested site.";
    }

    private static String paletteHintShort(String userMessage) {
        String m = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
        if (m.contains("coffee") || m.contains("café") || m.contains("cafe")) {
            return "coffee palette (#6f4e37 primary, cream bg)";
        }
        if (m.contains("gym") || m.contains("fitness")) {
            return "dark fitness palette (#ef4444 accent)";
        }
        return "default slate palette (--color-primary #2563eb)";
    }

    private static String paletteFor(String userMessage) {
        String m = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
        if (m.contains("coffee") || m.contains("café") || m.contains("cafe")) {
            return """
                    --color-bg: #faf7f2;
                    --color-surface: #ffffff;
                    --color-text: #2c1810;
                    --color-muted: #6b5344;
                    --color-primary: #6f4e37;
                    --color-accent: #c8a97e;
                    (warm coffee shop — earthy browns + cream)""";
        }
        if (m.contains("gym") || m.contains("fitness")) {
            return """
                    --color-bg: #0f1419;
                    --color-surface: #1a2332;
                    --color-text: #f0f4f8;
                    --color-muted: #94a3b8;
                    --color-primary: #ef4444;
                    --color-accent: #f97316;
                    (bold dark fitness — high contrast)""";
        }
        return """
                --color-bg: #f8fafc;
                --color-surface: #ffffff;
                --color-text: #0f172a;
                --color-muted: #64748b;
                --color-primary: #2563eb;
                --color-accent: #0ea5e9;
                (clean professional default)""";
    }

    private static String plainCssFileChecklist() {
        return """
                
                styles.css CHECKLIST:
                1) :root { palette + spacing vars above }
                2) *, *::before, *::after { box-sizing: border-box; }
                3) body { font-family; background; color; line-height: 1.6; margin: 0; }
                4) .container { max-width: 72rem; margin: 0 auto; padding: 0 var(--space-2); }
                5) Components: .btn, .card, .section, nav, footer with hover/focus states
                6) @media (min-width: 768px) { ... }
                """;
    }

    private static String tailwindCompanionCssChecklist() {
        return """
                
                styles.css CHECKLIST (Tailwind companion — keep SHORT):
                1) :root { palette vars for any custom color not in Tailwind config }
                2) Optional: body { font-family: 'Inter', system-ui, sans-serif; }
                3) Do NOT duplicate what Tailwind utilities already provide
                """;
    }

    private static String plainHtmlChecklist() {
        return """
                
                index.html CHECKLIST: viewport meta, Google Font link, link rel=stylesheet href=styles.css,
                semantic sections, realistic copy, .container wrapper, NO lorem ipsum.
                """;
    }

    private static String tailwindHtmlChecklist() {
        return """
                
                index.html CHECKLIST: viewport meta, Google Font link, Tailwind CDN script in <head>,
                link styles.css for :root tokens, build with Tailwind utility classes, realistic copy, NO lorem ipsum.
                """;
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, max) + "\n…[truncated]";
    }
}
