import type { Routes } from '@angular/router';

/**
 * `/settings` and `/settings/detection` — split per docs/NAV-IA-REDESIGN-PLAN.md §2.5,
 * docs/design/11-settings.md (Wave 4's F7 fix: "one destination, several names and doors"). Both
 * routes still live in one file per vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3 (B8) — see
 * `features/fly/fly.routes.ts`'s doc comment for why — the split is between the two *page*
 * components (`account-settings.ts`/`detection-settings.ts`), not the route file.
 */
export const SETTINGS_ROUTES: Routes = [
  {
    path: 'settings',
    title: 'Account settings · Vision',
    loadComponent: () => import('./account-settings').then((m) => m.AccountSettingsPage),
  },
  {
    path: 'settings/detection',
    title: 'Detection defaults · Vision',
    loadComponent: () => import('./detection-settings').then((m) => m.DetectionSettingsPage),
  },
];
