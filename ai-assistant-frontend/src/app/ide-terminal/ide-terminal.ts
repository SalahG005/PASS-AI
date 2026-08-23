import {
  AfterViewInit,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  NgZone,
  OnChanges,
  OnDestroy,
  Output,
  SimpleChanges,
  ViewChild
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { TerminalService, TerminalShell } from '../terminal.service';

/**
 * VS Code-like terminal panel.
 * Output streams via xterm; input is line-based (reliable on Windows ProcessBuilder).
 * This avoids double-echo and broken commands like `cd ..`.
 */
@Component({
  selector: 'app-ide-terminal',
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="term-wrap">
      <div #host class="xterm-host"></div>
      <div class="term-line-input">
        <span class="prompt">{{ shell === 'powershell' ? 'PS>' : '>' }}</span>
        <input
          #cmdInput
          type="text"
          [(ngModel)]="line"
          (keydown.enter)="submitLine($event)"
          (keydown.control.c)="interrupt(); $event.preventDefault()"
          spellcheck="false"
          autocomplete="off"
          [placeholder]="shell === 'powershell' ? 'Get-ChildItem, cd .., dir…' : 'dir, cd .., echo…'"
        />
      </div>
    </div>
  `,
  styles: [
    `
      :host {
        display: block;
        height: 100%;
        min-height: 0;
        background: var(--ide-term-bg, #12141c);
      }
      .term-wrap {
        display: grid;
        grid-template-rows: minmax(0, 1fr) 32px;
        height: 100%;
        min-height: 0;
        background: var(--ide-term-bg, #12141c);
      }
      .xterm-host {
        min-height: 0;
        overflow: hidden;
        padding: 6px 10px 2px;
        box-sizing: border-box;
        background: var(--ide-term-bg, #12141c);
      }
      .xterm-host ::ng-deep .xterm,
      .xterm-host ::ng-deep .xterm-viewport,
      .xterm-host ::ng-deep .xterm-screen {
        background: transparent !important;
      }
      .term-line-input {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 0 10px;
        border-top: 1px solid var(--ide-border, #252836);
        background: var(--ide-surface-2, #10131a);
      }
      .prompt {
        color: var(--ide-blue, #3aa0ff);
        font-family: Cascadia Mono, Consolas, monospace;
        font-size: 13px;
        flex-shrink: 0;
      }
      .term-line-input input {
        flex: 1;
        border: 0;
        outline: 0;
        background: transparent;
        color: var(--ide-text, #e8ecf3);
        font-family: Cascadia Mono, Consolas, monospace;
        font-size: 13px;
      }
      .term-line-input input::placeholder {
        color: var(--ide-muted, #8b93a7);
        opacity: 0.7;
      }
    `
  ]
})
export class IdeTerminalComponent implements AfterViewInit, OnChanges, OnDestroy {
  @ViewChild('host', { static: true }) host!: ElementRef<HTMLDivElement>;
  @ViewChild('cmdInput') cmdInput?: ElementRef<HTMLInputElement>;

  @Input() shell: TerminalShell = 'powershell';
  @Output() statusChange = new EventEmitter<'connecting' | 'open' | 'closed' | 'error'>();

  line = '';

  private term: Terminal | null = null;
  private fitAddon: FitAddon | null = null;
  private resizeObserver: ResizeObserver | null = null;
  private ready = false;
  private sending = false;
  /** Plain-text copy of the session so the assistant can explain what happened. */
  private transcript = '';
  private lastCmd = '';

  constructor(
    private terminal: TerminalService,
    private ngZone: NgZone
  ) {}

  ngAfterViewInit() {
    const bg = this.readCssVar('--ide-term-bg', '#12141c');
    const fg = this.readCssVar('--ide-text', '#e8ecf3');
    const cursor = this.readCssVar('--ide-blue', '#3aa0ff');

    this.ngZone.runOutsideAngular(() => {
      this.term = new Terminal({
        convertEol: true,
        disableStdin: true,
        cursorBlink: false,
        fontFamily: 'Cascadia Mono, Consolas, "Courier New", monospace',
        fontSize: 13,
        lineHeight: 1.25,
        theme: {
          background: bg,
          foreground: fg,
          cursor: cursor,
          cursorAccent: bg,
          selectionBackground: 'rgba(58, 160, 255, 0.35)',
          black: '#0c0e14',
          red: '#ff6e7f',
          green: '#7ddc9b',
          yellow: '#f0b429',
          blue: '#3aa0ff',
          magenta: '#c13fd6',
          cyan: '#11a8cd',
          white: '#e8ecf3',
          brightBlack: '#8b93a7',
          brightWhite: '#ffffff'
        },
        scrollback: 5000
      });
      this.fitAddon = new FitAddon();
      this.term.loadAddon(this.fitAddon);
      this.term.open(this.host.nativeElement);
      this.fitAddon.fit();
      this.ready = true;

      this.resizeObserver = new ResizeObserver(() => {
        try {
          this.fitAddon?.fit();
        } catch {
          /* ignore */
        }
      });
      this.resizeObserver.observe(this.host.nativeElement);
    });

    this.connect();
    setTimeout(() => this.focus(), 100);
  }

  ngOnChanges(changes: SimpleChanges) {
    if (changes['shell'] && !changes['shell'].firstChange && this.ready) {
      this.connect();
    }
  }

  ngOnDestroy() {
    this.resizeObserver?.disconnect();
    this.terminal.disconnect();
    this.term?.dispose();
    this.term = null;
  }

  connect() {
    this.statusChange.emit('connecting');
    this.term?.reset();
    this.line = '';
    this.transcript = '';
    this.ngZone.runOutsideAngular(() => {
      this.terminal.connect(
        (chunk) => {
          this.term?.write(chunk);
          this.appendTranscript(chunk);
        },
        (status) => {
          this.ngZone.run(() => this.statusChange.emit(status));
          if (status === 'open') {
            setTimeout(() => {
              this.fitAddon?.fit();
              this.focus();
            }, 0);
          }
        },
        this.shell
      );
    });
  }

  submitLine(event?: Event) {
    event?.preventDefault();
    const cmd = this.line;
    if (this.sending) {
      return;
    }
    const trimmed = cmd.trim().toLowerCase();
    if (trimmed === 'clear' || trimmed === 'cls') {
      this.term?.clear();
      this.line = '';
      return;
    }

    this.line = '';
    this.runCommand(cmd);
  }

  /** Run a command from outside (e.g. COMPOSER "run") — connects if needed. */
  runCommand(cmd: string): Promise<boolean> {
    const text = (cmd || '').trim();
    if (!text) {
      return Promise.resolve(false);
    }
    if (text.length) {
      this.term?.write(`${text}\r\n`);
      this.appendTranscript(`$ ${text}\n`);
      this.lastCmd = text;
    }
    this.sending = true;

    return new Promise((resolve) => {
      const finish = (ok: boolean) => {
        this.sending = false;
        this.focus();
        resolve(ok);
      };

      const trySend = (n: number) => {
        if (this.terminal.isConnected) {
          this.terminal.sendLine(text);
          setTimeout(() => finish(true), 80);
          return;
        }
        if (n === 0 && !this.terminal.isConnected && !this.terminal.isConnecting) {
          this.connect();
        }
        if (n < 80) {
          setTimeout(() => trySend(n + 1), 100);
        } else {
          finish(false);
        }
      };
      trySend(0);
    });
  }

  clear() {
    this.term?.clear();
    this.transcript = '';
  }

  /** Tail of the session, ANSI stripped — used to answer "did it work?". */
  recentOutput(maxChars = 8000): string {
    const text = this.transcript;
    return text.length > maxChars ? text.slice(-maxChars) : text;
  }

  get lastCommand(): string {
    return this.lastCmd;
  }

  private appendTranscript(chunk: string) {
    if (!chunk) {
      return;
    }
    // eslint-disable-next-line no-control-regex
    const plain = chunk.replace(/\u001b\[[0-9;?]*[ -/]*[@-~]/g, '').replace(/\r/g, '');
    this.transcript = (this.transcript + plain).slice(-60000);
  }

  focus() {
    this.cmdInput?.nativeElement?.focus();
  }

  interrupt() {
    this.terminal.interrupt();
  }

  killAndReconnect() {
    this.terminal.disconnect();
    this.connect();
  }

  private readCssVar(name: string, fallback: string): string {
    const ide = this.host?.nativeElement?.closest('.ide') as HTMLElement | null;
    if (ide) {
      const v = getComputedStyle(ide).getPropertyValue(name).trim();
      if (v) {
        return v;
      }
    }
    const fromDoc = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    return fromDoc || fallback;
  }
}
