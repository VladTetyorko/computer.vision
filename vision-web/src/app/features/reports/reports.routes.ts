import type { Routes } from '@angular/router';

/**
 * `/manage/reports` (docs/UI-REDESIGN-PLAN.md Wave 4) — own lazy chunk, split per
 * vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3 (B8). Spread inside `app.routes.ts`'s
 * `authGuard`-wrapped children group — no extra role gate, mirrors `/command`. Replaces the
 * `ComingSoon` scaffold `hubs.routes.ts` used to route this path to.
 */
export const REPORTS_ROUTES: Routes = [
  {
    path: 'manage/reports',
    title: 'Inventory reports · Vision',
    loadComponent: () => import('./reports').then((m) => m.ReportsPage),
  },
];
