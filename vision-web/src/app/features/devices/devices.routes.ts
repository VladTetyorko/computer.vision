import type { Routes } from '@angular/router';

/**
 * The `/devices` route. Split into its own file per vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3
 * (B8) — see `features/fly/fly.routes.ts`'s doc comment for why.
 */
export const DEVICES_ROUTES: Routes = [
  {
    path: 'devices',
    title: 'Devices · Vision',
    loadComponent: () => import('./devices').then((m) => m.DevicesPage),
  },
];
