import { makeEnvironmentProviders } from '@angular/core';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';

import { trainingEffects } from './training.effects';
import { trainingFeature } from './training.reducer';

/**
 * The `training` slice + its effects, registered by the route that uses it instead of at the root
 * injector (docs/plans/active/NGRX-MIGRATION-PLAN.md §9, wave N-split). Consumed by `manage/training` (`DatasetsFacade`) and `assets/:assetId/replay/:usageId` (`ReplayFacade`).
 *
 * Its facade (`core/training/training-facade.ts`) is `@Injectable()` and listed in `DatasetsPage`’s and `ReplayPage`’s own `providers:` — that is
 * what makes this slice eligible to live here rather than in `core/state/app-state.ts`: facade and
 * slice now share one lifetime, so neither can outlive the other and read a removed feature. See
 * `provideAppState()`'s own doc comment for the rule in full.
 *
 * Exported as a function for the same reason `provideAppState()` is: the route and every spec that
 * needs real `training` state register the **identical** pair, so a test can never exercise a slice
 * wired differently from the one that ships.
 */
export function provideTrainingState() {
  return makeEnvironmentProviders([provideState(trainingFeature), provideEffects(trainingEffects)]);
}
