import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface WorkspaceNode {
  name: string;
  path: string;
  type: 'file' | 'folder';
  children?: WorkspaceNode[];
  expanded?: boolean;
}

export interface FileContent {
  path: string;
  content: string;
}

export interface ProblemItem {
  severity: 'error' | 'warning' | 'info' | string;
  message: string;
  file: string;
  line: number;
}

export interface SearchHit {
  path: string;
  line: number;
  preview: string;
}

export interface UploadResult {
  ok: boolean;
  saved: number;
  tree?: WorkspaceNode;
}

export interface BindingResult {
  ok?: boolean;
  bound: boolean;
  path: string;
  tree?: WorkspaceNode;
  project?: ProjectSummary;
}

export interface ProjectSummary {
  projectId: string;
  name: string;
  absolutePath: string;
  storageType: string;
  createdAt?: string;
  updatedAt?: string;
  lastOpenedAt?: string;
}

export interface ProjectOpenResult {
  project: ProjectSummary;
  bound: boolean;
  path: string;
  tree?: WorkspaceNode;
}

export interface SnapshotResult {
  ok: boolean;
  gitAvailable?: boolean;
  created?: boolean;
  commit?: string;
  message?: string;
  error?: string;
}

export interface RevertResult {
  ok: boolean;
  message?: string;
  error?: string;
}

export interface BuildResult {
  ok: boolean;
  command: string;
  exitCode: number;
  output: string[];
  tail: string;
  projectDir: string;
  error?: string;
}

@Injectable({ providedIn: 'root' })
export class WorkspaceApi {
  private baseUrl = '/api/workspace';

  constructor(private http: HttpClient) {}

  tree(): Observable<WorkspaceNode> {
    return this.http.get<WorkspaceNode>(`${this.baseUrl}/tree`);
  }

  binding(): Observable<BindingResult> {
    return this.http.get<BindingResult>(`${this.baseUrl}/binding`);
  }

  openLocalFolder(): Observable<BindingResult> {
    return this.http.post<BindingResult>(`${this.baseUrl}/open-local`, {});
  }

  bindLocalFolder(path: string): Observable<BindingResult> {
    return this.http.post<BindingResult>(`${this.baseUrl}/bind`, { path });
  }

  unbindLocalFolder(): Observable<BindingResult> {
    return this.http.post<BindingResult>(`${this.baseUrl}/unbind`, {});
  }

  readFile(path: string): Observable<FileContent> {
    return this.http.get<FileContent>(`${this.baseUrl}/file`, { params: { path } });
  }

  writeFile(path: string, content: string): Observable<FileContent> {
    return this.http.put<FileContent>(`${this.baseUrl}/file`, { path, content });
  }

  createFile(path: string, content = ''): Observable<FileContent> {
    return this.http.post<FileContent>(`${this.baseUrl}/file`, { path, content });
  }

  createFolder(path: string): Observable<{ ok: boolean }> {
    return this.http.post<{ ok: boolean }>(`${this.baseUrl}/folder`, { path });
  }

  deletePath(path: string): Observable<{ ok: boolean }> {
    return this.http.delete<{ ok: boolean }>(`${this.baseUrl}/path`, { params: { path } });
  }

  renamePath(from: string, to: string): Observable<{ ok: boolean; path: string }> {
    return this.http.post<{ ok: boolean; path: string }>(`${this.baseUrl}/rename`, { from, to });
  }

  copyPath(from: string, to: string): Observable<{ ok: boolean; path: string }> {
    return this.http.post<{ ok: boolean; path: string }>(`${this.baseUrl}/copy`, { from, to });
  }

  absolutePath(path: string): Observable<{ path: string }> {
    return this.http.get<{ path: string }>(`${this.baseUrl}/absolute-path`, { params: { path } });
  }

  revealInExplorer(path: string): Observable<{ ok: boolean }> {
    return this.http.post<{ ok: boolean }>(`${this.baseUrl}/reveal`, { path });
  }

  clear(): Observable<{ ok: boolean }> {
    return this.http.post<{ ok: boolean }>(`${this.baseUrl}/clear`, {});
  }

  uploadZip(archive: Blob | File, replace = true): Observable<UploadResult> {
    const form = new FormData();
    form.append('archive', archive, 'workspace.zip');
    form.append('replace', String(replace));
    return this.http.post<UploadResult>(`${this.baseUrl}/upload-zip`, form);
  }

  upload(
    files: File[],
    relativePaths: string[],
    options: { replace?: boolean; final?: boolean } = {}
  ): Observable<UploadResult> {
    const form = new FormData();
    files.forEach((file) => form.append('files', file, file.name));
    relativePaths.forEach((p) => form.append('paths', p));
    form.append('replace', String(!!options.replace));
    form.append('final', String(options.final !== false));
    return this.http.post<UploadResult>(`${this.baseUrl}/upload`, form);
  }

  search(q: string): Observable<SearchHit[]> {
    return this.http.get<SearchHit[]>(`${this.baseUrl}/search`, { params: { q } });
  }

  problems(): Observable<ProblemItem[]> {
    return this.http.get<ProblemItem[]>(`${this.baseUrl}/problems`);
  }

  debugRun(command: string): Observable<{ output: string[] }> {
    return this.http.post<{ output: string[] }>(`${this.baseUrl}/debug/run`, { command });
  }

  snapshot(message: string): Observable<SnapshotResult> {
    return this.http.post<SnapshotResult>(`${this.baseUrl}/snapshot`, { message });
  }

  revert(commit?: string): Observable<RevertResult> {
    return this.http.post<RevertResult>(`${this.baseUrl}/revert`, commit ? { commit } : {});
  }

  build(): Observable<BuildResult> {
    return this.http.post<BuildResult>(`${this.baseUrl}/build`, {});
  }

  /** Copy Maven Wrapper into a folder that has pom.xml (no global mvn needed). */
  ensureMavenWrapper(path = ''): Observable<{
    ok: boolean;
    copied?: string[];
    command?: string;
    projectDir?: string;
    error?: string;
  }> {
    return this.http.post<{
      ok: boolean;
      copied?: string[];
      command?: string;
      projectDir?: string;
      error?: string;
    }>(`${this.baseUrl}/ensure-maven-wrapper`, { path });
  }

  listProjects(): Observable<ProjectSummary[]> {
    return this.http.get<ProjectSummary[]>('/api/projects');
  }

  createProject(name?: string): Observable<ProjectOpenResult> {
    return this.http.post<ProjectOpenResult>('/api/projects', name ? { name } : {});
  }

  openProject(projectId: string): Observable<ProjectOpenResult> {
    return this.http.post<ProjectOpenResult>(`/api/projects/${encodeURIComponent(projectId)}/open`, {});
  }

  openLocalProject(): Observable<ProjectOpenResult> {
    return this.http.post<ProjectOpenResult>('/api/projects/open-local', {});
  }

  linkProject(path: string, name?: string): Observable<ProjectOpenResult> {
    return this.http.post<ProjectOpenResult>('/api/projects/link', { path, name });
  }

  ensureProject(name?: string, workspaceId?: string): Observable<ProjectOpenResult> {
    return this.http.post<ProjectOpenResult>('/api/projects/ensure', {
      name,
      workspaceId
    });
  }

  saveProjectAs(projectId: string): Observable<ProjectOpenResult> {
    return this.http.post<ProjectOpenResult>(`/api/projects/${encodeURIComponent(projectId)}/save-as`, {});
  }

  renameProject(projectId: string, name: string): Observable<ProjectSummary> {
    return this.http.patch<ProjectSummary>(`/api/projects/${encodeURIComponent(projectId)}`, { name });
  }
}
