package com.pass.ai_assistant_backend.project;

import com.pass.ai_assistant_backend.project.dto.CreateProjectRequest;
import com.pass.ai_assistant_backend.project.dto.ProjectDto;
import com.pass.ai_assistant_backend.project.dto.RegisterLinkedProjectRequest;
import com.pass.ai_assistant_backend.service.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProjectService {

    public static final String STORAGE_APPDATA = "APPDATA";
    public static final String STORAGE_LINKED = "LINKED";
    public static final String STORAGE_RELOCATED = "RELOCATED";

    private final UserProjectRepository projects;
    private final WorkspaceService workspaceService;

    public ProjectService(UserProjectRepository projects, WorkspaceService workspaceService) {
        this.projects = projects;
        this.workspaceService = workspaceService;
    }

    @Transactional(readOnly = true)
    public List<ProjectDto> list(String email) {
        List<ProjectDto> out = new ArrayList<>();
        for (UserProject p : projects.findByEmailOrderByLastOpenedAtDesc(email)) {
            out.add(toDto(p));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public ProjectDto get(String email, String projectId) {
        return toDto(requireOwned(email, projectId));
    }

    @Transactional
    public ProjectDto create(String email, CreateProjectRequest request) throws IOException {
        String name = resolveName(request == null ? null : request.getName(), "Untitled Project");
        Path folder = allocateAppDataFolder(name);
        Files.createDirectories(folder);

        UserProject project = newUserProject(email, name, folder.toString(), STORAGE_APPDATA);
        projects.save(project);
        workspaceService.bindLocalFolder(email, project.getProjectId(), folder.toString());
        return toDto(project);
    }

    @Transactional
    public ProjectDto ensureForWorkspace(String email, String workspaceId, String preferredName) throws IOException {
        if (workspaceId == null || workspaceId.isBlank()) {
            return create(email, named(preferredName));
        }
        Optional<UserProject> existing = projects.findByProjectIdAndEmail(workspaceId, email);
        if (existing.isPresent()) {
            return touchOpened(existing.get());
        }

        // Migrate legacy session ids into durable projects.
        String name = resolveName(preferredName, "Workspace " + workspaceId);
        Path folder;
        String bound = workspaceService.getBoundAbsolutePath(email, workspaceId);
        String storage;
        if (bound != null && !bound.isBlank()) {
            folder = Paths.get(bound).toAbsolutePath().normalize();
            storage = STORAGE_LINKED;
        } else {
            folder = allocateAppDataFolder(name);
            Files.createDirectories(folder);
            // Copy sandbox session contents if present
            Path sandbox = workspaceService.resolveUserRoot(email, workspaceId);
            if (Files.isDirectory(sandbox) && !sandbox.equals(folder)) {
                copyDirectory(sandbox, folder);
            }
            storage = STORAGE_APPDATA;
            workspaceService.bindLocalFolder(email, workspaceId, folder.toString());
        }

        UserProject project = new UserProject();
        project.setProjectId(sanitizeProjectId(workspaceId));
        project.setEmail(email);
        project.setName(name);
        project.setAbsolutePath(folder.toString());
        project.setStorageType(storage);
        Instant now = Instant.now();
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        project.setLastOpenedAt(now);
        projects.save(project);
        return toDto(project);
    }

    @Transactional
    public ProjectDto open(String email, String projectId) throws IOException {
        UserProject project = requireOwned(email, projectId);
        Path path = Paths.get(project.getAbsolutePath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            Files.createDirectories(path);
        }
        // Heal projects that were named before folder renaming existed: they still sit in an
        // "Untitled Project-N" directory even though they have a real name.
        String folderName = path.getFileName() == null ? "" : path.getFileName().toString();
        if (folderName.toLowerCase().startsWith("untitled project")
                && !project.getName().toLowerCase().startsWith("untitled project")) {
            renameManagedFolder(email, project, project.getName());
            path = Paths.get(project.getAbsolutePath()).toAbsolutePath().normalize();
        }
        workspaceService.bindLocalFolder(email, project.getProjectId(), path.toString());
        return touchOpened(project);
    }

    @Transactional
    public ProjectDto registerLinked(String email, RegisterLinkedProjectRequest request) throws IOException {
        if (request == null || request.getPath() == null || request.getPath().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
        }
        Path folder = workspaceService.validateExternalFolderPublic(request.getPath());

        String folderName = folder.getFileName() != null ? folder.getFileName().toString() : "Imported Project";
        String name = resolveName(request.getName(), folderName);

        // Reuse existing project pointing at same path for this account
        for (UserProject existing : projects.findByEmailOrderByLastOpenedAtDesc(email)) {
            if (folder.toString().equalsIgnoreCase(existing.getAbsolutePath())) {
                workspaceService.bindLocalFolder(email, existing.getProjectId(), folder.toString());
                return touchOpened(existing);
            }
        }

        UserProject project = newUserProject(email, name, folder.toString(), STORAGE_LINKED);
        projects.save(project);
        workspaceService.bindLocalFolder(email, project.getProjectId(), folder.toString());
        return toDto(project);
    }

    @Transactional
    public ProjectDto pickAndRegisterLinked(String email) throws IOException {
        Path picked = workspaceService.pickFolderOnly();
        if (picked == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Folder selection cancelled");
        }
        RegisterLinkedProjectRequest req = new RegisterLinkedProjectRequest();
        req.setPath(picked.toString());
        return registerLinked(email, req);
    }

    @Transactional
    public ProjectDto rename(String email, String projectId, String name) {
        UserProject project = requireOwned(email, projectId);
        String next = resolveName(name, project.getName());
        project.setName(next);
        project.setUpdatedAt(Instant.now());
        // Keep the folder on disk in step with the display name, otherwise the file tree
        // keeps showing "Untitled Project-N" after an auto-rename.
        renameManagedFolder(email, project, next);
        projects.save(project);
        return toDto(project);
    }

    @Transactional
    public ProjectDto touchActivity(String email, String projectId) {
        Optional<UserProject> opt = projects.findByProjectIdAndEmail(projectId, email);
        if (opt.isEmpty()) {
            return null;
        }
        UserProject project = opt.get();
        Instant now = Instant.now();
        project.setUpdatedAt(now);
        project.setLastOpenedAt(now);
        projects.save(project);
        return toDto(project);
    }

    /**
     * Native Save Project: pick destination folder, copy project into a child folder,
     * rebind, and remove old AppData-managed folder when safe.
     */
    @Transactional
    public ProjectDto relocateWithPicker(String email, String projectId) throws IOException {
        UserProject project = requireOwned(email, projectId);
        Path pickedParent = workspaceService.pickProjectSaveLocation();
        if (pickedParent == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Folder selection cancelled");
        }
        return relocateToParent(email, project, pickedParent);
    }

    @Transactional
    public ProjectDto relocateToPath(String email, String projectId, String absoluteParentOrTarget) throws IOException {
        UserProject project = requireOwned(email, projectId);
        if (absoluteParentOrTarget == null || absoluteParentOrTarget.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
        }
        Path targetOrParent = Paths.get(absoluteParentOrTarget.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(targetOrParent)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Folder does not exist");
        }
        return relocateToParent(email, project, targetOrParent);
    }

    private ProjectDto relocateToParent(String email, UserProject project, Path pickedParent) throws IOException {
        Path source = Paths.get(project.getAbsolutePath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source project folder missing");
        }

        String folderName = sanitizeFolderName(project.getName());
        Path destination = pickedParent.resolve(folderName).normalize();
        // If user picked the project folder itself (same name already there), use it directly
        if (pickedParent.getFileName() != null
                && pickedParent.getFileName().toString().equalsIgnoreCase(folderName)
                && Files.isDirectory(pickedParent)) {
            destination = pickedParent;
        } else if (Files.exists(destination) && !destination.equals(source)) {
            destination = uniqueChild(pickedParent, folderName);
        }

        if (!destination.equals(source)) {
            Files.createDirectories(destination);
            copyDirectory(source, destination);
        }

        String previousType = project.getStorageType();
        Path previousPath = source;

        workspaceService.bindLocalFolder(email, project.getProjectId(), destination.toString());
        project.setAbsolutePath(destination.toString());
        project.setStorageType(STORAGE_RELOCATED);
        Instant now = Instant.now();
        project.setUpdatedAt(now);
        project.setLastOpenedAt(now);
        projects.save(project);

        // Only delete previous AppData-managed copy after successful relocation
        if (STORAGE_APPDATA.equals(previousType)
                && !previousPath.equals(destination)
                && isUnderPassAiAppData(previousPath)) {
            deleteDirectoryQuietly(previousPath);
        }

        return toDto(project);
    }

    @Transactional
    public void delete(String email, String projectId, boolean deleteFiles) throws IOException {
        UserProject project = requireOwned(email, projectId);
        workspaceService.unbindLocalFolder(email, projectId);
        if (deleteFiles && STORAGE_APPDATA.equals(project.getStorageType())) {
            Path path = Paths.get(project.getAbsolutePath());
            if (isUnderPassAiAppData(path)) {
                deleteDirectoryQuietly(path);
            }
        }
        projects.delete(project);
    }

    public UserProject requireOwned(String email, String projectId) {
        return projects.findByProjectIdAndEmail(projectId, email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    }

    public Optional<UserProject> findOwned(String email, String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        return projects.findByProjectIdAndEmail(projectId, email);
    }

    public Path resolveProjectRoot(String email, String projectId) throws IOException {
        UserProject project = requireOwned(email, projectId);
        Path path = Paths.get(project.getAbsolutePath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            Files.createDirectories(path);
        }
        return path;
    }

    public static Path passAiAppDataRoot() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            home = System.getenv("USERPROFILE");
        }
        if (home == null || home.isBlank()) {
            home = Paths.get(".").toAbsolutePath().toString();
        }
        return Paths.get(home, "Pass-AI").toAbsolutePath().normalize();
    }

    public static String sanitizeFolderName(String raw) {
        String base = raw == null ? "Untitled Project" : raw.trim();
        if (base.isBlank()) {
            base = "Untitled Project";
        }
        base = base.replaceAll("[\\\\/:*?\"<>|]", "-");
        base = base.replaceAll("\\s+", " ").trim();
        if (base.length() > 80) {
            base = base.substring(0, 80).trim();
        }
        if (base.isBlank() || ".".equals(base) || "..".equals(base)) {
            base = "Untitled Project";
        }
        return base;
    }

    public static String sanitizeProjectId(String raw) {
        if (raw != null && raw.matches("^[A-Za-z0-9_-]{4,64}$")) {
            return raw;
        }
        return newProjectId();
    }

    public static String newProjectId() {
        String id = "proj_" + UUID.randomUUID().toString().replace("-", "");
        return id.substring(0, Math.min(64, id.length()));
    }

    private UserProject newUserProject(String email, String name, String absolutePath, String storageType) {
        UserProject project = new UserProject();
        project.setProjectId(newProjectId());
        project.setEmail(email);
        project.setName(name);
        project.setAbsolutePath(absolutePath);
        project.setStorageType(storageType);
        Instant now = Instant.now();
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        project.setLastOpenedAt(now);
        return project;
    }

    private ProjectDto touchOpened(UserProject project) {
        Instant now = Instant.now();
        project.setLastOpenedAt(now);
        project.setUpdatedAt(now);
        projects.save(project);
        return toDto(project);
    }

    private CreateProjectRequest named(String name) {
        CreateProjectRequest req = new CreateProjectRequest();
        req.setName(name);
        return req;
    }

    private String resolveName(String requested, String fallback) {
        String name = requested == null || requested.isBlank() ? fallback : requested.trim();
        if (name.length() > 160) {
            name = name.substring(0, 160).trim();
        }
        return name;
    }

    /**
     * Rename the project directory to match the display name. Only ever touches folders the
     * app itself manages under the Pass-AI AppData root — a LINKED folder belongs to the user
     * and is never moved. Failures (folder locked by a running server) are non-fatal: the
     * display name still changes.
     */
    private void renameManagedFolder(String email, UserProject project, String displayName) {
        if (!STORAGE_APPDATA.equals(project.getStorageType())) {
            return;
        }
        try {
            Path current = Paths.get(project.getAbsolutePath()).toAbsolutePath().normalize();
            if (!Files.isDirectory(current) || !isUnderPassAiAppData(current)) {
                return;
            }
            Path parent = current.getParent();
            if (parent == null) {
                return;
            }
            String desired = sanitizeFolderName(displayName);
            String currentFolder = current.getFileName() == null ? "" : current.getFileName().toString();
            if (desired.isBlank() || desired.equals(currentFolder)) {
                return;
            }
            Path target = parent.resolve(desired);
            if (Files.exists(target)) {
                if (target.equals(current)) {
                    return;
                }
                target = uniqueChild(parent, desired);
            }
            Files.move(current, target);
            project.setAbsolutePath(target.toString());
            workspaceService.bindLocalFolder(email, project.getProjectId(), target.toString());
        } catch (IOException | RuntimeException e) {
            // Keep the new display name; the folder stays where it is.
        }
    }

    private Path allocateAppDataFolder(String displayName) throws IOException {
        Path root = passAiAppDataRoot();
        Files.createDirectories(root);
        String folderName = sanitizeFolderName(displayName);
        Path candidate = root.resolve(folderName);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        return uniqueChild(root, folderName);
    }

    private Path uniqueChild(Path parent, String baseName) {
        for (int i = 2; i < 1000; i++) {
            Path candidate = parent.resolve(baseName + "-" + i);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return parent.resolve(baseName + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private boolean isUnderPassAiAppData(Path path) {
        Path root = passAiAppDataRoot();
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(root);
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

    private void deleteDirectoryQuietly(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    private ProjectDto toDto(UserProject p) {
        return new ProjectDto(
                p.getProjectId(),
                p.getName(),
                p.getAbsolutePath(),
                p.getStorageType(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                p.getLastOpenedAt()
        );
    }
}
