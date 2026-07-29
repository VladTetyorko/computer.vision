import { ChangeDetectionStrategy, Component, HostListener, computed, inject } from '@angular/core';
import { UndoToastService, type UndoToastState } from './undo-toast.service';

/**
 * Renders `UndoToastService`'s single active toast (docs/OPS-CORE-PLAN.md §Q2) — mounted once at
 * the app root (`app.html`, next to `<vision-toast-host>`), bottom-**left** (see the service's own
 * doc comment for why that corner). A shrinking progress bar (pure CSS animation, timed off the
 * toast's own `timeoutMs` — no interval/rAF ticking needed) gives the countdown a visual, not just
 * the message text; `Escape` (keyboard-dismissable, same as the close button) commits rather than
 * undoes — dismissing is "I'm done deciding", never itself a second click of Undo.
 *
 * `@for (… track toast.id)`, not `@if`, over a computed single-element array: mirrors
 * `shared/ui/toast-host.ts`'s own precedent — tracking by id is what makes the DOM node (and so the
 * CSS progress animation) actually restart when `showUndo()` replaces one toast with another,
 * rather than Angular reusing the existing element and leaving a stale, already-part-way-shrunk bar.
 */
@Component({
  selector: 'vision-undo-toast',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @for (toast of toastList(); track toast.id) {
      <div class="undo-toast-area">
        <div class="undo-toast" role="status" aria-live="polite">
          <span class="message">{{ toast.message }}</span>
          <button type="button" class="action" (click)="toasts.undo()">Undo</button>
          <button type="button" class="close" aria-label="Dismiss" (click)="toasts.dismiss()">×</button>
          <div class="progress" [style.animationDuration.ms]="toast.timeoutMs"></div>
        </div>
      </div>
    }
  `,
  styles: `
    .undo-toast-area {
      position: fixed;
      bottom: 1rem;
      left: 1rem;
      z-index: 100;
      max-width: min(420px, calc(100vw - 2rem));
    }

    .undo-toast {
      position: relative;
      display: flex;
      align-items: center;
      gap: 0.6rem;
      padding: 0.6rem 0.75rem;
      border-radius: var(--radius-sm);
      border: 1px solid #1e5c3a;
      background: var(--ok-soft);
      color: #a8f0c6;
      box-shadow: var(--shadow);
      font-size: 0.85rem;
      overflow: hidden;
      animation: slide-in 0.18s ease-out;
    }

    .message {
      flex: 1;
    }

    button {
      background: none;
      border: none;
      color: inherit;
      opacity: 0.7;
      cursor: pointer;
      font-size: 1.1rem;
      line-height: 1;
      padding: 0;
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

    .progress {
      position: absolute;
      left: 0;
      bottom: 0;
      height: 2px;
      width: 100%;
      background: var(--ok);
      transform-origin: left;
      animation-name: shrink;
      animation-timing-function: linear;
      animation-fill-mode: forwards;
    }

    @keyframes shrink {
      from {
        transform: scaleX(1);
      }
      to {
        transform: scaleX(0);
      }
    }

    @keyframes slide-in {
      from {
        opacity: 0;
        transform: translateY(6px);
      }
    }
  `,
})
export class UndoToast {
  protected readonly toasts = inject(UndoToastService);

  /** `@for`'s own single-element array — see class doc for why this isn't a plain `@if`. */
  protected readonly toastList = computed<readonly UndoToastState[]>(() => {
    const active = this.toasts.toast();
    return active ? [active] : [];
  });

  /** Keyboard-dismissable: `Escape` commits, exactly like the `×` button — never undoes. */
  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    this.toasts.dismiss();
  }
}
