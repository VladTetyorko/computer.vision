import { Injectable, signal } from '@angular/core';

/** docs/plans/done/OPS-CORE-PLAN.md §Q2's own pinned default — long enough to notice, short enough to not pile up. */
export const DEFAULT_UNDO_TIMEOUT_MS = 10_000;

export interface UndoToastOptions {
  /** How long `Undo` stays clickable before the toast auto-commits. Defaults to 10s. */
  readonly timeoutMs?: number;
  /**
   * Runs once this toast's window closes without `Undo` — on natural timeout, an explicit dismiss
   * (`×` / Escape), or because a newer `showUndo()` replaced it before its own timer elapsed. Most
   * callers have nothing to do here: the mutation this toast is offering to reverse already
   * happened *before* `showUndo` was ever called (docs/plans/done/UX-REWORK-PLAN.md §U-a2's "undo over
   * confirm" — act first, offer a way back), so "commit" is usually a no-op. This exists for the
   * rare caller that wants to know the window is truly, finally closed, and for this service's own
   * replace-semantics to have something observable to test.
   */
  readonly onCommit?: () => void;
}

/** What `shared/ui/undo-toast.ts` renders — never more than one at a time (see class doc). */
export interface UndoToastState {
  readonly id: number;
  readonly message: string;
  readonly timeoutMs: number;
}

/**
 * The one-at-a-time "Undo" toast (docs/plans/done/OPS-CORE-PLAN.md §Q2) — a *separate* primitive from
 * `core/toast.service.ts`'s `ToastService`, not a mode of it: `ToastService` already supports an
 * optional action button (`ok(text, {label:'Undo', onClick})`, the precedent `features/devices/devices.ts`/
 * `features/asset-detail/asset-detail.ts`'s archive flows used before this landed), but its toasts
 * stack (several can be visible at once), auto-dismiss on each kind's own fixed schedule (4–9s),
 * and carry no progress indication — none of which fits "the operator gets exactly one open
 * question at a time: undo this, or don't" that an undo-over-confirm poka-yoke needs. Rendered by
 * `shared/ui/undo-toast.ts`, mounted once at the app root (`app.html`, alongside `<vision-toast-host>`)
 * bottom-**left** — `ToastHost` already owns bottom-right, and bottom-center is the Fly cockpit's
 * own primary Start/Stop control (`features/fly/fly.css#.hud-controls`) at every viewport this app
 * supports, so this is the one remaining corner that never sits under a primary action.
 *
 * **One toast at a time, commit-on-replace, undo-only-cancels**: calling `showUndo()` while a
 * previous one is still showing immediately *commits* that previous one (runs its own `onCommit`,
 * if any, and drops it — its underlying mutation already happened and simply stands) before
 * showing the new one. The only way a toast's action is ever reversed is the operator clicking
 * `Undo` on *that* toast before it is replaced or times out; replacing it, dismissing it, or
 * letting it expire are all "commit", never "undo" — an operator moving on to archive a second
 * device should never accidentally lose the chance to notice they replaced an open undo window,
 * but should also never have that replacement silently trigger a second mutation on their behalf.
 */
@Injectable({ providedIn: 'root' })
export class UndoToastService {
  private readonly state = signal<UndoToastState | null>(null);
  private sequence = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private pendingUndo: (() => void) | null = null;
  private pendingCommit: (() => void) | null = null;

  readonly toast = this.state.asReadonly();

  /** Applies `undo` immediately if the operator hasn't already replaced/dismissed this toast. */
  showUndo(message: string, undo: () => void, options?: UndoToastOptions): void {
    this.settleActive();
    const timeoutMs = options?.timeoutMs ?? DEFAULT_UNDO_TIMEOUT_MS;
    const id = ++this.sequence;
    this.pendingUndo = undo;
    this.pendingCommit = options?.onCommit ?? null;
    this.state.set({ id, message, timeoutMs });
    this.timer = setTimeout(() => this.commit(id), timeoutMs);
  }

  /** The toast's own `Undo` button — cancels the timer and runs `undo`; `onCommit` never fires. */
  undo(): void {
    if (!this.state()) {
      return;
    }
    this.clearTimer();
    const undo = this.pendingUndo;
    this.clearPending();
    this.state.set(null);
    undo?.();
  }

  /** Explicit dismiss (`×` button / Escape) — same resolution as letting the timer expire: commits. */
  dismiss(): void {
    const active = this.state();
    if (active) {
      this.commit(active.id);
    }
  }

  private commit(id: number): void {
    const active = this.state();
    // Guards a timer that fired after this toast was already replaced/undone — `clearTimeout` in
    // both those paths makes this unreachable in practice, kept as a defensive no-op regardless.
    if (!active || active.id !== id) {
      return;
    }
    this.clearTimer();
    const onCommit = this.pendingCommit;
    this.clearPending();
    this.state.set(null);
    onCommit?.();
  }

  private settleActive(): void {
    const active = this.state();
    if (!active) {
      return;
    }
    this.clearTimer();
    const onCommit = this.pendingCommit;
    this.clearPending();
    onCommit?.();
  }

  private clearTimer(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }

  private clearPending(): void {
    this.pendingUndo = null;
    this.pendingCommit = null;
  }
}
