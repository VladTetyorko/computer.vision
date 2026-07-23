import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from './api/vision-api';
import type { DetectionResult } from './api/models';
import { PollScheduler } from './poll-scheduler';
import { cvStatus, deriveChips } from './detections-logic';

/** How often a tracked stream's recent detections are re-read while polling is active. */
const POLL_INTERVAL_MS = 2_000;

/** How often the CV status dot's recency check ticks, independent of the poll cadence. */
const CLOCK_TICK_MS = 1_000;

/** Matches `StreamController#detections`'s own default limit (docs/MVP1-PLAN.md §C8). */
const DETECTIONS_LIMIT = 50;

/**
 * Tracks one stream's recent detections for the Live page's chip strip + CV status dot
 * (docs/MVP1-PLAN.md §C8 bullet 4): polls `GET /api/streams/{streamId}/detections` every 2s
 * while visible — the same poll/pause-on-hidden idiom as `TelemetryStore`, running off the same
 * shared `PollScheduler` (docs/CYCLES-PLAN.md §9, CU-b item 3) rather than its own `setInterval`.
 *
 * **Component-provided, not `providedIn: 'root'`.** `LivePage` lists this in its own
 * `providers`, so a fresh instance — and its poll registration — starts/stops with the route,
 * exactly like `TelemetryStore`.
 *
 * **Errors silent-degrade, no toast.** A failed or empty poll just leaves `results` at its
 * last-known value; the CV status dot naturally reads `'off'` once that value goes stale
 * (`detections-logic.ts#cvStatus`), so there is no separate error state to invent or report.
 */
@Injectable()
export class DetectionsStore {
  private readonly api = inject(VisionApi);
  private readonly scheduler = inject(PollScheduler);

  private readonly resultsSignal = signal<readonly DetectionResult[]>([]);
  private readonly nowSignal = signal(Date.now());

  private stopPollingFn: (() => void) | null = null;
  private readonly stopClock: () => void;

  /** Bumped on every `track`/`reset` so a stale in-flight poll can tell it has been superseded. */
  private generation = 0;

  readonly results = this.resultsSignal.asReadonly();

  /** The last ~8 distinct labels seen, each with its most recently recorded confidence. */
  readonly chips = computed(() => deriveChips(this.resultsSignal()));

  /** `'on'` (green) when the most recent result is fresh, `'off'` (grey) otherwise. */
  readonly status = computed(() => cvStatus(this.resultsSignal()[0]?.capturedAt, this.nowSignal()));

  constructor() {
    this.stopClock = this.scheduler.schedule(CLOCK_TICK_MS, () => this.nowSignal.set(Date.now()));
    inject(DestroyRef).onDestroy(() => {
      this.stopClock();
      this.stopPolling();
    });
  }

  /**
   * Starts polling `streamId`'s recent detections every 2s. Calling again (e.g. the stream
   * changed) supersedes any in-flight poll and clears prior results immediately, so a stale
   * stream's chips never linger into the next.
   */
  track(streamId: string): void {
    const generation = ++this.generation;
    this.stopPolling();
    this.resultsSignal.set([]);

    void this.pollOnce(streamId, generation);
    // Returns the poll's own promise so `PollScheduler`'s in-flight guard applies — see
    // `FleetStore`'s identical comment (docs/MVP2-PLAN.md §S, S-b).
    this.stopPollingFn = this.scheduler.schedule(POLL_INTERVAL_MS, () => this.pollOnce(streamId, generation));
  }

  /** Stops polling and clears results — call when there is no stream left to track. */
  reset(): void {
    this.generation++;
    this.stopPolling();
    this.resultsSignal.set([]);
  }

  private async pollOnce(streamId: string, generation: number): Promise<void> {
    try {
      const results = await this.api.streamDetections(streamId, DETECTIONS_LIMIT);
      if (generation === this.generation) {
        this.resultsSignal.set(results);
      }
    } catch {
      // Silent-degrade: a missed poll just leaves results (and therefore the CV dot) as they were.
    }
  }

  private stopPolling(): void {
    if (this.stopPollingFn !== null) {
      this.stopPollingFn();
      this.stopPollingFn = null;
    }
  }
}
