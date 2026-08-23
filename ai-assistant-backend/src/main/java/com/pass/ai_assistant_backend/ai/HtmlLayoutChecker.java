package com.pass.ai_assistant_backend.ai;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Cheap structural HTML checks (no browser). Catches common scaffold failures like
 * card sections stacked full-width because the parent lacks grid/flex layout classes.
 */
final class HtmlLayoutChecker {

    private static final Pattern CARD_HINT = Pattern.compile(
            "(?i)(\\bcard\\b|\\brounded|\\bshadow|\\bp-[4568]|\\bp-6|\\bp-8|feature|pricing|testimonial)");
    private static final Pattern LAYOUT_HINT = Pattern.compile(
            "(?i)(\\bgrid\\b|\\bflex\\b|grid-cols|flex-wrap|flex-row|md:grid|lg:grid)");

    private HtmlLayoutChecker() {
    }

    static List<String> analyze(String html) {
        List<String> warnings = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return warnings;
        }
        Document doc;
        try {
            doc = Jsoup.parse(html);
        } catch (Exception e) {
            warnings.add("Could not parse HTML for layout check.");
            return warnings;
        }

        checkStackedCards(doc, warnings);
        checkEmptyMain(doc, warnings);
        return warnings;
    }

    private static void checkStackedCards(Document doc, List<String> warnings) {
        Map<Element, List<Element>> byParent = new HashMap<>();
        Elements candidates = doc.select("div, section, article");
        for (Element el : candidates) {
            String cls = el.className();
            if (cls == null || cls.isBlank()) {
                continue;
            }
            if (!CARD_HINT.matcher(cls).find()) {
                continue;
            }
            Element parent = el.parent();
            if (parent == null || "body".equalsIgnoreCase(parent.tagName())) {
                continue;
            }
            byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(el);
        }

        for (Map.Entry<Element, List<Element>> e : byParent.entrySet()) {
            List<Element> cards = e.getValue();
            if (cards.size() < 3) {
                continue;
            }
            Element parent = e.getKey();
            String parentClass = parent.className() == null ? "" : parent.className();
            if (LAYOUT_HINT.matcher(parentClass).find()) {
                continue;
            }
            String parentTag = parent.tagName().toLowerCase(Locale.ROOT);
            String sample = cards.get(0).className();
            warnings.add("Layout: " + cards.size() + " card-like elements (<" + parentTag
                    + " class=\"" + truncate(parentClass, 60) + "\">) lack grid/flex on the parent — "
                    + "likely stacked full-width instead of a row/grid (e.g. add grid grid-cols-1 md:grid-cols-3 gap-6). "
                    + "Example child classes: \"" + truncate(sample, 80) + "\".");
        }
    }

    private static void checkEmptyMain(Document doc, List<String> warnings) {
        Element main = doc.selectFirst("main");
        if (main != null && main.text().trim().length() < 20) {
            warnings.add("Layout: <main> has very little visible text — page may render mostly empty.");
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
