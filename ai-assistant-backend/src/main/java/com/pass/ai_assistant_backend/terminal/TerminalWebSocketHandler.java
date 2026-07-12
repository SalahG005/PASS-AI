package com.pass.ai_assistant_backend.terminal;

import com.pass.ai_assistant_backend.service.WorkspaceService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Line-oriented terminal for Windows (no ConPTY).
 * Each client line is run as one command in a tracked working directory,
 * so {@code cd ..} and similar work reliably with ProcessBuilder.
 */
@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private static final Pattern CD_CMD = Pattern.compile(
            "^\\s*(?:cd|chdir|Set-Location|sl)\\s*(.*)$",
            Pattern.CASE_INSENSITIVE
    );

    private final WorkspaceService workspaceService;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, SessionState> states = new ConcurrentHashMap<>();

    public TerminalWebSocketHandler(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String email = (String) session.getAttributes().get("email");
        if (email == null || email.isBlank()) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Unauthorized"));
            return;
        }

        String workspaceId = (String) session.getAttributes().get("workspaceId");
        String shell = normalizeShell((String) session.getAttributes().get("shell"));
        Path cwd = workspaceService.resolveUserRoot(email, workspaceId);
        Files.createDirectories(cwd);

        boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        SessionState state = new SessionState(cwd.toAbsolutePath().normalize(), shell, isWindows);
        states.put(session.getId(), state);

        sendText(session, "PASS-AI terminal (" + shellLabel(shell, isWindows) + ")\r\n");
        sendText(session, "Working directory: " + state.cwd + "\r\n");
        sendPrompt(session, state);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SessionState state = states.get(session.getId());
        if (state == null) {
            return;
        }
        String payload = message.getPayload();
        if ("\u0003".equals(payload)) {
            state.busy.set(false);
            sendText(session, "^C\r\n");
            sendPrompt(session, state);
            return;
        }

        String line = stripLineEndings(payload);
        if (state.busy.get()) {
            sendText(session, "\r\n[busy — wait for the current command]\r\n");
            sendPrompt(session, state);
            return;
        }

        executor.submit(() -> runLine(session, state, line));
    }

    private void runLine(WebSocketSession session, SessionState state, String line) {
        if (!state.busy.compareAndSet(false, true)) {
            return;
        }
        try {
            if (line.isBlank()) {
                sendPrompt(session, state);
                return;
            }

            Matcher cd = CD_CMD.matcher(line);
            if (cd.matches()) {
                handleCd(session, state, cd.group(1) == null ? "" : cd.group(1).trim());
                return;
            }

            String lower = line.trim().toLowerCase(Locale.ROOT);
            if (lower.equals("pwd") || lower.equals("get-location") || lower.equals("echo %cd%")) {
                sendText(session, state.cwd + "\r\n");
                sendPrompt(session, state);
                return;
            }

            ProcessBuilder pb = buildCommand(state, line);
            pb.directory(state.cwd.toFile());
            pb.redirectErrorStream(true);
            if (state.isWindows) {
                pb.environment().put("PYTHONIOENCODING", "utf-8");
            }

            Charset charset = state.isWindows ? Charset.defaultCharset() : StandardCharsets.UTF_8;
            Process process = pb.start();
            streamProcess(session, process, charset);
            int code = process.waitFor();
            if (code != 0) {
                sendText(session, "\r\n[exit code " + code + "]\r\n");
            }
            sendPrompt(session, state);
        } catch (Exception e) {
            sendText(session, "\r\n[error] " + e.getMessage() + "\r\n");
            sendPrompt(session, state);
        } finally {
            state.busy.set(false);
        }
    }

    private void handleCd(WebSocketSession session, SessionState state, String target) {
        try {
            Path next;
            if (target.isEmpty() || target.equals("~")) {
                next = Path.of(System.getProperty("user.home"));
            } else {
                String cleaned = stripQuotes(target);
                Path asPath = Path.of(cleaned);
                next = asPath.isAbsolute() ? asPath : state.cwd.resolve(asPath);
            }
            next = next.toAbsolutePath().normalize();
            if (!Files.isDirectory(next)) {
                sendText(session, "cd : path not found: " + next + "\r\n");
            } else {
                state.cwd = next;
            }
        } catch (Exception e) {
            sendText(session, "cd : " + e.getMessage() + "\r\n");
        }
        sendPrompt(session, state);
    }

    private ProcessBuilder buildCommand(SessionState state, String line) {
        if (!state.isWindows) {
            return new ProcessBuilder("bash", "-lc", line);
        }
        if ("powershell".equals(state.shell)) {
            // One-shot command — interactive PS over redirected pipes does not work
            String script = "Set-Location -LiteralPath '" + escapePs(state.cwd.toString()) + "'; "
                    + line;
            return new ProcessBuilder(
                    "powershell.exe",
                    "-NoLogo",
                    "-NoProfile",
                    "-ExecutionPolicy", "Bypass",
                    "-Command",
                    script
            );
        }
        return new ProcessBuilder("cmd.exe", "/d", "/s", "/c", line);
    }

    private void streamProcess(WebSocketSession session, Process process, Charset charset) throws IOException {
        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[2048];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (!session.isOpen()) {
                    process.destroyForcibly();
                    break;
                }
                sendText(session, new String(buffer, 0, read, charset));
            }
        }
    }

    private void sendPrompt(WebSocketSession session, SessionState state) {
        if ("powershell".equals(state.shell)) {
            sendText(session, "\r\nPS " + state.cwd + "> ");
        } else if (state.isWindows) {
            sendText(session, "\r\n" + state.cwd + "> ");
        } else {
            sendText(session, "\r\n$ ");
        }
    }

    private void sendText(WebSocketSession session, String text) {
        if (!session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(text));
            }
        } catch (IOException ignored) {
        }
    }

    private String normalizeShell(String shell) {
        if (shell == null || shell.isBlank()) {
            return "cmd";
        }
        String value = shell.trim().toLowerCase(Locale.ROOT);
        if (value.contains("power") || value.equals("ps") || value.equals("pwsh")) {
            return "powershell";
        }
        return "cmd";
    }

    private String shellLabel(String shell, boolean isWindows) {
        if (!isWindows) {
            return "bash";
        }
        return "powershell".equals(shell) ? "Windows PowerShell" : "cmd";
    }

    private static String stripLineEndings(String payload) {
        if (payload == null) {
            return "";
        }
        return payload.replace("\r", "").replace("\n", "");
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char a = value.charAt(0);
            char b = value.charAt(value.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String escapePs(String path) {
        return path.replace("'", "''");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        states.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        states.remove(session.getId());
    }

    private static final class SessionState {
        private volatile Path cwd;
        private final String shell;
        private final boolean isWindows;
        private final AtomicBoolean busy = new AtomicBoolean(false);

        private SessionState(Path cwd, String shell, boolean isWindows) {
            this.cwd = cwd;
            this.shell = shell;
            this.isWindows = isWindows;
        }
    }
}
