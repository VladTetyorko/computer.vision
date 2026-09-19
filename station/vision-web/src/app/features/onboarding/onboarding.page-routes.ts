import type { Routes } from '@angular/router';

import { provideDiscoveryState } from '../../core/discovery/state/discovery.providers';

/**
 * The onboarding wizard (docs/plans/done/UX-REWORK-PLAN.md §U-d) — replaces the inline "+ Add
 * source" card the Devices/Warehouse page used to open on itself. Behind `onboarding.routes.ts`'s
 * `loadChildren` boundary, which also owns the `orgGuard`.
 *
 * `OnboardingStore` (provided by `OnboardingPage`) injects `DiscoveryInboxFacade`, so the
 * `discoveryInbox` slice is registered here rather than in `core/state/app-state.ts` — wave N-split,
 * docs/plans/active/NGRX-MIGRATION-PLAN.md §9. `/assets` registers the identical slice on its own
 * route; see the facade's doc comment for what stops being shared between the two.
 */
export const ONBOARDING_PAGE_ROUTES: Routes = [
  {
    path: '',
    title: 'Add vehicle · Vision',
    providers: [provideDiscoveryState()],
    loadComponent: () => import('./onboarding').then((m) => m.OnboardingPage),
  },
];
