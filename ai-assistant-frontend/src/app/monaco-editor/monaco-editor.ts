import {
  AfterViewInit,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnChanges,
  OnDestroy,
  Output,
  SimpleChanges,
  ViewChild,
  effect,
  inject
} from '@angular/core';
import { languageFromPath } from '../file-icons';
import { ThemeService } from '../theme';

declare global {
  interface Window {
    require?: {
      config: (cfg: Record<string, unknown>) => void;
      (modules: string[], callback: (monaco: MonacoNamespace) => void): void;
    };
    monaco?: MonacoNamespace;
  }
}

interface MonacoNamespace {
  editor: {
    create: (el: HTMLElement, options: Record<string, unknown>) => MonacoEditor;
    setModelLanguage: (model: MonacoModel, languageId: string) => void;
    defineTheme: (name: string, theme: Record<string, unknown>) => void;
    setTheme: (name: string) => void;
  };
  KeyMod: { CtrlCmd: number };
  KeyCode: { KeyS: number };
}

interface MonacoModel {
  setValue: (value: string) => void;
  getValue: () => string;
  uri?: { path?: string };
}

interface MonacoEditor {
  getValue: () => string;
  setValue: (value: string) => void;
  getModel: () => MonacoModel | null;
  updateOptions: (options: Record<string, unknown>) => void;
  layout: () => void;
  focus: () => void;
  dispose: () => void;
  onDidChangeModelContent: (listener: () => void) => { dispose: () => void };
  onDidChangeCursorPosition: (listener: (e: { position: { lineNumber: number; column: number } }) => void) => { dispose: () => void };
  addCommand: (keybinding: number, handler: () => void) => string | null;
}

@Component({
  selector: 'app-monaco-editor',
  standalone: true,
  template: `<div #host class="monaco-host"></div>`,
  styles: [
    `
      :host {
        display: block;
        width: 100%;
        height: 100%;
      }
      .monaco-host {
        width: 100%;
        height: 100%;
        min-height: 200px;
      }
    `
  ]
})
export class MonacoEditorComponent implements AfterViewInit, OnChanges, OnDestroy {
  @ViewChild('host', { static: true }) host!: ElementRef<HTMLDivElement>;

  @Input() value = '';
  @Input() path = 'untitled.txt';
  @Input() readOnly = false;

  @Output() valueChange = new EventEmitter<string>();
  @Output() saveRequest = new EventEmitter<void>();
  @Output() cursorChange = new EventEmitter<{ line: number; column: number }>();

  private editor: MonacoEditor | null = null;
  private monaco: MonacoNamespace | null = null;
  private suppressChange = false;
  private resizeObserver: ResizeObserver | null = null;
  private contentDisposable: { dispose: () => void } | null = null;
  private cursorDisposable: { dispose: () => void } | null = null;
  private readonly themeService = inject(ThemeService);

  constructor() {
    effect(() => {
      const mode = this.themeService.theme();
      if (this.monaco) {
        this.monaco.editor.setTheme(mode === 'light' ? 'pass-light' : 'pass-dark');
      }
    });
  }

  ngAfterViewInit() {
    void this.initMonaco();
  }

  ngOnChanges(changes: SimpleChanges) {
    if (!this.editor || !this.monaco) {
      return;
    }

    if (changes['path'] && !changes['path'].firstChange) {
      const model = this.editor.getModel();
      if (model) {
        this.monaco.editor.setModelLanguage(model, languageFromPath(this.path));
      }
    }

    if (changes['value'] && !changes['value'].firstChange) {
      const current = this.editor.getValue();
      if (current !== this.value) {
        this.suppressChange = true;
        this.editor.setValue(this.value ?? '');
        this.suppressChange = false;
      }
    }

    if (changes['readOnly']) {
      this.editor.updateOptions({ readOnly: this.readOnly });
    }
  }

  ngOnDestroy() {
    this.contentDisposable?.dispose();
    this.cursorDisposable?.dispose();
    this.resizeObserver?.disconnect();
    this.editor?.dispose();
    this.editor = null;
  }

  focus() {
    this.editor?.focus();
  }

