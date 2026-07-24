import type { Routes } from '@angular/router';

/**
 * The onboarding wizard's own route (docs/UX-REWORK-PLAN.md §U-d) — replaces the inline "+ Add
 * source" card the Devices/Warehouse page used to open on itself (`features/devices/devices.routes.ts`).
 * Split into its own file per vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3 (B8) — see
 * `features/fly/fly.routes.ts`'s doc comment for why.
 */
export const ONBOARDING_ROUTES: Routes = [
  {
    path: 'add-source',
    title: 'Add a source · Vision',
    loadComponent: () => import('./onboarding').then((m) => m.OnboardingPage),
  },
];
