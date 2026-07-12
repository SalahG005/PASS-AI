import { Component, ApplicationRef, ChangeDetectorRef, ElementRef, HostListener, NgZone, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NgTemplateOutlet } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { Auth } from '../auth';
import { getFileIcon } from '../file-icons';
import { MonacoEditorComponent } from '../monaco-editor/monaco-editor';
import { IdeTerminalComponent } from '../ide-terminal/ide-terminal';
import { TerminalService, TerminalShell } from '../terminal.service';
import { ThemeService } from '../theme';
import { WorkspaceSession } from '../workspace-session';
import {
  BindingResult,
  ProblemItem,
  SearchHit,
  UploadResult,
  WorkspaceApi,
  WorkspaceNode
} from '../workspace-api';

export type ActivityView = 'files' | 'search' | 'git' | 'debug' | 'extensions';
export type BottomPanelTab = 'terminal' | 'problems' | 'output' | 'debug';
export type AiTab = 'chat' | 'composer' | 'agent';

export interface EditorTab {
  id: string;
  title: string;
  path: string;
  content: string;
  dirty?: boolean;
  unsavedNew?: boolean;
}

export interface ChatMessage {
  id: number;
  role: 'assistant' | 'user';
  text: string;
}

export interface ExplorerClipboard {
  mode: 'cut' | 'copy';
  path: string;
  name: string;
  type: 'file' | 'folder';
}

export interface RecentFileEntry {
  path: string;
  name: string;
  openedAt: number;
}

export interface ContextMenuState {
  x: number;
  y: number;
  node: WorkspaceNode;
}

@Component({
  selector: 'app-chat-home',
  imports: [FormsModule, NgTemplateOutlet, MonacoEditorComponent, IdeTerminalComponent],
  templateUrl: './chat-home.html',
  styleUrl: './chat-home.css'
})
export class ChatHome implements OnInit, OnDestroy {
  @ViewChild('folderInput') folderInput?: ElementRef<HTMLInputElement>;
  @ViewChild('fileInput') fileInput?: ElementRef<HTMLInputElement>;
  @ViewChild(IdeTerminalComponent) ideTerminal?: IdeTerminalComponent;

  activeActivity: ActivityView = 'files';
  bottomTab: BottomPanelTab = 'terminal';
  aiTab: AiTab = 'chat';

  showSidebar = true;
  showBottomPanel = true;
  showAiPanel = true;
  showNewMenu = false;
  openMenu: 'file' | 'edit' | 'view' | 'help' | null = null;

  showOpenTargetDialog = false;
  pendingImportMode: 'folder' | 'files' | null = null;
  importing = false;
  importProgress = '';
  boundLocalPath = '';

  searchQuery = '';
  searchHits: SearchHit[] = [];
  searching = false;

  terminalStatus: 'connecting' | 'open' | 'closed' | 'error' = 'connecting';
  terminalShell: TerminalShell = 'powershell';
  bottomMaximized = false;
  bottomPanelHeight = 220;
  sidePanelWidth = 260;
  aiPanelWidth = 360;
  activeResize: 'bottom' | 'side' | 'ai' | null = null;

  private bottomHeightBeforeMax = 220;
  private resizeStartCoord = 0;
  private resizeStartSize = 220;

  chatInput = '';
  nextMsgId = 1;
  saving = false;
  loadingTree = false;
  statusMessage = '';

  debugCommand = 'dir';
  debugOutput: string[] = [];

  fileTree: WorkspaceNode[] = [];
  openTabs: EditorTab[] = [];
  activeTabId = '';
  tabCounter = 0;

  contextMenu: ContextMenuState | null = null;
  explorerClipboard: ExplorerClipboard | null = null;
  recentFiles: RecentFileEntry[] = [];
  recentFolders: string[] = [];
  showRecentSubmenu = false;
  private readonly recentFilesKey = 'pass-ai-recent-files';
  private readonly recentFoldersKey = 'pass-ai-recent-folders';
  private readonly maxRecent = 12;

  messages: ChatMessage[] = [];

  quickActions = [
    {
      title: 'Explain this code',
      desc: 'Get a detailed explanation of the selected code',
      prompt: 'Explain the currently open file in simple terms.',
      icon: 'explain' as const,
      tone: 'blue'
    },
    {
      title: 'Generate component',
      desc: 'Create a new React component',
      prompt: 'Generate a new React component with a clean structure.',
      icon: 'generate' as const,
      tone: 'violet'
    },
    {
      title: 'Refactor code',
      desc: 'Improve code structure and performance',
      prompt: 'Refactor this code for clarity and maintainability.',
      icon: 'refactor' as const,
      tone: 'teal'
    },
    {
      title: 'Find bugs',
      desc: 'Analyze code and find potential issues',
      prompt: 'Scan this file for bugs and edge cases.',
      icon: 'bugs' as const,
      tone: 'rose'
    }
  ];

  problems: ProblemItem[] = [];
  outputLines: string[] = ['[PASS AI] Workspace ready.', '[PASS AI] Waiting for activity…'];

  cursorLine = 1;
  cursorCol = 1;
  fileIcon = getFileIcon;

  constructor(
    private auth: Auth,
    private router: Router,
    private route: ActivatedRoute,
    private workspace: WorkspaceApi,
    private terminal: TerminalService,
    private workspaceSession: WorkspaceSession,
    private cdr: ChangeDetectorRef,
    private ngZone: NgZone,
    private appRef: ApplicationRef,
    private host: ElementRef<HTMLElement>,
    public theme: ThemeService
  ) {}

  ngOnInit() {
    this.openTabs = [];
    this.activeTabId = '';
    this.loadRecentLists();
    this.refreshTree();
    this.refreshProblems();
    this.refreshBinding();

    // New window opened with ?import=folder|files&ws=... → open real local folder
    const importMode = this.route.snapshot.queryParamMap.get('import');
    if (importMode === 'folder' || importMode === 'files') {
      void this.router.navigate([], {
        relativeTo: this.route,
        queryParams: {},
        replaceUrl: true
      });
      this.statusMessage = 'Select a real local folder…';
      setTimeout(() => {
        if (importMode === 'folder') {
          this.openRealLocalFolder();
        } else {
          this.fileInput?.nativeElement.click();
        }
      }, 400);
    }
  }

