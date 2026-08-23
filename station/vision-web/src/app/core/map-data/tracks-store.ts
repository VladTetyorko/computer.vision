import { Injectable, effect, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import type { ProjectedTrackResponse } from '../api/models';
import { PollScheduler } from '../poll-scheduler';
import { LiveStore } from '../live/live-store';
import { applyTrackEvent } from '../camera-geo/camera-geo-logic';

/**
 * Safety-net only, mirroring `LayersStore`/`MarksStore`'s own 30s cadence: the `map` live topic is
 * always-on and this store folds every `TRACK` delta in as it arrives (D3), so this poll exists
 * purely to reconcile a connection that was briefly down, or a deployment where the fixed-camera-geo
 * flag flips on mid-session (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md D8).
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
 * arrives; §5's own "trail via GET after reload"), then fold `LiveStore.mapEvents()` on top via
 * `core/camera-geo/camera-geo-logic.ts#applyTrackEvent`, the same `processedLiveEventCount` cursor
 * idiom `LayersStore`/`MarksStore`/`DrawingsStore` each keep independently over the one shared
 * arrival log (`core/live/live-store.ts#mapEvents`'s own doc comment explains why three-now-four
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
 */
@Injectable({ providedIn: 'root' })
export class TracksStore {
  private readonly api = inject(VisionApi);
  private readonly live = inject(LiveStore);

  private readonly tracksSignal = signal<readonly ProjectedTrackResponse[]>([]);
  /** Every projected track on a layer this viewer may see (D10) — already scoped server-side. */
  readonly tracks = this.tracksSignal.asReadonly();

  private readonly loadedSignal = signal(false);
  /** `true` once the first `refresh()` has settled (success or failure). */
  readonly loaded = this.loadedSignal.asReadonly();

  /** How many live map deltas (`LiveStore.mapEvents()`) this store has folded in — see the class doc's cursor note. */
  private processedLiveEventCount = 0;

  constructor() {
    void this.refresh();
    inject(PollScheduler).schedule(TRACKS_POLL_INTERVAL_MS, () => this.refresh());

    effect(() => {
      const events = this.live.mapEvents();
      if (events.length <= this.processedLiveEventCount) {
        return;
      }
      const newEvents = events.slice(this.processedLiveEventCount);
      this.processedLiveEventCount = events.length;
      this.tracksSignal.update((tracks) => newEvents.reduce(applyTrackEvent, tracks));
    });
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
