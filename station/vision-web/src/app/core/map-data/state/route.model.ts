import type { AssetRoute } from '../route-logic';

/** `RouteState.loadState`'s own tri-state — `AssetPanel`'s Telemetry tab reads this, never a raw try/catch. */
export type RouteLoadState = 'idle' | 'loading' | 'loaded' | 'error';

/**
 * The `route` slice's entire state (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N6) — replacing
 * `RouteStore`'s own four signals. Registered app-wide in `provideAppState()` like every other
 * slice, but read/written only through the page-provided {@link RouteFacade} (see that class's own
 * doc comment for why the slice being global doesn't make the *feature* global): `CommandPage` is
 * this app's only host today, one instance at a time, so there is no cross-host leakage risk this
 * state shape needs to guard against — unlike `seat`'s `Record<assetId, …>` keying.
 *
 * `noUsages` is `true` only once a `show()` call resolved and found zero recorded usages for the
 * asset — kept as its own flag, not derived from `routes.length === 0`, because a `loaded` empty
 * list can also mean "this asset's one usage recorded no GPS fix", which is a different message
 * (`RouteFacade`'s own doc comment / `route-logic.ts#buildAssetRoute`).
 */
export interface RouteState {
  readonly routes: readonly AssetRoute[];
  readonly loadState: RouteLoadState;
  readonly noUsages: boolean;
}

export const initialRouteState: RouteState = { routes: [], loadState: 'idle', noUsages: false };