  ngOnDestroy() {
    this.terminal.disconnect();
  }

  get activeTab(): EditorTab | undefined {
    return this.openTabs.find((t) => t.id === this.activeTabId);
  }

  get errorCount(): number {
    return this.problems.filter((p) => p.severity === 'error').length;
  }

  get warningCount(): number {
    return this.problems.filter((p) => p.severity === 'warning').length;
  }

  get infoCount(): number {
    return this.problems.filter((p) => p.severity === 'info').length;
  }

  setActivity(view: ActivityView) {
    if (this.activeActivity === view && this.showSidebar) {
      this.showSidebar = false;
      return;
    }
    this.activeActivity = view;
    this.showSidebar = true;
    if (view === 'search' && this.searchQuery.trim()) {
      this.runSearch();
    }
    if (view === 'files') {
      this.refreshTree();
    }
  }

  refreshTree(extraExpandPaths: string[] = []) {
    const expanded = this.collectExpandedPaths(this.fileTree);
    expanded.add(''); // root always open
    for (const p of extraExpandPaths) {
      if (p !== undefined && p !== null) {
        expanded.add(p);
        // Also expand all parents so the change is visible
        let cur = p;
        while (cur.includes('/')) {
          cur = cur.slice(0, cur.lastIndexOf('/'));
          expanded.add(cur);
        }
      }
    }
    this.loadingTree = true;
    this.workspace.tree().subscribe({
      next: (root) => {
        this.ngZone.run(() => {
          this.fileTree = this.applyExpandedState([root], expanded);
          this.loadingTree = false;
          this.statusMessage = this.boundLocalPath
            ? `Linked: ${this.boundLocalPath}`
            : 'Workspace synced';
          this.cdr.detectChanges();
        });
      },
      error: (err) => {
        this.ngZone.run(() => {
          this.loadingTree = false;
          this.statusMessage = 'Failed to load workspace';
          this.pushOutput(`Tree error: ${err?.message || err}`);
          this.cdr.detectChanges();
        });
      }
    });
  }

  private collectExpandedPaths(nodes: WorkspaceNode[], out = new Set<string>()): Set<string> {
    for (const n of nodes) {
      if (n.type === 'folder' && n.expanded) {
        out.add(n.path || '');
      }
      if (n.children?.length) {
        this.collectExpandedPaths(n.children, out);
      }
    }
    return out;
  }

  private applyExpandedState(nodes: WorkspaceNode[], expanded: Set<string>): WorkspaceNode[] {
    return this.filterTree(nodes).map((n) => {
      const path = n.path || '';
      const isFolder = n.type === 'folder';
      return {
        ...n,
        expanded: isFolder ? expanded.has(path) || path === '' : undefined,
        children: n.children?.length ? this.applyExpandedState(n.children, expanded) : []
      };
    });
  }

  refreshBinding() {
    this.workspace.binding().subscribe({
      next: (res) => {
        this.boundLocalPath = res.bound ? res.path : '';
        if (this.boundLocalPath) {
          this.statusMessage = `Linked: ${this.boundLocalPath}`;
        }
      },
      error: () => {
        this.boundLocalPath = '';
      }
    });
  }

  /** Opens native Windows folder dialog and works directly on that real folder. */
  openRealLocalFolder() {
    this.showNewMenu = false;
    this.openMenu = null;
    this.showRecentSubmenu = false;
    this.importing = true;
    this.importProgress = 'Opening folder picker…';
    this.statusMessage = this.importProgress;
    this.cdr.detectChanges();

    this.workspace.openLocalFolder().subscribe({
      next: (res) => this.applyLinkedWorkspace(res),
      error: (err) => {
        this.syncUiAfterOsDialog(() => {
          this.importing = false;
          this.importProgress = '';
          const msg = typeof err?.error === 'string' ? err.error : 'Folder open cancelled or failed';
          if (String(msg).toLowerCase().includes('cancel')) {
            this.statusMessage = 'Folder selection cancelled';
            return;
          }
          const typed = prompt(
            'Paste the full local folder path to edit for real (e.g. C:\\Users\\maram\\my-project):',
            this.boundLocalPath || 'C:\\Users\\maram\\'
          );
          if (!typed?.trim()) {
            this.statusMessage = msg;
            return;
          }
          this.bindTypedLocalFolder(typed.trim());
        });
      }
    });
  }

  bindTypedLocalFolder(path: string) {
    this.importing = true;
    this.importProgress = 'Linking folder…';
    this.statusMessage = this.importProgress;
    this.cdr.detectChanges();
    this.workspace.bindLocalFolder(path).subscribe({
      next: (res) => this.applyLinkedWorkspace(res),
      error: (err) => {
        this.syncUiAfterOsDialog(() => {
          this.importing = false;
          this.importProgress = '';
          this.statusMessage = 'Link failed';
          alert(typeof err?.error === 'string' ? err.error : 'Could not link folder');
        });
      }
    });
  }

  /** Apply linked-folder result and force explorer to repaint (OS dialog steals focus/zone). */
  private applyLinkedWorkspace(res: BindingResult) {
    this.syncUiAfterOsDialog(() => {
      this.importing = false;
      this.importProgress = '';
      this.boundLocalPath = res.path || '';
      if (res.path) {
        this.rememberRecentFolder(res.path);
      }
      this.openTabs = [];
      this.activeTabId = '';
      this.statusMessage = `Editing real folder: ${res.path}`;
      this.pushOutput(`[workspace] Linked real folder ${res.path}`);

      if (res.tree) {
        this.fileTree = this.applyExpandedState([res.tree], new Set(['']));
        this.loadingTree = false;
      }
      // Always re-fetch so the explorer updates even if the dialog left Angular's zone
      this.refreshTree(['']);
      this.refreshProblems();
      this.reconnectTerminal();
    });
  }

