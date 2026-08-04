import { Injectable, signal } from '@angular/core';

export type ToastKind = 'ok' | 'error' | 'info' | 'notification' | 'warning';

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
 * One dwell time for every kind (user decision, 2026-08-04): a toast leaves after 5 seconds, whatever
 * it says. This replaces the earlier severity-staggered ladder (ok 4s → info 5s → notification 6s →
 * warning 7s → error 9s), where each kind lingered in proportion to how much the operator was
 * expected to need to read it. Kind still drives *appearance* (`shared/ui/toast-host.ts`'s per-kind
 * colors) and whether an `action` button is offered — just no longer duration. Anything an operator
 * genuinely must not miss should therefore not rely on a toast outliving its siblings: it belongs in
 * durable chrome (the notification bell's own dropdown, an inline error on the control that failed),
 * not in a slightly slower fade.
 */
const DISMISS_AFTER_MS: Record<ToastKind, number> = {
  ok: 5_000,
  info: 5_000,
  notification: 5_000,
  warning: 5_000,
  error: 5_000,
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

  /**
   * A command genuinely sent but not confirmed (docs/DRONE-INFRA-PLAN.md I-e Stage 1 — the
   * "Bring home" button's `NO_ACK` outcome). No `action` — unlike `ok`/`notify`, there is nothing
   * useful for the operator to click from the toast itself, only the fact worth reading.
   */
  warn(text: string): void {
    this.push('warning', text);
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
