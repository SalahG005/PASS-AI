import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Auth } from './auth';
import { WorkspaceSession } from './workspace-session';

export interface ChatHistoryItem {
  role: 'user' | 'assistant' | 'system';
  content: string;
}

export interface ChatRequest {
  message: string;
  history?: ChatHistoryItem[];
  activePath?: string;
  mentionPaths?: string[];
  useRag?: boolean;
  /** Request post-scaffold HTML layout check (default true in UI). */
  scaffoldPreview?: boolean;
  selection?: string;
  openPaths?: string[];
  ragLanguage?: string;
  ragSymbolKind?: string;
  ragSymbolContains?: string;
}

export interface ChatResponse {
  reply: string;
  model: string;
  usedPaths: string[];
  ragPaths: string[];
}

export interface AiHealth {
  ok: boolean;
  ollamaReachable: boolean;
  chatModelPresent: boolean;
  embedModelPresent: boolean;
  baseUrl: string;
  chatModel: string;
  embedModel: string;
  message: string;
  models: string[];
}

export interface IndexResult {
  ok: boolean;
  filesIndexed: number;
  chunksStored: number;
  message: string;
}

export interface IndexStatus {
  chunks: number;
  indexed: boolean;
  embedModel: string;
}

export interface FileProposal {
  path: string;
  action: 'create' | 'overwrite' | string;
  content: string;
  previousContent?: string | null;
}

export interface AgentToolStep {
  name: string;
  argsSummary?: string;
  status?: string;
  resultPreview?: string;
}

export interface ReviewFinding {
  path?: string;
  line?: number;
  severity?: string;
  message?: string;
}

export interface ProjectPlanFile {
  path: string;
  purpose?: string;
  status?: string;
}

export interface ProjectPlanPhase {
  id?: string;
  name?: string;
  status?: string;
  files?: ProjectPlanFile[];
}

export interface ProjectPlan {
  title?: string;
  stack?: string;
  summary?: string;
  files?: ProjectPlanFile[];
  phases?: ProjectPlanPhase[];
  currentPhase?: number;
}

export interface InstallProposal {
  command: string;
  reason?: string;
  status?: string;
}

export interface InstallResult {
  ok: boolean;
  executed: string[];
  errors: string[];
  output: string;
}

export interface ScaffoldPreview {
  htmlPath?: string;
  screenshotBase64?: string;
  warnings?: string[];
  screenshotAvailable?: boolean;
}

export interface AgentResponse {
  reply: string;
  model: string;
  agentMode?: string;
  usedPaths: string[];
  ragPaths: string[];
  files: FileProposal[];
  steps?: AgentToolStep[];
  findings?: ReviewFinding[];
  testRunSummary?: string;
  projectPlan?: ProjectPlan;
  ollamaCallCount?: number;
  installProposals?: InstallProposal[];
  scaffoldPreview?: ScaffoldPreview;
}

export type SpecializedAgentMode = 'code' | 'review' | 'test' | 'docs' | 'research' | 'scaffold';

export interface AgentStreamCallbacks {
  onStatus?: (message: string) => void;
  onSteps?: (steps: AgentToolStep[]) => void;
  onPlan?: (plan: ProjectPlan) => void;
  onFiles?: (files: FileProposal[]) => void;
  onInstalls?: (installs: InstallProposal[]) => void;
  onDone: (result: AgentResponse) => void;
  onError: (message: string) => void;
}

export interface ApplyResult {
  ok: boolean;
  applied: string[];
  errors: string[];
}

export interface ConversationSummary {
  id: number;
  title: string;
  mode: string;
  workspaceId: string;
  createdAt: string;
  updatedAt: string;
  messageCount: number;
}

export interface ConversationMessage {
  id?: number;
  role: 'user' | 'assistant' | 'system' | string;
  content: string;
  sortOrder?: number;
  createdAt?: string;
}

export interface ConversationDetail {
  id: number;
  title: string;
  mode: string;
  workspaceId: string;
  createdAt: string;
  updatedAt: string;
  messages: ConversationMessage[];
}

@Injectable({ providedIn: 'root' })
export class AiApi {
  private baseUrl = '/api/ai';

  constructor(
    private http: HttpClient,
    private auth: Auth,
    private workspaceSession: WorkspaceSession
  ) {}