  /**
   * Native Windows dialogs (folder picker / prompt) pause the UI thread.
   * Force Angular to paint as soon as focus returns — no extra click needed.
   */
  private syncUiAfterOsDialog(action: () => void) {
    this.ngZone.run(() => {
      action();
      this.cdr.detectChanges();
      this.appRef.tick();
    });
    // OS dialog often returns focus a frame later — repaint then without re-running side effects
    setTimeout(() => {
      this.ngZone.run(() => {
        this.cdr.detectChanges();
        this.appRef.tick();
      });
    }, 0);
    setTimeout(() => {
      this.ngZone.run(() => {
        this.cdr.detectChanges();
        this.appRef.tick();
      });
    }, 100);
  }

  private markExpanded(nodes: WorkspaceNode[]): WorkspaceNode[] {
    return this.applyExpandedState(nodes, new Set(['']));
  }

  private markExpandedChildren(nodes: WorkspaceNode[]): WorkspaceNode[] {
    return this.applyExpandedState(nodes, new Set());
  }

  /** Run after native prompt/confirm so the explorer refreshes without needing an extra click. */
  private afterNativeDialog(action: () => void) {
    this.syncUiAfterOsDialog(action);
  }

  toggleNode(node: WorkspaceNode) {
    this.closeContextMenu();
    if (node.type === 'folder') {
      // Never treat cache folders as openable content
      if (this.isHiddenTreeNode(node)) {
        return;
      }
      node.expanded = !node.expanded;
      return;
    }
    // Binary / cache files are not editable in Monaco
    const lower = node.name.toLowerCase();
    if (lower.endsWith('.pyc') || lower.endsWith('.pyo') || lower.endsWith('.class')) {
      this.statusMessage = `${node.name} is a binary cache file (not editable)`;
      return;
    }
    this.openRemoteFile(node.path, node.name);
  }

  openExplorerContextMenu(event: MouseEvent, node: WorkspaceNode) {
    event.preventDefault();
    event.stopPropagation();
    this.openMenu = null;
    this.showNewMenu = false;
    const pad = 8;
    const menuW = 240;
    const menuH = 360;
    const x = Math.min(event.clientX, window.innerWidth - menuW - pad);
    const y = Math.min(event.clientY, window.innerHeight - menuH - pad);
    this.contextMenu = { x: Math.max(pad, x), y: Math.max(pad, y), node };
  }

  closeContextMenu() {
    this.contextMenu = null;
  }

  @HostListener('document:click')
  onDocumentClick() {
    this.closeContextMenu();
  }

  @HostListener('document:keydown.escape')
  onEscape() {
    this.closeContextMenu();
  }

  private parentDir(path: string): string {
    const normalized = (path || '').replace(/\\/g, '/');
    const idx = normalized.lastIndexOf('/');
    return idx >= 0 ? normalized.slice(0, idx) : '';
  }

  private joinPath(dir: string, name: string): string {
    const cleanName = name.replace(/\\/g, '/').replace(/^\/+/, '');
    if (!dir) {
      return cleanName;
    }
    return `${dir.replace(/\/+$/, '')}/${cleanName}`;
  }

  private targetDirForCreate(node: WorkspaceNode): string {
    return node.type === 'folder' ? node.path : this.parentDir(node.path);
  }

  private uniqueSiblingPath(dir: string, baseName: string): string {
    let candidate = this.joinPath(dir, baseName);
    let i = 1;
    const dot = baseName.lastIndexOf('.');
    const stem = dot > 0 ? baseName.slice(0, dot) : baseName;
    const ext = dot > 0 ? baseName.slice(dot) : '';
    while (this.pathExistsInTree(candidate)) {
      candidate = this.joinPath(dir, `${stem} copy${i > 1 ? ` ${i}` : ''}${ext}`);
      i += 1;
    }
    return candidate;
  }

  private pathExistsInTree(path: string): boolean {
    const walk = (nodes: WorkspaceNode[]): boolean => {
      for (const n of nodes) {
        if (n.path === path) {
          return true;
        }
        if (n.children?.length && walk(n.children)) {
          return true;
        }
      }
      return false;
    };
    return walk(this.fileTree);
  }

  private rewriteOpenTabPaths(from: string, to: string, isFolder: boolean) {
    for (const tab of this.openTabs) {
      if (!isFolder && tab.path === from) {
        tab.path = to;
        tab.title = to.split('/').pop() || to;
      } else if (isFolder && (tab.path === from || tab.path.startsWith(from + '/'))) {
        tab.path = to + tab.path.slice(from.length);
        tab.title = tab.path.split('/').pop() || tab.path;
      }
    }
  }

  private closeTabsUnderPath(path: string, isFolder: boolean) {
    const doomed = this.openTabs.filter((t) =>
      isFolder ? t.path === path || t.path.startsWith(path + '/') : t.path === path
    );
    for (const tab of doomed) {
      this.closeTab(new Event('click'), tab.id);
    }
  }

  ctxAddToChat() {
    const node = this.contextMenu?.node;
    this.closeContextMenu();
    if (!node) {
      return;
    }
    this.showAiPanel = true;
    this.aiTab = 'chat';
    const label = node.type === 'folder' ? 'folder' : 'file';
    const hint = `@${node.path} `;
    this.chatInput = (this.chatInput ? this.chatInput + ' ' : '') + hint;
    this.messages.push({
      id: this.nextMsgId++,
      role: 'assistant',
      text: `Added ${label} \`${node.path}\` to chat context.`
    });
    this.statusMessage = `Added ${node.name} to chat`;
  }

  ctxRevealInExplorer() {
    const node = this.contextMenu?.node;
    this.closeContextMenu();
    if (!node) {
      return;
    }
    this.workspace.revealInExplorer(node.path || '.').subscribe({
      next: () => {
        this.statusMessage = `Revealed ${node.name || 'workspace'} in File Explorer`;
      },
      error: (err) => {
        this.statusMessage = 'Reveal failed';
        alert(typeof err?.error === 'string' ? err.error : 'Could not open File Explorer');
      }
    });
  }

