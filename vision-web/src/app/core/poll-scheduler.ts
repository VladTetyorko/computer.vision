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
  readonly callback: () => void | Promise<void>;
  readonly ignoreHidden: boolean;
  /** True while a previously-returned promise from `callback` hasn't settled yet — see `tick`. */
  inFlight: boolean;
}

/** Narrows a callback's return value without assuming every caller returns a real `Promise`. */
function isThenable(value: void | Promise<void>): value is Promise<void> {
  return typeof value === 'object' && value !== null && typeof (value as Promise<void>).then === 'function';
}

export interface ScheduleOptions {
  /**
   * Runs this task even while `document.hidden` is `true`, instead of pausing like every other
   * task (see the class doc's "pause, never drop" contract). Defaults to `false` — every existing
   * caller is unaffected.
   *
   * **The one documented user** is `core/events-store.ts`'s global detection-events poll
   * (docs/MVP2-PLAN.md §E, E-b): its whole reason to exist is noticing a newly-opened event *while
   * the tab is in the background*, so it can fire a browser `Notification` — a task that pauses
   * the instant the tab backgrounds could never detect anything to notify about, since by
   * definition it would only ever observe new data while already visible. Every other poller in
   * this app has the opposite goal (don't burn battery/network on a tab nobody is looking at) and
   * must keep the default.
   */
  readonly ignoreHidden?: boolean;
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
   *
   * **In-flight guard (docs/MVP2-PLAN.md §S, S-b).** If `callback` returns a `Promise`, this task
   * is skipped on every due tick until that promise settles — a slow/hung backend then degrades
   * one task to stale data instead of firing an unbounded, ever-growing pile of overlapping HTTP
   * requests against it (every poller in this app calls a `VisionApi` method that returns a
   * `Promise`, so every real poll registration gets this for free; a synchronous clock-tick
   * callback, e.g. `() => this.nowSignal.set(Date.now())`, returns `void` and is entirely
   * unaffected — there is nothing to wait on). Settling with a rejection still clears the guard
   * (a `catch`-swallowing poller — this app's own silent-degrade convention — must not wedge
   * itself forever just because one request failed).
   */
  schedule(periodMs: number, callback: () => void | Promise<void>, options: ScheduleOptions = {}): () => void {
    const task: Task = {
      periodMs,
      nextDueAt: Date.now() + periodMs,
      callback,
      ignoreHidden: options.ignoreHidden ?? false,
      inFlight: false,
    };
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
    const paused = !shouldPoll(document.hidden);
    const now = Date.now();
    for (const task of this.tasks) {
      if (paused && !task.ignoreHidden) {
        continue; // waits for the next visible tick — due time untouched, nothing is dropped
      }
      if (task.inFlight) {
        continue; // the previous invocation hasn't settled yet — see `schedule`'s in-flight guard
      }
      if (now >= task.nextDueAt) {
        task.nextDueAt = now + task.periodMs;
        this.run(task);
      }
    }
  }

  private run(task: Task): void {
    const result = task.callback();
    if (!isThenable(result)) {
      return;
    }
    task.inFlight = true;
    result.then(
      () => {
        task.inFlight = false;
      },
      () => {
        task.inFlight = false; // a rejected poll still frees the next tick — see `schedule`'s doc.
      },
    );
  }
}
