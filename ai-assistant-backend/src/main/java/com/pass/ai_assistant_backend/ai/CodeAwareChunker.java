package com.pass.ai_assistant_backend.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits source into structure-aware chunks (class / method / function) with metadata,
 * falling back to paragraph then character windows for oversized or unknown files.
 */
public final class CodeAwareChunker {

    private CodeAwareChunker() {
    }

    public record CodeChunk(
            String content,
            String language,
            String symbolKind,
            String symbolName,
            String parentSymbol,
            int startLine,
            int endLine
    ) {
    }

    public static List<CodeChunk> chunk(String path, String content, int maxChars, int overlap) {
        String language = languageFromPath(path);
        List<CodeChunk> structural = switch (language) {
            case "java" -> chunkJavaLike(content, language, maxChars, overlap);
            case "typescript", "javascript" -> chunkJsTs(content, language, maxChars, overlap);
            case "python" -> chunkPython(content, language, maxChars, overlap);
            case "markdown" -> chunkMarkdown(content, language, maxChars, overlap);
            default -> List.of();
        };
        if (!structural.isEmpty()) {
            return structural;
        }
        return fallbackChunks(content, language, maxChars, overlap);
    }

    static String languageFromPath(String path) {
        if (path == null) {
            return "text";
        }
        String name = path.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "text";
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "java" -> "java";
            case "ts", "tsx" -> "typescript";
            case "js", "jsx", "mjs", "cjs" -> "javascript";
            case "py" -> "python";
            case "md", "markdown" -> "markdown";
            case "json" -> "json";
            case "xml" -> "xml";
            case "html", "htm" -> "html";
            case "css", "scss" -> "css";
            case "yml", "yaml" -> "yaml";
            case "properties" -> "properties";
            case "kt" -> "kotlin";
            case "cs" -> "csharp";
            case "go" -> "go";
            case "rs" -> "rust";
            case "sql" -> "sql";
            default -> ext;
        };
    }

    private static final Pattern JAVA_TYPE = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|protected|private|abstract|final|sealed|static|strictfp)\\s+)*"
                    + "(class|interface|enum|record)\\s+([A-Za-z_][\\w$]*)"
    );
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|protected|private|static|final|synchronized|abstract|native|default)\\s+)+"
                    + "(?:<[\\w\\s,?]+>\\s+)?"
                    + "[\\w.$\\[\\]<>,?\\s]+\\s+"
                    + "([A-Za-z_][\\w$]*)\\s*\\([^;]*\\)\\s*(?:throws[^{]*)?\\{"
    );

    private static List<CodeChunk> chunkJavaLike(String content, String language, int maxChars, int overlap) {
        List<CodeChunk> out = new ArrayList<>();
        Matcher typeMatcher = JAVA_TYPE.matcher(content);
        List<TypeSpan> typeSpans = new ArrayList<>();
        while (typeMatcher.find()) {
            int bodyStart = findBlockStart(content, typeMatcher.end() - 1);
            if (bodyStart < 0) {
                continue;
            }
            int bodyEnd = matchBrace(content, bodyStart);
            if (bodyEnd < 0) {
                bodyEnd = content.length();
            } else {
                bodyEnd++;
            }
            typeSpans.add(new TypeSpan(typeMatcher.group(1), typeMatcher.group(2), typeMatcher.start(), bodyEnd));
        }

        if (typeSpans.isEmpty()) {
            return splitByMethodsOrFallback(content, language, null, maxChars, overlap, JAVA_METHOD);
        }

        for (TypeSpan type : typeSpans) {
            String typeBody = content.substring(type.start, type.end);
            List<CodeChunk> methods = extractBracedMembers(
                    content, typeBody, type.start, language, "method", type.name, maxChars, overlap, JAVA_METHOD
            );
            if (methods.isEmpty()) {
                out.addAll(splitOversized(
                        typeBody, language, type.kind, type.name, null,
                        lineAt(content, type.start), lineAt(content, type.end - 1),
                        maxChars, overlap
                ));
            } else {
                // Preamble (imports + type header before first method) as its own chunk if meaningful
                CodeChunk first = methods.get(0);
                int firstAbs = offsetOfLine(content, first.startLine());
                if (firstAbs > type.start + 40) {
                    String preamble = content.substring(type.start, Math.min(firstAbs, type.end)).trim();
                    if (preamble.length() > 40) {
                        out.addAll(splitOversized(
                                preamble, language, type.kind, type.name, null,
                                lineAt(content, type.start), lineAt(content, firstAbs),
                                maxChars, overlap
                        ));
                    }
                }
                out.addAll(methods);
            }
        }
        // Leading file header (package/imports) before first type
        TypeSpan first = typeSpans.get(0);
        if (first.start > 20) {
            String header = content.substring(0, first.start).trim();
            if (!header.isBlank()) {
                out.add(0, new CodeChunk(
                        header, language, "module", fileStem(null), null,
                        1, lineAt(content, first.start)
                ));
            }
        }
        return out.isEmpty() ? fallbackChunks(content, language, maxChars, overlap) : out;
    }

    private static final Pattern JS_CLASS = Pattern.compile(
            "(?m)^[ \\t]*(?:export\\s+)?(?:default\\s+)?class\\s+([A-Za-z_][\\w$]*)"
    );
    private static final Pattern JS_FUNC = Pattern.compile(
            "(?m)^[ \\t]*(?:export\\s+)?(?:async\\s+)?function\\*?\\s+([A-Za-z_][\\w$]*)\\s*\\("
                    + "|(?m)^[ \\t]*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_][\\w$]*)\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_][\\w$]*)\\s*=>"
    );

    private static List<CodeChunk> chunkJsTs(String content, String language, int maxChars, int overlap) {
        List<CodeChunk> out = new ArrayList<>();
        Matcher classMatcher = JS_CLASS.matcher(content);
        List<TypeSpan> classes = new ArrayList<>();
        while (classMatcher.find()) {
            int bodyStart = findBlockStart(content, classMatcher.end() - 1);
            if (bodyStart < 0) {
                continue;
            }
            int bodyEnd = matchBrace(content, bodyStart);
            if (bodyEnd < 0) {
                bodyEnd = content.length();
            } else {
                bodyEnd++;
            }
            classes.add(new TypeSpan("class", classMatcher.group(1), classMatcher.start(), bodyEnd));
        }

        if (!classes.isEmpty()) {
            for (TypeSpan c : classes) {
                String body = content.substring(c.start, c.end);
                out.addAll(splitOversized(
                        body, language, "class", c.name, null,
                        lineAt(content, c.start), lineAt(content, c.end - 1),
                        maxChars, overlap
                ));
            }
        }

        Matcher fn = JS_FUNC.matcher(content);
        while (fn.find()) {
            String name = fn.group(1) != null ? fn.group(1) : fn.group(2);
            int start = fn.start();
            // skip if inside a class span we already captured
            boolean inside = false;
            for (TypeSpan c : classes) {
                if (start >= c.start && start < c.end) {
                    inside = true;
                    break;
                }
            }
            if (inside) {
                continue;
            }
            int bodyStart = content.indexOf('{', fn.end() - 1);
            int end;
            if (bodyStart >= 0 && bodyStart - fn.end() < 120) {
                end = matchBrace(content, bodyStart);
                end = end < 0 ? Math.min(content.length(), start + maxChars) : end + 1;
            } else {
                // arrow one-liner / no brace
                int semi = content.indexOf('\n', fn.end());
                end = semi < 0 ? content.length() : semi;
            }
            String piece = content.substring(start, Math.min(end, content.length()));
            out.addAll(splitOversized(
                    piece, language, "function", name, null,
                    lineAt(content, start), lineAt(content, Math.min(end, content.length()) - 1),
                    maxChars, overlap
            ));
        }
        return out.isEmpty() ? fallbackChunks(content, language, maxChars, overlap) : out;
    }

    private static final Pattern PY_BLOCK = Pattern.compile(
            "(?m)^( *)(class|def|async def)\\s+([A-Za-z_][\\w]*)\\s*[(:]"
    );

    private static List<CodeChunk> chunkPython(String content, String language, int maxChars, int overlap) {
        List<CodeChunk> out = new ArrayList<>();
        Matcher m = PY_BLOCK.matcher(content);
        List<int[]> starts = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        while (m.find()) {
            starts.add(new int[]{m.start(), m.end()});
            kinds.add(m.group(2).startsWith("def") || m.group(2).equals("async def") ? "function" : "class");
            names.add(m.group(3));
            indents.add(m.group(1).length());
        }
        if (starts.isEmpty()) {
            return fallbackChunks(content, language, maxChars, overlap);
        }
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i)[0];
            int indent = indents.get(i);
            int end = content.length();
            for (int j = i + 1; j < starts.size(); j++) {
                if (indents.get(j) <= indent) {
                    end = starts.get(j)[0];
                    break;
                }
            }
            String parent = null;
            if ("function".equals(kinds.get(i))) {
                for (int j = i - 1; j >= 0; j--) {
                    if ("class".equals(kinds.get(j)) && indents.get(j) < indent) {
                        parent = names.get(j);
                        break;
                    }
                }
            }
            String piece = content.substring(start, end);
            out.addAll(splitOversized(
                    piece, language, kinds.get(i), names.get(i), parent,
                    lineAt(content, start), lineAt(content, end - 1),
                    maxChars, overlap
            ));
        }
        return out;
    }

    private static List<CodeChunk> chunkMarkdown(String content, String language, int maxChars, int overlap) {
        List<CodeChunk> out = new ArrayList<>();
        Pattern h = Pattern.compile("(?m)^(#{1,3})\\s+(.+)$");
        Matcher m = h.matcher(content);
        List<Integer> starts = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
            titles.add(m.group(2).trim());
        }
        if (starts.isEmpty()) {
            return fallbackChunks(content, language, maxChars, overlap);
        }
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1) : content.length();
            String piece = content.substring(start, end);
            out.addAll(splitOversized(
                    piece, language, "section", titles.get(i), null,
                    lineAt(content, start), lineAt(content, end - 1),
                    maxChars, overlap
            ));
        }
        return out;
    }

    private static List<CodeChunk> splitByMethodsOrFallback(
            String content, String language, String parent, int maxChars, int overlap, Pattern methodPat
    ) {
        List<CodeChunk> methods = extractBracedMembers(
                content, content, 0, language, "method", parent, maxChars, overlap, methodPat
        );
        return methods.isEmpty() ? fallbackChunks(content, language, maxChars, overlap) : methods;
    }

    private static List<CodeChunk> extractBracedMembers(
            String full,
            String region,
            int regionAbsStart,
            String language,
            String kind,
            String parent,
            int maxChars,
            int overlap,
            Pattern methodPat
    ) {
        List<CodeChunk> out = new ArrayList<>();
        Matcher m = methodPat.matcher(region);
        while (m.find()) {
            String name = m.group(1);
            int localStart = m.start();
            int brace = region.indexOf('{', m.end() - 1);
            if (brace < 0) {
                continue;
            }
            int braceEnd = matchBrace(region, brace);
            int localEnd = braceEnd < 0 ? Math.min(region.length(), localStart + maxChars) : braceEnd + 1;
            int absStart = regionAbsStart + localStart;
            int absEnd = regionAbsStart + localEnd;
            String piece = full.substring(absStart, Math.min(absEnd, full.length()));
            out.addAll(splitOversized(
                    piece, language, kind, name, parent,
                    lineAt(full, absStart), lineAt(full, Math.min(absEnd, full.length()) - 1),
                    maxChars, overlap
            ));
        }
        return out;
    }

    private static List<CodeChunk> fallbackChunks(String content, String language, int maxChars, int overlap) {
        List<CodeChunk> out = new ArrayList<>();
        // Prefer blank-line paragraphs first
        String[] paras = content.split("\\n\\s*\\n");
        int cursor = 0;
        for (String para : paras) {
            if (para.isBlank()) {
                continue;
            }
            int start = content.indexOf(para, cursor);
            if (start < 0) {
                start = cursor;
            }
            int end = start + para.length();
            cursor = end;
            out.addAll(splitOversized(
                    para, language, "block", null, null,
                    lineAt(content, start), lineAt(content, end - 1),
                    maxChars, overlap
            ));
        }
        if (out.isEmpty()) {
            out.addAll(splitOversized(
                    content, language, "file", null, null,
                    1, lineAt(content, content.length() - 1),
                    maxChars, overlap
            ));
        }
        return out;
    }

    private static List<CodeChunk> splitOversized(
            String text,
            String language,
            String kind,
            String name,
            String parent,
            int startLine,
            int endLine,
            int maxChars,
            int overlap
    ) {
        List<CodeChunk> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String trimmed = text;
        if (trimmed.length() <= maxChars) {
            out.add(new CodeChunk(trimmed, language, kind, name, parent, startLine, Math.max(startLine, endLine)));
            return out;
        }
        int step = Math.max(1, maxChars - Math.max(0, overlap));
        int part = 0;
        for (int i = 0; i < trimmed.length(); i += step) {
            int end = Math.min(trimmed.length(), i + maxChars);
            String slice = trimmed.substring(i, end);
            String partName = name == null ? ("part-" + part) : (name + "#part" + part);
            // Approximate line numbers inside the slice
            int localStartLine = startLine + countNewlines(trimmed.substring(0, i));
            int localEndLine = localStartLine + countNewlines(slice);
            out.add(new CodeChunk(slice, language, kind, partName, parent, localStartLine, Math.max(localStartLine, localEndLine)));
            part++;
            if (end >= trimmed.length()) {
                break;
            }
        }
        return out;
    }

    private static int findBlockStart(String content, int from) {
        int i = content.indexOf('{', from);
        return i;
    }

    private static int matchBrace(String content, int openIdx) {
        if (openIdx < 0 || openIdx >= content.length() || content.charAt(openIdx) != '{') {
            return -1;
        }
        int depth = 0;
        boolean inStr = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        char strQuote = 0;
        for (int i = openIdx; i < content.length(); i++) {
            char c = content.charAt(i);
            char next = i + 1 < content.length() ? content.charAt(i + 1) : 0;
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inStr) {
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == strQuote) {
                    inStr = false;
                }
                continue;
            }
            if (inChar) {
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                if (c == '\'') {
                    inChar = true;
                } else {
                    inStr = true;
                    strQuote = c;
                }
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int lineAt(String content, int offset) {
        if (content == null || content.isEmpty()) {
            return 1;
        }
        int o = Math.max(0, Math.min(offset, content.length() - 1));
        int line = 1;
        for (int i = 0; i <= o; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static int offsetOfLine(String content, int line) {
        if (line <= 1) {
            return 0;
        }
        int current = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                current++;
                if (current == line) {
                    return i + 1;
                }
            }
        }
        return content.length();
    }

    private static int countNewlines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    private static String fileStem(String path) {
        return path == null ? "file" : path;
    }

    private record TypeSpan(String kind, String name, int start, int end) {
    }
}
