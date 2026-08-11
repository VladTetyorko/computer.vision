import type { Routes } from '@angular/router';

/**
 * The `/live/:deviceId` route (single-device cockpit, drill-in only). Split into its own file per
 * vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3 (B8) — see `features/fly/fly.routes.ts`'s doc
 * comment for why.
 */
export const LIVE_ROUTES: Routes = [
  {
    path: 'live/:deviceId',
    title: 'Live · Vision',
    loadComponent: () => import('./live').then((m) => m.LivePage),
  },
];
