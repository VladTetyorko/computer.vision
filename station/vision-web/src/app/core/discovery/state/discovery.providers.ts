import { makeEnvironmentProviders } from '@angular/core';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';

import { discoveryEffects } from './discovery.effects';
import { discoveryInboxFeature } from './discovery.reducer';

/**
 * The `discoveryInbox` slice + its effects, registered by the route that uses it instead of at the root
 * injector (docs/plans/active/NGRX-MIGRATION-PLAN.md §9, wave N-split). Consumed by `assets` (`FoundDevices`) and `add-source` (`OnboardingStore`).
 *
 * Its facade (`core/discovery/discovery-inbox-facade.ts`) is `@Injectable()` and listed in `InventoryPage`’s and `OnboardingPage`’s own `providers:` — that is
 * what makes this slice eligible to live here rather than in `core/state/app-state.ts`: facade and
 * slice now share one lifetime, so neither can outlive the other and read a removed feature. See
 * `provideAppState()`'s own doc comment for the rule in full.
 *
 * Exported as a function for the same reason `provideAppState()` is: the route and every spec that
 * needs real `discoveryInbox` state register the **identical** pair, so a test can never exercise a slice
 * wired differently from the one that ships.
 */
export function provideDiscoveryState() {
  return makeEnvironmentProviders([provideState(discoveryInboxFeature), provideEffects(discoveryEffects)]);
}
