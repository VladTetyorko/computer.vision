import { makeEnvironmentProviders } from '@angular/core';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';

import { onboardingEffects } from './onboarding.effects';
import { onboardingWizardFeature } from './onboarding.reducer';
import { OnboardingPhotoBuffer } from '../onboarding-photo-buffer';

/**
 * The `onboardingWizard` slice + its effects + `OnboardingPhotoBuffer`, registered on
 * `onboarding.page-routes.ts` rather than the root injector (docs/plans/active/
 * NGRX-MIGRATION-PLAN.md §9, wave N8b) — wizard state has no reason to survive leaving
 * `/add-source`, exactly like `provideDiscoveryState()` alongside it on the same route.
 *
 * `OnboardingPhotoBuffer` is bundled in here, not into `OnboardingPage`'s own component
 * `providers:`, because `onboarding.effects.ts#uploadAssetImage$` injects it directly (it is a plain
 * service, not a facade — legal for an effect per §3 rule 4) — an `@ngrx/effects` class is
 * constructed against the **environment** injector `provideEffects()` registers into, which cannot
 * see a component-level (`@Component({ providers: […] })`) provider. Registering it here instead
 * means both the effect and `OnboardingWizardFacade` (element-injector-provided on `OnboardingPage`,
 * which falls back to this route's environment injector) resolve the same instance.
 *
 * Exported as a function for the same reason `provideDiscoveryState()`/`provideAppState()` are: the
 * route and every spec that needs real `onboardingWizard` state register the **identical** pair.
 */
export function provideOnboardingState() {
  return makeEnvironmentProviders([provideState(onboardingWizardFeature), provideEffects(onboardingEffects), OnboardingPhotoBuffer]);
}