  ctxNewFile() {
    const node = this.contextMenu?.node;
    this.closeContextMenu();
    if (!node) {
      return;
    }
    const dir = this.targetDirForCreate(node);
    const name = prompt('New file name', 'Untitled.txt');
    if (!name?.trim()) {
      return;
    }
    const path = this.joinPath(dir, name.trim());
    this.afterNativeDialog(() => {
      this.workspace.createFile(path, '').subscribe({
        next: (file) => {
          this.refreshTree([dir]);
          this.openRemoteFile(file.path, file.path.split('/').pop());
          this.statusMessage = `Created ${file.path}`;
        },
        error: (err) => {
          alert(typeof err?.error === 'string' ? err.error : 'Create file failed');
        }
      });
    });
  }

  ctxNewFolder() {
    const node = this.contextMenu?.node;
    this.closeContextMenu();
    if (!node) {
      return;
    }
    const dir = this.targetDirForCreate(node);
    const name = prompt('New folder name', 'New Folder');
    if (!name?.trim()) {
      return;
    }
    const path = this.joinPath(dir, name.trim());
    this.afterNativeDialog(() => {
      this.workspace.createFolder(path).subscribe({
        next: () => {
          this.refreshTree([dir, path]);
          this.statusMessage = `Created folder ${path}`;
        },
        error: (err) => {
          alert(typeof err?.error === 'string' ? err.error : 'Create folder failed');
        }
      });
    });
  }

  ctxCopyPath(relative: boolean) {
    const node = this.contextMenu?.node;
    this.closeContextMenu();
    if (!node) {
      return;
    }
    if (relative) {
      const text = node.path || '.';
      navigator.clipboard.writeText(text).then(
        () => (this.statusMessage = `Copied relative path: ${text}`),
        () => (this.statusMessage = 'Clipboard copy failed')
      );
      return;
    }
    this.workspace.absolutePath(node.path || '.').subscribe({
      next: (res) => {
        navigator.clipboard.writeText(res.path).then(
          () => (this.statusMessage = `Copied path: ${res.path}`),
          () => (this.statusMessage = 'Clipboard copy failed')
        );
      },
      error: () => (this.statusMessage = 'Could not resolve absolute path')
    });
  }

  ctxCut() {
    const node = this.contextMenu?.node;
    this.closeContextMenu();
    if (!node || !node.path) {
      return;
    }
    this.explorerClipboard = { mode: 'cut', path: node.path, name: node.name, type: node.type };
    this.statusMessage = `Cut ${node.name}`;
  }

  ctxCopy() {
    const node = this.contextMenu?.node;
    this.closeContextMenu();
    if (!node || !node.path) {
      return;
    }
    this.explorerClipboard = { mode: 'copy', path: node.path, name: node.name, type: node.type };
    this.statusMessage = `Copied ${node.name}`;
  }

  ctxPaste() {
    const node = this.contextMenu?.node;
    const clip = this.explorerClipboard;
    this.closeContextMenu();
    if (!node || !clip) {
      return;
    }
    const destDir = this.targetDirForCreate(node);
    const to = this.uniqueSiblingPath(destDir, clip.name);
    if (clip.mode === 'copy') {
      this.workspace.copyPath(clip.path, to).subscribe({
        next: (res) => {
          this.refreshTree([destDir]);
          this.statusMessage = `Pasted ${res.path}`;
        },
        error: (err) => alert(typeof err?.error === 'string' ? err.error : 'Paste failed')
      });
      return;
    }
    this.workspace.renamePath(clip.path, to).subscribe({
      next: (res) => {
        this.rewriteOpenTabPaths(clip.path, res.path, clip.type === 'folder');
        this.explorerClipboard = null;
        this.refreshTree([destDir]);
        this.statusMessage = `Moved to ${res.path}`;
      },
      error: (err) => alert(typeof err?.error === 'string' ? err.error : 'Move failed')
    });
  }

  ctxRename() {
    const node = this.contextMenu?.node;
    this.closeContextMenu();
    if (!node || !node.path) {
      return;
    }
    const nextName = prompt('Rename to', node.name);
    if (!nextName?.trim() || nextName.trim() === node.name) {
      return;
    }
    const parent = this.parentDir(node.path);
    const to = this.joinPath(parent, nextName.trim());
    this.afterNativeDialog(() => {
      this.workspace.renamePath(node.path, to).subscribe({
        next: (res) => {
          this.rewriteOpenTabPaths(node.path, res.path, node.type === 'folder');
          this.refreshTree([parent]);
          this.statusMessage = `Renamed to ${res.path}`;
        },
        error: (err) => alert(typeof err?.error === 'string' ? err.error : 'Rename failed')
      });
    });
  }

  ctxDelete() {
    const node = this.contextMenu?.node;
    this.closeContextMenu();
    if (!node || !node.path) {
      return;
    }
    if (!confirm(`Delete ${node.path}?`)) {
      return;
    }
    const parent = this.parentDir(node.path);
    this.afterNativeDialog(() => {
      this.workspace.deletePath(node.path).subscribe({
        next: () => {
          this.closeTabsUnderPath(node.path, node.type === 'folder');
          this.refreshTree([parent]);
          this.refreshProblems();
          this.statusMessage = `Deleted ${node.path}`;
        },
        error: () => (this.statusMessage = 'Delete failed')
      });
    });
  }

  openRemoteFile(path: string, name?: string) {
    const existing = this.openTabs.find((t) => t.path === path && !t.unsavedNew);
    if (existing) {
      this.activeTabId = existing.id;
      this.rememberRecentFile(path, name || existing.title);
      return;
    }

    this.workspace.readFile(path).subscribe({
      next: (file) => {
        const title = name || path.split('/').pop() || path;
        const tab: EditorTab = {
          id: `file-${++this.tabCounter}`,
          title,
          path: file.path,
          content: file.content
        };
        this.openTabs.push(tab);
        this.activeTabId = tab.id;
        this.rememberRecentFile(file.path, title);
      },
      error: (err) => {
        this.statusMessage = 'Could not open file';
        this.pushOutput(`Open error: ${err?.error || err?.message || err}`);
      }
    });
  }

