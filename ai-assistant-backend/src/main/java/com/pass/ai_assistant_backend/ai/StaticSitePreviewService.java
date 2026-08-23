package com.pass.ai_assistant_backend.ai;

import com.pass.ai_assistant_backend.ai.dto.FileProposalDto;
import com.pass.ai_assistant_backend.ai.dto.ScaffoldPreviewDto;
import com.pass.ai_assistant_backend.dto.FileContentDto;
import com.pass.ai_assistant_backend.service.WorkspaceService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
public class StaticSitePreviewService {

    private final WorkspaceService workspaceService;
    private final ScaffoldPreviewProperties previewProps;
    private final ObjectMapper mapper = new ObjectMapper();

    public StaticSitePreviewService(WorkspaceService workspaceService, ScaffoldPreviewProperties previewProps) {
        this.workspaceService = workspaceService;
        this.previewProps = previewProps;
    }

    public ScaffoldPreviewDto previewStaticSite(String email, String workspaceId, List<FileProposalDto> files)
            throws IOException {
        if (!previewProps.isEnabled()) {
            return null;
        }
        ScaffoldPreviewDto preview = new ScaffoldPreviewDto();
        String htmlPath = pickHtmlPath(files);
        if (htmlPath == null) {
            return preview;
        }
        preview.setHtmlPath(htmlPath);

        String html = readHtml(email, workspaceId, files, htmlPath);
        if (html == null || html.isBlank()) {
            preview.getWarnings().add("No HTML content available for preview.");
            return preview;
        }

        List<String> warnings = new ArrayList<>(HtmlLayoutChecker.analyze(html));
        preview.setWarnings(warnings);

        if (!previewProps.isScreenshot()) {
            return preview;
        }

        Path tempDir = Files.createTempDirectory("pass-ai-preview-");
        try {
            materializeForPreview(email, workspaceId, files, tempDir);
            Path htmlFile = tempDir.resolve(htmlPath.replace('\\', '/'));
            if (!Files.isRegularFile(htmlFile)) {
                htmlFile = tempDir.resolve("index.html");
            }
            if (!Files.isRegularFile(htmlFile)) {
                preview.getWarnings().add("Screenshot skipped: index.html not found in preview bundle.");
                return preview;
            }

            Path png = tempDir.resolve("preview.png");
            if (runHeadlessScreenshot(htmlFile, png)) {
                byte[] bytes = Files.readAllBytes(png);
                preview.setScreenshotBase64(Base64.getEncoder().encodeToString(bytes));
                preview.setScreenshotAvailable(true);
            } else {
                preview.getWarnings().add(
                        "Screenshot skipped (install Node + Playwright for visuals). Structural checks still ran.");
            }
        } finally {
            deleteRecursive(tempDir);
        }
        return preview;
    }

    private static String pickHtmlPath(List<FileProposalDto> files) {
        if (files == null) {
            return null;
        }
        for (FileProposalDto f : files) {
            if (f.getPath() != null && f.getPath().replace('\\', '/').toLowerCase(Locale.ROOT).endsWith("index.html")) {
                return f.getPath().replace('\\', '/');
            }
        }
        for (FileProposalDto f : files) {
            String p = f.getPath() == null ? "" : f.getPath().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (p.endsWith(".html") || p.endsWith(".htm")) {
                return f.getPath().replace('\\', '/');
            }
        }
        return null;
    }

    private String readHtml(String email, String workspaceId, List<FileProposalDto> files, String htmlPath)
            throws IOException {
        if (files != null) {
            for (FileProposalDto f : files) {
                if (f.getPath() != null && f.getPath().replace('\\', '/').equals(htmlPath.replace('\\', '/'))) {
                    if (f.getContent() != null && !f.getContent().isBlank()) {
                        return f.getContent();
                    }
                }
            }
        }
        try {
            FileContentDto file = workspaceService.readFile(email, workspaceId, htmlPath);
            return file.getContent();
        } catch (Exception e) {
            return null;
        }
    }

    private void materializeForPreview(
            String email,
            String workspaceId,
            List<FileProposalDto> files,
            Path tempDir
    ) throws IOException {
        if (files != null) {
            for (FileProposalDto f : files) {
                if (f.getPath() == null) {
                    continue;
                }
                String rel = f.getPath().replace('\\', '/');
                if (f.getContent() != null) {
                    Path out = tempDir.resolve(rel);
                    Files.createDirectories(out.getParent());
                    Files.writeString(out, f.getContent(), StandardCharsets.UTF_8);
                }
            }
        }
        // Best-effort: copy styles.css from disk if missing from proposals
        try {
            FileContentDto css = workspaceService.readFile(email, workspaceId, "styles.css");
            Path cssOut = tempDir.resolve("styles.css");
            if (!Files.exists(cssOut) && css.getContent() != null) {
                Files.writeString(cssOut, css.getContent(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
    }

    private boolean runHeadlessScreenshot(Path htmlFile, Path pngOut) {
        Path script = Path.of("scripts", "preview-static-site.mjs").toAbsolutePath();
        if (!Files.isRegularFile(script)) {
            script = Path.of(System.getProperty("user.dir"), "scripts", "preview-static-site.mjs");
        }
        if (!Files.isRegularFile(script)) {
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "node",
                    script.toString(),
                    htmlFile.toUri().toString(),
                    pngOut.toString()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = p.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            if (p.exitValue() != 0) {
                return false;
            }
            if (Files.size(pngOut) > 0) {
                return true;
            }
            // JSON fallback { ok: true } on stdout
            JsonNode node = mapper.readTree(out.isBlank() ? "{}" : out);
            return node.path("ok").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteRecursive(Path dir) {
        try {
            if (Files.isDirectory(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
                }
            }
        } catch (IOException ignored) {
        }
    }
}
