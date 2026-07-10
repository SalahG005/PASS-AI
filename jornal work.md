# Journal Work

## Branch Review Notes

This branch turns the app into a browser-based IDE for authenticated users.

### Backend

- `SecurityConfig.java` keeps the app stateless, permits `/api/auth/**` and `/ws/**`, disables form login/basic auth/logout, and widens local CORS.
- `AuthController.java` now trims and lowercases email addresses and validates blank input before register/login.
- `AuthBeansConfig.java` adds an empty `UserDetailsService` to prevent Spring Boot from creating a default user.
- `WebSocketConfig.java` registers `/ws/terminal` and authenticates the WebSocket handshake with a JWT passed as a query parameter.
- `WorkspaceController.java` exposes workspace tree, file CRUD, folder creation, clear/import/upload, search, diagnostics, and debug-run endpoints.
- `WorkspaceService.java` stores each user in a hashed workspace root, supports per-session subfolders, safe path resolution, uploads, zip import, search, basic problem checks, and command execution.
- `TerminalWebSocketHandler.java` launches a real shell per WebSocket session and streams terminal output back to the client.
- `FileContentDto.java`, `FileNodeDto.java`, `ProblemDto.java`, and `SearchHitDto.java` are response/request DTOs for the workspace UI.
- `application.properties` adds the workspace root path and larger upload limits.

### Frontend

- `workspace-api.ts`, `workspace-session.ts`, `terminal.service.ts`, `theme.ts`, and `file-icons.ts` support workspace storage, terminal access, theme persistence, and file icon/language mapping.
- `monaco-editor.ts` embeds Monaco with custom themes and keyboard shortcuts.
- `chat-home.ts`, `chat-home.html`, and `chat-home.css` implement the IDE shell, explorer, editor tabs, terminal panel, search, diagnostics, and AI side panel.
- `login.ts` and `register.ts` handle auth forms and token storage.
- `auth.ts` stores the JWT, while `auth.interceptor.ts` attaches the JWT and `X-Workspace-Id` to API calls.
- `proxy.conf.json`, `angular.json`, `package.json`, and `package-lock.json` add dev proxying and Monaco-related assets/dependencies.

### Main Risks

- WebSocket auth uses a token in the URL query string, which is convenient but less safe than a header-based approach.
- `debug/run` can execute shell commands inside the workspace, so it is the most security-sensitive endpoint in the branch.
- The CORS rules are broader than before and are intended for localhost development.
- `application.properties` still contains local-only database credentials and a placeholder JWT secret.

### Merge Questions

1. Should WebSocket auth stay in the URL query string?
2. Is `debug/run` intended for production use or only local/dev?
3. Should mixed-case email accounts be migrated or normalized before merge?
4. Is the widened localhost CORS policy intentional outside development?

## Changes I Applied

I updated the auth flow and validation so the app behaves more safely and predictably:

- `app.routes.ts` now sends unauthenticated users to `/login` by default, and the wildcard route also falls back to `/login` instead of the chat shell.
- `auth.ts`, `auth.guard.ts`, `auth.interceptor.ts`, and `terminal.service.ts` now treat tokens as valid only if they exist and are not expired, which helps prevent the terminal WebSocket from opening with a stale or missing token.
- `login.ts` and `login.html` now show a clear user-facing error for failed login attempts and friendlier network/server messages.
- `register.ts` and `register.html` now validate email format, enforce the `@pass-consulting.com` domain on the frontend, and require strong passwords before submission.
- `AuthController.java` now enforces the same email-domain rule and password-strength rule on the backend, so the server rejects weak or out-of-policy registrations even if the frontend is bypassed.

I also verified the backend compiles successfully after these changes. The frontend build still has an existing CSS budget issue in `chat-home.css`, which is separate from the auth fixes.