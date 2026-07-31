import type { Routes } from '@angular/router';
import { orgGuard } from '../../core/org/org-guard';

/**
 * `/manage/roster` (docs/UI-REDESIGN-PLAN.md Wave 4) — own lazy chunk, split per
 * vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3 (B8). Spread inside `app.routes.ts`'s
 * `authGuard`-wrapped children group like every other feature; `orgGuard` (`core/org/org-guard.ts`,
 * the same guard `/org` uses) adds the ADMIN/MANAGER role check on top — a pilot is redirected to
 * `/fly`. Replaces the `ComingSoon` scaffold `hubs.routes.ts` used to route this path to.
 */
export const ROSTER_ROUTES: Routes = [
  {
    path: 'manage/roster',
    title: 'Pilots / roster · Vision',
    canActivate: [orgGuard],
    loadComponent: () => import('./roster').then((m) => m.RosterPage),
  },
];
