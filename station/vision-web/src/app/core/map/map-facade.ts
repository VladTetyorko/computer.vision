import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { Actions } from '@ngrx/effects';
import { Store } from '@ngrx/store';
import type { Device } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { findVideoDevice } from '../fleet/device-logic';
import { LiveFacade } from '../live/live-facade';
import { mergeTelemetrySamples } from '../live/live-fallback-logic';
import { PollScheduler } from '../poll-scheduler';
import { dispatchAndAwait } from '../state/dispatch-bridge';
import { bucketAssets, buildMarkers, snapshotFromSamples, type AssetTelemetrySnapshot } from './map-logic';
import { MapApiActions, MapPageActions } from './state/map.actions';
import { mapFeature } from './state/map.reducer';

/** How often the "n seconds ago" readout ticks — `FleetMapStore`'s own `CLOCK_TICK_MS` (L7d — "stays a local timer"). */
const CLOCK_TICK_MS = 1_000;

/**
 * `FleetMapStore`'s read/dispatch boundary (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N6).
 * `@Injectable()`, **not** `providedIn: 'root'` — same posture as the store it replaces:
 * `CommandPage` lists this in its own `providers`, so a fresh instance starts the moment that page
 * mounts and tears down the moment it doesn't. See `map.effects.ts#assetGate$`'s own doc comment for
 * why the `map` slice needed a new `activeConsumers` ref-count to keep that same lifetime-bound
 * behavior once the transport-management effects moved to NgRx's root-scoped, always-subscribed model.
 *
 * **Two deliberate, scoped exceptions to "every piece of state lives in NgRx" (both narrated in this
 * wave's own exit report, not silent omissions):**
 *  - The "now" clock ({@link nowSignal}) stays a plain local `signal`, ticked by a directly-injected
 *    `PollScheduler` right here — dispatching a root-level action every second for the app's whole
 *    lifetime just to drive a `sampleAgeSeconds` readout would be a cross-cutting cost for a value
 *    nothing outside this one component tree ever reads.
 *  - {@link resolveWatchDevice} calls `VisionApi` directly — an occasional, uncached, stateless
 *    passthrough query (`FleetMapStore`'s own doc comment: "occasional, not worth caching") with
 *    nothing to reconcile or make observable; NgRx's action/reducer/effect machinery exists to manage
 *    state transitions, not to gate every REST call universally.
 */
@Injectable()
export class MapFacade {
  private readonly store = inject(Store);
  private readonly actions$ = inject(Actions);
  private readonly api = inject(VisionApi);
  private readonly live = inject(LiveFacade);

  readonly assets = this.store.selectSignal(mapFeature.selectAssets);
  readonly buckets = computed(() => bucketAssets(this.assets()));
  private readonly trackers = this.store.selectSignal(mapFeature.selectTrackers);
  private readonly nowSignal = signal(Date.now());

  /**
   * Merges each tracked asset's one-time/fallback-poll backfill with `LiveFacade.telemetryFor
   * (assetId)()` — facade-to-facade composition, not effect-level injection, exactly the distinction
   * NGRX-MIGRATION-PLAN.md §9 draws (`live` state is read through selectors from an *effect*; a
   * *facade* composing a sibling facade's own cached signal is fine, and is how `map-store.ts`'s own
   * `markers` computed already worked). Ported verbatim from that computed, including the merge order
   * and the `nowSignal` dependency.
   */
  readonly markers = computed(() => {
    const telemetryByAsset = new Map<string, AssetTelemetrySnapshot>();
    const trackers = this.trackers();
    for (const assetId of Object.keys(trackers)) {
      const tracker = trackers[assetId];
      const merged = mergeTelemetrySamples(tracker.backfill, this.live.telemetryFor(assetId)());
      telemetryByAsset.set(assetId, snapshotFromSamples(merged));
    }
    return buildMarkers(this.assets(), telemetryByAsset, this.nowSignal());
  });

  constructor() {
    this.store.dispatch(MapPageActions.activated());
    const scheduler = inject(PollScheduler);
    const stopClock = scheduler.schedule(CLOCK_TICK_MS, () => this.nowSignal.set(Date.now()));
    inject(DestroyRef).onDestroy(() => {
      stopClock();
      this.store.dispatch(MapPageActions.released());
    });
  }

  /** Re-reads `GET /api/assets` right now, regardless of the current transport mode — `CommandFacade
   *  .addTestDrone`'s own need (a synthetic drone must appear without waiting on the next poll tick
   *  or live envelope). Ports `FleetMapStore.refresh()`'s public contract verbatim. */
  async refresh(): Promise<void> {
    await dispatchAndAwait(
      this.store,
      this.actions$,
      MapPageActions.refreshRequested(),
      MapApiActions.assetsLoaded,
      MapApiActions.assetsLoadFailed,
      () => undefined,
      () => undefined,
    );
  }

  /** The Watch action's target device, resolved fresh on click — see class doc for why this bypasses NgRx entirely. */
  async resolveWatchDevice(assetId: string): Promise<Device | undefined> {
    try {
      const details = await this.api.getAsset(assetId);
      return findVideoDevice(details.devices);
    } catch {
      return undefined; // best-effort — worst case the popup's Watch action just does nothing
    }
  }
}