  private async initMonaco() {
    this.monaco = await this.loadMonaco();
    this.monaco.editor.defineTheme('pass-dark', {
      base: 'vs-dark',
      inherit: true,
      rules: [
        { token: 'comment', foreground: '6A9955' },
        { token: 'string', foreground: 'CE9178' },
        { token: 'keyword', foreground: 'C586C0' },
        { token: 'number', foreground: 'B5CEA8' }
      ],
      colors: {
        'editor.background': '#0d0f16',
        'editor.foreground': '#dce3f2',
        'editorLineNumber.foreground': '#5b6478',
        'editorLineNumber.activeForeground': '#c5cddf',
        'editorCursor.foreground': '#3aa0ff',
        'editor.selectionBackground': '#264f78',
        'editor.inactiveSelectionBackground': '#3a3d41',
        'editorIndentGuide.background': '#2a2f3d',
        'editorIndentGuide.activeBackground': '#4a5166'
      }
    });
    this.monaco.editor.defineTheme('pass-light', {
      base: 'vs',
      inherit: true,
      rules: [],
      colors: {
        'editor.background': '#ffffff',
        'editor.foreground': '#1f2937',
        'editorLineNumber.foreground': '#9ca3af',
        'editorLineNumber.activeForeground': '#374151',
        'editorCursor.foreground': '#2563eb',
        'editor.selectionBackground': '#bfdbfe',
        'editor.inactiveSelectionBackground': '#e5e7eb',
        'editorIndentGuide.background': '#e5e7eb',
        'editorIndentGuide.activeBackground': '#cbd5e1'
      }
    });
    const themeName = this.themeService.isLight ? 'pass-light' : 'pass-dark';
    this.monaco.editor.setTheme(themeName);

    this.editor = this.monaco.editor.create(this.host.nativeElement, {
      value: this.value ?? '',
      language: languageFromPath(this.path),
      theme: themeName,
      automaticLayout: true,
      fontSize: 13,
      fontFamily: "'Cascadia Code', 'JetBrains Mono', 'Fira Code', Consolas, monospace",
      minimap: { enabled: true, scale: 1 },
      scrollBeyondLastLine: false,
      renderLineHighlight: 'line',
      tabSize: 2,
      insertSpaces: true,
      wordWrap: 'off',
      smoothScrolling: true,
      cursorBlinking: 'smooth',
      bracketPairColorization: { enabled: true },
      padding: { top: 8 },
      readOnly: this.readOnly
    });

    this.contentDisposable = this.editor.onDidChangeModelContent(() => {
      if (this.suppressChange || !this.editor) {
        return;
      }
      this.valueChange.emit(this.editor.getValue());
    });

    this.cursorDisposable = this.editor.onDidChangeCursorPosition((e) => {
      this.cursorChange.emit({ line: e.position.lineNumber, column: e.position.column });
    });

    this.editor.addCommand(this.monaco.KeyMod.CtrlCmd | this.monaco.KeyCode.KeyS, () => {
      this.saveRequest.emit();
    });

    this.resizeObserver = new ResizeObserver(() => this.editor?.layout());
    this.resizeObserver.observe(this.host.nativeElement);
    this.editor.focus();
  }

  private loadMonaco(): Promise<MonacoNamespace> {
    if (window.monaco) {
      return Promise.resolve(window.monaco);
    }

    return new Promise((resolve, reject) => {
      const existing = document.querySelector('script[data-monaco-loader]') as HTMLScriptElement | null;
      const onReady = () => {
        if (!window.require) {
          reject(new Error('Monaco loader missing'));
          return;
        }
        window.require.config({ paths: { vs: '/assets/monaco/vs' } });
        (window as unknown as { MonacoEnvironment?: { getWorkerUrl: (moduleId: string, label: string) => string } }).MonacoEnvironment = {
          getWorkerUrl: () => {
            const workerPath = '/assets/monaco/vs/base/worker/workerMain.js';
            const blob = new Blob(
              [`self.MonacoEnvironment={baseUrl:'/assets/monaco/'};importScripts('${workerPath}');`],
              { type: 'text/javascript' }
            );
            return URL.createObjectURL(blob);
          }
        };
        window.require(['vs/editor/editor.main'], () => {
          if (!window.monaco) {
            reject(new Error('Monaco failed to load'));
            return;
          }
          resolve(window.monaco);
        });
      };

      if (existing) {
        if (typeof window.require === 'function') {
          onReady();
        } else {
          existing.addEventListener('load', onReady);
        }
        return;
      }

      const script = document.createElement('script');
      script.dataset['monacoLoader'] = 'true';
      script.src = '/assets/monaco/vs/loader.js';
      script.async = true;
      script.onload = () => onReady();
      script.onerror = () => reject(new Error('Failed to load Monaco loader'));
      document.body.appendChild(script);
    });
  }
}
