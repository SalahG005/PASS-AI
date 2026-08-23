package com.pass.ai_assistant_backend.ai;

import com.pass.ai_assistant_backend.ai.dto.FileProposalDto;
import com.pass.ai_assistant_backend.ai.dto.ProjectPlanDto;
import com.pass.ai_assistant_backend.ai.dto.ProjectPlanFileDto;
import com.pass.ai_assistant_backend.ai.dto.ProjectPlanPhaseDto;
import com.pass.ai_assistant_backend.ai.dto.ReviewFindingDto;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses ```pass-tool JSON fences and legacy ```pass-file blocks from model output.
 * Also recovers common 7B mistakes: ```json fences and flat {"tool":"write_file","path":...} objects.
 * Uses brace-aware JSON extraction so nested ``` inside strings (common 7B mistake) does not truncate.
 */
public final class AgentToolParser {

    private static final Pattern TOOL_OPEN = Pattern.compile(
            "```(?:pass-tool|json)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private final ObjectMapper mapper;

    public AgentToolParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ParseResult parse(String rawInput) {
        // 7B models often write Python triple quotes in JSON ("content="""...""") which is not
        // valid JSON. Repair it up front so brace scanning and Jackson both see a clean object.
        String raw = normalizeTripleQuotedValues(rawInput);
        List<ToolCall> tools = new ArrayList<>();
        String withoutTools = raw == null ? "" : raw;

        if (raw != null && !raw.isBlank()) {
            StringBuilder cleaned = new StringBuilder();
            int cursor = 0;
            Matcher open = TOOL_OPEN.matcher(raw);
            while (open.find(cursor)) {
                cleaned.append(raw, cursor, open.start());
                int jsonStart = findJsonObjectStart(raw, open.end());
                if (jsonStart < 0) {
                    cursor = open.end();
                    continue;
                }
                String body = extractJsonObject(raw, jsonStart);
                if (body == null) {
                    cursor = open.end();
                    continue;
                }
                List<ToolCall> fromBody = parseToolJsonExpanded(body);
                // ```json may be ordinary data — only keep recognized tool objects
                if (!fromBody.isEmpty()) {
                    tools.addAll(fromBody);
                } else {
                    cleaned.append(raw, open.start(), jsonStart + body.length());
                }
                cursor = jsonStart + body.length();
                // skip optional trailing fence closer
                while (cursor < raw.length() && Character.isWhitespace(raw.charAt(cursor))) {
                    cursor++;
                }
                if (cursor + 3 <= raw.length() && raw.startsWith("```", cursor)) {
                    cursor += 3;
                }
            }
            if (cursor < raw.length()) {
                cleaned.append(raw, cursor, raw.length());
            }
            withoutTools = cleaned.toString();
        }

        // Bare / mistyped write_file JSON outside fences
        if (tools.stream().noneMatch(t -> "write_file".equals(t.name()))) {
            List<ToolCall> loose = extractLooseWriteFiles(raw);
            if (!loose.isEmpty()) {
                tools.addAll(loose);
                withoutTools = stripLooseWriteJson(withoutTools);
            }
        }

        // Last resort: the object is too broken for brace scanning (embedded ``` fences, a stray
        // unescaped quote inside content). Recover write_file by anchoring on the "..."}} terminator.
        if (tools.stream().noneMatch(t -> "write_file".equals(t.name()))) {
            ToolCall salvaged = salvageWriteFile(raw);
            if (salvaged != null) {
                tools.add(salvaged);
                withoutTools = "";
            }
        }

        // Shorthand tool syntax the 7B models love: search{query="..."}, read_file{path="..."},
        // done{message="..."} — NOT a JSON fence, so nothing above catches it and the call is never
        // executed. Parse it as a fallback when no JSON tool was found.
        if (tools.isEmpty()) {
            List<ToolCall> shorthand = extractShorthandTools(withoutTools);
            if (!shorthand.isEmpty()) {
                tools.addAll(shorthand);
                withoutTools = "";
            }
        }

        PassFileBlockParser.ParseResult legacy = PassFileBlockParser.parse(withoutTools);
        for (FileProposalDto f : legacy.files()) {
            tools.add(ToolCall.writeFile(f.getPath(), f.getAction(), f.getContent()));
        }

        String replyText = legacy.replyWithoutBlocks().trim();

        boolean hasWrite = tools.stream().anyMatch(t -> "write_file".equals(t.name()));
        boolean hasPlan = tools.stream().anyMatch(t -> "submit_plan".equals(t.name()));
        if (!hasWrite || !hasPlan) {
            MarkdownProjectRecovery.Recovery recovered = MarkdownProjectRecovery.recover(raw);
            if (!hasPlan && recovered.plan() != null && recovered.plan().getFiles() != null
                    && !recovered.plan().getFiles().isEmpty()) {
                tools.add(0, ToolCall.submitPlan(recovered.plan()));
                hasPlan = true;
            }
            if (!hasWrite && !recovered.files().isEmpty()) {
                if (!hasPlan) {
                    ProjectPlanDto synth = recovered.plan();
                    if (synth == null) {
                        synth = new ProjectPlanDto();
                        synth.setTitle("Recovered project");
                        List<ProjectPlanFileDto> list = new ArrayList<>();
                        for (FileProposalDto f : recovered.files()) {
                            list.add(new ProjectPlanFileDto(f.getPath(), ""));
                        }
                        synth.setFiles(list);
                    }
                    tools.add(0, ToolCall.submitPlan(synth));
                }
                for (FileProposalDto f : recovered.files()) {
                    tools.add(ToolCall.writeFile(f.getPath(), f.getAction(), f.getContent()));
                }
                if (recovered.cleanedText() != null && !recovered.cleanedText().isBlank()) {
                    replyText = recovered.cleanedText();
                }
            }
        }

        // Prefer real write_file tools over a bare done that only wrapped them
        List<ToolCall> writes = tools.stream().filter(t -> "write_file".equals(t.name())).toList();
        List<ToolCall> plans = tools.stream().filter(t -> "submit_plan".equals(t.name())).toList();
        List<ToolCall> other = tools.stream()
                .filter(t -> !"write_file".equals(t.name()) && !"submit_plan".equals(t.name()) && !"done".equals(t.name()))
                .toList();
        List<ToolCall> dones = tools.stream().filter(t -> "done".equals(t.name())).toList();
        List<ToolCall> ordered = new ArrayList<>();
        ordered.addAll(plans);
        ordered.addAll(writes);
        ordered.addAll(other);
        if (!writes.isEmpty() && !dones.isEmpty()) {
            // keep a short done after writes if present
            ordered.add(dones.get(dones.size() - 1));
        } else if (writes.isEmpty()) {
            ordered.addAll(dones);
        }

        return new ParseResult(ordered, replyText, raw == null ? "" : raw);
    }

    /** Parse one tool JSON and expand nested pass-tool / write_file buried in done.message. */
    private List<ToolCall> parseToolJsonExpanded(String body) {
        List<ToolCall> out = new ArrayList<>();
        ToolCall primary = parseToolJson(body);
        if (primary != null) {
            if ("done".equals(primary.name()) && primary.message() != null && looksLikeNestedTools(primary.message())) {
                List<ToolCall> nested = parse(primary.message()).tools();
                if (!nested.isEmpty()) {
                    out.addAll(nested);
                    // keep done only if it has a short human message left
                    String msg = primary.message();
                    if (msg.length() < 200 && !msg.contains("write_file") && !msg.contains("pass-tool")) {
                        out.add(primary);
                    }
                    return out;
                }
            }
            out.add(primary);
        } else {
            // broken outer JSON — still try to find write_file objects in the blob
            out.addAll(extractLooseWriteFiles(body));
        }
        return out;
    }

    private static boolean looksLikeNestedTools(String message) {
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("pass-tool") || m.contains("write_file") || m.contains("submit_plan");
    }

    private List<ToolCall> extractLooseWriteFiles(String blob) {
        List<ToolCall> out = new ArrayList<>();
        if (blob == null || blob.isBlank()) {
            return out;
        }
        int i = 0;
        while (i < blob.length()) {
            int idx = blob.indexOf("\"write_file\"", i);
            if (idx < 0) {
                idx = blob.indexOf("\"name\":\"write_file\"", i);
            }
            if (idx < 0) {
                break;
            }
            int objStart = blob.lastIndexOf('{', idx);
            if (objStart < 0) {
                i = idx + 1;
                continue;
            }
            String json = extractJsonObject(blob, objStart);
            if (json == null) {
                i = idx + 1;
                continue;
            }
            ToolCall call = parseToolJson(json);
            if (call != null && "write_file".equals(call.name())) {
                out.add(call);
            }
            i = objStart + json.length();
        }
        return out;
    }

    private ToolCall parseToolJson(String body) {
        try {
            JsonNode node = readJsonLenient(body);
            if (node == null) {
                return null;
            }
            String name = asText(node.get("name"));
            if (name == null || name.isBlank()) {
                // 7B models often emit {"tool":"write_file","path":...} instead of name+args
                name = asText(node.get("tool"));
            }
            if (name == null || name.isBlank()) {
                name = asText(node.get("action"));
                if (name != null && !"write_file".equalsIgnoreCase(name) && !"write".equalsIgnoreCase(name)
                        && !"overwrite".equalsIgnoreCase(name) && !"create".equalsIgnoreCase(name)) {
                    name = null;
                } else if (name != null && node.get("path") != null && node.get("content") != null) {
                    name = "write_file";
                }
            }
            if (name == null || name.isBlank()) {
                // Flat write_file without name/tool key
                if (node.get("path") != null && node.get("content") != null
                        && (node.get("action") != null || looksLikeSourcePath(asText(node.get("path"))))) {
                    name = "write_file";
                } else {
                    return null;
                }
            }
            JsonNode args = node.get("args");
            if (args == null || args.isNull()) {
                args = node.get("arguments");
            }
            // Flat shape: fields live on the root object
            if (args == null || args.isNull()) {
                args = node;
            }
            name = name.trim().toLowerCase(Locale.ROOT);
            return switch (name) {
                case "read_file", "read" -> ToolCall.readFile(asText(args, "path"));
                case "search", "grep" -> ToolCall.search(asText(args, "query"));
                case "write_file", "write", "propose_file" -> ToolCall.writeFile(
                        firstText(args, "path", "file", "filename"),
                        firstText(args, "action", "mode"),
                        firstText(args, "content", "code", "body", "text")
                );
                case "edit_file", "edit", "replace", "str_replace" -> ToolCall.editFile(
                        firstText(args, "path", "file", "filename"),
                        firstText(args, "old_string", "oldString", "old", "search", "find", "from"),
                        firstText(args, "new_string", "newString", "new", "replace", "replacement", "to")
                );
                case "run_terminal", "run", "shell" -> ToolCall.runTerminal(asText(args, "command"));
                case "propose_install", "install", "propose_install_command" -> ToolCall.proposeInstall(
                        firstText(args, "command", "cmd"),
                        firstText(args, "reason", "description", "message", "summary")
                );
                case "submit_findings", "findings" -> ToolCall.submitFindings(parseFindings(args));
                case "submit_plan", "plan_project", "plan" -> ToolCall.submitPlan(parsePlan(args));
                case "done", "finish" -> ToolCall.done(firstText(args, "message", "summary"));
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean looksLikeSourcePath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String p = path.toLowerCase(Locale.ROOT);
        return p.contains("/") || p.contains("\\") || p.contains(".");
    }

    /** Remove loose write_file JSON objects from assistant-visible text. */
    private String stripLooseWriteJson(String text) {
        if (text == null || text.isBlank() || !text.contains("write_file")) {
            return text == null ? "" : text;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int idx = text.indexOf("\"write_file\"", i);
            if (idx < 0) {
                out.append(text, i, text.length());
                break;
            }
            int objStart = text.lastIndexOf('{', idx);
            if (objStart < 0 || objStart < i) {
                out.append(text, i, idx + 1);
                i = idx + 1;
                continue;
            }
            String json = extractJsonObject(text, objStart);
            if (json == null) {
                out.append(text, i, idx + 1);
                i = idx + 1;
                continue;
            }
            ToolCall call = parseToolJson(json);
            if (call != null && "write_file".equals(call.name())) {
                out.append(text, i, objStart);
                i = objStart + json.length();
            } else {
                out.append(text, i, idx + 1);
                i = idx + 1;
            }
        }
        return out.toString();
    }

    /**
     * Parse JSON, tolerating the most common 7B mistake: literal (unescaped) newlines/tabs inside a
     * string value, e.g. a README written as a real multi-line block instead of one \n-escaped line.
     */
    private JsonNode readJsonLenient(String body) {
        if (body == null) {
            return null;
        }
        try {
            return mapper.readTree(body);
        } catch (Exception first) {
            try {
                return mapper.readTree(escapeControlCharsInStrings(body));
            } catch (Exception second) {
                return null;
            }
        }
    }

    /**
     * Escapes raw control characters (newlines, tabs, carriage returns) that appear INSIDE JSON
     * string literals so Jackson can parse them. String boundaries are tracked with the standard
     * quote/backslash state machine; text outside strings is copied verbatim.
     */
    static String escapeControlCharsInStrings(String json) {
        if (json == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(json.length() + 32);
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (!inString) {
                out.append(c);
                if (c == '"') {
                    inString = true;
                }
                continue;
            }
            if (escape) {
                out.append(c);
                escape = false;
            } else if (c == '\\') {
                out.append(c);
                escape = true;
            } else if (c == '"') {
                out.append(c);
                inString = false;
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c == '\t') {
                out.append("\\t");
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static final Pattern SHORTHAND_HEAD = Pattern.compile(
            "\\b(read_file|read|search|grep|run_terminal|run|shell|done|finish)\\s*\\{",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FIRST_QUOTED =
            Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");

    /**
     * Recover shorthand tool calls like {@code search{query="..."}} or {@code done{message="..."}}
     * that models emit instead of a ```pass-tool JSON fence. Only simple read-only tools are handled
     * here; write_file/edit_file with large bodies still go through the JSON/salvage paths.
     */
    List<ToolCall> extractShorthandTools(String text) {
        List<ToolCall> out = new ArrayList<>();
        if (text == null || text.isBlank() || !text.contains("{")) {
            return out;
        }
        Matcher m = SHORTHAND_HEAD.matcher(text);
        int from = 0;
        while (m.find(from)) {
            int brace = m.end() - 1;
            String block = extractJsonObject(text, brace);
            if (block == null) {
                from = m.end();
                continue;
            }
            String inner = block.substring(1, block.length() - 1).trim();
            String name = m.group(1).toLowerCase(Locale.ROOT);
            ToolCall call = switch (name) {
                case "read_file", "read" -> {
                    String v = shorthandArg(inner, "path", "file", "filename");
                    yield v == null ? null : ToolCall.readFile(v);
                }
                case "search", "grep" -> {
                    String v = shorthandArg(inner, "query", "q", "term");
                    yield v == null ? null : ToolCall.search(v);
                }
                case "run_terminal", "run", "shell" -> {
                    String v = shorthandArg(inner, "command", "cmd");
                    yield v == null ? null : ToolCall.runTerminal(v);
                }
                case "done", "finish" -> ToolCall.done(shorthandArgOrText(inner, "message", "summary"));
                default -> null;
            };
            if (call != null) {
                out.add(call);
            }
            from = brace + block.length();
        }
        return out;
    }

    private static String shorthandArg(String inner, String... keys) {
        for (String k : keys) {
            Matcher m = Pattern.compile("\"?" + k + "\"?\\s*[:=]\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
                    Pattern.CASE_INSENSITIVE).matcher(inner);
            if (m.find()) {
                return unescapeJsonString(m.group(1));
            }
        }
        for (String k : keys) {
            Matcher m = Pattern.compile("\"?" + k + "\"?\\s*[:=]\\s*([^,}\\r\\n]+)",
                    Pattern.CASE_INSENSITIVE).matcher(inner);
            if (m.find()) {
                return stripWrappingQuotes(m.group(1).trim());
            }
        }
        Matcher fq = FIRST_QUOTED.matcher(inner);
        return fq.find() ? unescapeJsonString(fq.group(1)) : null;
    }

    private static String shorthandArgOrText(String inner, String... keys) {
        String v = shorthandArg(inner, keys);
        return v != null ? v : stripWrappingQuotes(inner.trim());
    }

    private static String stripWrappingQuotes(String s) {
        if (s != null && s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return unescapeJsonString(s.substring(1, s.length() - 1));
        }
        return s;
    }

    private static final Pattern SALVAGE_PATH =
            Pattern.compile("\"(?:path|file|filename)\"\\s*[:=]\\s*\"([^\"\\r\\n]+)\"");
    private static final Pattern SALVAGE_ACTION =
            Pattern.compile("\"(?:action|mode)\"\\s*[:=]\\s*\"([^\"\\r\\n]+)\"");
    private static final Pattern SALVAGE_CONTENT_OPEN =
            Pattern.compile("\"(?:content|code|body|text)\"\\s*[:=]\\s*\"");

    /**
     * Recover a single write_file when the JSON is structurally unparseable — typically because the
     * model embedded ``` code fences and a stray unescaped quote inside "content", which fools any
     * brace/quote scanner. We treat "content" as raw text bounded by the trailing {@code "}} } marker
     * (the real end of args+root), then JSON-unescape it. Stray quotes survive as literal quotes.
     */
    static ToolCall salvageWriteFile(String raw) {
        if (raw == null || !raw.contains("write_file")) {
            return null;
        }
        Matcher open = SALVAGE_CONTENT_OPEN.matcher(raw);
        if (!open.find()) {
            return null;
        }
        int contentStart = open.end();
        int contentEnd = raw.lastIndexOf("\"}}");
        if (contentEnd < contentStart) {
            contentEnd = raw.lastIndexOf("\"}");
        }
        if (contentEnd < contentStart) {
            return null;
        }
        String path = firstGroup(SALVAGE_PATH, raw);
        if (path == null || path.isBlank()) {
            return null;
        }
        String action = firstGroup(SALVAGE_ACTION, raw);
        String content = unescapeJsonString(raw.substring(contentStart, contentEnd));
        return ToolCall.writeFile(path.trim(), action, content);
    }

    private static String firstGroup(Pattern pattern, String raw) {
        Matcher m = pattern.matcher(raw);
        return m.find() ? m.group(1) : null;
    }

    /** Decode JSON escape sequences, tolerating unknown escapes by keeping the char verbatim. */
    static String unescapeJsonString(String s) {
        if (s == null || s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException ex) {
                            sb.append('u');
                        }
                    } else {
                        sb.append('u');
                    }
                }
                default -> {
                    sb.append('\\');
                    sb.append(n);
                }
            }
        }
        return sb.toString();
    }

    /** Matches {@code "content="""…"""} and {@code "content":"""…"""} style values. */
    private static final Pattern TRIPLE_QUOTED_VALUE = Pattern.compile(
            "\"(\\w+)\"?\\s*[:=]\\s*\"\"\"([\\s\\S]*?)\"\"\""
    );

    /** Rewrites triple-quoted values into properly escaped JSON strings. */
    static String normalizeTripleQuotedValues(String raw) {
        if (raw == null || !raw.contains("\"\"\"")) {
            return raw;
        }
        Matcher m = TRIPLE_QUOTED_VALUE.matcher(raw);
        StringBuilder out = new StringBuilder();
        int last = 0;
        boolean found = false;
        while (m.find()) {
            found = true;
            out.append(raw, last, m.start());
            out.append('"').append(m.group(1)).append("\":");
            appendJsonString(out, m.group(2));
            last = m.end();
        }
        if (!found) {
            return raw;
        }
        out.append(raw, last, raw.length());
        return out.toString();
    }

    private static void appendJsonString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static int findJsonObjectStart(String raw, int from) {
        for (int i = from; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '{') {
                return i;
            }
            if (!Character.isWhitespace(c)) {
                return -1;
            }
        }
        return -1;
    }

    /** Extract a JSON object starting at {@code start} (must be '{'), respecting strings/escapes. */
    static String extractJsonObject(String raw, int start) {
        if (raw == null || start < 0 || start >= raw.length() || raw.charAt(start) != '{') {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return raw.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private ProjectPlanDto parsePlan(JsonNode args) {
        ProjectPlanDto plan = new ProjectPlanDto();
        if (args == null || args.isNull()) {
            return plan;
        }
        plan.setTitle(firstText(args, "title", "name"));
        plan.setStack(firstText(args, "stack", "tech"));
        plan.setSummary(firstText(args, "summary", "description"));

        JsonNode phasesNode = args.get("phases");
        if (phasesNode != null && phasesNode.isArray() && !phasesNode.isEmpty()) {
            List<ProjectPlanPhaseDto> phases = new ArrayList<>();
            int idx = 0;
            for (JsonNode pn : phasesNode) {
                if (pn == null || pn.isNull()) {
                    continue;
                }
                String name = firstText(pn, "name", "title", "id");
                if (name == null || name.isBlank()) {
                    name = "Phase " + (idx + 1);
                }
                String id = asText(pn.get("id"));
                if (id == null || id.isBlank()) {
                    id = String.valueOf(idx + 1);
                }
                ProjectPlanPhaseDto phase = new ProjectPlanPhaseDto(id, name);
                phase.setFiles(parseFileList(pn.get("files")));
                // Cap each phase for 7B
                if (phase.getFiles().size() > 12) {
                    phase.setFiles(new ArrayList<>(phase.getFiles().subList(0, 12)));
                }
                phases.add(phase);
                idx++;
                if (phases.size() >= 5) {
                    break;
                }
            }
            plan.setPhases(phases);
            int cur = 0;
            JsonNode curNode = args.get("currentPhase");
            if (curNode != null && !curNode.isNull()) {
                try {
                    cur = curNode.asInt();
                } catch (Exception ignored) {
                }
            }
            plan.setCurrentPhase(Math.min(Math.max(0, cur), Math.max(0, phases.size() - 1)));
            plan.syncFilesFromActivePhase();
            return plan;
        }

        plan.setFiles(parseFileList(args.get("files")));
        // Single-phase wrapper so UI can still show a phase strip
        if (!plan.getFiles().isEmpty()) {
            ProjectPlanPhaseDto only = new ProjectPlanPhaseDto("1", "MVP");
            only.setStatus("active");
            only.setFiles(new ArrayList<>(plan.getFiles()));
            plan.setPhases(List.of(only));
            plan.setCurrentPhase(0);
        }
        return plan;
    }

    private List<ProjectPlanFileDto> parseFileList(JsonNode files) {
        List<ProjectPlanFileDto> list = new ArrayList<>();
        if (files == null || !files.isArray()) {
            return list;
        }
        for (JsonNode n : files) {
            if (n == null || n.isNull()) {
                continue;
            }
            String path = asText(n.get("path"));
            if (path == null || path.isBlank()) {
                continue;
            }
            String purpose = asText(n.get("purpose"));
            if (purpose == null) {
                purpose = asText(n.get("description"));
            }
            list.add(new ProjectPlanFileDto(path.replace('\\', '/').trim(), purpose));
        }
        return list;
    }

    private List<ReviewFindingDto> parseFindings(JsonNode args) {
        List<ReviewFindingDto> out = new ArrayList<>();
        if (args == null || args.isNull()) {
            return out;
        }
        JsonNode arr = args.get("findings");
        if (arr == null || !arr.isArray()) {
            arr = args.isArray() ? args : null;
        }
        if (arr == null) {
            return out;
        }
        for (JsonNode n : arr) {
            if (n == null || n.isNull()) {
                continue;
            }
            String path = asText(n.get("path"));
            String message = asText(n.get("message"));
            if (message == null) {
                message = asText(n.get("msg"));
            }
            String severity = asText(n.get("severity"));
            if (severity == null || severity.isBlank()) {
                severity = "info";
            }
            Integer line = null;
            JsonNode lineNode = n.get("line");
            if (lineNode != null && !lineNode.isNull()) {
                try {
                    line = lineNode.asInt();
                } catch (Exception ignored) {
                }
            }
            out.add(new ReviewFindingDto(path, line, severity.toLowerCase(Locale.ROOT), message));
        }
        return out;
    }

    private static String asText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asString(null);
    }

    private static String asText(JsonNode args, String field) {
        if (args == null || args.isNull()) {
            return null;
        }
        return asText(args.get(field));
    }

    private static String firstText(JsonNode args, String... fields) {
        for (String f : fields) {
            String v = asText(args, f);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    public record ToolCall(
            String name,
            String path,
            String action,
            String content,
            String query,
            String command,
            String message,
            List<ReviewFindingDto> findings,
            ProjectPlanDto plan,
            String oldString,
            String newString
    ) {
        public static ToolCall readFile(String path) {
            return new ToolCall("read_file", path, null, null, null, null, null, null, null, null, null);
        }

        public static ToolCall search(String query) {
            return new ToolCall("search", null, null, null, query, null, null, null, null, null, null);
        }

        public static ToolCall writeFile(String path, String action, String content) {
            String act = action == null || action.isBlank() ? "overwrite" : action;
            return new ToolCall("write_file", path, act, content == null ? "" : content,
                    null, null, null, null, null, null, null);
        }

        public static ToolCall editFile(String path, String oldString, String newString) {
            return new ToolCall("edit_file", path, "edit", null, null, null, null, null, null,
                    oldString, newString == null ? "" : newString);
        }

        public static ToolCall runTerminal(String command) {
            return new ToolCall("run_terminal", null, null, null, null, command, null, null, null, null, null);
        }

        public static ToolCall proposeInstall(String command, String reason) {
            return new ToolCall("propose_install", null, null, reason, null, command, null, null, null, null, null);
        }

        public static ToolCall submitFindings(List<ReviewFindingDto> findings) {
            return new ToolCall("submit_findings", null, null, null, null, null, null,
                    findings != null ? findings : List.of(), null, null, null);
        }

        public static ToolCall submitPlan(ProjectPlanDto plan) {
            return new ToolCall("submit_plan", null, null, null, null, null, null, null, plan, null, null);
        }

        public static ToolCall done(String message) {
            return new ToolCall("done", null, null, null, null, null, message == null ? "" : message,
                    null, null, null, null);
        }
    }

    public record ParseResult(List<ToolCall> tools, String replyText, String raw) {
    }
}
