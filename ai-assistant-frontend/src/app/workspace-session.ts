import { Injectable } from '@angular/core';

const ACTIVE_PROJECT_KEY = 'pass_active_project_id';
const LEGACY_SESSION_KEY = 'pass_workspace_id';

@Injectable({ providedIn: 'root' })
export class WorkspaceSession {
  private workspaceId: string;
  private requestedFreshWindow = false;

  constructor() {
    const fromUrl = new URLSearchParams(window.location.search).get('ws');
    if (fromUrl && this.isValidId(fromUrl)) {
      this.workspaceId = fromUrl;
      // Keep the id local to this window so it never hijacks the active
      // project of the window that spawned it.
      this.requestedFreshWindow = true;
      sessionStorage.setItem(LEGACY_SESSION_KEY, fromUrl);
      return;
    }

    const active = localStorage.getItem(ACTIVE_PROJECT_KEY);
    if (active && this.isValidId(active)) {
      this.workspaceId = active;
      sessionStorage.setItem(LEGACY_SESSION_KEY, active);
      return;
    }

    const existing = sessionStorage.getItem(LEGACY_SESSION_KEY);
    if (existing && this.isValidId(existing)) {
      this.workspaceId = existing;
      return;
    }

    // Temporary id until /api/projects/ensure or create assigns a durable project
    this.workspaceId = this.createId();
    sessionStorage.setItem(LEGACY_SESSION_KEY, this.workspaceId);
  }

  get id(): string {
    return this.workspaceId;
  }

  /** True when this window was opened asking for its own empty workspace. */
  get isFreshWindow(): boolean {
    return this.requestedFreshWindow;
  }

  /** Switch the active project for this browser (persisted across reloads). */
  setActiveProject(projectId: string) {
    if (!this.isValidId(projectId)) {
      throw new Error('Invalid project id');
    }
    this.workspaceId = projectId;
    this.requestedFreshWindow = false;
    this.persist(projectId);
  }

  clearActiveProject() {
    this.workspaceId = this.createId();
    sessionStorage.setItem(LEGACY_SESSION_KEY, this.workspaceId);
    localStorage.removeItem(ACTIVE_PROJECT_KEY);
  }

  /** Create a brand-new empty workspace id (used by New window). */
  createFreshId(): string {
    return this.createId();
  }

  private persist(projectId: string) {
    sessionStorage.setItem(LEGACY_SESSION_KEY, projectId);
    localStorage.setItem(ACTIVE_PROJECT_KEY, projectId);
  }

  private isValidId(value: string): boolean {
    return /^[A-Za-z0-9_-]{4,64}$/.test(value);
  }

  private createId(): string {
    const rand = Math.random().toString(36).slice(2, 10);
    return `ws_${Date.now().toString(36)}_${rand}`;
  }
}
