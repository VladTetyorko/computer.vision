import { makeEnvironmentProviders } from '@angular/core';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';

import { telemetryEffects } from './telemetry.effects';
import { telemetryFeature } from './telemetry.reducer';

/**
 * The `telemetry` slice + its effects, registered by the route that uses it instead of at the root
 * injector (docs/plans/done/NGRX-MIGRATION-PLAN.md §9, wave N-split). Consumed by `fly`, `crew`, `live` and `asset-detail`.
 *
 * Exported as a function for the same reason `core/state/app-state.ts#provideAppState()` is: the
 * route and every spec that needs real `telemetry` state register the **identical** pair, so a test can
 * never exercise a slice wired differently from the one that ships. See `provideAppState()`'s own
 * doc comment for why this slice is not registered there, and what makes a slice eligible to move.
 */
export function provideTelemetryState() {
  return makeEnvironmentProviders([provideState(telemetryFeature), provideEffects(telemetryEffects)]);
}
