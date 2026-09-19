import { makeEnvironmentProviders } from '@angular/core';
import { provideEffects } from '@ngrx/effects';
import { provideState, provideStore } from '@ngrx/store';

import { authEffects } from '../auth/state/auth.effects';
import { authFeature } from '../auth/state/auth.reducer';
import { geofenceEffects } from '../geofence/state/geofence.effects';
import { geofenceFeature } from '../geofence/state/geofence.reducer';
import { liveEffects } from '../live/state/live.effects';
import { liveFeature } from '../live/state/live.reducer';
import { mapEffects } from '../map/state/map.effects';
import { mapFeature } from '../map/state/map.reducer';
import { drawingsEffects } from '../map-data/state/drawings.effects';
import { drawingsFeature } from '../map-data/state/drawings.reducer';
import { layersEffects } from '../map-data/state/layers.effects';
import { layersFeature } from '../map-data/state/layers.reducer';
import { marksEffects } from '../map-data/state/marks.effects';
import { marksFeature } from '../map-data/state/marks.reducer';
import { routeEffects } from '../map-data/state/route.effects';
import { routeFeature } from '../map-data/state/route.reducer';
import { tracksEffects } from '../map-data/state/tracks.effects';
import { tracksFeature } from '../map-data/state/tracks.reducer';
import { sidebarEffects } from '../shell/state/sidebar.effects';
import { sidebarHydrator } from '../shell/state/sidebar.hydration';
import { sidebarFeature } from '../shell/state/sidebar.reducer';
import { themeEffects } from '../shell/state/theme.effects';
import { themeHydrator } from '../shell/state/theme.hydration';
import { themeFeature } from '../shell/state/theme.reducer';
import { orgEffects } from '../org/state/org.effects';
import { orgFeature } from '../org/state/org.reducer';
import { seatEffects } from '../seat/state/seat.effects';
import { seatFeature } from '../seat/state/seat.reducer';
import { settingsEffects } from '../settings/state/settings.effects';
import { settingsHydrator } from '../settings/state/settings.hydration';
import { settingsFeature } from '../settings/state/settings.reducer';
import { overlayEffects } from '../ui/state/overlay.effects';
import { overlayFeature } from '../ui/state/overlay.reducer';
import { hydrationMetaReducer } from './hydration';

/**
 * Every app-wide state slice, registered once (docs/plans/active/NGRX-MIGRATION-PLAN.md §2).
 * Page-scoped *facades* are **not** here — e.g. `SeatFacade` stays `@Injectable()`, listed in its
 * own host component's `providers:` array like `SeatStore` was, so a fresh instance still
 * starts/stops with the page. Its underlying `seatFeature` *slice* and effects, though, are
 * registered here like every other slice: NgRx feature state is global by name regardless of which
 * injector provides the facade that reads it, and the slice's own `Record<assetId, …>` keying (not
 * injector scoping) is what keeps two hosts from ever reading each other's asset.
 *
 * Exported as a function rather than inlined into `app.config.ts` so a spec that needs real state
 * registers the identical store, with the identical hydrators and runtime checks, instead of a
 * hand-rolled subset that can drift from what the app actually runs.
 *
 * **`provideRouterStore` is deliberately left out** (see `app.config.ts`): NgRx's router-state sync
 * needs a `Router`, which most component specs have no reason to provide. Two consequences, both
 * load-bearing and both established the hard way:
 *  - `overlay.effects.ts#closeOnNavigation$` listens for `@ngrx/router-store`'s own `ROUTER_NAVIGATED`
 *    action rather than injecting `Router`, so every spec that never provides one
 *    (`theme-facade.spec.ts`/`sidebar-facade.spec.ts` included) still constructs this state without a
 *    `NullInjectorError`.
 *  - The auth slice's `logoutSideEffects$` *does* inject a plain `Router` for its post-logout
 *    redirect, and that is fine: Angular's own test environment supplies a real, un-navigated
 *    `Router` with no explicit `provideRouter(...)` — confirmed empirically for wave N2, not assumed.
 */
const HYDRATORS = [themeHydrator, sidebarHydrator, settingsHydrator];

export function provideAppState() {
  return makeEnvironmentProviders([
    // All four runtime checks stay on: this app's state is plain data end to end, so a mutation or a
    // non-serializable value is a defect, not a trade-off (NGRX-MIGRATION-PLAN §3 rule 11).
    provideStore(
      {},
      {
        metaReducers: [hydrationMetaReducer(HYDRATORS)],
        runtimeChecks: {
          strictStateImmutability: true,
          strictActionImmutability: true,
          strictStateSerializability: true,
          strictActionSerializability: true,
        },
      },
    ),
    provideState(themeFeature),
    provideState(sidebarFeature),
    provideState(overlayFeature),
    provideState(settingsFeature),
    provideState(orgFeature),
    provideState(seatFeature),
    provideState(authFeature),
    provideState(liveFeature),
    provideState(geofenceFeature),
    provideState(layersFeature),
    provideState(marksFeature),
    provideState(drawingsFeature),
    provideState(tracksFeature),
    provideState(routeFeature),
    provideState(mapFeature),
    provideEffects(
      themeEffects,
      sidebarEffects,
      overlayEffects,
      settingsEffects,
      orgEffects,
      seatEffects,
      authEffects,
      liveEffects,
      geofenceEffects,
      layersEffects,
      marksEffects,
      drawingsEffects,
      tracksEffects,
      routeEffects,
      mapEffects,
    ),
  ]);
}
