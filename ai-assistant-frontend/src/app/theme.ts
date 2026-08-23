import { Injectable, signal } from '@angular/core';

export type AppTheme = 'dark' | 'light';

const STORAGE_KEY = 'pass-ai-theme';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly theme = signal<AppTheme>('dark');

  constructor() {
    const saved = localStorage.getItem(STORAGE_KEY);
    const initial: AppTheme = saved === 'light' || saved === 'dark' ? saved : 'dark';
    this.apply(initial);
  }

  get isLight(): boolean {
    return this.theme() === 'light';
  }

  toggle(): void {
    this.apply(this.theme() === 'dark' ? 'light' : 'dark');
  }

  set(theme: AppTheme): void {
    this.apply(theme);
  }

  private apply(theme: AppTheme): void {
    this.theme.set(theme);
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem(STORAGE_KEY, theme);
  }
}
