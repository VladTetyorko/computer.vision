import type { Routes } from '@angular/router';

/**
 * The `/wall` route (the many-tiles overview). Split into its own file per
 * vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3 (B8) — see `features/fly/fly.routes.ts`'s doc
 * comment for why.
 */
export const WALL_ROUTES: Routes = [
  {
    path: 'wall',
    title: 'Wall · Vision',
    loadComponent: () => import('./wall').then((m) => m.WallPage),
  },
];
