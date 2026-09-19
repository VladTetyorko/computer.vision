import { DestroyRef, Injectable, computed, effect, inject } from '@angular/core';
import { Store } from '@ngrx/store';
import type { CorrectionResponse } from '../api/models';
import { LiveFacade } from '../live/live-facade';
import { resolveAssetScopedTransport } from '../live/live-fallback-logic';
import { GeoLiveActions, GeoPageActions } from './state/geo.actions';
import { geoFeature } from './state/geo.reducer';

/** Module-level, monotonically increasing — same "two live instances can never collide" guarantee as `WeatherFacade`'s identical field. */
let nextHostSequence = 0;

/**
 * Replaces `GeoStore` (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N7) — one asset's latest
 * visual-geolocation correction (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.3/§3.4), the cockpit
 * divergence chip + detail popover's and `TacticalMap`'s corrected-track layer's own source.
 * `@Injectable()`, **not** `providedIn: 'root'` — unchanged from the old store: `CockpitPage` lists
 * this in its own `providers` (alongside `TelemetryStore`/`DetectionsStore`), so a fresh instance —
 * and its poll/subscription — starts/stops with the route.
 *
 * <h2>Keyed by a synthetic `hostId`, mirroring `WeatherFacade`</h2>
 * Only `CockpitPage` provides this today, but the `geo` slice is registered app-wide by name
 * regardless of which injector provides the facade reading it — keying by a per-instance `hostId`
 * (not `assetId`) is what keeps two hosts from ever colliding should a second one appear, exactly
 * `WeatherFacade`'s own reasoning (see that class's doc comment).
 *
 * <h2>Poll vs. live is a *state* transition, not an effect reading this facade</h2>
 * This class's own constructor `effect()` is the **only** place that reads
 * `LiveFacade.connectionState()` — it turns that Signal into a plain `GeoLiveActions.transportResolved`
 * dispatch whenever the resolved transport actually changes. `geo.effects.ts#track$` never injects
 * `LiveFacade` itself; it only reacts to this slice's own actions, satisfying the demand-gated-store
 * rule this wave was warned about ("live state read through selectors over the `live` slice, never
 * by injecting `LiveFacade` into an effect").
 *
 * <h2>The live *value* is never duplicated into this slice</h2>
 * Unlike the old `GeoStore#liveResultSignal`, `latest` below reads `LiveFacade.geoFor(assetId)`
 * directly at render time whenever `transport === 'live'` — there is no local copy to keep in sync.
 * The "never blank on flip" behaviour survives as a plain fallback inside the `computed()`: reading
 * whichever of poll/live last had a value, regardless of which one is nominally current.
 */
@Injectable()
export class GeoFacade {
  private readonly store = inject(Store);
  private readonly live = inject(LiveFacade);
  private readonly hostId = `geo-host-${++nextHostSequence}`;
  private readonly byHostId = this.store.selectSignal(geoFeature.selectByHostId);
  private readonly hostState = computed(() => this.byHostId()[this.hostId]);

  /** Defense in depth against a caller re-entering `track()` with an unchanged asset id — mirrors `GeoStore`'s identical `lastTrackAssetId` field. */
  private lastTrackAssetId: string | undefined;

  /** Whichever source is currently live-fresher — `undefined` until this asset has a computed correction, in either transport. */
  readonly latest = computed<CorrectionResponse | undefined>(() => {
    const host = this.hostState();
    if (!host || host.assetId === undefined) {
      return undefined;
    }
    const liveValue = this.live.geoFor(host.assetId)();
    return host.transport === 'live' ? (liveValue ?? host.pollResult) : (host.pollResult ?? liveValue);
  });

  /** See `geo.model.ts#GeoHostState.disabled`'s own doc comment. */
  readonly disabled = computed(() => this.hostState()?.disabled ?? false);

  constructor() {
    // Bridges `LiveFacade.connectionState()` into this slice's own action stream — see class doc.
    effect(() => {
      const host = this.hostState();
      if (!host || host.assetId === undefined) {
        return;
      }
      const transport = resolveAssetScopedTransport(this.live.connectionState(), host.assetId);
      if (transport !== host.transport) {
        this.store.dispatch(GeoLiveActions.transportResolved({ hostId: this.hostId, transport }));
      }
    });

    inject(DestroyRef).onDestroy(() => {
      this.releaseCurrentAsset();
      this.store.dispatch(GeoPageActions.hostReleased({ hostId: this.hostId }));
    });
  }

  /** Starts tracking `assetId`'s latest correction. A no-op when `assetId` is unchanged from the current session — see `lastTrackAssetId`'s own doc comment. */
  track(assetId: string): void {
    if (this.lastTrackAssetId === assetId) {
      return;
    }
    this.releaseCurrentAsset();
    this.lastTrackAssetId = assetId;
    this.live.trackGeo(assetId); // ref-counted; lasts for this whole track()/reset() session
    const initialTransport = resolveAssetScopedTransport(this.live.connectionState(), assetId);
    this.store.dispatch(GeoPageActions.trackRequested({ hostId: this.hostId, assetId, initialTransport }));
  }

  /** Stops tracking (poll + live subscription alike) and clears the latest correction. */
  reset(): void {
    this.releaseCurrentAsset();
    this.lastTrackAssetId = undefined;
    this.store.dispatch(GeoPageActions.resetRequested({ hostId: this.hostId }));
  }

  private releaseCurrentAsset(): void {
    if (this.lastTrackAssetId !== undefined) {
      this.live.untrackGeo(this.lastTrackAssetId);
    }
  }
}
