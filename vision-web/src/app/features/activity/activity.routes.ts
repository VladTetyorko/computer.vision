import type { Routes } from '@angular/router';

/**
 * The `/activity` route (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2 feature 7) — "My activity", own lazy
 * chunk, split into its own file per vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3 (B8). Spread
 * inside `app.routes.ts`'s `authGuard`-wrapped children group (a session is required) but with **no
 * role gate** — every signed-in user, pilot included, may read their own activity. Linked from the
 * identity-chip menu, not a top-level nav tab.
 */
export const ACTIVITY_ROUTES: Routes = [
  {
    path: 'activity',
    title: 'My activity · Vision',
    loadComponent: () => import('./activity').then((m) => m.ActivityPage),
  },
];