  private loadRecentLists() {
    try {
      const files = JSON.parse(localStorage.getItem(this.recentFilesKey) || '[]');
      this.recentFiles = Array.isArray(files) ? files.slice(0, this.maxRecent) : [];
    } catch {
      this.recentFiles = [];
    }
    try {
      const folders = JSON.parse(localStorage.getItem(this.recentFoldersKey) || '[]');
      this.recentFolders = Array.isArray(folders) ? folders.slice(0, this.maxRecent) : [];
    } catch {
      this.recentFolders = [];
    }
  }

  private rememberRecentFile(path: string, name?: string) {
    if (!path) {
      return;
    }
    const entry: RecentFileEntry = {
      path,
      name: name || path.split('/').pop() || path,
      openedAt: Date.now()
    };
    this.recentFiles = [entry, ...this.recentFiles.filter((f) => f.path !== path)].slice(0, this.maxRecent);
    localStorage.setItem(this.recentFilesKey, JSON.stringify(this.recentFiles));
  }

  private rememberRecentFolder(absolutePath: string) {
    if (!absolutePath) {
      return;
    }
    this.recentFolders = [absolutePath, ...this.recentFolders.filter((p) => p !== absolutePath)].slice(
      0,
      this.maxRecent
    );
    localStorage.setItem(this.recentFoldersKey, JSON.stringify(this.recentFolders));
  }

  openRecentFile(entry: RecentFileEntry) {
    this.closeMenus();
    this.openRemoteFile(entry.path, entry.name);
  }

  openRecentFolder(absolutePath: string) {
    this.closeMenus();
    this.bindTypedLocalFolder(absolutePath);
  }

  clearRecentLists() {
    this.recentFiles = [];
    this.recentFolders = [];
    localStorage.removeItem(this.recentFilesKey);
    localStorage.removeItem(this.recentFoldersKey);
    this.statusMessage = 'Recent list cleared';
    this.closeMenus();
  }

  selectTab(id: string) {
    this.activeTabId = id;
  }

  closeTab(event: Event, id: string) {
    event.stopPropagation();
    const index = this.openTabs.findIndex((t) => t.id === id);
    if (index < 0) {
      return;
    }
    this.openTabs.splice(index, 1);
    if (this.activeTabId === id) {
      const next = this.openTabs[index] ?? this.openTabs[index - 1];
      this.activeTabId = next?.id ?? '';
    }
  }

  createNewFile() {
    this.showNewMenu = false;
    const name = prompt('New file name (e.g. src/Hello.java)', `Untitled-${++this.tabCounter}.txt`);
    if (!name) {
      return;
    }
    const path = name.replace(/\\/g, '/').replace(/^\/+/, '');
    const parent = this.parentDir(path);
    this.afterNativeDialog(() => {
      this.workspace.createFile(path, '').subscribe({
        next: (file) => {
          this.refreshTree([parent]);
          const tab: EditorTab = {
            id: `file-${++this.tabCounter}`,
            title: path.split('/').pop() || path,
            path: file.path,
            content: file.content,
            dirty: false
          };
          this.openTabs.push(tab);
          this.activeTabId = tab.id;
          this.rememberRecentFile(file.path, tab.title);
          this.statusMessage = `Created ${path}`;
          this.refreshProblems();
        },
        error: (err) => {
          this.statusMessage = 'Create file failed';
          alert(typeof err?.error === 'string' ? err.error : 'Create file failed');
        }
      });
    });
  }

  triggerImportFolder() {
    this.showNewMenu = false;
    this.openMenu = null;
    this.pendingImportMode = 'folder';
    this.showOpenTargetDialog = true;
  }

  triggerImportFiles() {
    this.showNewMenu = false;
    this.openMenu = null;
    this.pendingImportMode = 'files';
    this.showOpenTargetDialog = true;
  }

  chooseOpenTarget(target: 'this' | 'new') {
    this.showOpenTargetDialog = false;
    const mode = this.pendingImportMode;
    this.pendingImportMode = null;
    if (!mode) {
      return;
    }

    if (target === 'new') {
      this.openRealIdeWindow(mode);
      return;
    }

    // Give the dialog time to close before the OS folder picker opens
    setTimeout(() => {
      if (mode === 'folder') {
        this.openRealLocalFolder();
      } else {
        this.fileInput?.nativeElement.click();
      }
    }, 50);
  }

  /** Opens a real browser popup window (not just a tab) and starts import there. */
  private openRealIdeWindow(mode: 'folder' | 'files') {
    const width = Math.min(1480, screen.availWidth - 40);
    const height = Math.min(920, screen.availHeight - 60);
    const left = Math.max(0, Math.round((screen.availWidth - width) / 2));
    const top = Math.max(0, Math.round((screen.availHeight - height) / 2));
    const features = [
      'popup=yes',
      `width=${width}`,
      `height=${height}`,
      `left=${left}`,
      `top=${top}`,
      'menubar=no',
      'toolbar=no',
      'location=no',
      'status=no',
      'resizable=yes',
      'scrollbars=yes'
    ].join(',');

    const url = `${window.location.origin}/chat?import=${mode}&ws=${encodeURIComponent(this.workspaceSession.createFreshId())}`;
    const win = window.open(url, `pass-ai-${Date.now()}`, features);

    if (!win || win.closed) {
      this.statusMessage = 'Popup blocked — allow popups for localhost, or use This window.';
      alert('Popup blocked by the browser.\n\nAllow popups for http://localhost:4200, then try New window again.\nOr choose This window.');
      return;
    }

    try {
      win.focus();
    } catch {
      // ignore
    }
    this.statusMessage = 'Opened a new PASS AI window';
  }

