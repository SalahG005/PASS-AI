import { Injectable } from '@angular/core';

const STORAGE_KEY = 'pass_workspace_id';

@Injectable({ providedIn: 'root' })
export class WorkspaceSession {
  private workspaceId: string;

  constructor() {
    // Prefer URL ?ws= for new windows, else keep/create a session id for this browser tab
    const fromUrl = new URLSearchParams(window.location.search).get('ws');
    if (fromUrl && /^[A-Za-z0-9_-]{4,64}$/.test(fromUrl)) {
      this.workspaceId = fromUrl;
      sessionStorage.setItem(STORAGE_KEY, fromUrl);
      return;
    }

    const existing = sessionStorage.getItem(STORAGE_KEY);
    if (existing && /^[A-Za-z0-9_-]{4,64}$/.test(existing)) {
      this.workspaceId = existing;
      return;
    }

    this.workspaceId = this.createId();
    sessionStorage.setItem(STORAGE_KEY, this.workspaceId);
  }

  get id(): string {
    return this.workspaceId;
  }

  /** Create a brand-new empty workspace id (used by New window). */
  createFreshId(): string {
    return this.createId();
  }

  private createId(): string {
    const rand = Math.random().toString(36).slice(2, 10);
    return `ws_${Date.now().toString(36)}_${rand}`;
  }
}
