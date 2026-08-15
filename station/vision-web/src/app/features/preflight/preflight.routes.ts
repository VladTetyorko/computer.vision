import type { Routes } from '@angular/router';

/**
 * `/operate/preflight` (docs/plans/done/UI-REDESIGN-PLAN.md Wave 4) — own lazy chunk, split per
 * vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3 (B8). Spread inside `app.routes.ts`'s
 * `authGuard`-wrapped children group — no extra role gate, mirrors `/fly`. Replaces the
 * `ComingSoon` scaffold `hubs.routes.ts` used to route this path to.
 */
export const PREFLIGHT_ROUTES: Routes = [
  {
    path: 'operate/preflight',
    title: 'Pre-flight checklist · Vision',
    loadComponent: () => import('./preflight').then((m) => m.PreflightPage),
  },
];
