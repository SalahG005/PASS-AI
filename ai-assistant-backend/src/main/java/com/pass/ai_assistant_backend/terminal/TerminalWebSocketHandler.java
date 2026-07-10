package com.pass.ai_assistant_backend.terminal;

import com.pass.ai_assistant_backend.service.WorkspaceService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private final WorkspaceService workspaceService;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final Map<String, BufferedWriter> writers = new ConcurrentHashMap<>();

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
        ProcessBuilder pb = buildProcess(isWindows, shell);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        env.put("TERM", "xterm-256color");
        env.put("PASS_AI_WORKSPACE", cwd.toAbsolutePath().toString());
        // Force UTF-8 so French accents (é, è, ’…) render correctly in the UI
        if (isWindows) {
            env.put("PYTHONIOENCODING", "utf-8");
        }

        // Always decode/encode as UTF-8 (cmd/PowerShell are started in UTF-8 mode below)
        Charset charset = StandardCharsets.UTF_8;
        Process process = pb.start();

        processes.put(session.getId(), process);
        writers.put(session.getId(), new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), charset)));

        String shellLabel = shellLabel(shell, isWindows);
        session.sendMessage(new TextMessage(
                "PASS AI — " + shellLabel + "\r\n" +
                "Workspace: " + cwd.toAbsolutePath() + "\r\n" +
                "Type commands here (dir, cd, python, git, etc.)\r\n\r\n"
        ));

        executor.submit(() -> streamOutput(session, process, charset));
    }

    private ProcessBuilder buildProcess(boolean isWindows, String shell) {
        if (!isWindows) {
            return new ProcessBuilder("bash", "-i");
        }
        if ("powershell".equals(shell)) {
            return new ProcessBuilder(
                    "powershell.exe",
                    "-NoLogo",
                    "-NoExit",
                    "-ExecutionPolicy", "Bypass",
                    "-Command",
                    "chcp 65001 > $null; " +
                    "[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false); " +
                    "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false); " +
                    "$OutputEncoding = [Console]::OutputEncoding"
            );
        }
        // Invite de commandes: UTF-8 code page so accents are not garbled
        return new ProcessBuilder("cmd.exe", "/K", "chcp 65001 >nul & prompt $P$G");
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
        return "powershell".equals(shell) ? "Windows PowerShell" : "Invite de commandes (cmd)";
    }

    private void streamOutput(WebSocketSession session, Process process, Charset charset) {
        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[2048];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (!session.isOpen()) {
                    break;
                }
                String chunk = new String(buffer, 0, read, charset);
                session.sendMessage(new TextMessage(chunk));
            }
        } catch (IOException ignored) {
        } finally {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage("\r\n[shell exited]\r\n"));
                    session.close();
                }
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        BufferedWriter writer = writers.get(session.getId());
        if (writer == null) {
            return;
        }
        String payload = message.getPayload();
        if ("\u0003".equals(payload)) {
            Process process = processes.get(session.getId());
            if (process != null) {
                process.destroy();
            }
            return;
        }
        writer.write(payload);
        writer.flush();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanup(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        cleanup(session.getId());
    }

    private void cleanup(String sessionId) {
        BufferedWriter writer = writers.remove(sessionId);
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
        }
        Process process = processes.remove(sessionId);
        if (process != null) {
            process.destroy();
            try {
                process.destroyForcibly();
            } catch (Exception ignored) {
            }
        }
    }
}
