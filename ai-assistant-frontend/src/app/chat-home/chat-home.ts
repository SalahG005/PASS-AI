import { Component, ApplicationRef, ChangeDetectorRef, ElementRef, HostListener, NgZone, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
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
  BuildResult,
  CreateSkeletonProjectRequest,
  ProblemItem,
  ProjectOpenResult,
  ProjectSummary,
  SearchHit,
  UploadResult,
  WorkspaceApi,
  WorkspaceNode
} from '../workspace-api';
import { firstValueFrom, Subscription } from 'rxjs';
import { AiApi, AgentResponse, AgentToolStep, ChatHistoryItem, ConversationSummary, FileProposal, InstallProposal, ProjectPlan, ReviewFinding, ScaffoldPreview, SpecializedAgentMode } from '../ai-api';

export type ActivityView = 'files' | 'search' | 'git' | 'debug' | 'extensions';
export type BottomPanelTab = 'terminal' | 'problems' | 'output' | 'debug';
export type AiTab = 'chat' | 'history';

/** Capability level of the single assistant thread. */
export type AssistantMode = 'ask' | 'build';
/** RAG: Auto indexes when needed and uses RAG; On always uses RAG; Off never. */
export type RagMode = 'auto' | 'on' | 'off';

export const SPECIALIZED_AGENTS: { id: SpecializedAgentMode; label: string; hint: string }[] = [
  { id: 'scaffold', label: 'Scaffold', hint: 'Plan + multi-file project (Composer)' },
  { id: 'code', label: 'Code', hint: 'Edit code → Diff Apply' },
  { id: 'review', label: 'Review', hint: 'Read-only findings' },
  { id: 'test', label: 'Test', hint: 'Write & run tests' },
  { id: 'docs', label: 'Docs', hint: 'Generate documentation' },
  { id: 'research', label: 'Research', hint: 'Explain the codebase' }
];

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
  pending?: boolean;
  error?: boolean;
  /** Inline edit state for previously-sent user messages. */
  editing?: boolean;
  editText?: string;
  /** Workspace git commit captured before this message ran (for revert). */
  snapshotCommit?: string;
}

export interface EditSubmitDialog {
  messageId: number;
  text: string;
  canRevert: boolean;
  isAgent: boolean;
}

export interface ExplorerClipboard {
  mode: 'cut' | 'copy';
  path: string;
  name: string;
  type: 'file' | 'folder';
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
  @ViewChild('proposalPanelEl') proposalPanelEl?: ElementRef<HTMLDivElement>;

  activeActivity: ActivityView = 'files';
  bottomTab: BottomPanelTab = 'terminal';
  aiTab: AiTab = 'chat';

  showSidebar = true;
  showBottomPanel = true;
  showAiPanel = true;
  showNewMenu = false;
  openMenu: 'file' | 'edit' | 'view' | 'help' | null = null;

  showOpenTargetDialog = false;
  showCreateSkeletonModal = false;
  showSkeletonDepPicker = false;
  skeletonDepSearch = '';
  skeletonSubmitting = false;
  skeletonError = '';
  skeletonForm: {
    projectName: string;
    stack: 'spring-boot' | 'angular';
    groupId: string;
    artifactId: string;
    javaVersion: '17' | '21';
    packaging: 'jar' | 'war';
    configuration: 'properties' | 'yaml';
    dependencies: Record<'web' | 'jpa' | 'postgresql' | 'security' | 'lombok' | 'validation', boolean>;
    material: boolean;
    routing: boolean;
  } = this.defaultSkeletonForm();
  readonly skeletonSpringDeps: {
    id: 'web' | 'jpa' | 'postgresql' | 'security' | 'lombok' | 'validation';
    name: string;
    description: string;
  }[] = [
    { id: 'web', name: 'Spring Web', description: 'Build web, including RESTful, applications using Spring MVC.' },
    { id: 'jpa', name: 'Spring Data JPA', description: 'Persist data in SQL stores with Java Persistence API.' },
    { id: 'postgresql', name: 'PostgreSQL Driver', description: 'JDBC Driver for PostgreSQL database.' },
    { id: 'security', name: 'Spring Security', description: 'Highly customizable authentication and access-control.' },
    { id: 'lombok', name: 'Lombok', description: 'Java annotation library to reduce boilerplate code.' },
    { id: 'validation', name: 'Validation', description: 'Bean Validation with Hibernate validator.' }
  ];
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
  chatSending = false;
  chatStatus = '';
  indexing = false;
  indexChunkCount = 0;
  ragMode: RagMode = 'auto';
  private readonly ragModeKey = 'pass-ai-rag-mode';
  private autoIndexTimer: ReturnType<typeof setTimeout> | null = null;
  /**
   * One thread, two capability levels (like Cursor's Ask/Agent):
   * 'ask' answers with no tools; 'build' gives the agent tools and file proposals.
   */
  assistantMode: AssistantMode = 'ask';
  agentApplying = false;
  /** Plan / tool steps / findings belong to the build turn — hidden during plain Q&A. */
  artifactsVisible = true;
  verifyRunning = false;
  /** First snapshot commit of the current auto-fix run, for one-click revert. */
  verifyBaseCommit = '';
  pendingProposals: FileProposal[] = [];
  pendingInstalls: InstallProposal[] = [];
  scaffoldPreview: ScaffoldPreview | null = null;
  scaffoldPreviewEnabled = true;
  installApplying = false;
  /** User-chosen height of the proposals panel; null = default (CSS max-height). */
  proposalPanelHeight: number | null = null;
  private proposalResizeStartY = 0;
  private proposalResizeStartHeight = 0;
  agentToolSteps: AgentToolStep[] = [];
  reviewFindings: ReviewFinding[] = [];
  testRunSummary = '';
  specializedAgentMode: SpecializedAgentMode = 'scaffold';
  readonly specializedAgents = SPECIALIZED_AGENTS;
  projectPlan: ProjectPlan | null = null;
  /** Auto-continue rounds for Scaffold until plan files are proposed. */
  private scaffoldAutoRound = 0;
  private scaffoldStallCount = 0;
  private scaffoldLastMissingCount = -1;
  private readonly maxScaffoldAutoRounds = 10;
  expandedDiffPath: string | null = null;
  conversations: ConversationSummary[] = [];
  activeConversationId: number | null = null;
  activeConversationTitle = '';
  editorSelection = '';
  private cancelChatStream: (() => void) | null = null;
  private cancelAgentStream: (() => void) | null = null;
  private agentRequestSub: Subscription | null = null;
  editSubmitDialog: EditSubmitDialog | null = null;
  dontAskEditRevert = false;
  private readonly editRevertPrefKey = 'pass-ai-edit-revert-pref';
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
  recentProjects: ProjectSummary[] = [];
  activeProject: ProjectSummary | null = null;
  private contextNamedProjectId = '';
  showRecentSubmenu = false;
  private readonly maxRecent = 12;
  private autosaveTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly autosaveDelayMs = 900;
  private autosaveInFlight: Promise<void> | null = null;

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
    private aiApi: AiApi,
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
    this.loadRagMode();
    this.scaffoldPreviewEnabled = localStorage.getItem('pass-ai-scaffold-preview') !== '0';
    void this.bootstrapProjects();
    // Auto mode: if nothing indexed yet, start in the background
    setTimeout(() => this.maybeAutoIndex('startup'), 800);

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
    this.cancelChatStream?.();
    this.cancelAgentStream?.();
    this.agentRequestSub?.unsubscribe();
    this.agentRequestSub = null;
    if (this.autoIndexTimer) {
      clearTimeout(this.autoIndexTimer);
      this.autoIndexTimer = null;
    }
    if (this.autosaveTimer) {
      clearTimeout(this.autosaveTimer);
      this.autosaveTimer = null;
    }
    void this.flushAutosave();
    this.cancelChatStream = null;
    this.cancelAgentStream = null;
    this.stopProposalResize();
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

