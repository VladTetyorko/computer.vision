import type { Routes } from '@angular/router';

import { provideDiscoveryState } from '../../core/discovery/state/discovery.providers';
import { provideOnboardingState } from './state/onboarding.providers';

/**
 * The onboarding wizard (docs/plans/done/UX-REWORK-PLAN.md §U-d) — replaces the inline "+ Add
 * source" card the Devices/Warehouse page used to open on itself. Behind `onboarding.routes.ts`'s
 * `loadChildren` boundary, which also owns the `orgGuard`.
 *
 * `OnboardingWizardFacade` (provided by `OnboardingPage`) injects `DiscoveryInboxFacade`, so the
 * `discoveryInbox` slice is registered here rather than in `core/state/app-state.ts` — wave N-split,
 * docs/plans/done/NGRX-MIGRATION-PLAN.md §9. `/assets` registers the identical slice on its own
 * route; see the facade's doc comment for what stops being shared between the two.
 *
 * `provideOnboardingState()` (wave N8b) registers the wizard's own `onboardingWizard` slice + effects
 * + `OnboardingPhotoBuffer` alongside it — see that function's own doc comment for why
 * `OnboardingPhotoBuffer` has to live at this environment-injector level rather than on
 * `OnboardingPage`'s own component `providers:`.
 */
export const ONBOARDING_PAGE_ROUTES: Routes = [
  {
    path: '',
    title: 'Add vehicle · Vision',
    providers: [provideDiscoveryState(), provideOnboardingState()],
    loadComponent: () => import('./onboarding').then((m) => m.OnboardingPage),
  },
];
