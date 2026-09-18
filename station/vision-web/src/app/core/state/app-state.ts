import { makeEnvironmentProviders } from '@angular/core';
import { provideEffects } from '@ngrx/effects';
import { provideState, provideStore } from '@ngrx/store';

import { sidebarEffects } from '../shell/state/sidebar.effects';
import { sidebarHydrator } from '../shell/state/sidebar.hydration';
import { sidebarFeature } from '../shell/state/sidebar.reducer';
import { themeEffects } from '../shell/state/theme.effects';
import { themeHydrator } from '../shell/state/theme.hydration';
import { themeFeature } from '../shell/state/theme.reducer';
import { overlayEffects } from '../ui/state/overlay.effects';
import { overlayFeature } from '../ui/state/overlay.reducer';
import { hydrationMetaReducer } from './hydration';

/**
 * Every app-wide state slice, registered once (docs/plans/active/NGRX-MIGRATION-PLAN.md §2).
 * Page-scoped slices are **not** here — they live in their own feature's `*.routes.ts` `providers`,
 * so they load and tear down with the page.
 *
 * Exported as a function rather than inlined into `app.config.ts` so a spec that needs real state
 * registers the identical store, with the identical hydrators and runtime checks, instead of a
 * hand-rolled subset that can drift from what the app actually runs. Router wiring is deliberately
 * left out (see `app.config.ts`): `provideRouterStore` needs a `Router`, which most component specs
 * have no reason to provide — this is also why `overlay.effects.ts#closeOnNavigation$` listens for
 * `@ngrx/router-store`'s own `ROUTER_NAVIGATED` action rather than injecting `Router` directly: every
 * spec below that never provides a `Router` (`theme-facade.spec.ts`/`sidebar-facade.spec.ts`
 * included) must still be able to construct this state without a `NullInjectorError`.
 */
const HYDRATORS = [themeHydrator, sidebarHydrator];

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
    provideEffects(themeEffects, sidebarEffects, overlayEffects),
  ]);
}