  health(): Observable<AiHealth> {
    return this.http.get<AiHealth>(`${this.baseUrl}/health`);
  }

  chat(body: ChatRequest): Observable<ChatResponse> {
    return this.http.post<ChatResponse>(`${this.baseUrl}/chat`, body);
  }

  agent(body: ChatRequest): Observable<AgentResponse> {
    return this.http.post<AgentResponse>(`${this.baseUrl}/agent`, body);
  }

  /** Specialized agents — POST /api/ai/agents/{mode} */
  specializedAgent(mode: SpecializedAgentMode, body: ChatRequest): Observable<AgentResponse> {
    return this.http.post<AgentResponse>(`${this.baseUrl}/agents/${mode}`, body);
  }

  /**
   * Streams scaffold/agent progress via SSE from POST /api/ai/agents/{mode}/stream.
   * Returns an abort function (same pattern as chatStream).
   */
  agentStream(
    mode: SpecializedAgentMode,
    body: ChatRequest,
    callbacks: AgentStreamCallbacks
  ): () => void {
    const token = this.auth.getValidToken();
    if (!token) {
      callbacks.onError('Not authenticated');
      return () => undefined;
    }

    const controller = new AbortController();
    const url = `${this.baseUrl}/agents/${mode}/stream`;

    fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        Authorization: `Bearer ${token}`,
        'X-Workspace-Id': this.workspaceSession.id
      },
      body: JSON.stringify(body),
      signal: controller.signal
    })
      .then(async (res) => {
        if (!res.ok || !res.body) {
          let msg = `Agent failed (${res.status})`;
          try {
            const err = await res.json();
            if (err?.error) {
              msg = String(err.error);
            }
          } catch {
            /* ignore */
          }
          callbacks.onError(msg);
          return;
        }

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let eventName = 'message';

        while (true) {
          const { done, value } = await reader.read();
          if (done) {
            break;
          }
          buffer += decoder.decode(value, { stream: true });
          const parts = buffer.split('\n');
          buffer = parts.pop() ?? '';

          for (const rawLine of parts) {
            const line = rawLine.replace(/\r$/, '');
            if (!line) {
              eventName = 'message';
              continue;
            }
            if (line.startsWith('event:')) {
              eventName = line.slice(6).trim();
              continue;
            }
            if (!line.startsWith('data:')) {
              continue;
            }
            const data = line.slice(5).trim();
            if (eventName === 'status') {
              try {
                const parsed = JSON.parse(data) as { message?: string };
                if (parsed?.message) {
                  callbacks.onStatus?.(parsed.message);
                }
              } catch {
                callbacks.onStatus?.(data);
              }
            } else if (eventName === 'steps') {
              try {
                callbacks.onSteps?.(JSON.parse(data) as AgentToolStep[]);
              } catch {
                /* ignore malformed */
              }
            } else if (eventName === 'plan') {
              try {
                callbacks.onPlan?.(JSON.parse(data) as ProjectPlan);
              } catch {
                /* ignore malformed */
              }
            } else if (eventName === 'files') {
              try {
                callbacks.onFiles?.(JSON.parse(data) as FileProposal[]);
              } catch {
                /* ignore malformed */
              }
            } else if (eventName === 'installs') {
              try {
                callbacks.onInstalls?.(JSON.parse(data) as InstallProposal[]);
              } catch {
                /* ignore malformed */
              }
            } else if (eventName === 'done') {
              try {
                callbacks.onDone(JSON.parse(data) as AgentResponse);
              } catch {
                callbacks.onError('Invalid agent response');
              }
            } else if (eventName === 'error') {
              try {
                const parsed = JSON.parse(data);
                callbacks.onError(parsed.error || data);
              } catch {
                callbacks.onError(data);
              }
            }
          }
        }
      })
      .catch((err) => {
        if (err?.name === 'AbortError') {
          return;
        }
        callbacks.onError(err?.message || 'Network error talking to agent');
      });

    return () => controller.abort();
  }

  applyFiles(files: FileProposal[]): Observable<ApplyResult> {
    return this.http.post<ApplyResult>(`${this.baseUrl}/apply`, { files });
  }

  install(commands: InstallProposal[]): Observable<InstallResult> {
    return this.http.post<InstallResult>(`${this.baseUrl}/install`, { commands });
  }

  listConversations(workspaceId?: string): Observable<ConversationSummary[]> {
    const params = workspaceId ? { workspaceId } : undefined;
    return this.http.get<ConversationSummary[]>(`${this.baseUrl}/conversations`, { params });
  }

  getConversation(id: number): Observable<ConversationDetail> {
    return this.http.get<ConversationDetail>(`${this.baseUrl}/conversations/${id}`);
  }

  createConversation(body: { title?: string; mode?: string; workspaceId?: string } = {}): Observable<ConversationDetail> {
    return this.http.post<ConversationDetail>(`${this.baseUrl}/conversations`, body);
  }

  renameConversation(id: number, title: string): Observable<ConversationDetail> {
    return this.http.patch<ConversationDetail>(`${this.baseUrl}/conversations/${id}`, { title });
  }

  appendConversationMessages(
    id: number,
    messages: ConversationMessage[],
    title?: string
  ): Observable<ConversationDetail> {
    return this.http.post<ConversationDetail>(`${this.baseUrl}/conversations/${id}/messages`, {
      messages,
      title
    });
  }

  /** Create (if needed) + append user/assistant in one request. */
  saveConversationTurn(body: {
    conversationId?: number | null;
    mode?: string;
    workspaceId?: string;
    userMessage: string;
    assistantMessage: string;
    title?: string;
  }): Observable<ConversationDetail> {
    return this.http.post<ConversationDetail>(`${this.baseUrl}/conversations/turns`, body);
  }

  deleteConversation(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/conversations/${id}`);
  }

  index(): Observable<IndexResult> {
    return this.http.post<IndexResult>(`${this.baseUrl}/index`, {});
  }

  indexStatus(): Observable<IndexStatus> {
    return this.http.get<IndexStatus>(`${this.baseUrl}/index/status`);
  }

  /**
   * Streams tokens via SSE from POST /api/ai/chat/stream.
   * Calls onToken for each chunk, onDone with the final payload, onError on failure.
   */
  chatStream(
    body: ChatRequest,
    onToken: (token: string) => void,
    onDone: (result: ChatResponse) => void,
    onError: (message: string) => void
  ): () => void {
    const token = this.auth.getValidToken();
    if (!token) {
      onError('Not authenticated');
      return () => undefined;
    }

    const controller = new AbortController();
    const url = `${this.baseUrl}/chat/stream`;

    fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        Authorization: `Bearer ${token}`,
        'X-Workspace-Id': this.workspaceSession.id
      },
      body: JSON.stringify(body),
      signal: controller.signal
    })
      .then(async (res) => {
        if (!res.ok || !res.body) {
          let msg = `Chat failed (${res.status})`;
          try {
            const err = await res.json();
            if (err?.error) {
              msg = String(err.error);
            }
          } catch {
            /* ignore */
          }
          onError(msg);
          return;
        }

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let eventName = 'message';

        while (true) {
          const { done, value } = await reader.read();
          if (done) {
            break;
          }
          buffer += decoder.decode(value, { stream: true });
          const parts = buffer.split('\n');
          buffer = parts.pop() ?? '';

          for (const rawLine of parts) {
            const line = rawLine.replace(/\r$/, '');
            if (!line) {
              eventName = 'message';
              continue;
            }
            if (line.startsWith('event:')) {
              eventName = line.slice(6).trim();
              continue;
            }
            if (!line.startsWith('data:')) {
              continue;
            }
            const data = line.slice(5).trim();
            if (eventName === 'token') {
              // Spring may JSON-encode the string
              try {
                onToken(JSON.parse(data));
              } catch {
                onToken(data);
              }
            } else if (eventName === 'done') {
              try {
                onDone(JSON.parse(data) as ChatResponse);
              } catch {
                onDone({ reply: data, model: '', usedPaths: [], ragPaths: [] });
              }
            } else if (eventName === 'error') {
              try {
                const parsed = JSON.parse(data);
                onError(parsed.error || data);
              } catch {
                onError(data);
              }
            }
          }
        }
      })
      .catch((err) => {
        if (err?.name === 'AbortError') {
          return;
        }
        onError(err?.message || 'Network error talking to AI');
      });

    return () => controller.abort();
  }
}
