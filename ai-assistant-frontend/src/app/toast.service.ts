import { Injectable, signal } from '@angular/core';

export type ToastType = 'error' | 'success';

export interface ToastEntry {
  id: number;
  type: ToastType;
  message: string;
}

@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly toasts = signal<ToastEntry[]>([]);
  private nextId = 1;
  private timers = new Map<number, ReturnType<typeof setTimeout>>();

  show(message: string, type: ToastType = 'success', durationMs = 4500) {
    const text = message.trim();
    if (!text) {
      return;
    }

    const toast: ToastEntry = {
      id: this.nextId++,
      type,
      message: text
    };

    this.toasts.update((items) => [...items, toast]);

    const timer = setTimeout(() => this.dismiss(toast.id), durationMs);
    this.timers.set(toast.id, timer);
  }

  success(message: string, durationMs = 4500) {
    this.show(message, 'success', durationMs);
  }

  error(message: string, durationMs = 4500) {
    this.show(message, 'error', durationMs);
  }

  dismiss(id: number) {
    const timer = this.timers.get(id);
    if (timer) {
      clearTimeout(timer);
      this.timers.delete(id);
    }

    this.toasts.update((items) => items.filter((item) => item.id !== id));
  }
}