import { makeEnvironmentProviders } from '@angular/core';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';

import { groundingEffects } from './grounding.effects';
import { groundingFeature } from './grounding.reducer';

/**
 * The `grounding` slice + its effect, registered by the `/fly` route rather than at the root
 * injector (docs/plans/active/NGRX-MIGRATION-PLAN.md §9). `GroundingFacade` is page-provided by
 * `CockpitPage`, which is what makes the slice eligible to live here — see
 * `core/state/app-state.ts`'s own doc comment for why a `providedIn: 'root'` facade's slice may not.
 *
 * Exported as a function for the same reason every other `provide<Domain>State()` is: the route and
 * every spec needing real `grounding` state register the identical pair.
 */
export function provideGroundingState() {
  return makeEnvironmentProviders([provideState(groundingFeature), provideEffects(groundingEffects)]);
}
