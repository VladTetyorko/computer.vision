import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ToastService, type ToastAction } from '../../core/toast.service';

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
      bottom: var(--space-16);
      right: var(--space-16);
      z-index: 100;
      display: flex;
      flex-direction: column;
      gap: var(--space-8);
      max-width: min(420px, calc(100vw - 2rem));
    }

    .toast {
      display: flex;
      align-items: flex-start;
      gap: var(--space-8);
      padding: var(--space-8) var(--space-16);
      border-radius: var(--radius-sm);
      border: 1px solid var(--border);
      background: var(--panel-raised);
      box-shadow: var(--shadow);
      font-size: 0.85rem;
      animation: slide-in 0.18s ease-out;
    }

    .toast.error {
      background: var(--color-danger-soft);
      border-color: var(--color-danger-line);
      color: var(--color-danger-text);
    }

    /* No --color-success-line token exists in the frozen contract (success only has base/-soft/-text,
       unlike warn/danger's base/-soft/-line/-text) — flagged in the STYLE-TOKENS-PLAN migration report
       rather than inventing one here. The color property maps to the existing --color-success-text slot
       (accepted drift-consolidation, same as the table's other near-duplicate-hex rows); border-color
       stays a literal pending that token being added. */
    .toast.ok {
      background: var(--color-success-soft);
      border-color: var(--color-success-line);
      color: var(--color-success-text);
    }

    /* docs/plans/done/UX-REWORK-PLAN.md §U-c's own header-bell notification toasts (ToastService.notify) —
       the app's one accent, not a new hue (docs/plans/done/UX-REWORK-PLAN.md §U-b item 2's "one saturated
       accent" rule): this is neither a confirmation (.ok) nor a failure (.error), just "look at
       this", so it borrows the same accent every primary action/selection already uses. */
    .toast.notification {
      background: var(--color-info-soft);
      border-color: var(--color-info);
      color: var(--color-info-text);
    }

    /* docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1's "Bring home" NO_ACK toast — sent, not acknowledged: a
       genuine amber (the app's existing --warn hue, same as .chip.warn), never .error's red (the
       command did go out) and never .ok's green (nothing was actually confirmed). */
    .toast.warning {
      background: var(--color-warn-soft);
      border-color: var(--color-warn-line);
      color: var(--color-warn-text);
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
