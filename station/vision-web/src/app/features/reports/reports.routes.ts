import type { Routes } from '@angular/router';
import { orgGuard } from '../../core/org/org-guard';

/**
 * `/manage/reports` (docs/plans/done/UI-REDESIGN-PLAN.md Wave 4) — own lazy chunk, split per
 * vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3 (B8). Spread inside `app.routes.ts`'s
 * `authGuard`-wrapped children group. `orgGuard` (`core/org/org-guard.ts`) added per
 * docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 wave W1 rule 5 — the nav entry (`nav-entries.ts`'s
 * "Inventory reports", fleet group) is `managerOnly`, so the route now agrees (this withdraws the
 * "no extra role gate, mirrors `/command`" note this doc comment used to carry — `/command` itself
 * stays ungated, this page does not). Replaces the `ComingSoon` scaffold `hubs.routes.ts` used to
 * route this path to.
 */
export const REPORTS_ROUTES: Routes = [
  {
    path: 'manage/reports',
    title: 'Inventory reports · Vision',
    canActivate: [orgGuard],
    loadComponent: () => import('./reports').then((m) => m.ReportsPage),
  },
];
