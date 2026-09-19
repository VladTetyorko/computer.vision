import { makeEnvironmentProviders } from '@angular/core';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';

import { orgEffects } from './org.effects';
import { orgFeature } from './org.reducer';

/**
 * The `org` slice + its effects, registered by the routes that use it instead of at the root
 * injector (docs/plans/active/NGRX-MIGRATION-PLAN.md §9, waves N-split and N4). Consumed by the five map surfaces (through `<vision-layer-manager>`) and `manage/roster`.
 *
 * Its facade is `@Injectable()` and listed in each host page's own `providers:` — the five map pages
 * plus `OrgSettingsPage`, which is not routed itself but embedded in `CrewPage` at `manage/roster`
 * (WAREHOUSE-UX-PLAN.md §4 wave W7; `/org` is now only a guard that redirects there). **`org-guard.ts`
 * injects `AuthFacade`, not `OrgFacade`** — checked, and it is what would have forced this slice to
 * stay root, since that guard runs on routes reachable before any of these pages load.
 *
 * Registering one feature from several routes is safe — NgRx keys feature state by name, and only
 * one of these routes is ever active at a time. Exported as a function for the same reason
 * `core/state/app-state.ts#provideAppState()` is: route and spec register the **identical** pair,
 * so a test can never exercise a slice wired differently from the one that ships.
 */
export function provideOrgState() {
  return makeEnvironmentProviders([provideState(orgFeature), provideEffects(orgEffects)]);
}
