import { Injectable, signal } from '@angular/core';

export type ToastKind = 'ok' | 'error' | 'info' | 'notification';

/** An optional follow-up the user can take straight from the toast (docs/CYCLES-PLAN.md §4's "Watch"). */
export interface ToastAction {
  readonly label: string;
  readonly onClick: () => void;
}

export interface Toast {
  readonly id: number;
  readonly kind: ToastKind;
  readonly text: string;
  readonly action?: ToastAction;
}

/**
 * Errors linger long enough to read; confirmations get out of the way. `notification` (docs/UX-REWORK-PLAN.md
 * §U-c: "new events arrive as transient toasts") sits between `ok`/`info` — a background event the
 * operator didn't ask for, worth slightly longer than a confirmation they *did* trigger, but this
 * app has no reason to make it linger as long as an `error`.
 */
const DISMISS_AFTER_MS: Record<ToastKind, number> = {
  ok: 4_000,
  info: 5_000,
  error: 9_000,
  notification: 6_000,
};

@Injectable({ providedIn: 'root' })
export class ToastService {
  private readonly items = signal<readonly Toast[]>([]);
  private sequence = 0;

  readonly toasts = this.items.asReadonly();

  /** `action`, when given, renders as a button that runs `onClick` and dismisses the toast. */
  ok(text: string, action?: ToastAction): void {
    this.push('ok', text, action);
  }

  info(text: string): void {
    this.push('info', text);
  }

  error(text: string): void {
    this.push('error', text);
  }

  /**
   * A background event surfaced without the user asking for it — today, only the notification
   * bell's own newly-arrived detection events (`shared/ui/notification-bell.ts`). Takes an
   * `action` (mirrors `ok`'s own — docs/CYCLES-PLAN.md §4's "Watch" precedent) since the whole
   * point of one is a one-click way to look at whatever just happened, per the verb dictionary
   * ("Watch live"/"Details"). Visually its own kind (`shared/ui/toast-host.ts`'s `.toast.notification`)
   * rather than reusing `info`'s bare styling — distinct enough to read as "something happened
   * elsewhere", not a confirmation of something the operator just clicked.
   */
  notify(text: string, action?: ToastAction): void {
    this.push('notification', text, action);
  }

  dismiss(id: number): void {
    this.items.update((current) => current.filter((toast) => toast.id !== id));
  }

  private push(kind: ToastKind, text: string, action?: ToastAction): void {
    const id = ++this.sequence;
    this.items.update((current) => [...current, { id, kind, text, action }]);
    setTimeout(() => this.dismiss(id), DISMISS_AFTER_MS[kind]);
  }
}
