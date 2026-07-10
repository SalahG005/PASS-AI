import { Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NgTemplateOutlet } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { Auth } from '../auth';
import { getFileIcon } from '../file-icons';
import { MonacoEditorComponent } from '../monaco-editor/monaco-editor';
import { TerminalService, TerminalShell } from '../terminal.service';
import { ThemeService } from '../theme';
import { WorkspaceSession } from '../workspace-session';
import {
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

@Component({
  selector: 'app-chat-home',
  imports: [FormsModule, NgTemplateOutlet, MonacoEditorComponent],
  templateUrl: './chat-home.html',
  styleUrl: './chat-home.css'
})
export class ChatHome implements OnInit, OnDestroy {
  @ViewChild('folderInput') folderInput?: ElementRef<HTMLInputElement>;
  @ViewChild('fileInput') fileInput?: ElementRef<HTMLInputElement>;
  @ViewChild('terminalOut') terminalOut?: ElementRef<HTMLPreElement>;

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

  searchQuery = '';
  searchHits: SearchHit[] = [];
  searching = false;

  terminalInput = '';
  terminalText = '';
  terminalStatus: 'connecting' | 'open' | 'closed' | 'error' = 'connecting';
  terminalShell: TerminalShell = 'cmd';
  private terminalSending = false;

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
    public theme: ThemeService
  ) {}

  ngOnInit() {
    this.openTabs = [];
    this.activeTabId = '';
    this.refreshTree();
    this.refreshProblems();
    this.connectTerminal();

    // New window opened with ?import=folder|files&ws=... → empty workspace + file picker
    const importMode = this.route.snapshot.queryParamMap.get('import');
    if (importMode === 'folder' || importMode === 'files') {
      void this.router.navigate([], {
        relativeTo: this.route,
        queryParams: {},
        replaceUrl: true
      });
      this.statusMessage = 'Empty workspace — choose a folder to import';
      setTimeout(() => {
        if (importMode === 'folder') {
          this.folderInput?.nativeElement.click();
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

  refreshTree() {
    this.loadingTree = true;
    this.workspace.tree().subscribe({
      next: (root) => {
        this.fileTree = this.markExpanded([root]);
        this.loadingTree = false;
        this.statusMessage = 'Workspace synced';
      },
      error: (err) => {
        this.loadingTree = false;
        this.statusMessage = 'Failed to load workspace';
        this.pushOutput(`Tree error: ${err?.message || err}`);
      }
    });
  }

  private markExpanded(nodes: WorkspaceNode[]): WorkspaceNode[] {
    return this.filterTree(nodes).map((n) => ({
      ...n,
      // Don't auto-expand deep trees; keep root open only
      expanded: n.type === 'folder' && !n.path ? true : n.type === 'folder' ? false : undefined,
      children: n.children ? this.markExpandedChildren(n.children) : []
    }));
  }

  private markExpandedChildren(nodes: WorkspaceNode[]): WorkspaceNode[] {
    return this.filterTree(nodes).map((n) => ({
      ...n,
      expanded: false,
      children: n.children ? this.markExpandedChildren(n.children) : []
    }));
  }

  toggleNode(node: WorkspaceNode) {
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

  openRemoteFile(path: string, name?: string) {
    const existing = this.openTabs.find((t) => t.path === path && !t.unsavedNew);
    if (existing) {
      this.activeTabId = existing.id;
      return;
    }

    this.workspace.readFile(path).subscribe({
      next: (file) => {
        const tab: EditorTab = {
          id: `file-${++this.tabCounter}`,
          title: name || path.split('/').pop() || path,
          path: file.path,
          content: file.content
        };
        this.openTabs.push(tab);
        this.activeTabId = tab.id;
      },
      error: (err) => {
        this.statusMessage = 'Could not open file';
        this.pushOutput(`Open error: ${err?.error || err?.message || err}`);
      }
    });
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
    this.workspace.createFile(path, '').subscribe({
      next: (file) => {
        this.refreshTree();
        const tab: EditorTab = {
          id: `file-${++this.tabCounter}`,
          title: path.split('/').pop() || path,
          path: file.path,
          content: file.content,
          dirty: false
        };
        this.openTabs.push(tab);
        this.activeTabId = tab.id;
        this.statusMessage = `Created ${path}`;
        this.refreshProblems();
      },
      error: (err) => {
        this.statusMessage = 'Create file failed';
        alert(typeof err?.error === 'string' ? err.error : 'Create file failed');
      }
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

    // Give the dialog time to close before the OS file picker opens
    setTimeout(() => {
      if (mode === 'folder') {
        this.folderInput?.nativeElement.click();
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
  }

  menuNewFile() {
    this.closeMenus();
    this.createNewFile();
  }

  menuOpenFolder() {
    this.closeMenus();
    this.triggerImportFolder();
  }

  menuNewTerminal() {
    this.closeMenus();
    this.showBottomPanel = true;
    this.bottomTab = 'terminal';
    this.reconnectTerminal();
  }

  menuOpenIde() {
    this.closeMenus();
    this.openRealIdeWindow('folder');
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
      .map((n) => ({
        ...n,
        children: n.children ? this.filterTree(n.children) : []
      }));
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
    this.workspace.deletePath(tab.path).subscribe({
      next: () => {
        this.closeTab(new Event('click'), tab.id);
        this.refreshTree();
        this.refreshProblems();
        this.statusMessage = `Deleted ${tab.path}`;
      },
      error: () => {
        this.statusMessage = 'Delete failed';
      }
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
    this.terminal.connect(
      (chunk) => {
        this.terminalText += chunk;
        setTimeout(() => this.scrollTerminal(), 0);
      },
      (status) => {
        this.terminalStatus = status === 'open' ? 'open' : status;
        if (status === 'open') {
          const label = this.terminalShell === 'powershell' ? 'PowerShell' : 'Invite de commandes';
          this.pushOutput(`[terminal] ${label} connected to real workspace`);
        }
      },
      this.terminalShell
    );
  }

  switchTerminalShell(shell: TerminalShell) {
    if (this.terminalShell === shell && this.terminal.isConnected) {
      return;
    }
    this.terminalShell = shell;
    this.terminal.disconnect();
    this.terminalText = '';
    this.connectTerminal();
  }

  reconnectTerminal() {
    this.terminal.disconnect();
    this.terminalText += `\n— reconnecting ${this.terminalShell === 'powershell' ? 'PowerShell' : 'cmd'} —\n`;
    this.connectTerminal();
  }

  runTerminalCommand(event?: Event) {
    event?.preventDefault();
    event?.stopPropagation();
    const cmd = this.terminalInput;
    if (!cmd.trim()) {
      return;
    }
    // Prevent accidental double-Enter firing the same command twice
    if (this.terminalSending) {
      return;
    }
    const trimmed = cmd.trim().toLowerCase();
    if (trimmed === 'clear' || trimmed === 'cls') {
      this.terminalText = '';
      this.terminalInput = '';
      return;
    }

    this.terminalSending = true;
    // Append command onto the current shell prompt line (no extra '>').
    const t = this.terminalText;
    if (t.length === 0 || t.endsWith('\n') || t.endsWith('\r')) {
      this.terminalText += cmd + '\r\n';
    } else if (t.endsWith('>')) {
      this.terminalText += ' ' + cmd + '\r\n';
    } else {
      this.terminalText += '\r\n' + cmd + '\r\n';
    }
    this.terminalInput = '';
    setTimeout(() => this.scrollTerminal(), 0);

    const finish = () => {
      setTimeout(() => {
        this.terminalSending = false;
      }, 150);
    };

    if (!this.terminal.isConnected) {
      this.connectTerminal();
      const trySend = (attempt: number) => {
        if (this.terminal.isConnected) {
          this.terminal.sendLine(cmd);
          finish();
          return;
        }
        if (attempt < 40) {
          setTimeout(() => trySend(attempt + 1), 50);
        } else {
          finish();
        }
      };
      trySend(0);
      return;
    }
    this.terminal.sendLine(cmd);
    finish();
  }

  interruptTerminal() {
    this.terminal.interrupt();
  }

  private scrollTerminal() {
    const el = this.terminalOut?.nativeElement;
    if (el) {
      el.scrollTop = el.scrollHeight;
    }
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
