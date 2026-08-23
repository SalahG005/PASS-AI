package com.pass.ai_assistant_backend.project;

import com.pass.ai_assistant_backend.project.dto.CreateProjectRequest;
import com.pass.ai_assistant_backend.project.dto.CreateSkeletonProjectRequest;
import com.pass.ai_assistant_backend.project.dto.ProjectDto;
import com.pass.ai_assistant_backend.project.dto.RegisterLinkedProjectRequest;
import com.pass.ai_assistant_backend.service.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final WorkspaceService workspaceService;

    public ProjectController(ProjectService projectService, WorkspaceService workspaceService) {
        this.projectService = projectService;
        this.workspaceService = workspaceService;
    }

    private String email(Authentication authentication) {
        return authentication.getName();
    }

    @GetMapping
    public List<ProjectDto> list(Authentication authentication) {
        return projectService.list(email(authentication));
    }

    @GetMapping("/{projectId}")
    public ProjectDto get(Authentication authentication, @PathVariable String projectId) {
        return projectService.get(email(authentication), projectId);
    }

    @PostMapping("/skeleton")
    public ResponseEntity<?> createSkeleton(
            Authentication authentication,
            @RequestBody CreateSkeletonProjectRequest body
    ) {
        try {
            ProjectDto created = projectService.createSkeleton(email(authentication), body);
            return ResponseEntity.ok(Map.of(
                    "project", created,
                    "bound", true,
                    "path", created.getAbsolutePath(),
                    "tree", workspaceService.tree(email(authentication), created.getProjectId())
            ));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping
    public ResponseEntity<?> create(
            Authentication authentication,
            @RequestBody(required = false) CreateProjectRequest body
    ) {
        try {
            ProjectDto created = projectService.create(email(authentication), body);
            return ResponseEntity.ok(Map.of(
                    "project", created,
                    "bound", true,
                    "path", created.getAbsolutePath(),
                    "tree", workspaceService.tree(email(authentication), created.getProjectId())
            ));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/{projectId}/open")
    public ResponseEntity<?> open(Authentication authentication, @PathVariable String projectId) {
        try {
            ProjectDto opened = projectService.open(email(authentication), projectId);
            return ResponseEntity.ok(Map.of(
                    "project", opened,
                    "bound", true,
                    "path", opened.getAbsolutePath(),
                    "tree", workspaceService.tree(email(authentication), opened.getProjectId())
            ));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/link")
    public ResponseEntity<?> link(
            Authentication authentication,
            @RequestBody RegisterLinkedProjectRequest body
    ) {
        try {
            ProjectDto linked = projectService.registerLinked(email(authentication), body);
            return ResponseEntity.ok(Map.of(
                    "project", linked,
                    "bound", true,
                    "path", linked.getAbsolutePath(),
                    "tree", workspaceService.tree(email(authentication), linked.getProjectId())
            ));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/open-local")
    public ResponseEntity<?> openLocal(Authentication authentication) {
        try {
            ProjectDto linked = projectService.pickAndRegisterLinked(email(authentication));
            return ResponseEntity.ok(Map.of(
                    "project", linked,
                    "bound", true,
                    "path", linked.getAbsolutePath(),
                    "tree", workspaceService.tree(email(authentication), linked.getProjectId())
            ));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/{projectId}/save-as")
    public ResponseEntity<?> saveAs(Authentication authentication, @PathVariable String projectId) {
        try {
            ProjectDto relocated = projectService.relocateWithPicker(email(authentication), projectId);
            return ResponseEntity.ok(Map.of(
                    "project", relocated,
                    "bound", true,
                    "path", relocated.getAbsolutePath(),
                    "tree", workspaceService.tree(email(authentication), relocated.getProjectId())
            ));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/{projectId}/relocate")
    public ResponseEntity<?> relocate(
            Authentication authentication,
            @PathVariable String projectId,
            @RequestBody Map<String, String> body
    ) {
        try {
            String path = body == null ? null : body.get("path");
            ProjectDto relocated = projectService.relocateToPath(email(authentication), projectId, path);
            return ResponseEntity.ok(Map.of(
                    "project", relocated,
                    "bound", true,
                    "path", relocated.getAbsolutePath(),
                    "tree", workspaceService.tree(email(authentication), relocated.getProjectId())
            ));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PatchMapping("/{projectId}")
    public ProjectDto rename(
            Authentication authentication,
            @PathVariable String projectId,
            @RequestBody Map<String, String> body
    ) {
        return projectService.rename(email(authentication), projectId, body != null ? body.get("name") : null);
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<?> delete(
            Authentication authentication,
            @PathVariable String projectId,
            @RequestParam(value = "deleteFiles", defaultValue = "false") boolean deleteFiles
    ) {
        try {
            projectService.delete(email(authentication), projectId, deleteFiles);
            return ResponseEntity.noContent().build();
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/ensure")
    public ResponseEntity<?> ensure(
            Authentication authentication,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId,
            @RequestBody(required = false) Map<String, String> body
    ) {
        try {
            String name = body == null ? null : body.get("name");
            String ws = body != null && body.get("workspaceId") != null ? body.get("workspaceId") : workspaceId;
            ProjectDto ensured = projectService.ensureForWorkspace(email(authentication), ws, name);
            return ResponseEntity.ok(Map.of(
                    "project", ensured,
                    "bound", true,
                    "path", ensured.getAbsolutePath(),
                    "tree", workspaceService.tree(email(authentication), ensured.getProjectId())
            ));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }
}
