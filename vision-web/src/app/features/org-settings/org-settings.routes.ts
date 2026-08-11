import type { Routes } from '@angular/router';
import { orgGuard } from '../../core/org/org-guard';

/**
 * The `/org` route (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2) — org-settings, own lazy chunk, split into
 * its own file per vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3 (B8). Spread inside
 * `app.routes.ts`'s `authGuard`-wrapped children group like every other feature (so a signed-out
 * user hits `/login` first); `orgGuard` (`core/org/org-guard.ts`) adds the ADMIN/MANAGER role check
 * on top — a pilot is redirected to `/fly`.
 */
export const ORG_ROUTES: Routes = [
  {
    path: 'org',
    title: 'Organization · Vision',
    canActivate: [orgGuard],
    loadComponent: () => import('./org-settings').then((m) => m.OrgSettingsPage),
  },
];
