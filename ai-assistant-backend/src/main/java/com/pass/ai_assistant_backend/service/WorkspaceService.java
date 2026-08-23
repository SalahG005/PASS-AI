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
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Service
public class WorkspaceService {

    private final Path root;
    private final Path userHome;
    private final ConcurrentHashMap<String, Path> bindings = new ConcurrentHashMap<>();

    public WorkspaceService(@Value("${app.workspace.root}") String rootPath) throws IOException {
        this.root = Paths.get(rootPath).toAbsolutePath().normalize();
        this.userHome = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }

    public Path resolveUserRoot(String email) throws IOException {
        return resolveUserRoot(email, null);
    }

    public Path resolveUserRoot(String email, String workspaceId) throws IOException {
        Path bound = getBoundRoot(email, workspaceId);
        if (bound != null) {
            return bound;
        }

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

    public String getBoundAbsolutePath(String email, String workspaceId) throws IOException {
        Path bound = getBoundRoot(email, workspaceId);
        return bound == null ? null : bound.toString();
    }

    public boolean isBound(String email, String workspaceId) throws IOException {
        return getBoundRoot(email, workspaceId) != null;
    }

    public Path bindLocalFolder(String email, String workspaceId, String absolutePath) throws IOException {
        Path target = validateExternalFolder(absolutePath);
        Path link = linkFile(email, workspaceId);
        Files.createDirectories(link.getParent());
        Files.writeString(link, target.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        bindings.put(bindingKey(email, workspaceId), target);
        return target;
    }

    public void unbindLocalFolder(String email, String workspaceId) throws IOException {
        bindings.remove(bindingKey(email, workspaceId));
        Path link = linkFile(email, workspaceId);
        Files.deleteIfExists(link);
    }

    /**
     * Opens a native OS folder picker on the machine running the backend
     * (same PC as the user for local PASS AI), then binds that folder.
     */
    public Path pickAndBindLocalFolder(String email, String workspaceId) throws IOException {
        Path picked = pickFolderNative(false);
        if (picked == null) {
            return null;
        }
        return bindLocalFolder(email, workspaceId, picked.toString());
    }

    /** Opens the native folder picker without binding. */
    public Path pickFolderOnly() throws IOException {
        return pickFolderNative(false);
    }

    /** Opens a Save As-styled folder picker for relocating a complete project. */
    public Path pickProjectSaveLocation() throws IOException {
        return pickFolderNative(true);
    }

    /** Validates an absolute folder path against sandbox rules and returns the normalized path. */
    public Path validateExternalFolderPublic(String absolutePath) throws IOException {
        return validateExternalFolder(absolutePath);
    }

    private Path getBoundRoot(String email, String workspaceId) throws IOException {
        String key = bindingKey(email, workspaceId);
        Path cached = bindings.get(key);
        if (cached != null && Files.isDirectory(cached)) {
            return cached;
        }

        Path link = linkFile(email, workspaceId);
        if (!Files.isRegularFile(link)) {
            return null;
        }
        String raw = Files.readString(link, StandardCharsets.UTF_8).trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            Path target = validateExternalFolder(raw);
            bindings.put(key, target);
            return target;
        } catch (SecurityException | IOException ex) {
            Files.deleteIfExists(link);
            bindings.remove(key);
            return null;
        }
    }

    private Path linkFile(String email, String workspaceId) throws IOException {
        Path meta = root.resolve(hashEmail(email)).resolve(".links");
        Files.createDirectories(meta);
        String session = sanitizeWorkspaceId(workspaceId);
        return meta.resolve((session == null ? "default" : session) + ".link");
    }

    private String bindingKey(String email, String workspaceId) {
        String session = null;
        try {
            session = sanitizeWorkspaceId(workspaceId);
        } catch (SecurityException ignored) {
            session = null;
        }
        return hashEmail(email) + "::" + (session == null ? "default" : session);
    }

    private Path validateExternalFolder(String absolutePath) throws IOException {
        if (absolutePath == null || absolutePath.isBlank()) {
            throw new SecurityException("Folder path is required");
        }
        Path target = Paths.get(absolutePath.trim()).toAbsolutePath().normalize();
        if (!Files.exists(target) || !Files.isDirectory(target)) {
            throw new SecurityException("Folder does not exist: " + target);
        }
        if (!isAllowedExternalRoot(target)) {
            throw new SecurityException("Folder is not allowed: " + target);
        }
        return target;
    }

    private boolean isAllowedExternalRoot(Path target) {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        // Always allow under the current user home
        if (target.startsWith(userHome)) {
            return !isDeniedSystemPath(target);
        }
        // Windows: allow other folders on the same machine under typical user data drives,
        // but block Windows / Program Files / system roots.
        if (os.contains("win")) {
            return !isDeniedSystemPath(target);
        }
        // Non-Windows: stay under home only
        return false;
    }

    private boolean isDeniedSystemPath(Path target) {
        String p = target.toString().toLowerCase(Locale.ROOT).replace('/', '\\');
        return p.startsWith("c:\\windows")
                || p.startsWith("c:\\program files")
                || p.startsWith("c:\\program files (x86)")
                || p.equals("c:\\")
                || p.startsWith("/etc")
                || p.startsWith("/usr")
                || p.startsWith("/bin")
                || p.startsWith("/sbin")
                || p.startsWith("/System");
    }

    private Path pickFolderNative(boolean saveAs) throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return pickFolderWindows(saveAs);
        }
        // Fallback: no GUI picker — caller should use bind with an explicit path
        return null;
    }

    private Path pickFolderWindows(boolean saveAs) throws IOException {
        // Modern Explorer-style dialog (IFileOpenDialog), not the old tree FolderBrowserDialog
        Path script = extractPickerScript();
        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-STA",
                "-ExecutionPolicy", "Bypass",
                "-File", script.toAbsolutePath().toString(),
                saveAs ? "save" : "open"
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output;
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            boolean finished = process.waitFor(3, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Folder picker timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Folder picker interrupted", e);
        }
        if (output.isEmpty()) {
            return null;
        }
        String[] lines = output.split("\\R");
        String path = "";
        for (String line : lines) {
            if (!line.isBlank() && !line.startsWith("Add-Type") && !line.contains("Exception")) {
                path = line.trim();
            }
        }
        if (path.isEmpty() || path.contains("Error") || path.contains("Exception")) {
            return null;
        }
        return Paths.get(path).toAbsolutePath().normalize();
    }

    private Path extractPickerScript() throws IOException {
        Path tmp = Files.createTempFile("pass-ai-pick-folder-", ".ps1");
        tmp.toFile().deleteOnExit();
        try (var in = WorkspaceService.class.getResourceAsStream("/pick-folder-modern.ps1")) {
            if (in == null) {
                throw new IOException("Missing pick-folder-modern.ps1 on classpath");
            }
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        return tmp;
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
        String label;
        if (isBound(email, workspaceId)) {
            // Real linked folder → show the actual folder name
            label = userRoot.getFileName() != null ? userRoot.getFileName().toString() : userRoot.toString();
        } else {
            // Sandbox / new window session → never show raw ws_xxx id
            label = "workspace";
        }
        FileNodeDto rootNode = new FileNodeDto(label, "", "folder");
        rootNode.setName(label);
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

    public String renamePath(String email, String workspaceId, String fromRelative, String toRelative) throws IOException {
        Path from = resolveSafe(email, workspaceId, fromRelative);
        Path to = resolveSafe(email, workspaceId, toRelative);
        if (!Files.exists(from)) {
            throw new NoSuchFileException(fromRelative);
        }
        if (Files.exists(to)) {
            throw new FileAlreadyExistsException(toRelative);
        }
        Files.createDirectories(to.getParent());
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(from, to);
        }
        return toRelative(resolveUserRoot(email, workspaceId), to);
    }

    public String copyPath(String email, String workspaceId, String fromRelative, String toRelative) throws IOException {
        Path from = resolveSafe(email, workspaceId, fromRelative);
        Path to = resolveSafe(email, workspaceId, toRelative);
        if (!Files.exists(from)) {
            throw new NoSuchFileException(fromRelative);
        }
        if (Files.exists(to)) {
            throw new FileAlreadyExistsException(toRelative);
        }
        if (Files.isDirectory(from)) {
            copyDirectory(from, to);
        } else {
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
        }
        return toRelative(resolveUserRoot(email, workspaceId), to);
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path dest = target.resolve(source.relativize(dir));
                Files.createDirectories(dest);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path dest = target.resolve(source.relativize(file));
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public String absolutePath(String email, String workspaceId, String relativePath) throws IOException {
        return resolveSafe(email, workspaceId, relativePath).toAbsolutePath().toString();
    }

    public void revealInExplorer(String email, String workspaceId, String relativePath) throws IOException {
        Path target = resolveSafe(email, workspaceId, relativePath);
        if (!Files.exists(target)) {
            throw new NoSuchFileException(relativePath);
        }
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        ProcessBuilder pb;
        if (os.contains("win")) {
            // Select the file/folder in Windows Explorer
            pb = new ProcessBuilder("explorer.exe", "/select,", target.toAbsolutePath().toString());
        } else if (os.contains("mac")) {
            pb = new ProcessBuilder("open", "-R", target.toAbsolutePath().toString());
        } else {
            Path openDir = Files.isDirectory(target) ? target : target.getParent();
            pb = new ProcessBuilder("xdg-open", openDir.toAbsolutePath().toString());
        }
        pb.start();
    }

    public void clearWorkspace(String email) throws IOException {
        clearWorkspace(email, null);
    }

    public void clearWorkspace(String email, String workspaceId) throws IOException {
        if (isBound(email, workspaceId)) {
            throw new SecurityException(
                    "Refusing to clear a linked real folder. Open a sandbox workspace or unbind first.");
        }
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

    /**
     * Copy Maven Wrapper (mvnw.cmd / mvnw + .mvn/wrapper) into a project folder so Spring Boot
     * can run without a global {@code mvn} install. Source is the backend's own wrapper.
     *
     * @param projectDir relative dir containing pom.xml ("" or "backend")
     */
    public Map<String, Object> ensureMavenWrapper(String email, String workspaceId, String projectDir)
            throws IOException {
        Path targetDir = resolveSafe(email, workspaceId, projectDir == null ? "" : projectDir);
        Files.createDirectories(targetDir);
        Path pom = targetDir.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            throw new NoSuchFileException("pom.xml not found in " + (projectDir == null || projectDir.isBlank() ? "." : projectDir));
        }

        Path sourceRoot = findBackendMavenWrapperRoot();
        List<String> copied = new ArrayList<>();
        String[] names = {"mvnw.cmd", "mvnw"};
        for (String name : names) {
            Path src = sourceRoot.resolve(name);
            if (Files.isRegularFile(src)) {
                Path dest = targetDir.resolve(name);
                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                copied.add(toRelative(resolveUserRoot(email, workspaceId), dest));
            }
        }
        Path wrapperSrc = sourceRoot.resolve(".mvn").resolve("wrapper");
        if (Files.isDirectory(wrapperSrc)) {
            Path wrapperDest = targetDir.resolve(".mvn").resolve("wrapper");
            Files.createDirectories(wrapperDest);
            try (Stream<Path> stream = Files.list(wrapperSrc)) {
                for (Path src : stream.toList()) {
                    if (Files.isRegularFile(src)) {
                        Path dest = wrapperDest.resolve(src.getFileName().toString());
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                        copied.add(toRelative(resolveUserRoot(email, workspaceId), dest));
                    }
                }
            }
        }
        // Ensure Windows can execute mvnw.cmd
        Path mvnwCmd = targetDir.resolve("mvnw.cmd");
        boolean ready = Files.isRegularFile(mvnwCmd) || Files.isRegularFile(targetDir.resolve("mvnw"));
        return Map.of(
                "ok", ready,
                "copied", copied,
                "command", Files.isRegularFile(mvnwCmd) ? ".\\mvnw.cmd spring-boot:run" : "./mvnw spring-boot:run",
                "projectDir", projectDir == null ? "" : projectDir.replace('\\', '/')
        );
    }

    // ---- Auto-fix / verify loop support: git snapshots + build runner ----

    /**
     * Commit the whole workspace so a later revert can undo automatic edits.
     * Uses per-command git identity (never touches global config). Safe when git
     * is not installed — returns {@code gitAvailable:false} instead of throwing.
     */
    public Map<String, Object> snapshot(String email, String workspaceId, String message) throws IOException {
        Path dir = resolveUserRoot(email, workspaceId);
        String msg = (message == null || message.isBlank()) ? "PASS AI snapshot" : message.trim();

        ProcResult probe = runProcess(dir, 120, List.of("git", "--version"));
        if (!probe.started()) {
            return Map.of("ok", false, "gitAvailable", false,
                    "message", "git is not installed — automatic undo is unavailable");
        }

        boolean hasRepo = Files.isDirectory(dir.resolve(".git"));
        if (!hasRepo) {
            runProcess(dir, 120, List.of("git", "init"));
            runProcess(dir, 120, List.of("git", "symbolic-ref", "HEAD", "refs/heads/main"));
            ensureGitIgnore(dir);
        }
        runProcess(dir, 300, List.of("git", "add", "-A"));
        ProcResult commit = runProcess(dir, 300, List.of(
                "git", "-c", "user.email=passai@local", "-c", "user.name=PASS AI",
                "commit", "-m", msg, "--allow-empty"));
        ProcResult head = runProcess(dir, 60, List.of("git", "rev-parse", "HEAD"));
        String hash = head.output().isEmpty() ? "" : head.output().get(0).trim();

        return Map.of(
                "ok", commit.exitCode() == 0 || !hash.isBlank(),
                "gitAvailable", true,
                "created", !hasRepo,
                "commit", hash,
                "message", msg
        );
    }

    /** Discard all changes back to a snapshot (given commit, else last commit). */
    public Map<String, Object> revertToSnapshot(String email, String workspaceId, String commit) throws IOException {
        Path dir = resolveUserRoot(email, workspaceId);
        if (!Files.isDirectory(dir.resolve(".git"))) {
            return Map.of("ok", false, "message", "No snapshot to revert to (git repo not initialized)");
        }
        String target = (commit == null || commit.isBlank()) ? "HEAD" : commit.trim();
        if (!target.matches("[A-Za-z0-9_./-]{1,120}")) {
            return Map.of("ok", false, "message", "Invalid commit reference");
        }
        ProcResult reset = runProcess(dir, 120, List.of("git", "reset", "--hard", target));
        runProcess(dir, 120, List.of("git", "clean", "-fd"));
        return Map.of(
                "ok", reset.exitCode() == 0,
                "message", reset.exitCode() == 0 ? "Reverted to " + target : "Revert failed"
        );
    }

    /**
     * Detect the project type and run a NON-interactive build, capturing output + exit code.
     * Maven (via wrapper) → compile; Node → install (if needed) + build; static → nothing to build.
     */
    public Map<String, Object> runBuild(String email, String workspaceId) throws IOException {
        Path userRoot = resolveUserRoot(email, workspaceId);
        boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

        Path pom = findFile(userRoot, "pom.xml");
        if (pom != null) {
            Path projectDir = pom.getParent();
            if (!Files.isRegularFile(projectDir.resolve("mvnw.cmd")) && !Files.isRegularFile(projectDir.resolve("mvnw"))) {
                try {
                    ensureMavenWrapper(email, workspaceId, toRelative(userRoot, projectDir));
                } catch (Exception ignored) {
                    // fall through; build will report if mvn missing
                }
            }
            List<String> cmd = isWindows
                    ? List.of("cmd.exe", "/c", "mvnw.cmd", "-q", "-DskipTests", "compile")
                    : List.of("sh", "-c", "./mvnw -q -DskipTests compile");
            ProcResult r = runProcess(projectDir, 600, cmd);
            return buildResult(r, "mvnw compile", toRelative(userRoot, projectDir));
        }

        Path pkg = findFile(userRoot, "package.json");
        if (pkg != null) {
            Path projectDir = pkg.getParent();
            String pkgText = Files.readString(pkg, StandardCharsets.UTF_8);
            boolean hasBuildScript = pkgText.matches("(?s).*\"scripts\"\\s*:\\s*\\{[^}]*\"build\"\\s*:.*");
            if (!Files.isDirectory(projectDir.resolve("node_modules"))) {
                List<String> install = isWindows
                        ? List.of("cmd.exe", "/c", "npm", "install")
                        : List.of("sh", "-c", "npm install");
                ProcResult installRes = runProcess(projectDir, 600, install);
                if (installRes.exitCode() != 0) {
                    return buildResult(installRes, "npm install", toRelative(userRoot, projectDir));
                }
            }
            List<String> cmd;
            String label;
            if (hasBuildScript) {
                cmd = isWindows ? List.of("cmd.exe", "/c", "npm", "run", "build")
                        : List.of("sh", "-c", "npm run build");
                label = "npm run build";
            } else {
                cmd = isWindows ? List.of("cmd.exe", "/c", "npx", "--yes", "tsc", "--noEmit")
                        : List.of("sh", "-c", "npx --yes tsc --noEmit");
                label = "tsc --noEmit";
            }
            ProcResult r = runProcess(projectDir, 600, cmd);
            return buildResult(r, label, toRelative(userRoot, projectDir));
        }

        if (findFile(userRoot, "index.html") != null) {
            return Map.of("ok", true, "command", "(static site)", "exitCode", 0,
                    "output", List.of("Static HTML site — nothing to build."),
                    "tail", "Static HTML site — nothing to build.", "projectDir", "");
        }

        return Map.of("ok", false, "command", "(none)", "exitCode", -1,
                "output", List.of("No buildable project found (need pom.xml, package.json, or index.html)."),
                "tail", "No buildable project found (need pom.xml, package.json, or index.html).",
                "projectDir", "");
    }

    private Map<String, Object> buildResult(ProcResult r, String command, String projectDir) {
        boolean ok = r.started() && r.exitCode() == 0;
        List<String> out = r.output();
        String tail = out.isEmpty() ? "" : String.join("\n", out.subList(Math.max(0, out.size() - 60), out.size()));
        if (!r.started()) {
            String miss = command + " could not start (tool not installed / not on PATH).";
            return Map.of("ok", false, "command", command, "exitCode", -1,
                    "output", List.of(miss), "tail", miss, "projectDir", projectDir == null ? "" : projectDir);
        }
        return Map.of("ok", ok, "command", command, "exitCode", r.exitCode(),
                "output", out, "tail", tail, "projectDir", projectDir == null ? "" : projectDir);
    }

    private void ensureGitIgnore(Path dir) {
        Path gi = dir.resolve(".gitignore");
        if (Files.exists(gi)) {
            return;
        }
        try {
            Files.writeString(gi, String.join("\n",
                    "node_modules/", "target/", "dist/", "build/", ".gradle/", "*.class", ".DS_Store", ""),
                    StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    /** Shallow search skipping heavy/vendor dirs; returns the shallowest match. */
    private Path findFile(Path root, String name) throws IOException {
        if (!Files.isDirectory(root)) {
            return null;
        }
        final Path[] found = {null};
        final int maxDepth = 4;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                String n = d.getFileName() == null ? "" : d.getFileName().toString();
                if (!d.equals(root) && (n.equals("node_modules") || n.equals("target") || n.equals(".git")
                        || n.equals("dist") || n.equals("build") || n.equals(".mvn"))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (root.relativize(d).getNameCount() > maxDepth) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().equals(name)) {
                    if (found[0] == null
                            || root.relativize(file).getNameCount() < root.relativize(found[0]).getNameCount()) {
                        found[0] = file;
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found[0];
    }

    private ProcResult runProcess(Path dir, int timeoutSeconds, List<String> command) {
        List<String> output = new ArrayList<>();
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new ProcResult(false, -1, output);
        }
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                if (count < 600) {
                    output.add(line);
                }
                count++;
            }
            boolean done = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                output.add("Process timed out after " + timeoutSeconds + "s and was terminated.");
                return new ProcResult(true, -1, output);
            }
            return new ProcResult(true, process.exitValue(), output);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            process.destroyForcibly();
            output.add("Error: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            return new ProcResult(true, -1, output);
        }
    }

    private record ProcResult(boolean started, int exitCode, List<String> output) {
    }

    private Path findBackendMavenWrapperRoot() throws IOException {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd.resolve("mvnw.cmd")) || Files.isRegularFile(cwd.resolve("mvnw"))) {
            return cwd;
        }
        Path parent = cwd.getParent();
        if (parent != null) {
            Path backend = parent.resolve("ai-assistant-backend");
            if (Files.isRegularFile(backend.resolve("mvnw.cmd"))) {
                return backend;
            }
        }
        // Fall back: search upward a few levels
        Path p = cwd;
        for (int i = 0; i < 5 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("mvnw.cmd"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new NoSuchFileException("Maven wrapper (mvnw.cmd) not found next to the backend");
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
