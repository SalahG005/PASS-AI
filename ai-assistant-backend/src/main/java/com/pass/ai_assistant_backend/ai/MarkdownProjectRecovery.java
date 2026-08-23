package com.pass.ai_assistant_backend.ai;

import com.pass.ai_assistant_backend.ai.dto.FileProposalDto;
import com.pass.ai_assistant_backend.ai.dto.ProjectPlanDto;
import com.pass.ai_assistant_backend.ai.dto.ProjectPlanFileDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recovers project plans + file contents when the model dumps Markdown instead of pass-tool JSON.
 * Typical pattern:
 * <pre>
 * #### `pom.xml`
 * ```xml
 * ...
 * ```
 * </pre>
 */
public final class MarkdownProjectRecovery {

    private static final Set<String> LANG_TAGS = Set.of(
            "xml", "java", "kt", "kotlin", "js", "javascript", "ts", "typescript",
            "tsx", "jsx", "py", "python", "html", "css", "scss", "json", "yml", "yaml",
            "md", "markdown", "sql", "properties", "gradle", "groovy", "bash", "sh",
            "shell", "powershell", "cmd", "text", "txt", "toml", "ini", "dockerfile",
            "c", "cpp", "h", "cs", "go", "rs", "rust", "php", "rb", "swift"
    );

    /** #### `path/to/File.java` or ## File: `pom.xml` */
    private static final Pattern HEADING_PATH = Pattern.compile(
            "(?m)^#{1,6}\\s+(?:File:?\\s*)?`([^`\\n]+)`[^\\n]*$"
    );

    /** - `pom.xml` (Maven Build) */
    private static final Pattern BULLET_PATH = Pattern.compile(
            "(?m)^\\s*[-*]\\s+`([^`\\n]+)`(?:\\s*[—(]\\s*([^)\\n]+)\\)?)?"
    );

    private MarkdownProjectRecovery() {
    }

    public record Recovery(ProjectPlanDto plan, List<FileProposalDto> files, String cleanedText) {
    }

    public static Recovery recover(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Recovery(null, List.of(), "");
        }

        Map<String, FileProposalDto> files = new LinkedHashMap<>();
        String working = raw;
        Matcher headings = HEADING_PATH.matcher(raw);
        List<int[]> headingSpans = new ArrayList<>();
        List<String> headingPaths = new ArrayList<>();
        while (headings.find()) {
            String path = normalizePath(headings.group(1));
            if (!looksLikePath(path)) {
                continue;
            }
            headingPaths.add(path);
            headingSpans.add(new int[]{headings.start(), headings.end()});
        }

        for (int i = 0; i < headingPaths.size(); i++) {
            String path = headingPaths.get(i);
            int searchFrom = headingSpans.get(i)[1];
            int searchTo = i + 1 < headingSpans.size() ? headingSpans.get(i + 1)[0] : raw.length();
            Fence fence = findFence(raw, searchFrom, searchTo);
            if (fence == null) {
                continue;
            }
            // Skip language-only "paths" mistaken as files when fence lang equals path
            if (LANG_TAGS.contains(path.toLowerCase(Locale.ROOT))) {
                continue;
            }
            files.put(path, new FileProposalDto(path, "create", fence.content()));
        }

