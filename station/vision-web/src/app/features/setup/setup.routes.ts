import type { Routes } from '@angular/router';
import { setupGuard } from '../../core/auth/auth-guard';

/**
 * The `/setup` route (docs/plans/active/AUTH-ROLES-PLAN.md wave W3) — split into its own file per
 * vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3 (B8), same pattern as `LOGIN_ROUTES`.
 * **Deliberately not listed inside `app.routes.ts`'s `authGuard`-wrapped children group** — this
 * route must stay reachable with no session at all, same reason as `/login`.
 *
 * **`setupGuard`** (`core/auth/auth-guard.ts`) closes this page back down to a redirect home the
 * instant the one-way bootstrap latch flips — an admin already exists, so re-showing a
 * create-first-admin form here would just 409.
 */
export const SETUP_ROUTES: Routes = [
  {
    path: 'setup',
    title: 'Set up · Vision',
    canActivate: [setupGuard],
    loadComponent: () => import('./setup').then((m) => m.SetupPage),
  },
];
