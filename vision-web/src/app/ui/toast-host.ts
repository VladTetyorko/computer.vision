import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ToastService, type ToastAction } from '../core/toast.service';

@Component({
  selector: 'vision-toast-host',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toast-area" aria-live="polite" aria-atomic="false">
      @for (toast of toasts.toasts(); track toast.id) {
        <div class="toast" [class]="toast.kind">
          <span>{{ toast.text }}</span>
          @if (toast.action; as action) {
            <button type="button" class="action" (click)="runAction(toast.id, action)">
              {{ action.label }}
            </button>
          }
          <button type="button" aria-label="Dismiss" (click)="toasts.dismiss(toast.id)">×</button>
        </div>
      }
    </div>
  `,
  styles: `
    .toast-area {
      position: fixed;
      bottom: 1rem;
      right: 1rem;
      z-index: 100;
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      max-width: min(420px, calc(100vw - 2rem));
    }

    .toast {
      display: flex;
      align-items: flex-start;
      gap: 0.6rem;
      padding: 0.6rem 0.75rem;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border);
      background: var(--panel-raised);
      box-shadow: var(--shadow);
      font-size: 0.85rem;
      animation: slide-in 0.18s ease-out;
    }

    .toast.error {
      background: var(--danger-soft);
      border-color: #6b2530;
      color: #ffb3bd;
    }

    .toast.ok {
      background: var(--ok-soft);
      border-color: #1e5c3a;
      color: #a8f0c6;
    }

    button {
      background: none;
      border: none;
      color: inherit;
      opacity: 0.6;
      cursor: pointer;
      font-size: 1.1rem;
      line-height: 1;
      padding: 0;
      margin-left: auto;
    }

    button:hover {
      opacity: 1;
    }

    button.action {
      opacity: 1;
      font-size: 0.78rem;
      font-weight: 600;
      text-decoration: underline;
      white-space: nowrap;
    }

    @keyframes slide-in {
      from {
        opacity: 0;
        transform: translateY(6px);
      }
    }
  `,
})
export class ToastHost {
  protected readonly toasts = inject(ToastService);

  protected runAction(toastId: number, action: ToastAction): void {
    this.toasts.dismiss(toastId);
    action.onClick();
  }
}
