package com.pass.ai_assistant_backend.controller;

import com.pass.ai_assistant_backend.dto.FileContentDto;
import com.pass.ai_assistant_backend.dto.FileNodeDto;
import com.pass.ai_assistant_backend.dto.ProblemDto;
import com.pass.ai_assistant_backend.dto.SearchHitDto;
import com.pass.ai_assistant_backend.service.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    private String email(Authentication authentication) {
        return authentication.getName();
    }

    @GetMapping("/tree")
    public FileNodeDto tree(
            Authentication authentication,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) throws IOException {
        return workspaceService.tree(email(authentication), workspaceId);
    }

    @GetMapping("/file")
    public ResponseEntity<?> readFile(
            Authentication authentication,
            @RequestParam String path,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) {
        try {
            return ResponseEntity.ok(workspaceService.readFile(email(authentication), workspaceId, path));
        } catch (NoSuchFileException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("File not found");
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PutMapping("/file")
    public ResponseEntity<?> writeFile(
            Authentication authentication,
            @RequestBody FileContentDto body,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) {
        try {
            return ResponseEntity.ok(workspaceService.writeFile(email(authentication), workspaceId, body.getPath(), body.getContent()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/file")
    public ResponseEntity<?> createFile(
            Authentication authentication,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) {
        try {
            String path = body.get("path");
            workspaceService.createFile(email(authentication), workspaceId, path);
            String content = body.getOrDefault("content", "");
            return ResponseEntity.ok(workspaceService.writeFile(email(authentication), workspaceId, path, content));
        } catch (FileAlreadyExistsException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("File already exists");
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/folder")
    public ResponseEntity<?> createFolder(
            Authentication authentication,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) {
        try {
            workspaceService.createFolder(email(authentication), workspaceId, body.get("path"));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @DeleteMapping("/path")
    public ResponseEntity<?> deletePath(
            Authentication authentication,
            @RequestParam String path,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) {
        try {
            workspaceService.deletePath(email(authentication), workspaceId, path);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/rename")
    public ResponseEntity<?> rename(
            Authentication authentication,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) {
        try {
            String path = workspaceService.renamePath(
                    email(authentication), workspaceId, body.get("from"), body.get("to"));
            return ResponseEntity.ok(Map.of("ok", true, "path", path));
        } catch (NoSuchFileException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Path not found");
        } catch (FileAlreadyExistsException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Target already exists");
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/copy")
    public ResponseEntity<?> copy(
            Authentication authentication,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) {
        try {
            String path = workspaceService.copyPath(
                    email(authentication), workspaceId, body.get("from"), body.get("to"));
            return ResponseEntity.ok(Map.of("ok", true, "path", path));
        } catch (NoSuchFileException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Path not found");
        } catch (FileAlreadyExistsException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Target already exists");
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @GetMapping("/absolute-path")
    public ResponseEntity<?> absolutePath(
            Authentication authentication,
            @RequestParam String path,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) {
        try {
            String absolute = workspaceService.absolutePath(email(authentication), workspaceId, path);
            return ResponseEntity.ok(Map.of("path", absolute));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/reveal")
    public ResponseEntity<?> reveal(
            Authentication authentication,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) {
        try {
            workspaceService.revealInExplorer(email(authentication), workspaceId, body.get("path"));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (NoSuchFileException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Path not found");
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @GetMapping("/binding")
    public ResponseEntity<?> binding(
            Authentication authentication,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) throws IOException {
        String path = workspaceService.getBoundAbsolutePath(email(authentication), workspaceId);
        return ResponseEntity.ok(Map.of(
                "bound", path != null,
                "path", path == null ? "" : path
        ));
    }

    @PostMapping("/open-local")
    public ResponseEntity<?> openLocal(
            Authentication authentication,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) {
        try {
            var bound = workspaceService.pickAndBindLocalFolder(email(authentication), workspaceId);
            if (bound == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Folder selection cancelled");
            }
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "bound", true,
                    "path", bound.toString(),
                    "tree", workspaceService.tree(email(authentication), workspaceId)
            ));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/bind")
    public ResponseEntity<?> bind(
            Authentication authentication,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) {
        try {
            var bound = workspaceService.bindLocalFolder(email(authentication), workspaceId, body.get("path"));
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "bound", true,
                    "path", bound.toString(),
                    "tree", workspaceService.tree(email(authentication), workspaceId)
            ));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/unbind")
    public ResponseEntity<?> unbind(
            Authentication authentication,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) {
        try {
            workspaceService.unbindLocalFolder(email(authentication), workspaceId);
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "bound", false,
                    "tree", workspaceService.tree(email(authentication), workspaceId)
            ));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/clear")
    public ResponseEntity<?> clear(
            Authentication authentication,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) {
        try {
            workspaceService.clearWorkspace(email(authentication), workspaceId);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/upload-zip")
    public ResponseEntity<?> uploadZip(
            Authentication authentication,
            @RequestParam("archive") MultipartFile archive,
            @RequestParam(value = "replace", required = false, defaultValue = "true") boolean replace,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) {
        try {
            int saved = workspaceService.importZip(email(authentication), workspaceId, archive, replace);
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "saved", saved,
                    "tree", workspaceService.tree(email(authentication), workspaceId)
            ));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(e.getMessage() != null ? e.getMessage() : "Zip import failed");
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(
            Authentication authentication,
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "paths", required = false) String[] paths,
            @RequestParam(value = "replace", required = false, defaultValue = "false") boolean replace,
            @RequestParam(value = "final", required = false, defaultValue = "true") boolean isFinal,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) {
        try {
            if (replace) {
                workspaceService.clearWorkspace(email(authentication), workspaceId);
            }
            int saved = workspaceService.uploadFiles(email(authentication), workspaceId, files, paths);
            if (isFinal) {
                return ResponseEntity.ok(Map.of(
                        "ok", true,
                        "saved", saved,
                        "tree", workspaceService.tree(email(authentication), workspaceId)
                ));
            }
            return ResponseEntity.ok(Map.of("ok", true, "saved", saved));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(e.getMessage() != null ? e.getMessage() : "Upload failed");
        }
    }

    @GetMapping("/search")
    public List<SearchHitDto> search(
            Authentication authentication,
            @RequestParam String q,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) throws IOException {
        return workspaceService.search(email(authentication), workspaceId, q);
    }

    @GetMapping("/problems")
    public List<ProblemDto> problems(
            Authentication authentication,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) throws IOException {
        return workspaceService.analyzeProblems(email(authentication), workspaceId);
    }

    @PostMapping("/debug/run")
    public ResponseEntity<?> debugRun(
            Authentication authentication,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId
    ) {
        try {
            List<String> output = workspaceService.runDebugCommand(email(authentication), workspaceId, body.get("command"));
            return ResponseEntity.ok(Map.of("output", output));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }
}
