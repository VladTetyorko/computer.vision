import type { Routes } from '@angular/router';

/**
 * `/settings` — split per docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.5, docs/extracts/design/11-settings.md
 * (Wave 4's F7 fix: "one destination, several names and doors").
 *
 * `/settings/detection` used to own its own page (`detection-settings.ts`, the single-org "detection
 * defaults" form) — replaced outright by `/vision/profiles` (docs/plans/active/CV-SETTINGS-PLAN.md §4,
 * wave W6, §8 Q4: "delete `/settings/detection` outright"). The old page component and its facade/logic
 * are deleted, not merely unrouted; this entry is now a plain redirect so any saved link/bookmark still
 * lands somewhere useful instead of 404ing.
 */
export const SETTINGS_ROUTES: Routes = [
  {
    path: 'settings',
    title: 'Account settings · Vision',
    loadComponent: () => import('./account-settings').then((m) => m.AccountSettingsPage),
  },
  {
    path: 'settings/detection',
    pathMatch: 'full',
    redirectTo: 'vision/profiles',
  },
];
