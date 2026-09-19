import { makeEnvironmentProviders } from '@angular/core';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';

import { weatherEffects } from './weather.effects';
import { weatherFeature } from './weather.reducer';

/**
 * The `weather` slice + its effects, registered by the route that uses it instead of at the root
 * injector (docs/plans/active/NGRX-MIGRATION-PLAN.md §9, wave N-split). Consumed by `fly` and `command`.
 *
 * Exported as a function for the same reason `core/state/app-state.ts#provideAppState()` is: the
 * route and every spec that needs real `weather` state register the **identical** pair, so a test can
 * never exercise a slice wired differently from the one that ships. See `provideAppState()`'s own
 * doc comment for why this slice is not registered there, and what makes a slice eligible to move.
 */
export function provideWeatherState() {
  return makeEnvironmentProviders([provideState(weatherFeature), provideEffects(weatherEffects)]);
}
