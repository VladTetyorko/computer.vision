import type { Routes } from '@angular/router';
import { orgGuard } from '../../core/org/org-guard';

/**
 * The onboarding wizard's own route (docs/plans/done/UX-REWORK-PLAN.md §U-d) — replaces the inline "+ Add
 * source" card the Devices/Warehouse page used to open on itself (`features/devices/devices.routes.ts`).
 * Split into its own file per vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3 (B8) — see
 * `features/fly/fly.routes.ts`'s doc comment for why. `orgGuard` (`core/org/org-guard.ts`) added per
 * docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 wave W1 rule 5 — the nav entry (`nav-entries.ts`'s "Add
 * vehicle", fleet group) is `managerOnly`, so the route now agrees.
 */
export const ONBOARDING_ROUTES: Routes = [
  {
    path: 'add-source',
    title: 'Add a source · Vision',
    canActivate: [orgGuard],
    loadComponent: () => import('./onboarding').then((m) => m.OnboardingPage),
  },
];
