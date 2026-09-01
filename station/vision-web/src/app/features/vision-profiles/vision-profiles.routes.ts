import type { Routes } from '@angular/router';
import { orgGuard } from '../../core/org/org-guard';

/**
 * `/vision/profiles` (docs/plans/active/CV-SETTINGS-PLAN.md §4, wave W6) — own lazy chunk, matching
 * every other routed feature (`models.routes.ts`'s own doc comment). `orgGuard` (`core/org/org-guard.ts`)
 * per docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 rule 6 — every VISION nav entry is `managerOnly`
 * (`nav-entries.ts`), so its route carries the matching `canActivate: [orgGuard]` gate.
 *
 * `/settings/detection` redirects here (`settings.routes.ts`) — the old single-org "detection
 * defaults" page this replaces.
 */
export const VISION_PROFILES_ROUTES: Routes = [
  {
    path: 'vision/profiles',
    title: 'Profiles · Vision',
    canActivate: [orgGuard],
    loadComponent: () => import('./vision-profiles').then((m) => m.VisionProfilesPage),
  },
];
