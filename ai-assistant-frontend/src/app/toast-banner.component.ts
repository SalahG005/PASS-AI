import { Component } from '@angular/core';
import { ToastService } from './toast.service';

@Component({
  selector: 'app-toast-banner',
  standalone: true,
  template: `
    <div class="toast-stack" aria-live="polite" aria-atomic="true">
      @for (toast of toastService.toasts(); track toast.id) {
        <div class="toast" [class.toast-error]="toast.type === 'error'" [class.toast-success]="toast.type === 'success'">
          <div class="toast-accent" aria-hidden="true"></div>
          <div class="toast-body">
            <p class="toast-title">{{ toast.type === 'error' ? 'Error' : 'Success' }}</p>
            <p class="toast-message">{{ toast.message }}</p>
          </div>
          <button type="button" class="toast-close" (click)="toastService.dismiss(toast.id)" aria-label="Dismiss notification">
            ×
          </button>
        </div>
      }
    </div>
  `,
  styles: [
    `
      :host {
        position: fixed;
        left: 0;
        right: 0;
        top: 0;
        z-index: 200;
        pointer-events: none;
      }

      .toast-stack {
        display: grid;
        gap: 10px;
        justify-items: center;
        padding: 16px;
      }

      .toast {
        pointer-events: auto;
        width: min(560px, calc(100vw - 32px));
        display: grid;
        grid-template-columns: 6px 1fr auto;
        align-items: stretch;
        gap: 14px;
        padding: 14px 14px 14px 0;
        border-radius: 14px;
        background: rgba(16, 19, 28, 0.96);
        border: 1px solid var(--border-subtle);
        box-shadow: 0 18px 50px rgba(0, 0, 0, 0.35);
        backdrop-filter: blur(16px);
      }

      html[data-theme='light'] .toast {
        background: rgba(255, 255, 255, 0.96);
      }

      .toast-accent {
        border-radius: 999px;
        margin-left: 8px;
      }

      .toast-error .toast-accent {
        background: linear-gradient(180deg, #ff8a5c 0%, #ff5f7a 100%);
      }

      .toast-success .toast-accent {
        background: var(--accent-gradient);
      }

      .toast-body {
        min-width: 0;
      }

      .toast-title {
        margin: 0 0 4px;
        font-size: 12px;
        font-weight: 700;
        letter-spacing: 0.12em;
        text-transform: uppercase;
        color: var(--text-secondary);
      }

      .toast-message {
        margin: 0;
        color: var(--text-primary);
        line-height: 1.45;
        word-break: break-word;
      }

      .toast-close {
        border: none;
        background: transparent;
        color: var(--text-secondary);
        font-size: 22px;
        line-height: 1;
        padding: 0 6px 0 0;
        cursor: pointer;
      }

      .toast-close:hover {
        color: var(--text-primary);
      }
    `
  ]
})
export class ToastBannerComponent {
  constructor(public toastService: ToastService) {}
}