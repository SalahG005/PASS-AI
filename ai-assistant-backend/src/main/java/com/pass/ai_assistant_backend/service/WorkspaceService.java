package com.pass.ai_assistant_backend.service;

import com.pass.ai_assistant_backend.dto.FileContentDto;
import com.pass.ai_assistant_backend.dto.FileNodeDto;
import com.pass.ai_assistant_backend.dto.ProblemDto;
import com.pass.ai_assistant_backend.dto.SearchHitDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@Service
public class WorkspaceService {

    private final Path root;

    public WorkspaceService(@Value("${app.workspace.root}") String rootPath) throws IOException {
        this.root = Paths.get(rootPath).toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }

    public Path resolveUserRoot(String email) throws IOException {
        return resolveUserRoot(email, null);
    }

    public Path resolveUserRoot(String email, String workspaceId) throws IOException {
        Path userRoot = root.resolve(hashEmail(email)).normalize();
        if (!userRoot.startsWith(root)) {
            throw new SecurityException("Invalid workspace path");
        }
        Files.createDirectories(userRoot);

        String session = sanitizeWorkspaceId(workspaceId);
        if (session == null) {
            return userRoot;
        }

        Path sessionRoot = userRoot.resolve("sessions").resolve(session).normalize();
        if (!sessionRoot.startsWith(userRoot.resolve("sessions"))) {
            throw new SecurityException("Invalid workspace session");
        }
        Files.createDirectories(sessionRoot);
        return sessionRoot;
    }

    private String sanitizeWorkspaceId(String workspaceId) {
        if (workspaceId == null) {
            return null;
        }
        String value = workspaceId.trim();
        if (value.isEmpty() || "default".equalsIgnoreCase(value)) {
            return null;
        }
        // Only allow safe session ids from the client
        if (!value.matches("^[A-Za-z0-9_-]{4,64}$")) {
            throw new SecurityException("Invalid workspace id");
        }
        return value;
    }

    public FileNodeDto tree(String email) throws IOException {
        return tree(email, null);
    }

    public FileNodeDto tree(String email, String workspaceId) throws IOException {
        Path userRoot = resolveUserRoot(email, workspaceId);
        FileNodeDto rootNode = new FileNodeDto(userRoot.getFileName().toString(), "", "folder");
        rootNode.setName("workspace");
        rootNode.setChildren(listChildren(userRoot, userRoot));
        return rootNode;
    }

    private List<FileNodeDto> listChildren(Path userRoot, Path dir) throws IOException {
        List<FileNodeDto> nodes = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> sorted = stream
                    .filter(p -> !isHiddenName(p.getFileName().toString()))
                    .sorted(Comparator
                            .comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();

            for (Path child : sorted) {
                String relative = toRelative(userRoot, child);
                if (Files.isDirectory(child)) {
                    FileNodeDto folder = new FileNodeDto(child.getFileName().toString(), relative, "folder");
                    folder.setChildren(listChildren(userRoot, child));
                    nodes.add(folder);
                } else {
                    nodes.add(new FileNodeDto(child.getFileName().toString(), relative, "file"));
                }
            }
        }
        return nodes;
    }

    public FileContentDto readFile(String email, String relativePath) throws IOException {
        return readFile(email, null, relativePath);
    }

    public FileContentDto readFile(String email, String workspaceId, String relativePath) throws IOException {
        Path file = resolveSafe(email, workspaceId, relativePath);
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(relativePath);
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        return new FileContentDto(toRelative(resolveUserRoot(email, workspaceId), file), content);
    }

    public FileContentDto writeFile(String email, String relativePath, String content) throws IOException {
        return writeFile(email, null, relativePath, content);
    }