        ProjectPlanDto plan = buildPlan(raw, files);
        String cleaned = files.isEmpty() ? raw.trim() : stripRecoveredNoise(working, files.keySet());
        return new Recovery(plan, new ArrayList<>(files.values()), cleaned);
    }

    private static ProjectPlanDto buildPlan(String raw, Map<String, FileProposalDto> files) {
        Map<String, String> purposes = new LinkedHashMap<>();
        Matcher bullets = BULLET_PATH.matcher(raw);
        while (bullets.find()) {
            String path = normalizePath(bullets.group(1));
            if (!looksLikePath(path)) {
                continue;
            }
            String purpose = bullets.group(2) == null ? "" : bullets.group(2).trim();
            purposes.putIfAbsent(path, purpose);
        }
        for (String path : files.keySet()) {
            purposes.putIfAbsent(path, "");
        }
        if (purposes.isEmpty()) {
            return null;
        }
        ProjectPlanDto plan = new ProjectPlanDto();
        plan.setTitle("Recovered project plan");
        plan.setSummary("Parsed from model Markdown (pass-tool missing).");
        List<ProjectPlanFileDto> list = new ArrayList<>();
        for (Map.Entry<String, String> e : purposes.entrySet()) {
            ProjectPlanFileDto f = new ProjectPlanFileDto(e.getKey(), e.getValue());
            if (files.containsKey(e.getKey())) {
                f.setStatus("proposed");
            }
            list.add(f);
        }
        plan.setFiles(list);
        return plan;
    }

    private static Fence findFence(String raw, int from, int to) {
        int open = indexOfFenceOpen(raw, from, to);
        if (open < 0) {
            return null;
        }
        int lineEnd = raw.indexOf('\n', open);
        if (lineEnd < 0 || lineEnd >= to) {
            return null;
        }
        int contentStart = lineEnd + 1;
        int depth = 1;
        int i = contentStart;
        while (i < to && depth > 0) {
            int next = raw.indexOf("```", i);
            if (next < 0 || next >= to) {
                return null;
            }
            int lineStart = raw.lastIndexOf('\n', next) + 1;
            // Only treat fence markers at line start
            if (lineStart != next && !(lineStart == 0 && next == 0)) {
                // allow if only whitespace before ```
                String before = raw.substring(lineStart, next);
                if (!before.isBlank()) {
                    i = next + 3;
                    continue;
                }
            }
            int after = next + 3;
            while (after < raw.length() && (raw.charAt(after) == ' ' || raw.charAt(after) == '\t')) {
                after++;
            }
            int tagEnd = after;
            while (tagEnd < raw.length()) {
                char c = raw.charAt(tagEnd);
                if (c == '\n' || c == '\r' || c == ' ' || c == '\t') {
                    break;
                }
                tagEnd++;
            }
            String tag = raw.substring(after, tagEnd).trim().toLowerCase(Locale.ROOT);
            boolean isClose = tag.isEmpty();
            if (isClose) {
                depth--;
                if (depth == 0) {
                    String content = raw.substring(contentStart, next);
                    if (content.endsWith("\n")) {
                        content = content.substring(0, content.length() - 1);
                    }
                    return new Fence(content);
                }
            } else {
                // nested open (e.g. ```sh inside markdown)
                depth++;
            }
            i = Math.max(tagEnd, next + 3);
        }
        return null;
    }

    private static int indexOfFenceOpen(String raw, int from, int to) {
        int i = from;
        while (i < to) {
            int next = raw.indexOf("```", i);
            if (next < 0 || next >= to) {
                return -1;
            }
            int lineStart = raw.lastIndexOf('\n', next) + 1;
            String before = raw.substring(lineStart, next);
            if (!before.isBlank()) {
                i = next + 3;
                continue;
            }
            return next;
        }
        return -1;
    }

    private static boolean looksLikePath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String p = path.trim();
        if (p.contains("://") || p.startsWith("#")) {
            return false;
        }
        if (LANG_TAGS.contains(p.toLowerCase(Locale.ROOT))) {
            return false;
        }
        // must look like a file path (has extension or known build file)
        String lower = p.toLowerCase(Locale.ROOT);
        if (lower.equals("dockerfile") || lower.equals("makefile") || lower.equals("gemfile")) {
            return true;
        }
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        String name = slash >= 0 ? p.substring(slash + 1) : p;
        return name.contains(".");
    }

    private static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/').trim();
    }

    private static String stripRecoveredNoise(String raw, Iterable<String> paths) {
        String s = raw;
        for (String path : paths) {
            s = s.replace(path, "[file:" + path + "]");
        }
        if (s.length() > 1200) {
            return s.substring(0, 1200) + "…";
        }
        return s.trim();
    }

    private record Fence(String content) {
    }
}
