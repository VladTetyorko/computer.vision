import type { Routes } from '@angular/router';

/**
 * The `/settings` route. Split into its own file per vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3
 * (B8) — see `features/fly/fly.routes.ts`'s doc comment for why.
 */
export const SETTINGS_ROUTES: Routes = [
  {
    path: 'settings',
    title: 'Settings · Vision',
    loadComponent: () => import('./settings').then((m) => m.SettingsPage),
  },
];
