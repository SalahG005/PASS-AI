package com.pass.ai_assistant_backend.ai;

import com.pass.ai_assistant_backend.ai.dto.FileProposalDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses agent replies for pass-file fences and a few common fallbacks.
 */
public final class PassFileBlockParser {

    private static final Pattern BLOCK = Pattern.compile(
            "```pass-file\\s+([^\\n`]*?)\\n(.*?)```",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern XML_BLOCK = Pattern.compile(
            "<pass-file\\s+([^>]+)>(.*?)</pass-file>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    /** ```hello.txt / create — models sometimes skip the pass-file tag */
    private static final Pattern FENCE_NAMED = Pattern.compile(
            "```(?:create|overwrite|file|text|txt)?\\s*([\\w./\\\\-]+\\.[\\w]+)\\s*\\n(.*?)```",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern PATH = Pattern.compile(
            "path\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ACTION = Pattern.compile(
            "action\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
    );

    private PassFileBlockParser() {
    }

    public record ParseResult(String replyWithoutBlocks, List<FileProposalDto> files) {
    }

    public static ParseResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParseResult("", List.of());
        }
        Map<String, FileProposalDto> byPath = new LinkedHashMap<>();
        String working = raw;

        working = consume(working, BLOCK, byPath, true);
        working = consumeXml(working, byPath);
        if (byPath.isEmpty()) {
            working = consumeNamedFences(raw, byPath);
        }

        String cleaned = byPath.isEmpty() ? raw.trim() : working.trim();
        return new ParseResult(cleaned, new ArrayList<>(byPath.values()));
    }

    private static String consume(
            String raw,
            Pattern blockPattern,
            Map<String, FileProposalDto> byPath,
            boolean requirePathAttr
    ) {
        Matcher matcher = blockPattern.matcher(raw);
        StringBuffer cleaned = new StringBuffer();
        while (matcher.find()) {
            String header = matcher.group(1) == null ? "" : matcher.group(1).trim();
            String content = trimContent(matcher.group(2));
            String path = extract(PATH, header);
            if ((path == null || path.isBlank()) && requirePathAttr) {
                matcher.appendReplacement(cleaned, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            if (path == null || path.isBlank()) {
                path = header;
            }
            path = path.replace('\\', '/').trim();
            String action = normalizeAction(extract(ACTION, header));
            byPath.put(path, new FileProposalDto(path, action, content));
            matcher.appendReplacement(cleaned, Matcher.quoteReplacement("\n[Proposed file: " + path + " (" + action + ")]\n"));
        }
        matcher.appendTail(cleaned);
        return cleaned.toString();
    }

    private static String consumeXml(String raw, Map<String, FileProposalDto> byPath) {
        Matcher matcher = XML_BLOCK.matcher(raw);
        StringBuffer cleaned = new StringBuffer();
        while (matcher.find()) {
            String header = matcher.group(1) == null ? "" : matcher.group(1).trim();
            String content = trimContent(matcher.group(2));
            String path = extract(PATH, header);
            if (path == null || path.isBlank()) {
                matcher.appendReplacement(cleaned, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            path = path.replace('\\', '/').trim();
            String action = normalizeAction(extract(ACTION, header));
            byPath.put(path, new FileProposalDto(path, action, content));
            matcher.appendReplacement(cleaned, Matcher.quoteReplacement("\n[Proposed file: " + path + " (" + action + ")]\n"));
        }
        matcher.appendTail(cleaned);
        return cleaned.toString();
    }

    private static String consumeNamedFences(String raw, Map<String, FileProposalDto> byPath) {
        Matcher matcher = FENCE_NAMED.matcher(raw);
        StringBuffer cleaned = new StringBuffer();
        while (matcher.find()) {
            String path = matcher.group(1).replace('\\', '/').trim();
            if (path.equalsIgnoreCase("bash") || path.equalsIgnoreCase("sh")
                    || path.equalsIgnoreCase("shell") || path.equalsIgnoreCase("powershell")
                    || path.equalsIgnoreCase("cmd") || path.equalsIgnoreCase("zsh")) {
                matcher.appendReplacement(cleaned, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            String content = trimContent(matcher.group(2));
            byPath.put(path, new FileProposalDto(path, "create", content));
            matcher.appendReplacement(cleaned, Matcher.quoteReplacement("\n[Proposed file: " + path + " (create)]\n"));
        }
        matcher.appendTail(cleaned);
        return cleaned.toString();
    }

    private static String normalizeAction(String action) {
        if (action == null || action.isBlank()) {
            return "create";
        }
        String a = action.trim().toLowerCase(Locale.ROOT);
        if (!a.equals("create") && !a.equals("overwrite")) {
            return "create";
        }
        return a;
    }

    private static String trimContent(String content) {
        if (content == null) {
            return "";
        }
        if (content.endsWith("\n")) {
            return content.substring(0, content.length() - 1);
        }
        return content;
    }

    private static String extract(Pattern pattern, String header) {
        Matcher m = pattern.matcher(header);
        return m.find() ? m.group(1) : null;
    }
}
