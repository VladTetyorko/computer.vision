import { Injectable } from '@angular/core';

/**
 * Background polling pauses while the tab is hidden rather than stopping forever — the idiom
 * every poller in this app (`FleetStore`, `TelemetryStore`, `FleetMapStore`, `DetectionsStore`)
 * followed before `PollScheduler` consolidated them. Kept here as the scheduler's single source
 * of truth; `core/telemetry-logic.ts#shouldPoll` re-exports this so existing imports of the
 * telemetry-flavored name keep working.
 */
export function shouldPoll(documentHidden: boolean): boolean {
  return !documentHidden;
}

/** The scheduler's own heartbeat — every registered cadence must be a multiple of this. */
const TICK_MS = 1_000;

interface Task {
  readonly periodMs: number;
  nextDueAt: number;
  readonly callback: () => void;
}

/**
 * One timer authority for every "poll while the tab is visible" consumer in the app
 * (docs/CYCLES-PLAN.md §9, CU-b item 3).
 *
 * Before this, `FleetStore`, `TelemetryStore`, `FleetMapStore` (both its asset poll and its
 * per-streaming-asset telemetry trackers, one `setInterval` each) and `DetectionsStore` each ran
 * their own `setInterval` — up to 1 + 1 + (1 + N) + 1 real browser timers alive at once on the
 * `/map` tab alone, N being however many assets are currently streaming. `PollScheduler` replaces
 * all of them with a single shared `setInterval` ticking at `TICK_MS`; every consumer instead
 * registers a `(periodMs, callback)` pair and gets called back on its own cadence, checked against
 * a per-task due time on each shared tick. Registering the first task lazily starts the underlying
 * timer; unregistering the last one stops it — so a page with nothing polling (or a test that
 * never subscribes) never leaves a dangling interval.
 *
 * **Pause, never drop, while hidden.** A tick where `document.hidden` is `true` is a no-op for
 * every task — due times are left exactly where they were, so the task fires on the next visible
 * tick rather than firing a burst of catch-up calls. This is the same "pause on hidden" contract
 * every consumer already had individually.
 *
 * **Provided in root** (unlike the per-page stores that use it): the timer authority itself is
 * process-wide — `FleetStore` (an app-wide singleton) and page-scoped stores like `TelemetryStore`
 * all share the one instance, which is exactly the point.
 */
@Injectable({ providedIn: 'root' })
export class PollScheduler {
  private readonly tasks = new Set<Task>();
  private tickHandle: ReturnType<typeof setInterval> | null = null;

  /**
   * Registers `callback` to run roughly every `periodMs` while the tab is visible, starting one
   * `periodMs` from now (mirrors every store's previous idiom of doing an immediate first fetch
   * itself, then relying on the interval only for the *next* one). Returns an unsubscribe
   * function — call it on `DestroyRef.onDestroy`/`reset()`/tracker teardown, exactly where a
   * `clearInterval(handle)` used to go.
   */
  schedule(periodMs: number, callback: () => void): () => void {
    const task: Task = { periodMs, nextDueAt: Date.now() + periodMs, callback };
    this.tasks.add(task);
    this.ensureTicking();
    return () => {
      this.tasks.delete(task);
      if (this.tasks.size === 0) {
        this.stopTicking();
      }
    };
  }

  private ensureTicking(): void {
    if (this.tickHandle !== null) {
      return;
    }
    this.tickHandle = setInterval(() => this.tick(), TICK_MS);
  }

  private stopTicking(): void {
    if (this.tickHandle !== null) {
      clearInterval(this.tickHandle);
      this.tickHandle = null;
    }
  }

  private tick(): void {
    if (!shouldPoll(document.hidden)) {
      return; // every due task just waits for the next visible tick — none are dropped
    }
    const now = Date.now();
    for (const task of this.tasks) {
      if (now >= task.nextDueAt) {
        task.nextDueAt = now + task.periodMs;
        task.callback();
      }
    }
  }
}
