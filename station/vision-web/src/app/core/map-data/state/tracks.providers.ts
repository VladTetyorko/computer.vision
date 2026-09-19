import { makeEnvironmentProviders } from '@angular/core';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';

import { tracksEffects } from './tracks.effects';
import { tracksFeature } from './tracks.reducer';

/**
 * The `tracks` slice + its effects, registered by the routes that use it instead of at the root
 * injector (docs/plans/done/NGRX-MIGRATION-PLAN.md §9, waves N-split and N4). Consumed by `assets/:assetId` alone — `AssetDetailFacade` is its only injector.
 *
 * Its facade is `@Injectable()` and listed in `AssetDetailPage`'s own `providers:`, which is what makes
 * the slice eligible to live here: facade and slice share one lifetime, so neither outlives the
 * other.
 *
 * Registering one feature from several routes is safe — NgRx keys feature state by name, and only
 * one of these routes is ever active at a time. Exported as a function for the same reason
 * `core/state/app-state.ts#provideAppState()` is: route and spec register the **identical** pair,
 * so a test can never exercise a slice wired differently from the one that ships.
 */
export function provideTracksState() {
  return makeEnvironmentProviders([provideState(tracksFeature), provideEffects(tracksEffects)]);
}