  cancelOpenTarget() {
    this.showOpenTargetDialog = false;
    this.pendingImportMode = null;
  }

  toggleMenu(menu: 'file' | 'edit' | 'view' | 'help', event: Event) {
    event.stopPropagation();
    this.showNewMenu = false;
    this.openMenu = this.openMenu === menu ? null : menu;
  }

  closeMenus() {
    this.openMenu = null;
    this.showNewMenu = false;
    this.showRecentSubmenu = false;
  }

  menuNewFile() {
    this.closeMenus();
    this.createNewFile();
  }

  menuOpenFolder() {
    this.closeMenus();
    this.triggerImportFolder();
  }

  toggleRecentSubmenu(event: Event) {
    event.stopPropagation();
    this.showRecentSubmenu = !this.showRecentSubmenu;
  }

  menuNewTerminal() {
    this.closeMenus();
    this.showBottomPanel = true;
    this.bottomTab = 'terminal';
    this.reconnectTerminal();
  }

  menuOpenIde() {
    this.menuNewWindow();
  }

  /** Opens a fresh empty IDE window (root labeled "workspace"). */
  menuNewWindow() {
    this.closeMenus();
    this.openFreshIdeWindow();
  }

  /** Opens a real browser popup with an empty isolated workspace. */
  private openFreshIdeWindow() {
    const width = Math.min(1480, screen.availWidth - 40);
    const height = Math.min(920, screen.availHeight - 60);
    const left = Math.max(0, Math.round((screen.availWidth - width) / 2));
    const top = Math.max(0, Math.round((screen.availHeight - height) / 2));
    const features = [
      'popup=yes',
      `width=${width}`,
      `height=${height}`,
      `left=${left}`,
      `top=${top}`,
      'menubar=no',
      'toolbar=no',
      'location=no',
      'status=no',
      'resizable=yes',
      'scrollbars=yes'
    ].join(',');

    const url = `${window.location.origin}/chat?ws=${encodeURIComponent(this.workspaceSession.createFreshId())}`;
    const win = window.open(url, `pass-ai-${Date.now()}`, features);

    if (!win || win.closed) {
      this.statusMessage = 'Popup blocked — allow popups for localhost.';
      alert('Popup blocked by the browser.\n\nAllow popups for http://localhost:4200, then try New Window again.');
      return;
    }

    try {
      win.focus();
    } catch {
      // ignore
    }
    this.statusMessage = 'Opened a new PASS AI window';
  }

  menuToggleSidebar() {
    this.closeMenus();
    this.showSidebar = !this.showSidebar;
  }

  menuToggleTerminal() {
    this.closeMenus();
    this.showBottomPanel = !this.showBottomPanel;
  }

  menuToggleAi() {
    this.closeMenus();
    this.showAiPanel = !this.showAiPanel;
  }

  menuSave() {
    this.closeMenus();
    this.saveActiveFile();
  }

  private shouldSkipUploadPath(path: string): boolean {
    const lower = path.toLowerCase().replace(/\\/g, '/');
    return (
      lower.includes('/node_modules/') ||
      lower.startsWith('node_modules/') ||
      lower.includes('/.git/') ||
      lower.startsWith('.git/') ||
      lower.includes('/__pycache__/') ||
      lower.startsWith('__pycache__/') ||
      lower.includes('/.pytest_cache/') ||
      lower.includes('/.mypy_cache/') ||
      lower.includes('/dist/') ||
      lower.includes('/target/') ||
      lower.includes('/.next/') ||
      lower.includes('/build/') ||
      lower.includes('/.idea/') ||
      lower.includes('/.vscode/') ||
      lower.endsWith('.pyc') ||
      lower.endsWith('.pyo') ||
      lower.endsWith('.jar') ||
      lower.endsWith('.war') ||
      lower.endsWith('.exe') ||
      lower.endsWith('.dll')
    );
  }

  /** Hide cache / generated folders from the explorer tree. */
  private isHiddenTreeNode(node: WorkspaceNode): boolean {
    const name = node.name.toLowerCase();
    return (
      name === '__pycache__' ||
      name === 'node_modules' ||
      name === '.git' ||
      name === '.pytest_cache' ||
      name === '.mypy_cache' ||
      name.endsWith('.pyc') ||
      name.endsWith('.pyo')
    );
  }

  private filterTree(nodes: WorkspaceNode[]): WorkspaceNode[] {
    return nodes
      .filter((n) => !this.isHiddenTreeNode(n))
      .map((n) => {
        const isRootSessionId =
          !n.path && typeof n.name === 'string' && /^ws_[a-z0-9_]+$/i.test(n.name);
        return {
          ...n,
          name: isRootSessionId ? 'workspace' : n.name,
          children: n.children ? this.filterTree(n.children) : []
        };
      });
  }

