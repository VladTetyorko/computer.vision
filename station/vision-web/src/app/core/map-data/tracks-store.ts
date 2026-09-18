import { Injectable, effect, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import type { ProjectedTrackResponse } from '../api/models';
import { PollScheduler } from '../poll-scheduler';
import { LiveFacade } from '../live/live-facade';
import { isLiveAvailable } from '../live/live-fallback-logic';
import { applyTrackEvent } from '../camera-geo/camera-geo-logic';

/**
 * Safety-net only, mirroring `LayersStore`/`MarksStore`'s own 30s cadence: the `map` live topic is
 * always-on and this store folds every `TRACK` delta in as it arrives (D3), so this poll exists
 * purely to reconcile a connection that is genuinely down, or a deployment where the
 * fixed-camera-geo flag flips on mid-session (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md D8).
 *
 * **Gated on live, not unconditional** (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1) — runs
 * **only** while `activeConsumers > 0` **and** `LiveFacade` is not `'open'`; see
 * `MarksStore.applyTransport`'s identical state table (`applyTransport` below implements the same
 * one).
 */
const TRACKS_POLL_INTERVAL_MS = 30_000;

/**
 * `TracksStore` — the app's one source of truth for projected map tracks
 * (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md §5/D3, wave G5). `providedIn: 'root'`, started at boot
 * alongside `MarksStore`/`LayersStore`/`DrawingsStore` — the same "one poller, every host reads the
 * same signal" posture, since `<vision-tactical-map>`'s track layer is meant to be embeddable on
 * every host that already carries the other map-data stores (Command, the Fly cockpit, `/live`,
 * asset detail).
 *
 * <h2>Initial GET + live deltas, exactly like every other `core/map-data/**` store</h2>
 * `GET /api/map/tracks` first (the only place `trail` — the durable, decimated history — ever
 * arrives; §5's own "trail via GET after reload"), then fold `LiveFacade.mapEvents()` on top via
 * `core/camera-geo/camera-geo-logic.ts#applyTrackEvent`, the same `processedLiveEventCount` cursor
 * idiom `LayersStore`/`MarksStore`/`DrawingsStore` each keep independently over the one shared
 * arrival log (`core/live/live-facade.ts#mapEvents`'s own doc comment explains why three-now-four
 * consumers read one signal rather than sharing a fold).
 *
 * <h2>Flag-off degrades to "no tracks", not an error</h2>
 * While `vision.geo.fixed-camera.enabled=false` (the default, D8) `GET /api/map/tracks` 409s. This
 * store treats that exactly like any other background-refresh failure — the last-known (empty) list
 * survives, no toast — so an unflagged deployment's map simply shows no track layer, matching D8's
 * own "the feature is inert, not wrong" and this app's "degrade honestly, never a blocked page" rule.
 * There is nothing to mutate here from this wave — pose/calibration writes live on
 * `core/camera-geo/camera-pose-panel.ts`'s own per-asset calls, not this shared read model — so,
 * unlike `LayersStore`, this store has no `run()`/toast seam of its own.
 *
 * <h2>Polling is demand-gated (ALWAYS-ON-FLOW-PLAN.md §4 Wave C3)</h2>
 * See `MarksStore`'s identical doc section — same defect, same fix: {@link activate}/{@link release}.
 * Today's only confirmed direct injector is `AssetDetailFacade`; kept as an explicit ref-count (not a
 * bespoke one-consumer flag) so a future second consumer composes for free.
 *
 * <h2>...and now also gated on live itself (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1)</h2>
 * Same composition as `MarksStore.applyTransport` — `activeConsumers > 0 && !isLiveAvailable(...)`
 * is the only state that runs the 30s poll; see that method's own doc comment for the full table.
 */
@Injectable({ providedIn: 'root' })
export class TracksStore {
  private readonly api = inject(VisionApi);
  private readonly live = inject(LiveFacade);
  private readonly scheduler = inject(PollScheduler);

  private readonly tracksSignal = signal<readonly ProjectedTrackResponse[]>([]);
  /** Every projected track on a layer this viewer may see (D10) — already scoped server-side. */
  readonly tracks = this.tracksSignal.asReadonly();

  private readonly loadedSignal = signal(false);
  /** `true` once the first `refresh()` has settled (success or failure). */
  readonly loaded = this.loadedSignal.asReadonly();

  /** How many live map deltas (`LiveFacade.mapEvents()`) this store has folded in — see the class doc's cursor note. */
  private processedLiveEventCount = 0;

  /** Ref-count of live consumers — see {@link activate}/{@link release}. */
  private activeConsumers = 0;
  /** The safety-net poll's own unsubscribe, held only while `activeConsumers > 0`. */
  private stopPollFn: (() => void) | null = null;

  /**
   * `false` while this store is (or should be) relying on the safety-net poll rather than live —
   * see {@link applyTransport}'s own doc comment for the full state table this tracks. Starts
   * `false` so this store's very first `applyTransport` call — whichever way `liveAvailable`
   * resolves — is always treated as a genuine transition, never a spurious no-op.
   */
  private liveGated = false;

  constructor() {
    effect(() => {
      const events = this.live.mapEvents();
      if (events.length <= this.processedLiveEventCount) {
        return;
      }
      const newEvents = events.slice(this.processedLiveEventCount);
      this.processedLiveEventCount = events.length;
      this.tracksSignal.update((tracks) => newEvents.reduce(applyTrackEvent, tracks));
    });

    // Re-evaluates poll-vs-live whenever `LiveFacade` (re)connects or drops
    // (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1) — mirrors `FleetStore`/
    // `EventsStore`'s identical reconnect-driven effect.
    effect(() => {
      this.applyTransport(isLiveAvailable(this.live.connectionState()));
    });
  }

  /**
   * Registers demand — see `MarksStore.activate`'s identical doc comment for the full rationale.
   * The first `activate()` since the last full `release()` routes through {@link applyTransport}
   * with the current transport; further concurrent consumers just bump the count.
   */
  activate(): void {
    this.activeConsumers++;
    if (this.activeConsumers > 1) {
      return;
    }
    this.applyTransport(isLiveAvailable(this.live.connectionState()));
  }

  /** The matching teardown — call from the consumer's own `DestroyRef.onDestroy`. */
  release(): void {
    if (this.activeConsumers === 0) {
      return; // defensive — a mismatched release should never go negative
    }
    this.activeConsumers--;
    if (this.activeConsumers === 0 && this.stopPollFn !== null) {
      this.stopPollFn();
      this.stopPollFn = null;
    }
  }

  /**
   * D1's frozen gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3) — see
   * `MarksStore.applyTransport`'s own doc comment for the full state table; this is the identical
   * shape. Called both by the reconnect-driven `effect()` above and by `activate()` itself.
   */
  private applyTransport(liveAvailable: boolean): void {
    if (this.activeConsumers === 0) {
      this.stopPolling();
      // Forget the transport mode too. A live outage that starts *and ends* while nothing is
      // mounted delivers no deltas and leaves no trace, so a stale `liveGated` would make the next
      // `activate()` skip its reconcile and show data missing everything the outage swallowed.
      // Clearing it here also restores `activate()`'s documented "first consumer re-fetches"
      // contract, which the live gate would otherwise have quietly weakened.
      this.liveGated = false;
      return;
    }
    if (liveAvailable) {
      if (!this.liveGated) {
        this.stopPolling();
        void this.refresh();
        this.liveGated = true;
      }
      return;
    }
    this.liveGated = false;
    if (this.stopPollFn !== null) {
      return; // already polling
    }
    void this.refresh();
    this.stopPollFn = this.scheduler.schedule(TRACKS_POLL_INTERVAL_MS, () => this.refresh());
  }

  private stopPolling(): void {
    this.stopPollFn?.();
    this.stopPollFn = null;
  }

  async refresh(): Promise<void> {
    try {
      this.tracksSignal.set((await this.api.listMapTracks()).tracks);
    } catch {
      // Silent-degrade, like every other background poller here — a transient failure (or the
      // feature flag being off, D8) keeps the last-known list rather than flashing every track away.
    } finally {
      this.loadedSignal.set(true);
    }
  }
}
