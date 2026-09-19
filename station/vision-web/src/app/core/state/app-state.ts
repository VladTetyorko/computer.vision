import { makeEnvironmentProviders } from '@angular/core';
import { provideEffects } from '@ngrx/effects';
import { provideState, provideStore } from '@ngrx/store';

import { authEffects } from '../auth/state/auth.effects';
import { authFeature } from '../auth/state/auth.reducer';
import { liveEffects } from '../live/state/live.effects';
import { liveFeature } from '../live/state/live.reducer';
import { geofenceEffects } from '../geofence/state/geofence.effects';
import { geofenceFeature } from '../geofence/state/geofence.reducer';
import { drawingsEffects } from '../map-data/state/drawings.effects';
import { drawingsFeature } from '../map-data/state/drawings.reducer';
import { layersEffects } from '../map-data/state/layers.effects';
import { layersFeature } from '../map-data/state/layers.reducer';
import { marksEffects } from '../map-data/state/marks.effects';
import { marksFeature } from '../map-data/state/marks.reducer';
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
import { settingsEffects } from '../settings/state/settings.effects';
import { settingsHydrator } from '../settings/state/settings.hydration';
import { settingsFeature } from '../settings/state/settings.reducer';
import { overlayEffects } from '../ui/state/overlay.effects';
import { overlayFeature } from '../ui/state/overlay.reducer';
import { eventsEffects } from '../events/state/events.effects';
import { eventsFeature } from '../events/state/events.reducer';
import { hydrationMetaReducer } from './hydration';

/**
 * Every **root** state slice, registered once (docs/plans/active/NGRX-MIGRATION-PLAN.md §2, and §9
 * for wave N-split). "Root" means reachable before any lazy route loads: `app.ts` itself, the five
 * always-on shell components (`app-sidebar`/`notification-bell`/`identity-chip` included), and every
 * `providedIn: 'root'` service or guard. Those 13 slices ship in the initial bundle because they
 * genuinely must.
 *
 * **A page-scoped slice is registered by its route instead**, through its own
 * `core/<domain>/state/<domain>.providers.ts#provide<Domain>State()` — see
 * `features/fly/fly.page-routes.ts` for the shape. That is what keeps `/fly`-only state out of the
 * bundle a visitor to `/settings` downloads; before N-split all 26 slices loaded for everyone.
 *
 * **The test for whether a slice may move is its facade's injectability, not its consumer count.**
 * A page-provided facade (`@Injectable()`, listed in its host component's `providers:`) shares one
 * lifetime with the route that registers the slice, so both appear and disappear together. A
 * `providedIn: 'root'` facade does not: it outlives the route, and would then read selectors of a
 * feature NgRx has already removed. `MarksFacade`/`LayersFacade`/`DrawingsFacade`/`TracksFacade`/
 * `GeofenceFacade`/`OrgFacade` are root-provided and therefore stay here, however few features read
 * them. Making one of them page-provided first is what would let its slice move — do that
 * deliberately, never as a side effect of chasing bytes.
 *
 * Four facades were moved that way in wave N-split itself, once it was clear that **every** class
 * injecting each of them already sat behind a lazy route: `ThresholdsFacade` (`fly`),
 * `ControlProfileFacade` (`fly`, `manage/controller`), `TrainingFacade` (`manage/training`,
 * `assets/:assetId/replay/:usageId`) and `DiscoveryInboxFacade` (`assets`, `add-source`). Each is now
 * `@Injectable()` in its host page's `providers:`, and each carries in its own doc comment the one
 * behaviour that changes when a slice is loaded per page rather than per session — read that before
 * moving a fifth. `OrgFacade` is the one that failed the test and stayed: `shared/map/map-controls/
 * layer-manager.ts` injects it, and that control renders on several map surfaces at once.
 *
 * Note that `seatFeature`'s own `Record<assetId, …>` keying (not injector scoping) is still what
 * keeps two hosts from reading each other's asset — moving where the slice is *registered* changes
 * nothing about that.
 *
 * Exported as a function rather than inlined into `app.config.ts` so a spec that needs real state
 * registers the identical store, with the identical hydrators and runtime checks, instead of a
 * hand-rolled subset that can drift from what the app actually runs. A spec exercising a page-scoped
 * slice adds that slice's own `provide<Domain>State()` alongside this, exactly as its route does.
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
    provideState(authFeature),
    provideState(liveFeature),
    provideState(eventsFeature),
    provideState(geofenceFeature),
    provideState(layersFeature),
    provideState(marksFeature),
    provideState(drawingsFeature),
    provideState(tracksFeature),
    provideEffects(
      themeEffects,
      sidebarEffects,
      overlayEffects,
      settingsEffects,
      orgEffects,
      authEffects,
      liveEffects,
      eventsEffects,
      geofenceEffects,
      layersEffects,
      marksEffects,
      drawingsEffects,
      tracksEffects,
    ),
  ]);
}