  private collectFiles(list: FileList, useRelativePath: boolean): { files: File[]; paths: string[]; skipped: number } {
    const files: File[] = [];
    const paths: string[] = [];
    let skipped = 0;
    for (let i = 0; i < list.length; i++) {
      const file = list.item(i);
      if (!file) {
        continue;
      }
      const relative = useRelativePath
        ? ((file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name).replace(/\\/g, '/')
        : file.name;
      if (this.shouldSkipUploadPath(relative)) {
        skipped++;
        continue;
      }
      files.push(file);
      paths.push(relative);
    }
    return { files, paths, skipped };
  }

  /** Pack any number of files into one ZIP, then upload once (fast). */
  private async uploadAsZip(files: File[], paths: string[], replace: boolean) {
    this.importing = true;
    this.importProgress = `Packing ${files.length} files…`;
    this.statusMessage = this.importProgress;

    try {
      const JSZip = (await import('jszip')).default;
      const zip = new JSZip();

      // Add files in parallel batches for speed
      const batchSize = 50;
      for (let i = 0; i < files.length; i += batchSize) {
        const slice = files.slice(i, i + batchSize);
        const slicePaths = paths.slice(i, i + batchSize);
        this.importProgress = `Packing ${Math.min(i + batchSize, files.length)} / ${files.length}…`;
        this.statusMessage = this.importProgress;
        await Promise.all(
          slice.map(async (file, idx) => {
            const path = slicePaths[idx];
            zip.file(path, file);
          })
        );
      }

      this.importProgress = 'Compressing archive…';
      this.statusMessage = this.importProgress;
      const blob = await zip.generateAsync({
        type: 'blob',
        compression: 'DEFLATE',
        compressionOptions: { level: 1 } // fast compression
      });

      this.importProgress = `Uploading ${(blob.size / (1024 * 1024)).toFixed(1)} MB…`;
      this.statusMessage = this.importProgress;

      const result = await new Promise<UploadResult>((resolve, reject) => {
        this.workspace.uploadZip(blob, replace).subscribe({ next: resolve, error: reject });
      });

      if (result.tree) {
        this.fileTree = this.markExpanded([result.tree]);
      } else {
        this.refreshTree();
      }

      this.activeActivity = 'files';
      this.showSidebar = true;
      this.statusMessage = `Imported ${result.saved} files`;
      this.importProgress = '';
      this.pushOutput(`[import] Fast ZIP upload: ${result.saved} files`);
      this.refreshProblems();
      this.reconnectTerminal();
    } catch (err: unknown) {
      const anyErr = err as { error?: string | { message?: string }; message?: string; status?: number };
      let message = 'Import failed';
      if (typeof anyErr?.error === 'string') {
        message = anyErr.error;
      } else if (anyErr?.error && typeof anyErr.error === 'object' && anyErr.error.message) {
        message = anyErr.error.message;
      } else if (anyErr?.message) {
        message = anyErr.message;
      }
      if (anyErr?.status === 413 || /Maximum upload size exceeded/i.test(message)) {
        message = 'Archive too large. Try excluding node_modules / build folders.';
      }
      this.statusMessage = 'Import failed';
      this.importProgress = '';
      this.pushOutput(`Import error: ${message}`);
      alert(message);
    } finally {
      this.importing = false;
    }
  }

  async onFolderPicked(event: Event) {
    const input = event.target as HTMLInputElement;
    const list = input.files;
    if (!list || list.length === 0) {
      return;
    }

    const { files, paths, skipped } = this.collectFiles(list, true);
    input.value = '';

    if (files.length === 0) {
      this.statusMessage = skipped
        ? `Nothing to import (${skipped} heavy/generated files skipped)`
        : 'Nothing to import';
      return;
    }

    this.pushOutput(`[import] Selected ${list.length} files → packing ${files.length} (skipped ${skipped})`);
    await this.uploadAsZip(files, paths, true);
  }

  async onFilesPicked(event: Event) {
    const input = event.target as HTMLInputElement;
    const list = input.files;
    if (!list || list.length === 0) {
      return;
    }
    const { files, paths } = this.collectFiles(list, false);
    input.value = '';
    if (!files.length) {
      this.statusMessage = 'No valid files to import';
      return;
    }
    await this.uploadAsZip(files, paths, false);
  }

  @HostListener('window:keydown', ['$event'])
  onGlobalKeydown(event: KeyboardEvent) {
    const key = event.key.toLowerCase();
    if ((event.ctrlKey || event.metaKey) && key === 's') {
      event.preventDefault();
      this.saveActiveFile();
      return;
    }
    if ((event.ctrlKey || event.metaKey) && key === 'o') {
      event.preventDefault();
      this.triggerImportFolder();
      return;
    }
    if ((event.ctrlKey || event.metaKey) && key === 'n' && !event.shiftKey) {
      event.preventDefault();
      this.createNewFile();
      return;
    }
    if ((event.ctrlKey || event.metaKey) && event.shiftKey && key === 'n') {
      event.preventDefault();
      this.menuOpenIde();
    }
  }

  onEditorValueChange(value: string) {
    const tab = this.activeTab;
    if (!tab) {
      return;
    }
    tab.content = value;
    tab.dirty = true;
  }

  onEditorCursor(pos: { line: number; column: number }) {
    this.cursorLine = pos.line;
    this.cursorCol = pos.column;
  }

  saveActiveFile() {
    const tab = this.activeTab;
    if (!tab) {
      return;
    }
    this.saving = true;
    this.workspace.writeFile(tab.path, tab.content).subscribe({
      next: () => {
        tab.dirty = false;
        this.saving = false;
        this.statusMessage = `Saved ${tab.path}`;
        this.pushOutput(`[save] ${tab.path}`);
        this.refreshProblems();
      },
      error: (err) => {
        this.saving = false;
        this.statusMessage = 'Save failed';
        alert(typeof err?.error === 'string' ? err.error : 'Save failed');
      }
    });
  }

  deleteActivePath() {
    const tab = this.activeTab;
    if (!tab) {
      return;
    }
    if (!confirm(`Delete ${tab.path}?`)) {
      return;
    }
    const parent = this.parentDir(tab.path);
    this.afterNativeDialog(() => {
      this.workspace.deletePath(tab.path).subscribe({
        next: () => {
          this.closeTab(new Event('click'), tab.id);
          this.refreshTree([parent]);
          this.refreshProblems();
          this.statusMessage = `Deleted ${tab.path}`;
        },
        error: () => {
          this.statusMessage = 'Delete failed';
        }
      });
    });
  }

  runSearch() {
    const q = this.searchQuery.trim();
    if (!q) {
      this.searchHits = [];
      return;
    }
    this.searching = true;
    this.workspace.search(q).subscribe({
      next: (hits) => {
        this.searchHits = hits;
        this.searching = false;
      },
      error: () => {
        this.searching = false;
        this.searchHits = [];
      }
    });
  }

  refreshProblems() {
    this.workspace.problems().subscribe({
      next: (problems) => {
        this.problems = problems;
      },
      error: () => {
        this.problems = [{ severity: 'warning', message: 'Could not load diagnostics', file: 'workspace', line: 1 }];
      }
    });
  }

  connectTerminal() {
    this.terminalStatus = 'connecting';
    this.ideTerminal?.connect();
  }

  switchTerminalShell(shell: TerminalShell) {
    if (this.terminalShell === shell) {
      return;
    }
    this.terminalShell = shell;
    // IdeTerminalComponent reconnects via ngOnChanges when shell input changes
    setTimeout(() => this.ideTerminal?.focus(), 50);
  }

  reconnectTerminal() {
    this.terminalStatus = 'connecting';
    this.ideTerminal?.killAndReconnect();
  }

  interruptTerminal() {
    this.ideTerminal?.interrupt();
  }

  clearTerminal() {
    this.ideTerminal?.clear();
  }

  onTerminalStatus(status: 'connecting' | 'open' | 'closed' | 'error') {
    this.terminalStatus = status;
  }

  killTerminal() {
    this.terminal.disconnect();
    this.terminalStatus = 'closed';
  }

  startPanelResize(kind: 'bottom' | 'side' | 'ai', event: MouseEvent) {
    if (event.button !== 0) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    if (kind === 'bottom' && this.bottomMaximized) {
      this.bottomMaximized = false;
    }
    this.activeResize = kind;
    this.resizeStartCoord = kind === 'bottom' ? event.clientY : event.clientX;
    this.resizeStartSize =
      kind === 'bottom' ? this.bottomPanelHeight : kind === 'side' ? this.sidePanelWidth : this.aiPanelWidth;
    document.body.style.cursor = kind === 'bottom' ? 'ns-resize' : 'ew-resize';
    document.body.style.userSelect = 'none';
  }

  @HostListener('document:mousemove', ['$event'])
  onPanelResizeMove(event: MouseEvent) {
    if (!this.activeResize) {
      return;
    }
    if (this.activeResize === 'bottom') {
      const delta = this.resizeStartCoord - event.clientY;
      this.bottomPanelHeight = this.clampBottomHeight(this.resizeStartSize + delta);
      return;
    }
    if (this.activeResize === 'side') {
      const delta = event.clientX - this.resizeStartCoord;
      this.sidePanelWidth = this.clampSideWidth(this.resizeStartSize + delta);
      return;
    }
    const delta = this.resizeStartCoord - event.clientX;
    this.aiPanelWidth = this.clampAiWidth(this.resizeStartSize + delta);
  }

  @HostListener('document:mouseup')
  onPanelResizeEnd() {
    if (!this.activeResize) {
      return;
    }
    this.activeResize = null;
    document.body.style.cursor = '';
    document.body.style.userSelect = '';
  }

  resetBottomPanelHeight() {
    this.bottomMaximized = false;
    this.bottomPanelHeight = 220;
  }

  resetSidePanelWidth() {
    this.sidePanelWidth = 260;
  }

  resetAiPanelWidth() {
    this.aiPanelWidth = 360;
  }

  toggleBottomMaximize() {
    if (!this.bottomMaximized) {
      this.bottomHeightBeforeMax = this.bottomPanelHeight;
      const workspaceEl = this.host.nativeElement.querySelector('.workspace') as HTMLElement | null;
      const avail = workspaceEl?.clientHeight ?? 600;
      this.bottomPanelHeight = this.clampBottomHeight(Math.floor(avail * 0.72));
      this.bottomMaximized = true;
    } else {
      this.bottomPanelHeight = this.clampBottomHeight(this.bottomHeightBeforeMax);
      this.bottomMaximized = false;
    }
  }

  private clampBottomHeight(height: number): number {
    const workspaceEl = this.host.nativeElement.querySelector('.workspace') as HTMLElement | null;
    const avail = workspaceEl?.clientHeight ?? 800;
    const min = 120;
    const max = Math.max(min, Math.floor(avail * 0.85));
    return Math.min(max, Math.max(min, Math.round(height)));
  }

  private clampSideWidth(width: number): number {
    const body = this.host.nativeElement.querySelector('.ide-body') as HTMLElement | null;
    const avail = body?.clientWidth ?? 1200;
    const min = 160;
    const max = Math.max(min, Math.floor(avail * 0.45));
    return Math.min(max, Math.max(min, Math.round(width)));
  }

  private clampAiWidth(width: number): number {
    const body = this.host.nativeElement.querySelector('.ide-body') as HTMLElement | null;
    const avail = body?.clientWidth ?? 1200;
    const min = 260;
    const max = Math.max(min, Math.floor(avail * 0.5));
    return Math.min(max, Math.max(min, Math.round(width)));
  }

  runDebug() {
    const command = this.debugCommand.trim();
    if (!command) {
      return;
    }
    this.bottomTab = 'debug';
    this.showBottomPanel = true;
    this.debugOutput = [`$ ${command}`, 'Running…'];
    this.workspace.debugRun(command).subscribe({
      next: (res) => {
        this.debugOutput = [`$ ${command}`, ...res.output];
        this.pushOutput(`[debug] ${command}`);
      },
      error: (err) => {
        this.debugOutput = [`$ ${command}`, `Error: ${err?.error || err?.message || err}`];
      }
    });
  }

  resetChat() {
    this.messages = [];
    this.chatInput = '';
    this.nextMsgId = 1;
  }

  useQuickAction(prompt: string) {
    this.chatInput = prompt;
    this.sendChat();
  }

  sendChat() {
    const text = this.chatInput.trim();
    if (!text) {
      return;
    }
    this.messages.push({ id: this.nextMsgId++, role: 'user', text });
    this.chatInput = '';
    const fileHint = this.activeTab ? `\nActive file: ${this.activeTab.path}` : '';
    this.messages.push({
      id: this.nextMsgId++,
      role: 'assistant',
      text: `Noted.${fileHint}\n\nAI model wiring is next — workspace/terminal/debug are already live on the Java backend.`
    });
  }

  logout() {
    this.terminal.disconnect();
    this.auth.logout();
    this.router.navigate(['/login']);
  }

  private pushOutput(line: string) {
    this.outputLines = [...this.outputLines.slice(-200), line];
  }
}
