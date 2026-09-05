import type { Routes } from '@angular/router';
import { loginGuard } from '../../../core/auth/auth-guard';

/**
 * The `/login` route (docs/plans/done/U-AUTH-PLAN.md wave 4) — split into its own file per
 * vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3 (B8), see `features/fly/fly.routes.ts`'s doc comment
 * for why. **Deliberately not listed inside `app.routes.ts`'s `authGuard`-wrapped children group**
 * — this is the one route that must stay reachable with no session at all.
 *
 * **`loginGuard`** (docs/plans/active/AUTH-ROLES-PLAN.md wave W3) bounces to `/setup` instead when the
 * one-way bootstrap latch is still open — a fresh station has no account for this form to sign in
 * as at all.
 */
export const LOGIN_ROUTES: Routes = [
  {
    path: 'login',
    title: 'Sign in · Vision',
    canActivate: [loginGuard],
    loadComponent: () => import('./login').then((m) => m.LoginPage),
  },
];
