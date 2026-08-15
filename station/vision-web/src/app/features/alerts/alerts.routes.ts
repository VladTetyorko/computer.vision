import type { Routes } from '@angular/router';

/**
 * `/monitor/alerts` (docs/plans/done/UI-REDESIGN-PLAN.md Wave 4) — own lazy chunk, split per
 * vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3 (B8). Spread inside `app.routes.ts`'s
 * `authGuard`-wrapped children group like every other feature — no extra role gate, mirrors `/wall`.
 * Replaces the `ComingSoon` scaffold `hubs.routes.ts` used to route this path to.
 */
export const ALERTS_ROUTES: Routes = [
  {
    path: 'monitor/alerts',
    title: 'Alerts center · Vision',
    loadComponent: () => import('./alerts').then((m) => m.AlertsPage),
  },
];
