import type { AssetScopedTransport } from '../../live/live-fallback-logic';
import type { CorrectionResponse } from '../../api/models';

/**
 * One `GeoFacade` host's own session (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N7, replacing
 * `GeoStore`) — keyed by a synthetic `hostId` in {@link GeoState.byHostId}, exactly `WeatherState`'s
 * own `byHostId` posture (`core/weather/state/weather.model.ts`): today only `CockpitPage` provides
 * `GeoFacade`, but keying by a per-instance `hostId` rather than by `assetId` means two independent
 * hosts can never collide even if that ever changes.
 *
 * `pollResult`/the live projection are deliberately asymmetric: `pollResult` is state (this slice
 * owns fetching it), while the live value is **never copied in here** — `GeoFacade.latest` reads it
 * straight from `LiveFacade.geoFor(assetId)` at render time (see that class's own doc comment) — so
 * this state only needs to remember *which* transport is current, not a duplicate of the live value.
 */
export interface GeoHostState {
  /** The asset this host is currently tracking, or `undefined` while idle (after `reset()`, or before the first `track()`). */
  readonly assetId: string | undefined;
  /** Which source `GeoFacade.latest` should prefer right now — `'poll'` while idle, too, so a fresh `track()` always starts from a known state. */
  readonly transport: AssetScopedTransport;
  /** Kept fresh by the 2s poll while `transport === 'poll'`; stale/unused while `'live'` — mirrors `GeoStore#pollResultSignal`. */
  readonly pollResult: CorrectionResponse | undefined;
  /** Sticky once a poll observes the D9 flag-off 409 — see `GeoStore#disabledSignal`'s own doc comment for why this never resets except on a fresh `track()`/`reset()`. */
  readonly disabled: boolean;
}

export const initialGeoHostState: GeoHostState = {
  assetId: undefined,
  transport: 'poll',
  pollResult: undefined,
  disabled: false,
};

export interface GeoState {
  readonly byHostId: Readonly<Record<string, GeoHostState>>;
}

export const initialGeoState: GeoState = { byHostId: {} };
