import { beforeEach, describe, expect, it } from 'vitest';
import { WorkspaceSession } from './workspace-session';

describe('WorkspaceSession', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    window.history.replaceState({}, '', '/chat');
  });

  it('persists active project across instances', () => {
    const first = new WorkspaceSession();
    first.setActiveProject('proj_abc123def456');
    expect(localStorage.getItem('pass_active_project_id')).toBe('proj_abc123def456');

    const second = new WorkspaceSession();
    expect(second.id).toBe('proj_abc123def456');
  });

  it('keeps a new window isolated from the active project', () => {
    localStorage.setItem('pass_active_project_id', 'proj_existing_one');
    window.history.replaceState({}, '', '/chat?ws=ws_fresh_window1');

    const fresh = new WorkspaceSession();

    expect(fresh.id).toBe('ws_fresh_window1');
    expect(fresh.isFreshWindow).toBe(true);
    expect(localStorage.getItem('pass_active_project_id')).toBe('proj_existing_one');
  });

  it('stops being a fresh window once a project is activated', () => {
    window.history.replaceState({}, '', '/chat?ws=ws_fresh_window2');
    const session = new WorkspaceSession();

    session.setActiveProject('proj_real_project');

    expect(session.isFreshWindow).toBe(false);
    expect(localStorage.getItem('pass_active_project_id')).toBe('proj_real_project');
  });

  it('scopes recent-file keys by account and project', () => {
    const email = 'user@pass-consulting.com';
    const projectId = 'proj_one';
    const key = `pass-ai-recent-files::${email}::${projectId}`;
    localStorage.setItem(key, JSON.stringify([{ path: 'a.ts', name: 'a.ts', openedAt: 1 }]));
    expect(JSON.parse(localStorage.getItem(key) || '[]')).toHaveLength(1);
    expect(localStorage.getItem(`pass-ai-recent-files::other@pass-consulting.com::${projectId}`)).toBeNull();
  });
});
