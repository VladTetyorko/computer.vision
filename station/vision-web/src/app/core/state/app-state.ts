import { makeEnvironmentProviders } from '@angular/core';
import { provideEffects } from '@ngrx/effects';
import { provideState, provideStore } from '@ngrx/store';

import { authEffects } from '../auth/state/auth.effects';
import { authFeature } from '../auth/state/auth.reducer';
import { liveEffects } from '../live/state/live.effects';
import { liveFeature } from '../live/state/live.reducer';
import { sidebarEffects } from '../shell/state/sidebar.effects';
import { sidebarHydrator } from '../shell/state/sidebar.hydration';
import { sidebarFeature } from '../shell/state/sidebar.reducer';
import { themeEffects } from '../shell/state/theme.effects';
import { themeHydrator } from '../shell/state/theme.hydration';
import { themeFeature } from '../shell/state/theme.reducer';
import { settingsEffects } from '../settings/state/settings.effects';
import { settingsHydrator } from '../settings/state/settings.hydration';
import { settingsFeature } from '../settings/state/settings.reducer';
import { overlayEffects } from '../ui/state/overlay.effects';
import { overlayFeature } from '../ui/state/overlay.reducer';
import { eventsEffects } from '../events/state/events.effects';
import { eventsFeature } from '../events/state/events.reducer';
import { fleetEffects } from '../fleet/state/fleet.effects';
import { fleetFeature } from '../fleet/state/fleet.reducer';
import { systemStatusEffects } from '../system-status/state/system-status.effects';
import { systemStatusFeature } from '../system-status/state/system-status.reducer';
import { hydrationMetaReducer } from './hydration';

/**
 * Every **root** state slice, registered once (docs/plans/done/NGRX-MIGRATION-PLAN.md §2, and §9
 * for wave N-split). "Root" means reachable before any lazy route loads: `app.ts` itself, the five
 * always-on shell components (`app-sidebar`/`notification-bell`/`identity-chip` included), and every
 * `providedIn: 'root'` service or guard. Those **7** slices — `theme`, `sidebar`, `overlay`,
 * `settings`, `auth`, `live`, `events` — shipped in the initial bundle from wave N-split onward because
 * they genuinely must, and were exactly the seven NGRX-MIGRATION-PLAN.md §9 predicted before the
 * split was attempted. **Wave N4b adds two more: `fleet` and `systemStatus`.** Both are read by
 * `app.ts`/`shared/ui/app-sidebar/app-sidebar.ts`/`shared/ui/notification-bell.ts` — components that
 * render before any lazy route resolves — so neither is eligible for the page-scoped
 * `provide<Domain>State()` treatment described two paragraphs down; see `fleet-facade.ts`'s and
 * `system-status-facade.ts`'s own class docs for the specific always-on reader each must serve.
 * `core/system-events/` deliberately contributes **no** slice at all — see
 * `system-events-facade.ts`'s own class doc for why (NGRX-MIGRATION-PLAN.md §4's "N4 fleet: 3 slices"
 * is corrected by that doc comment, not this one).
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
 * feature NgRx has already removed. Ten facades have been moved that way, each only once it was
 * clear that **every** class injecting it already sat behind a lazy route: `ThresholdsFacade`,
 * `ControlProfileFacade`, `TrainingFacade` and `DiscoveryInboxFacade` in wave N-split, then
 * `MarksFacade`, `LayersFacade`, `DrawingsFacade`, `TracksFacade`, `GeofenceFacade` and `OrgFacade`
 * in wave N4. Each is `@Injectable()` in its host page's `providers:` and carries in its own doc
 * comment the one behaviour that changes when a slice loads per page rather than per session — read
 * that before moving an eleventh, and never move one as a side effect of chasing bytes.
 *
 * **Consumer-counting is what makes this go wrong, twice over.** `OrgFacade` looked root-bound
 * because `shared/map/map-controls/layer-manager.ts` injects it — but that control only ever renders
 * inside a map, and every map sits behind a lazy route; what actually decides it is whether a
 * *root-reachable* injector exists (here: does `org-guard.ts` inject `OrgFacade`? It does not — it
 * injects `AuthFacade`). In the other direction, `CrewSeatPage` provides all five map facades while
 * `CrewFacade` injects none of them: its **template** renders `<vision-map-tools>`, whose children
 * inject them directly. A grep for `inject(XFacade)` finds the first case and misses the second, and
 * the second fails at runtime with a `NullInjectorError` that neither `tsc` nor the specs catch.
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
    provideState(authFeature),
    provideState(liveFeature),
    provideState(eventsFeature),
    provideState(fleetFeature),
    provideState(systemStatusFeature),
    provideEffects(
      themeEffects,
      sidebarEffects,
      overlayEffects,
      settingsEffects,
      authEffects,
      liveEffects,
      eventsEffects,
      fleetEffects,
      systemStatusEffects,
    ),
  ]);
}
