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
    this.ngZone.runOutsideAngular(() => {
      this.terminal.connect(
        (chunk) => {
          this.term?.write(chunk);
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

    // Echo once in the output pane (input is separate from xterm)
    if (cmd.length) {
      this.term?.write(`${cmd}\r\n`);
    }

    this.line = '';
    this.sending = true;

    if (!this.terminal.isConnected) {
      this.connect();
      const trySend = (n: number) => {
        if (this.terminal.isConnected) {
          this.terminal.sendLine(cmd);
          this.sending = false;
          return;
        }
        if (n < 40) {
          setTimeout(() => trySend(n + 1), 50);
        } else {
          this.sending = false;
        }
      };
      trySend(0);
      return;
    }

    this.terminal.sendLine(cmd);
    setTimeout(() => {
      this.sending = false;
      this.focus();
    }, 80);
  }

  clear() {
    this.term?.clear();
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
