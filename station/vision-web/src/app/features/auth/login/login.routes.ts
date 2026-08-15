import type { Routes } from '@angular/router';

/**
 * The `/login` route (docs/plans/done/U-AUTH-PLAN.md wave 4) — split into its own file per
 * vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3 (B8), see `features/fly/fly.routes.ts`'s doc comment
 * for why. **Deliberately not listed inside `app.routes.ts`'s `authGuard`-wrapped children group**
 * — this is the one route that must stay reachable with no session at all.
 */
export const LOGIN_ROUTES: Routes = [
  {
    path: 'login',
    title: 'Sign in · Vision',
    loadComponent: () => import('./login').then((m) => m.LoginPage),
  },
];
