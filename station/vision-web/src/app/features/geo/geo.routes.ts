import type { Routes } from '@angular/router';
import { orgGuard } from '../../core/org/org-guard';

/**
 * `/manage/geo/regions` (docs/plans/active/VISUAL-GEO-V2-PLAN.md §3.8, wave H6) — own lazy chunk, split per
 * vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3. Spread inside `app.routes.ts`'s
 * `authGuard`-wrapped children group like every other feature; `orgGuard` (`core/org/org-guard.ts`,
 * the same guard `/manage/roster` uses) gates the **whole page**, not just create/delete — unlike
 * `/manage/training` (open to any user who can see a dataset), viewing the region list has no value
 * to a non-manager, so a pilot is redirected to `/fly` before the page even loads.
 */
export const GEO_ROUTES: Routes = [
  {
    path: 'manage/geo/regions',
    title: 'Geo regions · Vision',
    canActivate: [orgGuard],
    loadComponent: () => import('./region-manager').then((m) => m.RegionManagerPage),
  },
];
