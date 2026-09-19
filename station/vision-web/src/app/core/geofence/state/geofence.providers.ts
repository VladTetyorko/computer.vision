import { makeEnvironmentProviders } from '@angular/core';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';

import { geofenceEffects } from './geofence.effects';
import { geofenceFeature } from './geofence.reducer';

/**
 * The `geofence` slice + its effects, registered by the routes that use it instead of at the root
 * injector (docs/plans/active/NGRX-MIGRATION-PLAN.md §9, waves N-split and N4). Consumed by the five map surfaces — `fly`, `command`, `crew`, `live` and `assets/:assetId`.
 *
 * Its facade is `@Injectable()` and listed in each of those pages' own `providers:`, which is what
 * makes the slice eligible to live here: facade and slice share one lifetime, so neither outlives
 * the other. **The consumers are not only the page facades** — every control inside
 * `<vision-map-tools>` (`shared/map/map-controls/**`) injects these facades directly too, which is
 * why `CrewSeatPage` provides them despite `CrewFacade` injecting none of them.
 *
 * Registering one feature from several routes is safe — NgRx keys feature state by name, and only
 * one of these routes is ever active at a time. Exported as a function for the same reason
 * `core/state/app-state.ts#provideAppState()` is: route and spec register the **identical** pair,
 * so a test can never exercise a slice wired differently from the one that ships.
 */
export function provideGeofenceState() {
  return makeEnvironmentProviders([provideState(geofenceFeature), provideEffects(geofenceEffects)]);
}
