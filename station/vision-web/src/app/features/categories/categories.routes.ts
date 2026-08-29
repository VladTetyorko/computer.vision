import type { Routes } from '@angular/router';
import { orgGuard } from '../../core/org/org-guard';

/**
 * `/manage/categories` (docs/plans/done/UI-REDESIGN-PLAN.md Wave 4) — own lazy chunk, split per
 * vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3 (B8). Spread inside `app.routes.ts`'s
 * `authGuard`-wrapped children group. `orgGuard` (`core/org/org-guard.ts`) added per
 * docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 wave W1 rule 5 — the nav entry (`nav-entries.ts`'s "Asset
 * categories", fleet group) is `managerOnly`, so the route now agrees (this withdraws the "no extra
 * role gate, mirrors `/assets`" note this doc comment used to carry — `/assets` itself stays
 * ungated, this child page does not). Replaces the `ComingSoon` scaffold `hubs.routes.ts` used to
 * route this path to.
 */
export const CATEGORIES_ROUTES: Routes = [
  {
    path: 'manage/categories',
    title: 'Asset categories · Vision',
    canActivate: [orgGuard],
    loadComponent: () => import('./categories').then((m) => m.CategoriesPage),
  },
];
