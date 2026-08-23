import { Injectable } from '@angular/core';
import { Auth } from './auth';
import { WorkspaceSession } from './workspace-session';

export type TerminalShell = 'cmd' | 'powershell';

@Injectable({ providedIn: 'root' })
export class TerminalService {
  private socket: WebSocket | null = null;
  private onDataCb: ((chunk: string) => void) | null = null;
  private onStatusCb: ((status: 'open' | 'closed' | 'error') => void) | null = null;
  private currentShell: TerminalShell = 'cmd';

  constructor(private auth: Auth, private workspaceSession: WorkspaceSession) {}

  get shell(): TerminalShell {
    return this.currentShell;
  }

  connect(
    onData: (chunk: string) => void,
    onStatus?: (status: 'open' | 'closed' | 'error') => void,
    shell: TerminalShell = 'cmd'
  ) {
    this.disconnect();
    this.currentShell = shell;
    this.onDataCb = onData;
    this.onStatusCb = onStatus ?? null;

    const token = this.auth.getValidToken();
    if (!token) {
      this.onStatusCb?.('error');
      return;
    }

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsId = encodeURIComponent(this.workspaceSession.id);
    const shellParam = encodeURIComponent(shell);
    const url =
      `${protocol}//${window.location.host}/ws/terminal` +
      `?token=${encodeURIComponent(token)}&ws=${wsId}&shell=${shellParam}`;
    this.socket = new WebSocket(url);

    this.socket.onopen = () => this.onStatusCb?.('open');
    this.socket.onclose = () => this.onStatusCb?.('closed');
    this.socket.onerror = () => this.onStatusCb?.('error');
    this.socket.onmessage = (event) => {
      this.onDataCb?.(typeof event.data === 'string' ? event.data : String(event.data));
    };
  }

  send(text: string) {
    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
      this.socket.send(text);
    }
  }

  sendLine(line: string) {
    // Windows shells expect CRLF
    this.send(line + '\r\n');
  }

  interrupt() {
    this.send('\u0003');
  }

  disconnect() {
    if (this.socket) {
      this.socket.close();
      this.socket = null;
    }
  }

  get isConnected(): boolean {
    return !!this.socket && this.socket.readyState === WebSocket.OPEN;
  }

  get isConnecting(): boolean {
    return !!this.socket && this.socket.readyState === WebSocket.CONNECTING;
  }
}