  /** Opens native Windows folder dialog and registers it as an account project. */
  openRealLocalFolder() {
    this.showNewMenu = false;
    this.openMenu = null;
    this.showRecentSubmenu = false;
    this.importing = true;
    this.importProgress = 'Opening folder picker…';
    this.statusMessage = this.importProgress;
    this.cdr.detectChanges();

    this.workspace.openLocalProject().subscribe({
      next: (res) => this.applyOpenedProject(res),
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
    this.workspace.linkProject(path).subscribe({
      next: (res) => this.applyOpenedProject(res),
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

  /** Apply opened/created project and force explorer to repaint. */
  private applyOpenedProject(res: ProjectOpenResult | BindingResult) {
    this.syncUiAfterOsDialog(() => {
      this.importing = false;
      this.importProgress = '';
      const project = (res as ProjectOpenResult).project;
      if (project?.projectId) {
        this.activateProject(project);
      }
      this.boundLocalPath = res.path || project?.absolutePath || '';
      this.openTabs = [];
      this.activeTabId = '';
      this.activeConversationId = null;
      this.activeConversationTitle = '';
      this.messages = [];
      this.pendingProposals = [];
      this.statusMessage = `Editing project: ${project?.name || res.path}`;
      this.pushOutput(`[workspace] Opened ${this.boundLocalPath}`);

      if (res.tree) {
        this.fileTree = this.applyExpandedState([res.tree], new Set(['']));
        this.loadingTree = false;
      }
      this.refreshTree(['']);
      this.refreshProblems();
      this.reconnectTerminal();
      this.refreshConversations();
      this.refreshRecentProjects();
      this.maybeAutoIndex('bind');
    });
  }

  /** Apply linked-folder result and force explorer to repaint (OS dialog steals focus/zone). */
  private applyLinkedWorkspace(res: BindingResult) {
    this.applyOpenedProject(res);
  }

  private activateProject(project: ProjectSummary) {
    this.activeProject = project;
    this.workspaceSession.setActiveProject(project.projectId);
    this.boundLocalPath = project.absolutePath || this.boundLocalPath;
  }

  private async bootstrapProjects() {
    try {
      const list = await firstValueFrom(this.workspace.listProjects());
      this.recentProjects = list || [];
      const currentId = this.workspaceSession.id;
      const match = this.recentProjects.find((p) => p.projectId === currentId);
      if (match) {
        const opened = await firstValueFrom(this.workspace.openProject(match.projectId));
        this.applyOpenedProject(opened);
        return;
      }
      if (!this.workspaceSession.isFreshWindow && this.recentProjects.length > 0) {
        const opened = await firstValueFrom(this.workspace.openProject(this.recentProjects[0].projectId));
        this.applyOpenedProject(opened);
        return;
      }
      // No projects yet — migrate current session / create AppData project
      const ensured = await firstValueFrom(
        this.workspace.ensureProject('Untitled Project', this.workspaceSession.id)
      );
      this.applyOpenedProject(ensured);
    } catch (err: any) {
      this.statusMessage = 'Could not load projects';
      this.pushOutput(`[projects] ${err?.message || err}`);
      this.refreshTree();
      this.refreshProblems();
      this.refreshBinding();
      this.refreshAiHealth();
      this.refreshIndexStatus();
      this.refreshConversations();
    } finally {
      this.refreshAiHealth();
      this.refreshIndexStatus();
    }
  }

  createNewProject() {
    this.closeMenus();
    const name = prompt('New project name', 'My Project');
    if (name === null) {
      return;
    }
    const trimmed = name.trim() || 'Untitled Project';
    void this.flushAutosave().then(() => {
      this.importing = true;
      this.importProgress = 'Creating project…';
      this.statusMessage = this.importProgress;
      this.workspace.createProject(trimmed).subscribe({
        next: (res) => this.applyOpenedProject(res),
        error: (err) => {
          this.importing = false;
          this.importProgress = '';
          this.statusMessage = 'Create project failed';
          alert(typeof err?.error === 'string' ? err.error : 'Create project failed');
        }
      });
    });
  }

  openCreateSkeletonModal() {
    this.closeMenus();
    this.skeletonError = '';
    this.skeletonDepSearch = '';
    this.showSkeletonDepPicker = false;
    this.skeletonForm = this.defaultSkeletonForm();
    this.syncSkeletonArtifactId();
    this.showCreateSkeletonModal = true;
  }

  cancelCreateSkeletonModal() {
    if (this.skeletonSubmitting) {
      return;
    }
    this.showCreateSkeletonModal = false;
    this.showSkeletonDepPicker = false;
    this.skeletonError = '';
  }

  openSkeletonDepPicker() {
    this.skeletonDepSearch = '';
    this.showSkeletonDepPicker = true;
  }

  closeSkeletonDepPicker() {
    this.showSkeletonDepPicker = false;
    this.skeletonDepSearch = '';
  }

  toggleSkeletonDependency(id: 'web' | 'jpa' | 'postgresql' | 'security' | 'lombok' | 'validation') {
    this.skeletonForm.dependencies[id] = !this.skeletonForm.dependencies[id];
  }

  removeSkeletonDependency(id: 'web' | 'jpa' | 'postgresql' | 'security' | 'lombok' | 'validation') {
    this.skeletonForm.dependencies[id] = false;
  }

  get skeletonPackageName(): string {
    const group = (this.skeletonForm.groupId || 'com.pass').trim();
    const artifact = (this.skeletonForm.artifactId || 'demo').trim().replace(/-/g, '_');
    return `${group}.${artifact}`;
  }

  get selectedSkeletonDeps() {
    return this.skeletonSpringDeps.filter((dep) => this.skeletonForm.dependencies[dep.id]);
  }

  get filteredSkeletonDeps() {
    const q = this.skeletonDepSearch.trim().toLowerCase();
    if (!q) {
      return this.skeletonSpringDeps;
    }
    return this.skeletonSpringDeps.filter(
      (dep) => dep.name.toLowerCase().includes(q) || dep.description.toLowerCase().includes(q)
    );
  }

  onSkeletonProjectNameChange() {
    this.syncSkeletonArtifactId();
  }

  onSkeletonStackChange() {
    this.skeletonError = '';
    this.showSkeletonDepPicker = false;
  }

  private defaultSkeletonForm() {
    return {
      projectName: 'demo',
      stack: 'spring-boot' as const,
      groupId: 'com.pass',
      artifactId: 'demo',
      javaVersion: '17' as const,
      packaging: 'jar' as const,
      configuration: 'properties' as const,
      dependencies: {
        web: true,
        jpa: false,
        postgresql: false,
        security: false,
        lombok: false,
        validation: false
      },
      material: false,
      routing: true
    };
  }

  private syncSkeletonArtifactId() {
    const slug = (this.skeletonForm.projectName || 'demo')
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '');
    this.skeletonForm.artifactId = slug || 'demo';
  }

  submitCreateSkeleton() {
    if (this.skeletonSubmitting) {
      return;
    }
    if (!this.auth.getValidToken()) {
      this.handleSkeletonAuthFailure();
      return;
    }
    const name = this.skeletonForm.projectName.trim();
    if (!name) {
      this.skeletonError = 'Project name is required.';
      return;
    }

    const body: CreateSkeletonProjectRequest = {
      projectName: name,
      stack: this.skeletonForm.stack
    };

    if (this.skeletonForm.stack === 'spring-boot') {
      body.groupId = this.skeletonForm.groupId.trim() || 'com.pass';
      body.artifactId = this.skeletonForm.artifactId.trim() || 'my-project';
      body.javaVersion = this.skeletonForm.javaVersion;
      body.packaging = this.skeletonForm.packaging;
      body.dependencies = Object.entries(this.skeletonForm.dependencies)
        .filter(([, enabled]) => enabled)
        .map(([dep]) => dep);
      if (!body.dependencies.length) {
        body.dependencies = ['web'];
      }
    } else {
      body.routing = this.skeletonForm.routing;
      body.material = this.skeletonForm.material;
    }

    void this.flushAutosave().then(() => {
      this.skeletonSubmitting = true;
      this.skeletonError = '';
      this.importing = true;
      this.importProgress = `Generating ${this.skeletonForm.stack === 'spring-boot' ? 'Spring Boot' : 'Angular'} project…`;
      this.statusMessage = this.importProgress;

      this.workspace.createSkeletonProject(body).subscribe({
        next: (res) => {
          this.skeletonSubmitting = false;
          this.showCreateSkeletonModal = false;
          this.applyOpenedProject(res);
        },
        error: (err: unknown) => {
          this.skeletonSubmitting = false;
          this.importing = false;
          this.importProgress = '';
          if (err instanceof HttpErrorResponse && err.status === 401) {
            this.handleSkeletonAuthFailure();
            return;
          }
          const msg =
            err instanceof HttpErrorResponse
              ? typeof err.error === 'string'
                ? err.error
                : (err.error as { message?: string })?.message || err.message
              : 'Project generation failed';
          this.skeletonError = msg || 'Project generation failed';
          this.statusMessage = 'Create project failed';
        }
      });
    });
  }

  private handleSkeletonAuthFailure() {
    this.skeletonSubmitting = false;
    this.importing = false;
    this.importProgress = '';
    this.skeletonError = 'Your session expired. Please sign in again.';
    this.statusMessage = 'Session expired';
    this.auth.logout();
    void this.router.navigate(['/login']);
  }

  openRecentProject(project: ProjectSummary) {
    this.closeMenus();
    if (!project?.projectId) {
      return;
    }
    if (this.activeProject?.projectId === project.projectId) {
      this.statusMessage = `Already in ${project.name}`;
      return;
    }
    void this.flushAutosave().then(() => {
      this.importing = true;
      this.importProgress = `Opening ${project.name}…`;
      this.workspace.openProject(project.projectId).subscribe({
        next: (res) => this.applyOpenedProject(res),
        error: (err) => {
          this.importing = false;
          this.importProgress = '';
          this.statusMessage = 'Could not open project';
          alert(typeof err?.error === 'string' ? err.error : 'Could not open project');
        }
      });
    });
  }

  refreshRecentProjects() {
    this.workspace.listProjects().subscribe({
      next: (list) => {
        this.recentProjects = (list || []).slice(0, this.maxRecent);
        this.cdr.markForCheck();
      },
      error: () => {
        this.recentProjects = [];
      }
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
    if (this.showSkeletonDepPicker) {
      this.closeSkeletonDepPicker();
      return;
    }
    if (this.showCreateSkeletonModal) {
      this.cancelCreateSkeletonModal();
      return;
    }
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
    const tab = this.openTabs[index];
    const finishClose = () => {
      const i = this.openTabs.findIndex((t) => t.id === id);
      if (i < 0) {
        return;
      }
      this.openTabs.splice(i, 1);
      if (this.activeTabId === id) {
        const next = this.openTabs[i] ?? this.openTabs[i - 1];
        this.activeTabId = next?.id ?? '';
      }
    };
    if (tab?.dirty) {
      void this.writeTab(tab, false).finally(finishClose);
      return;
    }
    finishClose();
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

  menuNewProject() {
    this.closeMenus();
    this.createNewProject();
  }

  menuCreateProject() {
    this.openCreateSkeletonModal();
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
    this.saveProjectAs();
  }

  onEditorValueChange(value: string) {
    const tab = this.activeTab;
    if (!tab) {
      return;
    }
    tab.content = value;
    tab.dirty = true;
    this.scheduleAutosave();
  }

  saveActiveFile() {
    const tab = this.activeTab;
    if (!tab) {
      return;
    }
    void this.writeTab(tab, true);
  }

  /** Save Project: native folder picker → relocate and continue editing there. */
  saveProjectAs() {
    const projectId = this.activeProject?.projectId || this.workspaceSession.id;
    if (!projectId) {
      this.statusMessage = 'No active project to save';
      return;
    }
    void this.flushAutosave().then(() => {
      this.saving = true;
      this.statusMessage = 'Choose where to save the project…';
      this.workspace.saveProjectAs(projectId).subscribe({
        next: (res) => {
          this.saving = false;
          this.applyOpenedProject(res);
          this.statusMessage = `Project saved to ${res.path}`;
        },
        error: (err) => {
          this.saving = false;
          const msg = typeof err?.error === 'string' ? err.error : 'Save project cancelled or failed';
          if (String(msg).toLowerCase().includes('cancel')) {
            this.statusMessage = 'Save cancelled';
            return;
          }
          this.statusMessage = 'Save project failed';
          alert(msg);
        }
      });
    });
  }

  private scheduleAutosave() {
    if (this.autosaveTimer) {
      clearTimeout(this.autosaveTimer);
    }
    this.autosaveTimer = setTimeout(() => {
      this.autosaveTimer = null;
      void this.flushAutosave();
    }, this.autosaveDelayMs);
  }

  private async flushAutosave(): Promise<void> {
    if (this.autosaveInFlight) {
      await this.autosaveInFlight;
    }
    const dirty = this.openTabs.filter((t) => t.dirty && t.path);
    if (!dirty.length) {
      return;
    }
    this.autosaveInFlight = (async () => {
      for (const tab of dirty) {
        await this.writeTab(tab, false);
      }
    })();
    try {
      await this.autosaveInFlight;
    } finally {
      this.autosaveInFlight = null;
    }
  }

  private async writeTab(tab: EditorTab, showStatus: boolean): Promise<void> {
    if (!tab.path) {
      return;
    }
    if (showStatus) {
      this.saving = true;
    }
    try {
      await firstValueFrom(this.workspace.writeFile(tab.path, tab.content));
      tab.dirty = false;
      if (showStatus) {
        this.statusMessage = `Saved ${tab.path}`;
      }
      this.pushOutput(`[save] ${tab.path}`);
      this.refreshProblems();
      this.scheduleAutoIndex(2500);
    } catch (err: any) {
      tab.dirty = true;
      this.statusMessage = 'Autosave failed';
      this.pushOutput(`[save] failed ${tab.path}: ${err?.message || err}`);
      if (showStatus) {
        alert(typeof err?.error === 'string' ? err.error : 'Save failed');
      }
    } finally {
      if (showStatus) {
        this.saving = false;
      }
    }
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
      // Ensure imported content belongs to a durable account project
      try {
        const ensured = await firstValueFrom(
          this.workspace.ensureProject(
            this.activeProject?.name || 'Imported Project',
            this.workspaceSession.id
          )
        );
        this.activateProject(ensured.project);
        this.refreshRecentProjects();
        this.refreshConversations();
        if (ensured.tree) {
          this.fileTree = this.markExpanded([ensured.tree]);
        }
      } catch {
        // keep imported files even if project registration fails
      }
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
    if (this.showCreateSkeletonModal) {
      if ((event.ctrlKey || event.metaKey) && key === 'enter') {
        event.preventDefault();
        this.submitCreateSkeleton();
        return;
      }
      if ((event.ctrlKey || event.metaKey) && key === 'b' && this.skeletonForm.stack === 'spring-boot') {
        event.preventDefault();
        this.openSkeletonDepPicker();
        return;
      }
    }
    if ((event.ctrlKey || event.metaKey) && key === 's') {
      event.preventDefault();
      this.saveProjectAs();
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

  onEditorCursor(pos: { line: number; column: number }) {
    this.cursorLine = pos.line;
    this.cursorCol = pos.column;
  }

  onEditorSelection(text: string) {
    this.editorSelection = text || '';
  }

  private buildAiExtras(): { activePath?: string; mentionPaths: string[]; selection?: string; openPaths: string[] } {
    return {
      activePath: this.activeTab?.path,
      mentionPaths: [],
      selection: this.editorSelection?.trim() ? this.editorSelection : undefined,
      openPaths: this.openTabs.map((t) => t.path).filter(Boolean)
    };
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
    this.cancelChatStream?.();
    this.cancelChatStream = null;
    this.chatSending = false;
    this.editSubmitDialog = null;
    this.messages = [];
    this.chatInput = '';
    this.nextMsgId = 1;
    this.activeConversationId = null;
    this.activeConversationTitle = '';
    this.aiTab = 'chat';
  }

  refreshConversations() {
    this.aiApi.listConversations(this.workspaceSession.id).subscribe({
      next: (list) => {
        this.conversations = list || [];
        this.cdr.markForCheck();
      },
      error: () => {
        this.conversations = [];
      }
    });
  }

  openHistoryTab() {
    this.aiTab = 'history';
    this.refreshConversations();
  }

  newConversation() {
    this.resetChat();
    this.resetAgentArtifacts();
    this.activeConversationId = null;
    this.activeConversationTitle = '';
    this.aiTab = 'chat';
    this.statusMessage = 'New conversation';
  }

  openConversation(id: number) {
    if (!id || this.chatSending) {
      return;
    }
    this.aiApi.getConversation(id).subscribe({
      next: (detail) => {
        this.activeConversationId = detail.id;
        this.activeConversationTitle = detail.title || 'Conversation';
        this.assistantMode = detail.mode === 'agent' ? 'build' : 'ask';
        this.aiTab = 'chat';
        const mapped: ChatMessage[] = (detail.messages || []).map((m, idx) => ({
          id: m.id ?? idx + 1,
          role: m.role === 'assistant' ? 'assistant' : 'user',
          text: m.content || ''
        }));
        this.nextMsgId = mapped.reduce((max, m) => Math.max(max, m.id), 0) + 1;
        this.messages = mapped;
        this.pendingProposals = [];
        this.statusMessage =
          mapped.length === 0
            ? `Opened “${detail.title}” (no saved messages yet — send to continue)`
            : `Loaded: ${detail.title}`;
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.statusMessage = err?.error?.error || err?.message || 'Could not load conversation';
      }
    });
  }

  deleteConversation(id: number, event?: Event) {
    event?.stopPropagation();
    if (!id) {
      return;
    }
    this.aiApi.deleteConversation(id).subscribe({
      next: () => {
        if (this.activeConversationId === id) {
          this.newConversation();
        }
        this.refreshConversations();
        this.statusMessage = 'Conversation deleted';
      },
      error: (err) => {
        this.statusMessage = err?.error?.error || 'Delete failed';
      }
    });
  }

  private persistTurn(mode: 'chat' | 'agent', userText: string, assistantText: string) {
    if (!assistantText?.trim()) {
      return;
    }
    this.aiApi
      .saveConversationTurn({
        conversationId: this.activeConversationId,
        mode,
        workspaceId: this.workspaceSession.id,
        userMessage: userText,
        assistantMessage: assistantText,
        title: userText.slice(0, 80)
      })
      .subscribe({
        next: (detail) => {
          this.activeConversationId = detail.id;
          this.activeConversationTitle = detail.title || this.activeConversationTitle;
          this.refreshConversations();
        },
        error: (err) => {
          const msg = err?.error?.error || err?.error?.message || err?.message || 'save failed';
          this.pushOutput(`[ai] could not save conversation: ${msg}`);
        }
      });
  }

  useQuickAction(prompt: string) {
    this.chatInput = prompt;
    this.send();
  }

  onChatEnter(event: Event) {
    const ke = event as KeyboardEvent;
    if (ke.shiftKey) {
      return;
    }
    ke.preventDefault();
    this.send();
  }

  onEditEnter(event: Event, msg: ChatMessage) {
    const ke = event as KeyboardEvent;
    if (ke.shiftKey) {
      return;
    }
    ke.preventDefault();
    this.submitEditMessage(msg);
  }

  refreshAiHealth() {
    this.aiApi.health().subscribe({
      next: (h) => {
        this.chatStatus = h.message || (h.ok ? 'AI ready' : 'AI unavailable');
        this.statusMessage = this.chatStatus;
        this.cdr.markForCheck();
      },
      error: () => {
        this.chatStatus = 'Cannot reach AI backend';
      }
    });
  }

  refreshIndexStatus() {
    this.aiApi.indexStatus().subscribe({
      next: (s) => {
        this.indexChunkCount = s.chunks ?? 0;
        this.cdr.markForCheck();
      },
      error: () => {
        this.indexChunkCount = 0;
      }
    });
  }

  setRagMode(mode: RagMode) {
    this.ragMode = mode;
    try {
      localStorage.setItem(this.ragModeKey, mode);
    } catch {
      /* ignore */
    }
    this.statusMessage =
      mode === 'auto'
        ? 'RAG Auto: indexes when needed, uses semantic search'
        : mode === 'on'
          ? 'RAG On: always use indexed snippets'
          : 'RAG Off: open file + @mentions only';
    if (mode === 'auto' || mode === 'on') {
      this.maybeAutoIndex('mode');
    }
    this.cdr.markForCheck();
  }

  private loadRagMode() {
    try {
      const raw = localStorage.getItem(this.ragModeKey);
      if (raw === 'auto' || raw === 'on' || raw === 'off') {
        this.ragMode = raw;
      }
    } catch {
      this.ragMode = 'auto';
    }
  }

  /** Whether this turn should request RAG from the backend. */
  private shouldUseRag(): boolean {
    if (this.ragMode === 'off') {
      return false;
    }
    if (this.ragMode === 'on') {
      return true;
    }
    // auto: use RAG only once something is indexed
    return this.indexChunkCount > 0;
  }

  scheduleAutoIndex(delayMs = 1200) {
    if (this.ragMode === 'off') {
      return;
    }
    if (this.autoIndexTimer) {
      clearTimeout(this.autoIndexTimer);
    }
    this.autoIndexTimer = setTimeout(() => {
      this.autoIndexTimer = null;
      this.maybeAutoIndex('schedule');
    }, delayMs);
  }

  private maybeAutoIndex(_reason: string) {
    if (this.ragMode === 'off' || this.indexing) {
      return;
    }
    // Reindex when empty, after folder bind, or when user switches to Auto/On
    const needIndex =
      this.indexChunkCount === 0 || _reason === 'bind' || _reason === 'mode';
    if (!needIndex) {
      return;
    }
    this.indexWorkspaceForRag(true);
  }

  indexWorkspaceForRag(silent = false) {
    if (this.indexing) {
      return;
    }
    this.indexing = true;
    if (!silent) {
      this.statusMessage = 'Indexing workspace for semantic search…';
    }
    this.aiApi.index().subscribe({
      next: (res) => {
        this.indexing = false;
        this.indexChunkCount = res.chunksStored;
        this.statusMessage = res.message;
        this.pushOutput(`[ai] ${res.message}`);
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.indexing = false;
        const msg = err?.error?.error || err?.message || 'Index failed';
        this.statusMessage = String(msg);
        this.pushOutput(`[ai] index error: ${msg}`);
        this.cdr.markForCheck();
      }
    });
  }

  /**
   * In Auto/On with an empty index, wait for indexing before sending (best-effort).
   * skipEmptyIndex: scaffold on a blank project skips indexing (nothing useful to retrieve yet).
   */
  private ensureRagBeforeSend(then: () => void, options?: { skipEmptyIndex?: boolean }) {
    if (this.ragMode === 'off') {
      then();
      return;
    }
    if (options?.skipEmptyIndex && this.indexChunkCount === 0 && !this.indexing) {
      then();
      return;
    }
    if (this.indexChunkCount > 0 || this.indexing) {
      then();
      return;
    }
    this.indexing = true;
    this.statusMessage = 'Auto-indexing workspace for RAG…';
    this.aiApi.index().subscribe({
      next: (res) => {
        this.indexing = false;
        this.indexChunkCount = res.chunksStored ?? 0;
        this.pushOutput(`[ai] ${res.message || 'indexed'}`);
        then();
      },
      error: () => {
        this.indexing = false;
        then();
      }
    });
  }

  /**
   * Single entry point for the one thread. The Ask/Build toggle decides the capability
   * level — nothing guesses your intent from keywords.
   */
  send() {
    const text = this.chatInput.trim();
    if (!text || this.chatSending) {
      return;
    }
    if (this.assistantMode === 'build') {
      this.sendBuild(text);
    } else {
      this.sendAsk(text);
    }
  }

  setAssistantMode(mode: AssistantMode) {
    if (this.assistantMode === mode) {
      return;
    }
    this.assistantMode = mode;
    this.statusMessage =
      mode === 'build'
        ? 'Build mode — the agent can read, edit and propose files'
        : 'Ask mode — answers only, no file changes';
  }

  /** Ask: streaming answer, no tools, never touches files. */
  private sendAsk(text: string) {
    // A question is not part of the build: tuck the plan/steps away (state is kept,
    // so "Show plan" brings it back with its phase progress intact).
    this.artifactsVisible = false;
    const userMsg: ChatMessage = { id: this.nextMsgId++, role: 'user', text };
    this.messages.push(userMsg);
    this.snapshotBeforeMessage(userMsg);
    this.chatInput = '';
    const assistantId = this.nextMsgId++;
    this.messages.push({ id: assistantId, role: 'assistant', text: '', pending: true });
    this.chatSending = true;
    this.cdr.detectChanges();

    this.ensureRagBeforeSend(() => this.runChatStream(text, assistantId));
  }

  /** Capture a workspace git snapshot so an edited message can revert file changes. */
  private snapshotBeforeMessage(msg: ChatMessage) {
    this.workspace.snapshot(`Before message: ${msg.text.slice(0, 60)}`).subscribe({
      next: (res) => {
        if (res?.commit) {
          msg.snapshotCommit = res.commit;
        }
      },
      error: () => undefined
    });
  }

  /** Begin editing a previously-sent user message (chat or agent). */
  startEditMessage(msg: ChatMessage) {
    if (msg.role !== 'user' || this.chatSending) {
      return;
    }
    for (const m of this.messages) {
      m.editing = false;
    }
    msg.editing = true;
    msg.editText = msg.text;
    this.cdr.detectChanges();
  }

  cancelEditMessage(msg: ChatMessage) {
    msg.editing = false;
    msg.editText = undefined;
  }

  /** User confirmed the inline edit — decide whether to ask about reverting. */
  submitEditMessage(msg: ChatMessage) {
    const next = (msg.editText ?? '').trim();
    if (!next) {
      return;
    }
    const isAgent = this.messages.includes(msg);
    const canRevert = !!msg.snapshotCommit;
    const pref = localStorage.getItem(this.editRevertPrefKey);
    if (pref === 'revert' || pref === 'keep') {
      this.resubmitFromMessage(msg.id, next, pref === 'revert' && canRevert, isAgent);
      return;
    }
    this.dontAskEditRevert = false;
    this.editSubmitDialog = { messageId: msg.id, text: next, canRevert, isAgent };
    this.cdr.detectChanges();
  }

  cancelEditDialog() {
    this.editSubmitDialog = null;
  }

  confirmEditDialog(revert: boolean) {
    const dialog = this.editSubmitDialog;
    if (!dialog) {
      return;
    }
    if (this.dontAskEditRevert) {
      localStorage.setItem(this.editRevertPrefKey, revert ? 'revert' : 'keep');
    }
    this.editSubmitDialog = null;
    this.resubmitFromMessage(dialog.messageId, dialog.text, revert && dialog.canRevert, dialog.isAgent);
  }

  /** Rewrite a user message, clear everything after it, optionally revert files, then re-send. */
  private resubmitFromMessage(messageId: number, newText: string, revert: boolean, isAgent: boolean) {
    const list = isAgent ? this.messages : this.messages;
    const idx = list.findIndex((m) => m.id === messageId);
    if (idx < 0) {
      return;
    }
    const msg = list[idx];
    const commit = msg.snapshotCommit;
    msg.text = newText;
    msg.editing = false;
    msg.editText = undefined;
    // Clear all messages after the edited one
    const trimmed = list.slice(0, idx + 1);
    if (isAgent) {
      this.messages = trimmed;
    } else {
      this.messages = trimmed;
    }

    const proceed = () => {
      const assistantId = this.nextMsgId++;
      if (isAgent) {
        this.messages.push({ id: assistantId, role: 'assistant', text: 'Working…', pending: true });
        this.chatSending = true;
        this.pendingProposals = [];
        this.agentToolSteps = [];
        this.reviewFindings = [];
        this.testRunSummary = '';
        this.expandedDiffPath = null;
        this.cdr.detectChanges();
        this.ensureRagBeforeSend(() => this.runAgentRequest(newText, assistantId, []));
      } else {
        this.messages.push({ id: assistantId, role: 'assistant', text: '', pending: true });
        this.chatSending = true;
        this.cdr.detectChanges();
        this.ensureRagBeforeSend(() => this.runChatStream(newText, assistantId));
      }
    };

    if (revert && commit) {
      this.statusMessage = 'Reverting file changes…';
      this.workspace.revert(commit).subscribe({
        next: () => {
          this.refreshTree();
          this.refreshProblems();
          this.pushOutput(`[ai] reverted workspace to ${commit.slice(0, 8)}`);
          proceed();
        },
        error: (err) => {
          this.statusMessage = 'Revert failed — continuing without revert';
          this.pushOutput(`[ai] revert failed: ${err?.error?.message || err?.message || err}`);
          proceed();
        }
      });
    } else {
      proceed();
    }
  }

  private runChatStream(text: string, assistantId: number) {
    const mentionPaths = this.extractMentionPaths(text);
    const extras = this.buildAiExtras();
    const history: ChatHistoryItem[] = this.messages
      .filter((m) => m.id !== assistantId && !m.error)
      .slice(0, -1)
      .map((m) => ({
        role: m.role,
        content: m.text
      }));

    const body = {
      message: text,
      history,
      activePath: extras.activePath,
      mentionPaths,
      selection: extras.selection,
      openPaths: extras.openPaths,
      useRag: this.shouldUseRag()
    };

    this.cancelChatStream?.();
    this.cancelChatStream = this.aiApi.chatStream(
      body,
      (token) => {
        this.ngZone.run(() => {
          const msg = this.messages.find((m) => m.id === assistantId);
          if (msg) {
            msg.text += token;
            msg.pending = true;
            this.cdr.detectChanges();
          }
        });
      },
      (done) => {
        this.ngZone.run(() => {
          const msg = this.messages.find((m) => m.id === assistantId);
          if (msg) {
            msg.text = done.reply || msg.text;
            msg.pending = false;
          }
          this.chatSending = false;
          this.cancelChatStream = null;
          const ctx = [...(done.usedPaths || []), ...(done.ragPaths || [])];
          if (ctx.length) {
            this.pushOutput(`[ai] context: ${ctx.join(', ')}`);
          } else if (this.ragMode !== 'off') {
            this.pushOutput(`[ai] RAG mode=${this.ragMode} · ${this.indexChunkCount} chunks`);
          }
          const assistantText = msg?.text || done.reply || '';
          if (!msg?.error && assistantText) {
            this.persistTurn('chat', text, assistantText);
          }
          this.cdr.detectChanges();
        });
      },
      (error) => {
        this.ngZone.run(() => {
          const msg = this.messages.find((m) => m.id === assistantId);
          if (msg) {
            msg.text = msg.text || `Error: ${error}`;
            msg.pending = false;
            msg.error = true;
          }
          this.chatSending = false;
          this.cancelChatStream = null;
          this.chatStatus = error;
          this.statusMessage = error;
          this.pushOutput(`[ai] ${error}`);
          this.cdr.detectChanges();
        });
      }
    );
  }

  private extractMentionPaths(text: string): string[] {
    const paths = new Set<string>();
    const re = /@([\w./\\-]+)/g;
    let m: RegExpExecArray | null;
    while ((m = re.exec(text)) !== null) {
      paths.add(m[1].replace(/\\/g, '/'));
    }
    return [...paths];
  }



  /**
   * Answer "did it work / how do I open it" from the real terminal output.
   * Never re-runs the command.
   */
  handleProjectStatus(userText: string) {
    this.chatInput = '';
    this.aiTab = 'chat';
    this.messages.push({ id: this.nextMsgId++, role: 'user', text: userText });

    const log = this.ideTerminal?.recentOutput() || '';
    const lastCmd = this.ideTerminal?.lastCommand || '';
    const answer = this.explainRunOutcome(log, lastCmd);

    this.messages.push({ id: this.nextMsgId++, role: 'assistant', text: answer.text });
    this.statusMessage = answer.status;
    this.cdr.detectChanges();

    if (answer.openUrl) {
      try {
        window.open(answer.openUrl, '_blank');
      } catch {
        /* popup blocked */
      }
    }
  }

  /** Read the terminal tail and say plainly what happened. */
  private explainRunOutcome(
    log: string,
    lastCmd: string
  ): { text: string; status: string; openUrl?: string } {
    if (!log.trim()) {
      return {
        text:
          'Nothing has run in the TERMINAL yet, so there is no result to report.\n\n' +
          'Say **run** and I will start the project, then ask me again and I will read the output.',
        status: 'Nothing has run yet'
      };
    }

    const portInUse = /port (\d+) was already in use/i.exec(log);
    const failedToStart = /APPLICATION FAILED TO START/i.test(log);
    if (failedToStart && portInUse) {
      const port = portInUse[1];
      return {
        text:
          `**No — the app did not start.** Port **${port}** is already taken by another process, ` +
          `so Spring Boot shut down right after building.\n\n` +
          `The build itself was fine (\`BUILD SUCCESS\`) — only the web server failed.\n\n` +
          `Two ways forward:\n` +
          `1. **Use a free port** — add \`server.port=8090\` to ` +
          `\`backend/src/main/resources/application.properties\`, then say **run**.\n` +
          `2. **Free port ${port}** — in TERMINAL run ` +
          `\`netstat -ano | findstr :${port}\` then \`taskkill /PID <pid> /F\`, then say **run**.\n\n` +
          `Tell me which one you prefer and I will do it.`,
        status: `Port ${port} already in use`
      };
    }

    if (failedToStart) {
      const desc = /Description:\s*\n+\s*([^\n]+)/i.exec(log);
      return {
        text:
          `**The app did not start.**${desc ? ` Reason: ${desc[1].trim()}` : ''}\n\n` +
          `Paste the red error block here and I will propose the file fixes.`,
        status: 'Application failed to start'
      };
    }

    if (/BUILD FAILURE/i.test(log) || /COMPILATION ERROR/i.test(log)) {
      return {
        text:
          '**The build failed** — nothing is running yet.\n\n' +
          'Paste the `[ERROR]` lines here (or say **fix**) and I will propose corrected files to Apply.',
        status: 'Build failed'
      };
    }

    const tomcat = /Tomcat started on port[^\d]*(\d+)/i.exec(log);
    const started = /Started \w+Application in/i.test(log);
    if (tomcat && started) {
      const url = `http://localhost:${tomcat[1]}`;
      return {
        text:
          `**Yes — it is running.** Spring Boot started on port **${tomcat[1]}**.\n\n` +
          `Open ${url} (I just opened it for you).\n\n` +
          `A bare \`/\` may show a 404 — that is normal until a controller maps it. ` +
          `Your endpoints are the paths in your \`@GetMapping\` / \`@RequestMapping\` annotations.\n\n` +
          `Keep the TERMINAL tab open; closing it stops the server.`,
        status: `Running on port ${tomcat[1]}`,
        openUrl: url
      };
    }

    const pyServer = /Serving HTTP on .* port (\d+)/i.exec(log);
    if (pyServer) {
      const url = `http://127.0.0.1:${pyServer[1]}/`;
      return {
        text:
          `**Yes — the static site is being served** on port **${pyServer[1]}**.\n\n` +
          `Open ${url} (opened for you). Stop it with Ctrl+C in TERMINAL.`,
        status: `Static site on ${pyServer[1]}`,
        openUrl: url
      };
    }

    const viteLocal = /(?:Local|On Your Network):\s*(https?:\/\/[^\s]+)/i.exec(log);
    if (viteLocal) {
      return {
        text: `**Yes — the dev server is up.** Open ${viteLocal[1]} (opened for you).`,
        status: 'Dev server running',
        openUrl: viteLocal[1]
      };
    }

    if (/Downloading from central|Downloaded from central/i.test(log) && !started) {
      return {
        text:
          '**Still working** — Maven is downloading dependencies. That is the first-run cost.\n\n' +
          'Wait for `Started ...Application in X seconds`, then ask me again and I will give you the URL.',
        status: 'Maven downloading dependencies'
      };
    }

    const tail = log.trim().split('\n').slice(-12).join('\n');
    return {
      text:
        `I could not find a clear success or failure marker in the terminal.\n\n` +
        (lastCmd ? `Last command: \`${lastCmd}\`\n\n` : '') +
        `Last lines:\n\`\`\`\n${tail}\n\`\`\`\n\n` +
        `If that looks like an error, say **fix** and I will propose file changes.`,
      status: 'Run outcome unclear'
    };
  }

  /**
   * Cursor-style close-the-loop: snapshot → build → if it fails, ask the Code agent
   * to fix, auto-apply, snapshot, rebuild — up to a cap. A git snapshot before each
   * pass means every automatic edit can be undone with one click.
   */
  async runVerifyLoop(userText: string) {
    if (this.verifyRunning || this.chatSending) {
      return;
    }
    this.verifyRunning = true;
    this.aiTab = 'chat';
    this.chatInput = '';
    this.chatInput = '';
    this.specializedAgentMode = 'code';
    this.messages.push({ id: this.nextMsgId++, role: 'user', text: userText });
    const statusId = this.nextMsgId++;
    this.messages.push({
      id: statusId,
      role: 'assistant',
      text: 'Starting build-and-fix loop…',
      pending: true
    });
    this.cdr.detectChanges();

    const say = (text: string, pending = false) => {
      this.messages.push({ id: this.nextMsgId++, role: 'assistant', text, pending });
      this.cdr.detectChanges();
    };
    const setStatus = (text: string) => {
      const m = this.messages.find((x) => x.id === statusId);
      if (m) {
        m.text = text;
      }
      this.statusMessage = text;
      this.cdr.detectChanges();
    };

    const maxAttempts = 3;
    try {
      setStatus('Saving a snapshot so changes can be undone…');
      const snap = await firstValueFrom(this.workspace.snapshot('auto-fix: baseline'));
      this.verifyBaseCommit = snap?.commit || '';
      if (snap && snap.gitAvailable === false) {
        say('Note: git is not installed, so automatic undo is off for this run. I will still build and fix.');
      }

      for (let attempt = 0; attempt <= maxAttempts; attempt++) {
        setStatus(attempt === 0 ? 'Building…' : `Rebuilding (attempt ${attempt}/${maxAttempts})…`);
        let build: BuildResult;
        try {
          build = await firstValueFrom(this.workspace.build());
        } catch (e: unknown) {
          say(`Could not run the build: ${this.errText(e)}`);
          break;
        }
        this.pushOutput(`[verify] ${build.command} → exit ${build.exitCode}`);

        if (build.ok) {
          setStatus('Build passed');
          say(
            `**Build passed** (\`${build.command}\`).\n\n` +
              `Now say **run** to start it, then ask me "did it work?" for the URL.`
          );
          break;
        }

        if (attempt === maxAttempts) {
          setStatus('Still failing after auto-fixes');
          say(
            `**Still failing after ${maxAttempts} auto-fix attempts.** Last errors:\n\n` +
              '```\n' + this.tailText(build.tail, 1600) + '\n```\n\n' +
              (this.verifyBaseCommit
                ? 'You can undo all of these automatic edits with the **Undo auto-fix** button.'
                : 'Review the changes and adjust manually.')
          );
          break;
        }

        say(
          `Attempt ${attempt + 1}: build failed — reading the error and proposing a fix…\n\n` +
            '```\n' + this.tailText(build.tail, 1200) + '\n```',
          true
        );

        const hintPaths = this.collectWorkspaceFilePaths(this.fileTree)
          .filter((p) => /pom\.xml$|package\.json$|\.java$|\.ts$|\.tsx$|\.js$|\.html$/i.test(p))
          .slice(0, 16)
          .map((p) => `- ${p}`)
          .join('\n');

        const fixPrompt =
          `[FIX ERROR] The build failed. Fix it with a SURGICAL change. ` +
          `read_file the failing file, then edit_file with the exact old_string→new_string. ` +
          `Emit ONLY \`\`\`pass-tool fences. Do NOT scaffold or rewrite whole files from memory. ` +
          `Spring Boot 3: jakarta.persistence (not javax). Java class name MUST match filename.\n\n` +
          `Known files:\n${hintPaths || '(use search)'}\n\n` +
          `BUILD OUTPUT (${build.command}):\n\`\`\`\n${this.tailText(build.tail, 4000)}\n\`\`\`\n\n` +
          `Then done with a one-line summary.`;

        let res;
        try {
          res = await firstValueFrom(
            this.aiApi.specializedAgent('code', { message: fixPrompt, useRag: this.shouldUseRag() })
          );
        } catch (e: unknown) {
          say(`The fix agent failed: ${this.errText(e)}`);
          break;
        }

        const files = res?.files || [];
        if (!files.length) {
          say(
            'The agent did not propose any file changes, so I stopped.\n\n' +
              (res?.reply ? `It said:\n\n${this.tailText(res.reply, 800)}` : '') +
              '\n\nPaste the error here and I will try a targeted fix.'
          );
          break;
        }

        setStatus(`Applying ${files.length} fix(es)…`);
        try {
          const applied = await firstValueFrom(this.aiApi.applyFiles(files));
          say(
            `Applied ${applied.applied.length} file(s): ${applied.applied.join(', ')}` +
              (applied.errors.length ? `\nErrors: ${applied.errors.join('; ')}` : '')
          );
        } catch (e: unknown) {
          say(`Could not apply the proposed fix: ${this.errText(e)}`);
          break;
        }

        await firstValueFrom(this.workspace.snapshot(`auto-fix: attempt ${attempt + 1}`));
        this.refreshTree();
      }
    } catch (e: unknown) {
      say(`Auto-fix loop error: ${this.errText(e)}`);
    } finally {
      const m = this.messages.find((x) => x.id === statusId);
      if (m) {
        m.pending = false;
      }
      this.verifyRunning = false;
      this.cdr.detectChanges();
    }
  }

  /** Undo every edit the auto-fix loop made, back to its baseline snapshot. */
  revertAutoFix() {
    if (this.verifyRunning) {
      return;
    }
    this.workspace.revert(this.verifyBaseCommit || undefined).subscribe({
      next: (res) => {
        this.messages.push({
          id: this.nextMsgId++,
          role: 'assistant',
          text: res.ok ? 'Reverted all auto-fix edits to the baseline snapshot.' : `Revert failed: ${res.message || ''}`
        });
        this.statusMessage = res.ok ? 'Reverted auto-fix edits' : 'Revert failed';
        this.verifyBaseCommit = '';
        this.refreshTree();
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.statusMessage = 'Revert failed';
        this.pushOutput(`[verify] revert error: ${err?.error?.error || err?.message || err}`);
      }
    });
  }

  get canRevertAutoFix(): boolean {
    return !!this.verifyBaseCommit && !this.verifyRunning;
  }

  private tailText(text: string, max: number): string {
    const t = text || '';
    return t.length > max ? '…' + t.slice(-max) : t;
  }

  private errText(e: unknown): string {
    const err = e as { error?: { error?: string }; message?: string };
    return err?.error?.error || err?.message || String(e);
  }

  /** Detect project type and start it in the IDE terminal (no file rewrite). */
  handleRunProject(userText: string) {
    this.chatInput = '';
    const paths = this.collectWorkspaceFilePaths(this.fileTree);
    const hasRootIndex = paths.some((p) => /^index\.html$/i.test(p));
    const hasSrcStaticIndex = paths.some((p) =>
      /(^|\/)src\/main\/resources\/(static\/)?index\.html$/i.test(p)
    );
    const pomPath = paths.find((p) => /(^|\/)pom\.xml$/i.test(p));
    const hasPom = !!pomPath;
    const hasPkg = paths.some((p) => /(^|\/)package\.json$/i.test(p));
    const hasMvnW = paths.some((p) => /(^|\/)mvnw(\.cmd)?$/i.test(p));

    this.showBottomPanel = true;
    this.bottomTab = 'terminal';
    this.aiTab = 'chat';

    this.messages.push({ id: this.nextMsgId++, role: 'user', text: userText });

    // Scaffold often puts index.html at project root — serve it directly even if pom.xml exists.
    if (hasRootIndex && !hasSrcStaticIndex) {
      const staticUrl = 'http://127.0.0.1:5500/';
      const cmd = this.staticServerCommand(5500, this.terminalShell);
      this.finishRunKickoff(
        cmd,
        'Static site at project root: open **http://127.0.0.1:5500/** in your browser.\n' +
          (hasPom ? '(Spring Boot `pom.xml` is present but your HTML is in the root folder — static server shows it.)' : ''),
        true,
        staticUrl
      );
      return;
    }

    if (hasPom) {
      const pomDir = pomPath!.includes('/') ? pomPath!.replace(/\/pom\.xml$/i, '') : '';
      const startSpring = (useWrapper: boolean) => {
        const runCmd = useWrapper ? '.\\mvnw.cmd spring-boot:run' : 'mvn spring-boot:run';
        const cmd = pomDir ? `cd ${pomDir}; ${runCmd}` : runCmd;
        this.finishRunKickoff(
          cmd,
          (useWrapper
            ? 'Spring Boot via Maven Wrapper. First run may download Maven — wait for **Started …Application**, then open **http://localhost:8080**'
            : 'Spring Boot with system Maven. Watch for **Started …Application**, then open **http://localhost:8080**') +
            '\nAsk me "did it work?" and I will read the terminal output.',
          false
        );
      };

      if (hasMvnW) {
        startSpring(true);
        return;
      }

      this.messages.push({
        id: this.nextMsgId++,
        role: 'assistant',
        text: 'Maven is not on PATH. Seeding **Maven Wrapper** (`mvnw.cmd`) into the project, then starting…'
      });
      this.statusMessage = 'Seeding Maven Wrapper…';
      this.cdr.detectChanges();
      this.workspace.ensureMavenWrapper(pomDir).subscribe({
        next: (res) => {
          if (!res.ok) {
            this.messages.push({
              id: this.nextMsgId++,
              role: 'assistant',
              text:
                `Could not add Maven Wrapper: ${res.error || 'unknown'}.\n` +
                `Install Maven or copy mvnw.cmd into the project, then say **run**.`
            });
            this.statusMessage = 'Maven wrapper failed';
            this.cdr.detectChanges();
            return;
          }
          this.refreshTree(pomDir ? [pomDir] : ['']);
          startSpring(true);
        },
        error: (err) => {
          this.messages.push({
            id: this.nextMsgId++,
            role: 'assistant',
            text:
              `Could not seed Maven Wrapper (${err?.error?.error || err?.message || 'error'}).\n` +
              `Install Maven and add it to PATH, then say **run**.`
          });
          this.statusMessage = 'Maven wrapper failed';
          this.cdr.detectChanges();
        }
      });
      return;
    }

    if (hasPkg) {
      this.finishRunKickoff('npm start', 'Node: running npm start — check the terminal for the local URL.', false);
      return;
    }

    this.messages.push({
      id: this.nextMsgId++,
      role: 'assistant',
      text:
        'No runnable project detected (need index.html, pom.xml, or package.json). ' +
        'Create a project first with COMPOSER, Apply all, then say “run”.'
    });
    this.statusMessage = 'Nothing to run yet';
    this.cdr.detectChanges();
  }

  /** Fast static server command — frees port 5500 then starts Python http.server. */
  private staticServerCommand(port: number, shell: TerminalShell): string {
    if (shell === 'cmd') {
      return (
        `for /f "tokens=5" %a in ('netstat -ano ^| findstr :${port} ^| findstr LISTENING') do taskkill /F /PID %a 2>nul & ` +
        `(py -m http.server ${port} || python -m http.server ${port} || python3 -m http.server ${port})`
      );
    }
    return (
      `$p=(Get-NetTCPConnection -LocalPort ${port} -State Listen -ErrorAction SilentlyContinue).OwningProcess|Select-Object -First 1;` +
      `if($p){Stop-Process -Id $p -Force -ErrorAction SilentlyContinue};` +
      `if(Get-Command py -ErrorAction SilentlyContinue){py -m http.server ${port}}` +
      `elseif(Get-Command python -ErrorAction SilentlyContinue){python -m http.server ${port}}` +
      `else{python3 -m http.server ${port}}`
    );
  }

  /** Poll localhost until a static server responds (or timeout). */
  private async waitForLocalServer(url: string, timeoutMs = 45000): Promise<boolean> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      try {
        await fetch(url, { mode: 'no-cors', cache: 'no-store' });
        return true;
      } catch {
        await new Promise((r) => setTimeout(r, 400));
      }
    }
    return false;
  }

  private finishRunKickoff(cmd: string, tip: string, openStatic: boolean, staticUrl?: string) {
    this.messages.push({
      id: this.nextMsgId++,
      role: 'assistant',
      text:
        `Running without regenerating files.\n\n$ ${cmd}\n\n${tip}\n` +
        `(Started in the TERMINAL panel — leave it open.)`
    });
    this.statusMessage = `Running: ${cmd}`;
    this.pushOutput(`[run] ${cmd}`);
    this.cdr.detectChanges();

    const kickOff = async () => {
      this.showBottomPanel = true;
      this.bottomTab = 'terminal';
      this.cdr.detectChanges();
      await new Promise((r) => setTimeout(r, 150));

      let term = this.ideTerminal;
      if (!term) {
        await new Promise((r) => setTimeout(r, 300));
        term = this.ideTerminal;
      }
      if (!term) {
        this.statusMessage = 'Terminal panel unavailable — paste in TERMINAL: ' + cmd;
        this.messages.push({
          id: this.nextMsgId++,
          role: 'assistant',
          text: `Could not reach the terminal panel. Paste this in TERMINAL:\n\n${cmd}`
        });
        this.cdr.detectChanges();
        return;
      }

      const ok = await term.runCommand(cmd);
      if (ok) {
        this.statusMessage = `Running in TERMINAL: ${cmd}`;
        if (openStatic && staticUrl) {
          const up = await this.waitForLocalServer(staticUrl);
          if (up) {
            try {
              window.open(staticUrl, '_blank');
            } catch {
              /* popup blocked */
            }
          } else {
            this.messages.push({
              id: this.nextMsgId++,
              role: 'assistant',
              text:
                `Server did not respond on **${staticUrl}** yet. ` +
                `Check TERMINAL for errors (Python missing?) or open the URL manually when you see **Serving HTTP**.`
            });
            this.cdr.detectChanges();
          }
        }
      } else {
        this.statusMessage = 'Terminal not connected — paste: ' + cmd;
        this.messages.push({
          id: this.nextMsgId++,
          role: 'assistant',
          text:
            `Terminal still not connected after retry. Click TERMINAL → + (reconnect), then paste:\n\n${cmd}`
        });
      }
      this.cdr.detectChanges();
    };
    void kickOff();
  }

  get hasScaffoldMissing(): boolean {
    return this.scaffoldMissingPaths().length > 0;
  }

  private collectWorkspaceFilePaths(nodes: WorkspaceNode[]): string[] {
    const out: string[] = [];
    const walk = (list: WorkspaceNode[]) => {
      for (const n of list || []) {
        if (n.type === 'file' && n.path) {
          out.push(n.path.replace(/\\/g, '/'));
        }
        if (n.children?.length) {
          walk(n.children);
        }
      }
    };
    walk(nodes);
    // also include open tabs (in case tree not refreshed)
    for (const t of this.openTabs) {
      if (t.path) {
        out.push(t.path.replace(/\\/g, '/'));
      }
    }
    return out;
  }

  /** Build: the agent runs tools and queues file proposals for review. */
  private sendBuild(text: string) {
    if (!text || this.chatSending) {
      return;
    }
    this.maybeRenameProjectFromContext(text);
    const isContinue =
      text.startsWith('Continue the scaffold') ||
      text.startsWith('Begin phase') ||
      text.startsWith('[FIX ERROR]');
    if (!isContinue && this.specializedAgentMode === 'scaffold') {
      this.scaffoldAutoRound = 0;
      this.scaffoldStallCount = 0;
      this.scaffoldLastMissingCount = -1;
    }
    // Only a scaffold continuation inherits the previous plan and proposals —
    // a brand-new request starts from a clean slate.
    const keepProposals = isContinue && this.specializedAgentMode === 'scaffold' && this.pendingProposals.length > 0;
    const previousProposals = keepProposals ? [...this.pendingProposals] : [];
    const agentUserMsg: ChatMessage = { id: this.nextMsgId++, role: 'user', text };
    this.messages.push(agentUserMsg);
    this.snapshotBeforeMessage(agentUserMsg);
    this.chatInput = '';
    const assistantId = this.nextMsgId++;
    const scaffoldHint =
      this.specializedAgentMode === 'scaffold' && !isContinue
        ? 'Planning scaffold… Large prompts can take **20–45 minutes** on CPU — tool steps appear live below.'
        : '';
    this.messages.push({
      id: assistantId,
      role: 'assistant',
      text: isContinue ? 'Auto-continuing scaffold…' : scaffoldHint || 'Working…',
      pending: true
    });
    this.chatSending = true;
    if (!keepProposals) {
      this.pendingProposals = [];
    }
    this.pendingInstalls = [];
    this.scaffoldPreview = null;
    this.agentToolSteps = [];
    this.reviewFindings = [];
    this.testRunSummary = '';
    this.expandedDiffPath = null;
    // Keep the plan across turns — a fresh scaffold response overwrites it in
    // mergeProjectPlan, so we never destroy it just because the user typed something.
    this.artifactsVisible = true;
    this.cdr.detectChanges();

    this.ensureRagBeforeSend(() => this.runAgentRequest(text, assistantId, previousProposals), {
      skipEmptyIndex: this.specializedAgentMode === 'scaffold'
    });
  }

  continueScaffold() {
    if (!this.projectPlan?.files?.length || this.chatSending) {
      return;
    }
    const stillMissing = this.scaffoldMissingPaths();
    if (!stillMissing.length) {
      if (this.canContinueNextPhase) {
        this.continueNextPhase();
        return;
      }
      this.statusMessage = 'All planned files are proposed — Apply all';
      this.scaffoldAutoRound = 0;
      return;
    }
    this.sendBuild(
      `Continue the scaffold. write_file the remaining planned files only:\n` +
        stillMissing.map((p) => `- ${p}`).join('\n') +
        `\nThen call done. Do not re-submit the full plan.` +
        this.scaffoldDesignContinuityHint()
    );
  }

  get canContinueNextPhase(): boolean {
    const plan = this.projectPlan;
    if (!plan?.phases?.length) {
      return false;
    }
    const idx = plan.currentPhase ?? 0;
    if (idx + 1 >= plan.phases.length) {
      return false;
    }
    const files = plan.files || [];
    if (!files.length) {
      return false;
    }
    // Only advance after the active phase was Applied to disk
    return files.every((f) => f.status === 'applied');
  }

  continueNextPhase() {
    if (!this.canContinueNextPhase || this.chatSending || !this.projectPlan?.phases) {
      return;
    }
    const next = (this.projectPlan.currentPhase ?? 0) + 1;
    const phase = this.projectPlan.phases[next];
    if (!phase) {
      return;
    }
    const files = (phase.files?.length ? phase.files : []).map((f) => f.path);
    this.projectPlan.currentPhase = next;
    if (phase.files?.length) {
      this.projectPlan.files = phase.files.map((f) => ({ ...f, status: f.status === 'applied' ? 'applied' : 'pending' }));
    }
    this.scaffoldAutoRound = 0;
    this.scaffoldStallCount = 0;
    this.scaffoldLastMissingCount = -1;
    this.sendBuild(
      `Begin phase ${next + 1}: ${phase.name || 'Next'}.\n` +
        `Call submit_plan for THIS phase only (currentPhase: ${next}) with these files, then write_file each:\n` +
        (files.length
          ? files.map((p) => `- ${p}`).join('\n')
          : '- (choose 6–10 focused files for this phase)') +
        `\nDo NOT rewrite earlier phases. Do not dump Markdown.` +
        this.scaffoldDesignContinuityHint()
    );
  }

  /** Remind scaffold to reuse styles.css tokens across phases/pages. */
  private scaffoldDesignContinuityHint(): string {
    const css = (this.pendingProposals || []).find((p) => /(^|\/)styles\.css$/i.test(p.path));
    if (!css?.content) {
      return '\n\nKeep the same visual design system (colors, typography, spacing) as phase 1 — read styles.css first if it exists.';
    }
    const excerpt = css.content.length > 1200 ? css.content.slice(0, 1200) + '\n…' : css.content;
    return (
      '\n\nDESIGN CONTINUITY — reuse this styles.css palette/classes (do NOT invent a new theme):\n```css\n' +
      excerpt +
      '\n```'
    );
  }

  private scaffoldMissingPaths(): string[] {
    if (!this.projectPlan?.files?.length) {
      return [];
    }
    const proposed = new Set((this.pendingProposals || []).map((p) => p.path.replace(/\\/g, '/')));
    return this.projectPlan.files
      .map((f) => (f.path || '').replace(/\\/g, '/'))
      .filter((p) => {
        if (!p || proposed.has(p)) {
          return false;
        }
        const meta = this.projectPlan!.files!.find((f) => (f.path || '').replace(/\\/g, '/') === p);
        return meta?.status !== 'applied' && meta?.status !== 'proposed';
      });
  }

  /** After each scaffold response, keep going until the plan is fully proposed. */
  private maybeAutoContinueScaffold() {
    if (this.specializedAgentMode !== 'scaffold' || this.chatSending) {
      return;
    }
    const missing = this.scaffoldMissingPaths();
    if (!missing.length) {
      this.scaffoldAutoRound = 0;
      this.scaffoldStallCount = 0;
      this.scaffoldLastMissingCount = -1;
      if (this.pendingProposals.length) {
        const phaseName = this.projectPlan?.phases?.[(this.projectPlan.currentPhase ?? 0)]?.name;
        this.statusMessage = phaseName
          ? `Phase "${phaseName}" ready — Apply all` + (this.canContinueNextPhase ? ', then Next phase' : '')
          : `Scaffold complete: ${this.pendingProposals.length} file(s) ready — Apply all`;
      }
      return;
    }
    if (this.scaffoldAutoRound >= this.maxScaffoldAutoRounds) {
      this.statusMessage = `Auto-continue stopped after ${this.maxScaffoldAutoRounds} rounds (${missing.length} still missing). Click Continue missing.`;
      return;
    }
    if (this.scaffoldLastMissingCount === missing.length) {
      this.scaffoldStallCount++;
      if (this.scaffoldStallCount >= 2) {
        this.statusMessage = `Auto-continue stalled (${missing.length} still missing). Click Continue missing.`;
        return;
      }
    } else {
      this.scaffoldStallCount = 0;
    }
    this.scaffoldLastMissingCount = missing.length;
    this.scaffoldAutoRound++;
    this.statusMessage = `Auto-continuing… ${missing.length} file(s) left (round ${this.scaffoldAutoRound}/${this.maxScaffoldAutoRounds})`;
    this.cdr.detectChanges();
    setTimeout(() => {
      if (!this.chatSending && this.specializedAgentMode === 'scaffold') {
        this.continueScaffold();
      }
    }, 400);
  }

  setSpecializedAgentMode(mode: SpecializedAgentMode) {
    this.specializedAgentMode = mode;
    this.assistantMode = 'build';
    this.aiTab = 'chat';
    const meta = this.specializedAgents.find((a) => a.id === mode);
    this.statusMessage = meta ? `${meta.label} Agent — ${meta.hint}` : mode;
  }

  private trimAgentHistory(history: ChatHistoryItem[], maxChars = 16000): ChatHistoryItem[] {
    if (!history.length) {
      return history;
    }
    let total = 0;
    const kept: ChatHistoryItem[] = [];
    for (let i = history.length - 1; i >= 0; i--) {
      const item = history[i];
      const len = (item.content || '').length;
      if (total + len > maxChars && kept.length > 0) {
        break;
      }
      kept.unshift(item);
      total += len;
    }
    return kept;
  }

  private runAgentRequest(text: string, assistantId: number, previousProposals: FileProposal[] = []) {
    const token = this.auth.getValidToken() ?? this.auth.getToken();
    if (!token) {
      const msg = this.messages.find((m) => m.id === assistantId);
      if (msg) {
        msg.text = 'Not logged in — open Login, sign in, then try again. Your workspace files are safe.';
        msg.pending = false;
        msg.error = true;
      }
      this.chatSending = false;
      this.statusMessage = 'Please log in';
      this.cdr.detectChanges();
      return;
    }

    const mentionPaths = this.extractMentionPaths(text);
    const extras = this.buildAiExtras();
    const history: ChatHistoryItem[] = this.trimAgentHistory(
      this.messages
        .filter((m) => m.id !== assistantId && !m.error)
        .slice(0, -1)
        .map((m) => ({ role: m.role, content: m.text }))
    );

    const body = {
      message: text,
      history,
      activePath: extras.activePath,
      mentionPaths,
      selection: extras.selection,
      openPaths: extras.openPaths,
      useRag: this.shouldUseRag(),
      scaffoldPreview: this.scaffoldPreviewEnabled
    };

    this.agentRequestSub?.unsubscribe();
    this.agentRequestSub = null;
    this.cancelAgentStream?.();
    this.cancelAgentStream = null;

    if (this.specializedAgentMode === 'scaffold') {
      this.cancelAgentStream = this.aiApi.agentStream('scaffold', body, {
        onStatus: (message) => {
          this.statusMessage = message;
          const pending = this.messages.find((m) => m.id === assistantId);
          if (pending?.pending) {
            pending.text = message;
          }
          this.cdr.detectChanges();
        },
        onSteps: (steps) => {
          this.agentToolSteps = steps;
          this.artifactsVisible = true;
          this.cdr.detectChanges();
        },
        onPlan: (plan) => {
          if (plan?.files?.length || plan?.phases?.length) {
            this.mergeProjectPlan(plan);
            this.cdr.detectChanges();
          }
        },
        onFiles: (files) => {
          if (files?.length) {
            this.applyAgentProposals(files, previousProposals);
            this.expandedDiffPath = this.pendingProposals[0]?.path ?? this.expandedDiffPath;
            this.cdr.detectChanges();
          }
        },
        onInstalls: (installs) => {
          this.pendingInstalls = installs || [];
          this.cdr.detectChanges();
        },
        onDone: (res) => {
          this.cancelAgentStream = null;
          this.finishAgentRequest(res, text, assistantId, previousProposals);
        },
        onError: (message) => {
          this.cancelAgentStream = null;
          this.failAgentRequest({ error: { error: message }, message, status: 0 }, assistantId);
        }
      });
      return;
    }

    this.agentRequestSub = this.aiApi.specializedAgent(this.specializedAgentMode, body).subscribe({
      next: (res) => {
        this.agentRequestSub = null;
        this.finishAgentRequest(res, text, assistantId, previousProposals);
      },
      error: (err) => {
        this.agentRequestSub = null;
        this.failAgentRequest(err, assistantId);
      }
    });
  }

  private applyAgentProposals(incoming: FileProposal[], previousProposals: FileProposal[]) {
    if (previousProposals.length && this.specializedAgentMode === 'scaffold') {
      const byPath = new Map<string, FileProposal>();
      for (const p of previousProposals) {
        byPath.set(p.path.replace(/\\/g, '/'), p);
      }
      for (const p of incoming) {
        byPath.set(p.path.replace(/\\/g, '/'), p);
      }
      this.pendingProposals = [...byPath.values()];
    } else {
      this.pendingProposals = incoming;
    }
  }

  private finishAgentRequest(
    res: AgentResponse,
    text: string,
    assistantId: number,
    previousProposals: FileProposal[]
  ) {
    const msg = this.messages.find((m) => m.id === assistantId);
    if (msg) {
      msg.text = res.reply || '(no explanation)';
      msg.pending = false;
    }
    this.applyAgentProposals(res.files || [], previousProposals);
    this.agentToolSteps = res.steps || [];
    this.pendingInstalls = res.installProposals || [];
    this.scaffoldPreview = res.scaffoldPreview || null;
    this.reviewFindings = res.findings || [];
    this.testRunSummary = res.testRunSummary || '';
    if (res.projectPlan?.files?.length || res.projectPlan?.phases?.length) {
      this.mergeProjectPlan(res.projectPlan);
    }
    this.syncPlanStatusesFromProposals();
    this.chatSending = false;
    if (this.pendingProposals.length) {
      this.expandedDiffPath = this.pendingProposals[0].path;
      this.statusMessage = `${this.pendingProposals.length} file change(s) ready — review diffs & Apply`;
      if (this.aiPanelWidth < 480) {
        this.aiPanelWidth = this.clampAiWidth(520);
      }
    } else if (this.reviewFindings.length) {
      this.statusMessage = `Review: ${this.reviewFindings.length} finding(s)`;
    } else if (this.projectPlan?.files?.length) {
      this.statusMessage = `Plan: ${this.projectPlan.title || 'project'} (${this.projectPlan.files.length} files)`;
    } else if (res.ollamaCallCount) {
      this.statusMessage = `Agent finished (${res.ollamaCallCount} model call(s))`;
    }
    const assistantText = msg?.text || res.reply || '';
    if (assistantText) {
      this.persistTurn('agent', text, assistantText);
    }
    this.cdr.detectChanges();
    if (this.specializedAgentMode === 'scaffold') {
      this.maybeAutoContinueScaffold();
    }
  }

  private failAgentRequest(err: { error?: { error?: string }; message?: string; status?: number }, assistantId: number) {
    const msg = this.messages.find((m) => m.id === assistantId);
    let errorText = err?.error?.error || err?.message || 'Agent failed';
    if (err?.status === 0) {
      errorText =
        'Connection lost before the server finished (HTTP 0). Scaffold with long prompts can take **20–45+ minutes**. ' +
        'Ensure the backend (port 8081) and Ollama are running, restart `ng serve` so the proxy timeout refresh applies, then retry. ' +
        'Tip: split huge specs into phases (“build homepage first”, then “add pricing page”).';
    } else if (err?.status === 401) {
      errorText =
        'Unauthorized (401). Click Sign in again (top/menu), then retry. Do not worry — project files stay on disk.';
    } else if (err?.status === 502 || err?.status === 504) {
      errorText =
        (err?.error?.error || errorText) +
        ' — Ollama may have timed out or stopped. Check `ollama serve` and try a shorter prompt.';
    }
    if (msg) {
      msg.text = String(errorText);
      msg.pending = false;
      msg.error = true;
    }
    this.chatSending = false;
    this.scaffoldAutoRound = 0;
    this.statusMessage = String(errorText);
    this.pushOutput(`[agent] ${errorText}`);
    this.cdr.detectChanges();
  }

  /** Stop an in-progress chat stream (shown while the assistant is thinking). */
  /** Stop whatever the single thread is doing — a stream in Ask, or a tool run in Build. */
  stopAssistant() {
    if (this.assistantMode === 'build') {
      this.stopAgent();
    } else {
      this.stopChat();
    }
  }

  stopChat() {
    this.cancelChatStream?.();
    this.cancelChatStream = null;
    const pending = this.messages.find((m) => m.pending);
    if (pending) {
      pending.pending = false;
      if (!pending.text.trim()) {
        pending.text = 'Stopped.';
      }
    }
    this.chatSending = false;
    this.statusMessage = 'Stopped';
    this.cdr.detectChanges();
  }

  /** Stop an in-progress agent run (shown while the agent is working). */
  stopAgent() {
    this.agentRequestSub?.unsubscribe();
    this.agentRequestSub = null;
    this.cancelAgentStream?.();
    this.cancelAgentStream = null;
    const pending = this.messages.find((m) => m.pending);
    if (pending) {
      pending.pending = false;
      if (!pending.text.trim() || pending.text === 'Working…' || pending.text === 'Auto-continuing scaffold…') {
        pending.text = 'Stopped.';
      }
    }
    this.chatSending = false;
    this.scaffoldAutoRound = 0;
    this.statusMessage = 'Stopped';
    this.cdr.detectChanges();
  }

  discardProposals() {
    this.pendingProposals = [];
    this.pendingInstalls = [];
    this.scaffoldPreview = null;
    this.agentToolSteps = [];
    this.expandedDiffPath = null;
    this.statusMessage = 'Discarded proposed files';
  }

  private syncPlanStatusesFromProposals() {
    if (!this.projectPlan?.files?.length) {
      return;
    }
    const proposed = new Set((this.pendingProposals || []).map((p) => p.path.replace(/\\/g, '/')));
    for (const f of this.projectPlan.files) {
      const path = (f.path || '').replace(/\\/g, '/');
      if (f.status === 'applied') {
        continue;
      }
      f.status = proposed.has(path) ? 'proposed' : f.status === 'proposed' ? 'pending' : f.status || 'pending';
    }
    const idx = this.projectPlan.currentPhase ?? 0;
    const phase = this.projectPlan.phases?.[idx];
    if (phase?.files) {
      for (const f of phase.files) {
        const path = (f.path || '').replace(/\\/g, '/');
        if (f.status === 'applied') {
          continue;
        }
        f.status = proposed.has(path) ? 'proposed' : f.status || 'pending';
      }
      const allProposed = phase.files.every(
        (f) => f.status === 'proposed' || f.status === 'applied' || proposed.has((f.path || '').replace(/\\/g, '/'))
      );
      if (allProposed) {
        phase.status = 'proposed';
      } else {
        phase.status = 'active';
      }
    }
  }

  private mergeProjectPlan(incoming: ProjectPlan) {
    if (!incoming) {
      return;
    }
    if (incoming.phases?.length) {
      // Prefer server multi-phase plan; keep applied statuses from local when paths match
      const prev = this.projectPlan;
      this.projectPlan = {
        ...incoming,
        currentPhase: incoming.currentPhase ?? prev?.currentPhase ?? 0
      };
      if (prev?.phases?.length) {
        for (const ph of this.projectPlan.phases || []) {
          const old = prev.phases.find((p) => p.name === ph.name || p.id === ph.id);
          if (!old?.files) {
            continue;
          }
          for (const f of ph.files || []) {
            const match = old.files.find((x) => (x.path || '').replace(/\\/g, '/') === (f.path || '').replace(/\\/g, '/'));
            if (match?.status === 'applied') {
              f.status = 'applied';
            }
          }
        }
      }
      const cur = this.projectPlan.phases?.[this.projectPlan.currentPhase ?? 0];
      if (cur?.files?.length) {
        this.projectPlan.files = cur.files;
      }
      this.maybeRenameFromPlan();
      return;
    }
    if (incoming.files?.length) {
      if (this.projectPlan?.phases?.length) {
        const idx = this.projectPlan.currentPhase ?? 0;
        const phase = this.projectPlan.phases[idx];
        if (phase) {
          phase.files = incoming.files;
          phase.status = 'active';
        }
        this.projectPlan.files = incoming.files;
        this.projectPlan.title = incoming.title || this.projectPlan.title;
        this.projectPlan.stack = incoming.stack || this.projectPlan.stack;
        this.projectPlan.summary = incoming.summary || this.projectPlan.summary;
      } else {
        this.projectPlan = incoming;
      }
      this.maybeRenameFromPlan();
    }
  }

  applyOneProposal(file: FileProposal) {
    this.applyProposals([file]);
  }

  applyAllProposals() {
    if (!this.pendingProposals.length) {
      return;
    }
    this.applyProposals([...this.pendingProposals]);
  }

  installAllProposals() {
    if (!this.pendingInstalls.length || this.installApplying) {
      return;
    }
    this.installApplying = true;
    this.statusMessage = `Installing ${this.pendingInstalls.length} package command(s)…`;
    this.aiApi.install(this.pendingInstalls).subscribe({
      next: (res) => {
        this.installApplying = false;
        this.pendingInstalls = [];
        this.statusMessage = res.ok
          ? `Install complete (${res.executed.length} command(s))`
          : `Install errors: ${(res.errors || []).join('; ')}`;
        if (res.output) {
          this.pushOutput(`[install]\n${res.output}`);
        }
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.installApplying = false;
        this.statusMessage = err?.error?.error || err?.message || 'Install failed';
        this.cdr.detectChanges();
      }
    });
  }

  toggleScaffoldPreview(enabled: boolean) {
    this.scaffoldPreviewEnabled = enabled;
    localStorage.setItem('pass-ai-scaffold-preview', enabled ? '1' : '0');
  }

  /**
   * Re-read applied files that are already open. Without this the tab keeps the pre-Apply
   * text, so the change looks like it never happened — and a later autosave of that stale
   * buffer would overwrite what was just applied.
   */
  private reloadAppliedTabs(appliedPaths: string[]) {
    for (const path of appliedPaths) {
      const tab = this.openTabs.find((t) => t.path === path && !t.unsavedNew);
      if (!tab) {
        continue;
      }
      this.workspace.readFile(path).subscribe({
        next: (file) => {
          tab.content = file.content;
          tab.dirty = false;
          this.cdr.detectChanges();
        },
        error: () => undefined
      });
    }
  }

  /** Drag the splitter above the proposals panel to grow/shrink it. */
  startProposalResize(event: MouseEvent) {
    const panel = this.proposalPanelEl?.nativeElement;
    if (!panel) {
      return;
    }
    event.preventDefault();
    this.proposalResizeStartY = event.clientY;
    this.proposalResizeStartHeight = panel.getBoundingClientRect().height;
    document.addEventListener('mousemove', this.onProposalResizeMove);
    document.addEventListener('mouseup', this.onProposalResizeEnd);
    // Inline, because component styles are encapsulated and cannot target <body>.
    document.body.style.userSelect = 'none';
    document.body.style.cursor = 'ns-resize';
  }

  /** Double-click the splitter to go back to the automatic height. */
  resetProposalHeight() {
    this.proposalPanelHeight = null;
  }

  private readonly onProposalResizeMove = (event: MouseEvent) => {
    const panel = this.proposalPanelEl?.nativeElement;
    if (!panel) {
      return;
    }
    // Dragging up must make the panel taller, hence start - current.
    const delta = this.proposalResizeStartY - event.clientY;
    const pane = panel.parentElement;
    const paneHeight = pane ? pane.getBoundingClientRect().height : 0;
    const max = paneHeight > 0 ? paneHeight * 0.85 : this.proposalResizeStartHeight + delta;
    const next = Math.min(max, Math.max(120, this.proposalResizeStartHeight + delta));
    this.proposalPanelHeight = Math.round(next);
    this.cdr.detectChanges();
  };

  private readonly onProposalResizeEnd = () => {
    this.stopProposalResize();
  };

  private stopProposalResize() {
    document.removeEventListener('mousemove', this.onProposalResizeMove);
    document.removeEventListener('mouseup', this.onProposalResizeEnd);
    document.body.style.userSelect = '';
    document.body.style.cursor = '';
  }

  /** Flip the tool-step log from "queued" to "applied" so the panel matches what's on disk. */
  private markToolStepsApplied(appliedPaths: string[]) {
    if (!this.agentToolSteps.length || !appliedPaths.length) {
      return;
    }
    const done = new Set(appliedPaths.map((p) => p.replace(/\\/g, '/')));
    for (const step of this.agentToolSteps) {
      if (step.name !== 'write_file' && step.name !== 'edit_file') {
        continue;
      }
      if (step.status !== 'queued') {
        continue;
      }
      const path = (step.resultPreview || '').replace(/\\/g, '/');
      if (done.has(path)) {
        step.status = 'applied';
      }
    }
  }

  private applyProposals(files: FileProposal[]) {
    if (!files.length || this.agentApplying) {
      return;
    }
    void this.flushAutosave().then(() => {
    this.agentApplying = true;
    this.statusMessage = `Applying ${files.length} file(s)…`;
    this.aiApi.applyFiles(files).subscribe({
      next: (res) => {
        this.agentApplying = false;
        const applied = res.applied || [];
        const errors = res.errors || [];
        this.pendingProposals = this.pendingProposals.filter((p) => !applied.includes(p.path));
        const expand = applied.map((p) => {
          const i = p.lastIndexOf('/');
          return i > 0 ? p.slice(0, i) : '';
        });
        this.refreshTree([...new Set(expand)]);
        if (applied.length) {
          const first = applied[0];
          const name = first.split('/').pop() || first;
          this.reloadAppliedTabs(applied);
          this.openRemoteFile(first, name);
          this.statusMessage = `Applied ${applied.length} file(s)`;
          this.pushOutput(`[agent] applied: ${applied.join(', ')}`);
          this.markToolStepsApplied(applied);
          this.messages.push({
            id: this.nextMsgId++,
            role: 'assistant',
            text: `Applied ${applied.length} file(s):\n${applied.map((p) => '• ' + p).join('\n')}`
          });
          this.maybeRenameFromPlan();
          if (this.projectPlan?.files?.length) {
            const appliedSet = new Set(applied.map((p) => p.replace(/\\/g, '/')));
            for (const f of this.projectPlan.files) {
              if (appliedSet.has((f.path || '').replace(/\\/g, '/'))) {
                f.status = 'applied';
              }
            }
            const idx = this.projectPlan.currentPhase ?? 0;
            const phase = this.projectPlan.phases?.[idx];
            if (phase?.files) {
              for (const f of phase.files) {
                if (appliedSet.has((f.path || '').replace(/\\/g, '/'))) {
                  f.status = 'applied';
                }
              }
              const allApplied = phase.files.every((f) => f.status === 'applied');
              if (allApplied) {
                phase.status = 'applied';
                if (this.canContinueNextPhase) {
                  this.statusMessage = `Phase "${phase.name || idx + 1}" applied — click Next phase`;
                }
              }
            }
          }
        }
        if (errors.length) {
          this.statusMessage = `Apply errors: ${errors.join('; ')}`;
          this.pushOutput(`[agent] apply errors: ${errors.join('; ')}`);
        }
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.agentApplying = false;
        const msg = err?.error?.error || err?.message || 'Apply failed';
        this.statusMessage = String(msg);
        this.pushOutput(`[agent] ${msg}`);
        this.cdr.detectChanges();
      }
    });
    });
  }

  private maybeRenameFromPlan() {
    const title = this.projectPlan?.title?.trim();
    const projectId = this.activeProject?.projectId;
    if (!title || !projectId) {
      return;
    }
    const current = this.activeProject?.name || '';
    if (
      current &&
      !/^untitled/i.test(current) &&
      current !== 'Workspace' &&
      this.contextNamedProjectId !== projectId
    ) {
      return;
    }
    this.workspace.renameProject(projectId, title).subscribe({
      next: (updated) => {
        this.activeProject = updated;
        this.contextNamedProjectId = '';
        this.refreshRecentProjects();
        // The folder on disk was renamed too, so the tree label needs a reload.
        this.refreshTree(['']);
      },
      error: () => undefined
    });
  }

  private maybeRenameProjectFromContext(prompt: string) {
    const projectId = this.activeProject?.projectId;
    const current = this.activeProject?.name || '';
    if (!projectId || (current && !/^untitled/i.test(current) && current !== 'Workspace')) {
      return;
    }

    const title = this.projectNameFromPrompt(prompt);
    if (!title) {
      return;
    }

    this.contextNamedProjectId = projectId;
    this.workspace.renameProject(projectId, title).subscribe({
      next: (updated) => {
        this.activeProject = updated;
        this.refreshRecentProjects();
        this.refreshTree(['']);
        this.statusMessage = `Project named “${updated.name}”`;
      },
      error: () => {
        this.contextNamedProjectId = '';
      }
    });
  }

  private projectNameFromPrompt(prompt: string): string {
    let value = prompt
      .replace(/\r?\n/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();

    const purpose = value.match(/\b(?:for|pour)\s+(.+?)(?=\s+(?:with|using|avec|en utilisant|that|qui)\b|[.!?]|$)/i);
    if (purpose?.[1]) {
      value = purpose[1];
    } else {
      value = value
        .replace(/^(?:please\s+|s'il vous plaît\s+)?(?:i want you to\s+|je veux que tu\s+|can you\s+|peux-tu\s+)?/i, '')
        .replace(/^(?:create|build|make|develop|generate|crée|créer|fais|développe)\s+/i, '')
        .replace(/^(?:me\s+)?(?:a|an|the|un|une|le|la)\s+/i, '')
        .replace(/^(?:new\s+|nouveau\s+|nouvelle\s+)?(?:project|app|application|website|site|projet)\s*/i, '');
    }

    value = value
      .replace(/\b(?:project|projet)\b/gi, '')
      .replace(/\s+/g, ' ')
      .trim()
      .split(' ')
      .slice(0, 6)
      .join(' ');

    if (value.length < 3) {
      return '';
    }
    return value
      .slice(0, 60)
      .replace(/(^|\s)\p{L}/gu, (letter) => letter.toUpperCase());
  }

  previewProposal(content: string): string {
    if (!content) {
      return '(empty)';
    }
    return content.length > 400 ? content.slice(0, 400) + '\n…' : content;
  }

  toggleDiff(path: string) {
    this.expandedDiffPath = this.expandedDiffPath === path ? null : path;
  }

  /** Simple line-oriented unified diff for Diff Apply review. */
  proposalDiff(file: FileProposal): string {
    return this.proposalDiffLines(file)
      .map((l) => l.text)
      .join('\n');
  }

  proposalDiffLines(file: FileProposal): { kind: 'add' | 'del' | 'ctx'; text: string }[] {
    const before = (file.previousContent ?? '').replace(/\r\n/g, '\n');
    const after = (file.content ?? '').replace(/\r\n/g, '\n');
    const lines: { kind: 'add' | 'del' | 'ctx'; text: string }[] = [];
    if (!before) {
      for (const l of after.split('\n')) {
        lines.push({ kind: 'add', text: '+' + l });
      }
      return lines.length > 2500 ? lines.slice(0, 2500).concat([{ kind: 'ctx', text: '…' }]) : lines;
    }
    const a = before.split('\n');
    const b = after.split('\n');
    const max = Math.max(a.length, b.length);
    for (let i = 0; i < max; i++) {
      const left = a[i];
      const right = b[i];
      if (left === right) {
        if (left !== undefined) {
          lines.push({ kind: 'ctx', text: ' ' + left });
        }
      } else {
        if (left !== undefined) {
          lines.push({ kind: 'del', text: '-' + left });
        }
        if (right !== undefined) {
          lines.push({ kind: 'add', text: '+' + right });
        }
      }
    }
    return lines.length > 2500 ? lines.slice(0, 2500).concat([{ kind: 'ctx', text: '…' }]) : lines;
  }

  /** Clear the agent-side artifacts (proposals, plan, steps) without touching the thread. */
  resetAgentArtifacts() {
    this.pendingProposals = [];
    this.agentToolSteps = [];
    this.reviewFindings = [];
    this.testRunSummary = '';
    this.projectPlan = null;
    this.expandedDiffPath = null;
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


