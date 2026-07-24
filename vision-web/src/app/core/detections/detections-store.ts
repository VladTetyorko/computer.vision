import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import type { DetectionResult } from '../api/models';
import { PollScheduler } from '../poll-scheduler';
import { LiveStore } from '../live/live-store';
import { cvStatus, deriveChips } from './detections-logic';
import { type AssetScopedTransport, resolveAssetScopedTransport, trackSessionKey } from '../live/live-fallback-logic';

/** How often a tracked stream's recent detections are re-read while **polling** (the fallback) is active. */
const POLL_INTERVAL_MS = 2_000;

/** How often the CV status dot's recency check ticks, independent of the poll cadence. */
const CLOCK_TICK_MS = 1_000;

/** Matches `StreamController#detections`'s own default limit (docs/MVP1-PLAN.md §C8) — also the cap on the live-accumulated list below. */
const DETECTIONS_LIMIT = 50;

/**
 * Tracks one stream's recent detections for the Live page's chip strip + CV status dot
 * (docs/MVP1-PLAN.md §C8 bullet 4): polls `GET /api/streams/{streamId}/detections` every 2s while
 * visible, or — when `assetId` is given and {@link LiveStore} is open (docs/REALTIME-PLAN.md §4,
 * Phase R-c) — subscribes to that asset's live `detections:<assetId>` topic instead.
 *
 * **`track(streamId, assetId?)`** (`assetId` new in R-c, mirroring `TelemetryStore.track`'s own
 * R-a-established `assetId?` split): the live `detections` topic is asset-scoped, not stream-scoped
 * (docs/REALTIME-PLAN.md §4, item 2 — there is no way to subscribe to "this stream's detections"
 * over SSE, only "this asset's"), so a caller with no asset id in scope (`LivePage`/`WallTile` — a
 * bare `deviceId`/`streamId`, no asset context) always polls by `streamId`, regardless of whether
 * `LiveStore` is otherwise connected — exactly as before this cycle. `FlyPage`/`AssetDetailPage`
 * (both already have an asset id) pass it, and get live detections when available.
 *
 * **Latest-frame-only, accumulated client-side**: `LiveStore.detectionsFor` only ever holds the
 * single most recent result (the coalescing registry's own "detections keep only the latest per
 * asset", docs/REALTIME-PLAN.md §4, item 3 — there is no backlog to replay). This store still
 * presents the same shape the poll fallback always has — a short recent-history list, newest first
 * — by prepending every newly-arrived live result onto a running `liveResultsSignal` itself
 * (capped at `DETECTIONS_LIMIT`, same as the poll's own `limit` query param), rather than
 * re-deriving history from a single latest value on every read.
 *
 * **Subscription lifecycle vs. transport, kept separate** (mirrors `TelemetryStore`'s own split —
 * see that class's doc comment for the full reasoning): `track()` subscribes to `LiveStore` once
 * (ref-counted there) for the whole session, torn down only by a matching `reset()`/a later
 * `track()` call; whether `results` is actually *fed* from that subscription or from the local poll
 * toggles purely with `LiveStore.connectionState()`, so a transient SSE drop never spuriously
 * unsubscribes/resubscribes. Switching either direction seeds the *other* signal from whatever was
 * last visible, so the chip strip never blanks for a beat on a transport flip.
 *
 * **Component-provided, not `providedIn: 'root'`.** `LivePage` lists this in its own
 * `providers`, so a fresh instance — and its poll/subscription — starts/stops with the route,
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
  private readonly live = inject(LiveStore);

  /** Kept fresh by the 2s poll while `transportSignal() === 'poll'`; stale/unused while `'live'`. */
  private readonly pollResultsSignal = signal<readonly DetectionResult[]>([]);
  /** Accumulated by the live-update effect below while `transportSignal() === 'live'`. */
  private readonly liveResultsSignal = signal<readonly DetectionResult[]>([]);
  /** The `assetId` passed to the current `track()` call, or `undefined` — drives the transport decision. */
  private readonly currentAssetIdSignal = signal<string | undefined>(undefined);
  /** Which source `results` currently reads from — see `applyTransport`. */
  private readonly transportSignal = signal<AssetScopedTransport>('poll');
  private readonly nowSignal = signal(Date.now());

  private stopPollingFn: (() => void) | null = null;
  private readonly stopClock: () => void;

  /** Bumped on every `track`/`reset` so a stale in-flight poll can tell it has been superseded. */
  private generation = 0;
  /** Whether a `track()` call is currently in effect — guards the transport effect. */
  private tracking = false;
  /** The stream currently being tracked — reused by `applyTransport`'s poll branch across transport flips. */
  private currentStreamId: string | undefined;
  /**
   * The `(streamId, assetId)` pair the current session is for, or `undefined` after `reset()` —
   * defense in depth against a caller re-entering `track()` with an unchanged id; see
   * `TelemetryStore`'s identical `lastTrackKey` field for the full reasoning (this store's own
   * `track()` has no `await` at all, so the risk here is an even *tighter* same-tick re-notify loop,
   * not just an async one).
   */
  private lastTrackKey: string | undefined;

  /** Whichever source is currently active — every other computed below derives from this. */
  readonly results = computed<readonly DetectionResult[]>(() =>
    this.transportSignal() === 'live' ? this.liveResultsSignal() : this.pollResultsSignal(),
  );

  /** The last ~8 distinct labels seen, each with its most recently recorded confidence. */
  readonly chips = computed(() => deriveChips(this.results()));

  /** `'on'` (green) when the most recent result is fresh, `'off'` (grey) otherwise. */
  readonly status = computed(() => cvStatus(this.results()[0]?.capturedAt, this.nowSignal()));

  constructor() {
    this.stopClock = this.scheduler.schedule(CLOCK_TICK_MS, () => this.nowSignal.set(Date.now()));

    // Mirrors `TelemetryStore`'s identical effect — see that class's own doc comment for the full
    // "subscription lifecycle vs. transport" split this relies on.
    effect(() => {
      const connectionState = this.live.connectionState();
      const assetId = this.currentAssetIdSignal();
      if (!this.tracking) {
        return;
      }
      this.applyTransport(resolveAssetScopedTransport(connectionState, assetId));
    });

    // Accumulates every newly-arrived live latest-frame result — see class doc's "Latest-frame-only,
    // accumulated client-side". Runs regardless of `transportSignal`'s current value (cheap, and
    // keeps `liveResultsSignal` warm so a poll→live switch has continuity immediately, not just
    // from the next arrival) — reading `results`/`chips`/`status` is what actually gates on transport.
    effect(() => {
      const assetId = this.currentAssetIdSignal();
      if (assetId === undefined) {
        return;
      }
      const latest = this.live.detectionsFor(assetId)();
      if (latest === undefined) {
        return;
      }
      this.liveResultsSignal.update((existing) => {
        if (existing[0] === latest) {
          return existing; // this exact envelope was already applied — effect re-ran for an unrelated reason
        }
        return [latest, ...existing].slice(0, DETECTIONS_LIMIT);
      });
    });

    inject(DestroyRef).onDestroy(() => {
      this.stopClock();
      this.teardownTracking();
    });
  }

  /**
   * Starts tracking `streamId`'s recent detections. Calling again (e.g. the stream changed)
   * supersedes any in-flight poll/subscription and clears prior results immediately, so a stale
   * stream's chips never linger into the next.
   *
   * **A no-op when `(streamId, assetId)` is unchanged from the current session** — see
   * `lastTrackKey`'s own doc comment for why this matters beyond avoiding redundant work.
   */
  track(streamId: string, assetId?: string): void {
    const key = trackSessionKey(streamId, assetId);
    if (this.lastTrackKey === key) {
      return;
    }
    this.lastTrackKey = key;

    const generation = ++this.generation;
    this.tracking = false;
    this.teardownTracking(); // tears down the *previous* session's subscription/poll, if any
    this.pollResultsSignal.set([]);
    this.liveResultsSignal.set([]);
    this.currentStreamId = streamId;

    this.tracking = true;
    this.currentAssetIdSignal.set(assetId);
    if (assetId !== undefined) {
      this.live.trackDetections(assetId); // ref-counted; lasts for this whole track()/reset() session
    }
    this.applyTransport(resolveAssetScopedTransport(this.live.connectionState(), assetId));
  }

  /** Stops tracking (poll + live subscription alike) and clears results. */
  reset(): void {
    this.generation++;
    this.tracking = false;
    this.lastTrackKey = undefined;
    this.teardownTracking();
    this.currentAssetIdSignal.set(undefined);
    this.currentStreamId = undefined;
    this.pollResultsSignal.set([]);
    this.liveResultsSignal.set([]);
    this.transportSignal.set('poll');
  }

  /**
   * Switches which source `results` reads from and, correspondingly, whether the local poll is
   * running — **not** whether the live subscription itself exists (that's `track()`/`reset()`'s
   * job — see class doc). Seeds the *other* signal from whatever was last visible so the chip strip
   * never blanks for a beat on the flip. Mirrors `TelemetryStore.applyTransport`.
   */
  private applyTransport(next: AssetScopedTransport): void {
    if (next === 'live') {
      if (this.liveResultsSignal().length === 0 && this.pollResultsSignal().length > 0) {
        this.liveResultsSignal.set(this.pollResultsSignal());
      }
      this.transportSignal.set(next);
      this.stopPolling();
      return;
    }
    if (this.pollResultsSignal().length === 0 && this.liveResultsSignal().length > 0) {
      this.pollResultsSignal.set(this.liveResultsSignal());
    }
    this.transportSignal.set(next);
    const streamId = this.currentStreamId;
    if (streamId === undefined || this.stopPollingFn !== null) {
      return; // nothing to poll, or already polling
    }
    const generation = this.generation;
    void this.pollOnce(streamId, generation);
    // Returns the poll's own promise so `PollScheduler`'s in-flight guard applies — see
    // `FleetStore`'s identical comment (docs/MVP2-PLAN.md §S, S-b).
    this.stopPollingFn = this.scheduler.schedule(POLL_INTERVAL_MS, () => this.pollOnce(streamId, generation));
  }

  private async pollOnce(streamId: string, generation: number): Promise<void> {
    try {
      const results = await this.api.streamDetections(streamId, DETECTIONS_LIMIT);
      if (generation === this.generation) {
        this.pollResultsSignal.set(results);
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

  /** Stops the local poll and releases the live subscription for whatever asset the *previous* session tracked. */
  private teardownTracking(): void {
    this.stopPolling();
    const assetId = this.currentAssetIdSignal();
    if (assetId !== undefined) {
      this.live.untrackDetections(assetId);
    }
  }
}
