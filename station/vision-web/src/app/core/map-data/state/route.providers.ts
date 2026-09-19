import { makeEnvironmentProviders } from '@angular/core';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';

import { routeEffects } from './route.effects';
import { routeFeature } from './route.reducer';

/**
 * The `route` slice + its effects, registered by the route that uses it instead of at the root
 * injector (docs/plans/active/NGRX-MIGRATION-PLAN.md §9, wave N-split). Consumed by `command` — the asset-panel route span, its only consumer.
 *
 * Exported as a function for the same reason `core/state/app-state.ts#provideAppState()` is: the
 * route and every spec that needs real `route` state register the **identical** pair, so a test can
 * never exercise a slice wired differently from the one that ships. See `provideAppState()`'s own
 * doc comment for why this slice is not registered there, and what makes a slice eligible to move.
 */
export function provideRouteState() {
  return makeEnvironmentProviders([provideState(routeFeature), provideEffects(routeEffects)]);
}