    public FileContentDto writeFile(String email, String workspaceId, String relativePath, String content) throws IOException {
        Path file = resolveSafe(email, workspaceId, relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return new FileContentDto(toRelative(resolveUserRoot(email, workspaceId), file), content == null ? "" : content);
    }

    public void createFile(String email, String relativePath) throws IOException {
        createFile(email, null, relativePath);
    }

    public void createFile(String email, String workspaceId, String relativePath) throws IOException {
        Path file = resolveSafe(email, workspaceId, relativePath);
        Files.createDirectories(file.getParent());
        if (Files.exists(file)) {
            throw new FileAlreadyExistsException(relativePath);
        }
        Files.createFile(file);
    }

    public void createFolder(String email, String relativePath) throws IOException {
        createFolder(email, null, relativePath);
    }

    public void createFolder(String email, String workspaceId, String relativePath) throws IOException {
        Path folder = resolveSafe(email, workspaceId, relativePath);
        Files.createDirectories(folder);
    }

    public void deletePath(String email, String relativePath) throws IOException {
        deletePath(email, null, relativePath);
    }

    public void deletePath(String email, String workspaceId, String relativePath) throws IOException {
        Path target = resolveSafe(email, workspaceId, relativePath);
        if (!Files.exists(target)) {
            return;
        }
        if (Files.isDirectory(target)) {
            try (Stream<Path> walk = Files.walk(target)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            }
        } else {
            Files.deleteIfExists(target);
        }
    }

    public void clearWorkspace(String email) throws IOException {
        clearWorkspace(email, null);
    }

    public void clearWorkspace(String email, String workspaceId) throws IOException {
        Path userRoot = resolveUserRoot(email, workspaceId);
        if (!Files.exists(userRoot)) {
            return;
        }
        boolean isDefault = sanitizeWorkspaceId(workspaceId) == null;
        try (Stream<Path> walk = Files.walk(userRoot)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                if (path.equals(userRoot)) {
                    return;
                }
                // Never wipe other IDE window sessions when clearing the main workspace
                if (isDefault && userRoot.resolve("sessions").equals(path)) {
                    return;
                }
                if (isDefault && path.startsWith(userRoot.resolve("sessions"))) {
                    return;
                }
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }

    public int uploadFiles(String email, MultipartFile[] files, String[] relativePaths) throws IOException {
        return uploadFiles(email, null, files, relativePaths);
    }

    public int uploadFiles(String email, String workspaceId, MultipartFile[] files, String[] relativePaths) throws IOException {
        if (files == null || files.length == 0) {
            return 0;
        }
        int saved = 0;
        for (int i = 0; i < files.length; i++) {
            MultipartFile file = files[i];
            if (file == null || file.isEmpty()) {
                continue;
            }
            String relative = (relativePaths != null && i < relativePaths.length && relativePaths[i] != null && !relativePaths[i].isBlank())
                    ? relativePaths[i]
                    : file.getOriginalFilename();
            if (relative == null || relative.isBlank()) {
                continue;
            }
            relative = normalizeRelative(relative);
            if (shouldSkipRelative(relative)) {
                continue;
            }
            Path target = resolveSafe(email, workspaceId, relative);
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(target, file.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            saved++;
        }
        return saved;
    }

    /**
     * Fast path: one ZIP archive containing any number of files.
     */
    public int importZip(String email, MultipartFile zipFile, boolean replace) throws IOException {
        return importZip(email, null, zipFile, replace);
    }

    public int importZip(String email, String workspaceId, MultipartFile zipFile, boolean replace) throws IOException {
        if (zipFile == null || zipFile.isEmpty()) {
            return 0;
        }
        if (replace) {
            clearWorkspace(email, workspaceId);
        }

        int saved = 0;
        Path userRoot = resolveUserRoot(email, workspaceId);

        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(zipFile.getInputStream())) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zis.getNextEntry()) != null) {
                String relative = normalizeRelative(entry.getName());
                if (relative.isBlank() || shouldSkipRelative(relative)) {
                    zis.closeEntry();
                    continue;
                }
                Path target = resolveSafe(email, workspaceId, relative);
                if (!target.startsWith(userRoot)) {
                    zis.closeEntry();
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    zis.closeEntry();
                    continue;
                }
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (var out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    int read;
                    while ((read = zis.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                saved++;
                zis.closeEntry();
            }
        }
        return saved;
    }

    private String normalizeRelative(String relative) {
        String value = relative == null ? "" : relative.replace('\\', '/');
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    private boolean shouldSkipRelative(String relative) {
        String lower = relative.toLowerCase(Locale.ROOT);
        return lower.contains("..")
                || lower.contains("/node_modules/") || lower.startsWith("node_modules/")
                || lower.contains("/.git/") || lower.startsWith(".git/")
                || lower.contains("/__pycache__/") || lower.startsWith("__pycache__/")
                || lower.contains("/.pytest_cache/") || lower.contains("/.mypy_cache/")
                || lower.contains("/dist/") || lower.contains("/target/")
                || lower.contains("/.next/") || lower.contains("/build/")
                || lower.contains("/.idea/") || lower.contains("/.vscode/")
                || lower.endsWith(".pyc") || lower.endsWith(".pyo")
                || lower.endsWith(".jar") || lower.endsWith(".war")
                || lower.endsWith(".exe") || lower.endsWith(".dll");
    }

    private boolean isHiddenName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("__pycache__")
                || lower.equals("node_modules")
                || lower.equals(".git")
                || lower.equals(".pytest_cache")
                || lower.equals(".mypy_cache")
                || lower.endsWith(".pyc")
                || lower.endsWith(".pyo");
    }

    public List<SearchHitDto> search(String email, String query) throws IOException {
        return search(email, null, query);
    }

    public List<SearchHitDto> search(String email, String workspaceId, String query) throws IOException {
        List<SearchHitDto> hits = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return hits;
        }
        Path userRoot = resolveUserRoot(email, workspaceId);
        String needle = query.toLowerCase(Locale.ROOT);

        Files.walkFileTree(userRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.size() > 1_000_000) {
                    return FileVisitResult.CONTINUE;
                }
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!(name.endsWith(".java") || name.endsWith(".ts") || name.endsWith(".js")
                        || name.endsWith(".json") || name.endsWith(".md") || name.endsWith(".xml")
                        || name.endsWith(".html") || name.endsWith(".css") || name.endsWith(".txt")
                        || name.endsWith(".py") || name.endsWith(".properties") || name.endsWith(".yml")
                        || name.endsWith(".yaml") || !name.contains("."))) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        if (line.toLowerCase(Locale.ROOT).contains(needle)) {
                            hits.add(new SearchHitDto(toRelative(userRoot, file), i + 1, trimPreview(line)));
                            if (hits.size() >= 100) {
                                return FileVisitResult.TERMINATE;
                            }
                        }
                    }
                } catch (IOException ignored) {
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return hits;
    }

    public List<ProblemDto> analyzeProblems(String email) throws IOException {
        return analyzeProblems(email, null);
    }

    public List<ProblemDto> analyzeProblems(String email, String workspaceId) throws IOException {
        List<ProblemDto> problems = new ArrayList<>();
        Path userRoot = resolveUserRoot(email, workspaceId);

        Files.walkFileTree(userRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                String relative = toRelative(userRoot, file);
                try {
                    if (name.endsWith(".java")) {
                        analyzeJava(file, relative, problems);
                    } else if (name.endsWith(".json")) {
                        analyzeJson(file, relative, problems);
                    } else if (name.endsWith(".ts") || name.endsWith(".js")) {
                        analyzeBraces(file, relative, problems);
                    }
                } catch (IOException ignored) {
                }
                return FileVisitResult.CONTINUE;
            }
        });

        if (problems.isEmpty()) {
            problems.add(new ProblemDto("info", "No issues detected in workspace.", "workspace", 1));
        }
        return problems;
    }

    public List<String> runDebugCommand(String email, String command) throws IOException, InterruptedException {
        return runDebugCommand(email, null, command);
    }

    public List<String> runDebugCommand(String email, String workspaceId, String command) throws IOException, InterruptedException {
        Path userRoot = resolveUserRoot(email, workspaceId);
        List<String> output = new ArrayList<>();
        if (command == null || command.isBlank()) {
            output.add("No command provided.");
            return output;
        }

        boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        ProcessBuilder pb = isWindows
                ? new ProcessBuilder("cmd.exe", "/c", command)
                : new ProcessBuilder("bash", "-lc", command);
        pb.directory(userRoot.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < 400) {
                output.add(line);
                count++;
            }
        }
        int code = process.waitFor();
        output.add("Process exited with code " + code);
        return output;
    }

    private void analyzeJava(Path file, String relative, List<ProblemDto> problems) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int open = 0;
        int close = 0;
        boolean hasClass = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            open += countChar(line, '{');
            close += countChar(line, '}');
            if (line.contains("class ") || line.contains("interface ") || line.contains("enum ") || line.contains("record ")) {
                hasClass = true;
            }
            if (line.contains("System.out.println") && !line.trim().endsWith(";") && !line.trim().endsWith("{")) {
                problems.add(new ProblemDto("warning", "Possible missing semicolon", relative, i + 1));
            }
        }
        if (open != close) {
            problems.add(new ProblemDto("error", "Unbalanced braces {=" + open + " }=" + close, relative, 1));
        }
        if (!hasClass && !lines.isEmpty()) {
            problems.add(new ProblemDto("info", "No class/interface declaration found", relative, 1));
        }
    }

    private void analyzeJson(Path file, String relative, List<ProblemDto> problems) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8).trim();
        if (content.isEmpty()) {
            problems.add(new ProblemDto("warning", "Empty JSON file", relative, 1));
            return;
        }
        int open = countChar(content, '{') + countChar(content, '[');
        int close = countChar(content, '}') + countChar(content, ']');
        if (open != close) {
            problems.add(new ProblemDto("error", "Unbalanced JSON brackets", relative, 1));
        }
        if (!(content.startsWith("{") || content.startsWith("["))) {
            problems.add(new ProblemDto("error", "JSON should start with { or [", relative, 1));
        }
    }

    private void analyzeBraces(Path file, String relative, List<ProblemDto> problems) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        int open = countChar(content, '{');
        int close = countChar(content, '}');
        if (open != close) {
            problems.add(new ProblemDto("error", "Unbalanced braces", relative, 1));
        }
    }

    private int countChar(String text, char c) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private String trimPreview(String line) {
        String trimmed = line.trim();
        return trimmed.length() > 160 ? trimmed.substring(0, 157) + "..." : trimmed;
    }

    public Path resolveSafe(String email, String relativePath) throws IOException {
        return resolveSafe(email, null, relativePath);
    }

    public Path resolveSafe(String email, String workspaceId, String relativePath) throws IOException {
        Path userRoot = resolveUserRoot(email, workspaceId);
        String normalized = relativePath == null ? "" : relativePath.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.contains("..")) {
            throw new SecurityException("Path traversal is not allowed");
        }
        Path resolved = userRoot.resolve(normalized).normalize();
        if (!resolved.startsWith(userRoot)) {
            throw new SecurityException("Invalid path");
        }
        return resolved;
    }

    private String toRelative(Path userRoot, Path path) {
        return userRoot.relativize(path).toString().replace('\\', '/');
    }

    private String hashEmail(String email) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(email.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            return email.replaceAll("[^a-zA-Z0-9]", "_");
        }
    }
}
