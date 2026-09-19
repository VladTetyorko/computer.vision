import type { AssetSummary, TelemetrySample } from '../../api/models';

/**
 * One streaming asset's telemetry bookkeeping (docs/plans/done/NGRX-MIGRATION-PLAN.md wave N6,
 * replacing `map-store.ts#AssetTracker`). No `generation` counter — `map.effects.ts#trackerLifecycle$`
 * uses RxJS `groupBy(assetId)` + `switchMap` cancellation instead (see that file's own doc comment),
 * the same substitution `route.effects.ts#show$` already established for `RouteStore`'s old counter.
 * No `stopPolling` handle either — whether this tracker is currently polling is derived reactively
 * off the `live` slice inside `map.effects.ts#pollTelemetry$`, not stored.
 */
export interface MapTrackerState {
  /** The one-time backfill (or latest fallback-poll read) — see `map-store.ts#AssetTracker.backfill`'s own doc comment for the merge-with-live rationale, ported verbatim. */
  readonly backfill: readonly TelemetrySample[];
  /** The open usage {@link backfill} was read from — `undefined` until resolved (a streaming asset with no open usage yet has nothing to poll or backfill). */
  readonly usageId: string | undefined;
}

/**
 * The `map` slice's entire state — replacing `FleetMapStore`'s `assetsSignal`/`trackers`/
 * `trackedAssetIdsSignal` (its `nowSignal` clock stays a facade-local plain signal; see
 * `map-facade.ts`'s own doc comment for why that one deliberately never joins NgRx state).
 *
 * `trackers` is keyed by `assetId` — a plain `Record`, not `@ngrx/entity` (this wave's mandatory-entity
 * list, NGRX-MIGRATION-PLAN.md §3 rule 4, names marks/layers/drawings/tracks only; a `Record` needs no
 * adapter machinery for a collection nothing ever sorts, paginates, or iterates by anything but key).
 */
export interface MapState {
  readonly assets: readonly AssetSummary[];
  /** Ref-count of demand — see `map.effects.ts`'s own doc comment for why this slice needs one at all despite `FleetMapStore` itself never having had one (a genuine plan-translation gap this wave found). */
  readonly activeConsumers: number;
  readonly trackers: Record<string, MapTrackerState>;
}

export const initialMapState: MapState = { assets: [], activeConsumers: 0, trackers: {} };

/** How often assets are re-read while active and live is unavailable — `FleetMapStore`'s own `ASSET_POLL_INTERVAL_MS`. */
export const ASSET_POLL_INTERVAL_MS = 5_000;

/** Matches `AssetController#telemetry`'s own default-overriding call site — `FleetMapStore`'s own `TELEMETRY_LIMIT`. */
export const TELEMETRY_LIMIT = 200;

/** How often a streaming asset's telemetry is re-read while live is unavailable — `FleetMapStore`'s own `TELEMETRY_POLL_INTERVAL_MS`. */
export const TELEMETRY_POLL_INTERVAL_MS = 2_000;
