import { inject } from '@angular/core';
import { Router, type CanActivateFn } from '@angular/router';
import { AuthStore } from './auth-store';
import { needsLogin } from './auth-logic';

/**
 * Gates every real page behind a session once auth is enabled (docs/U-AUTH-PLAN.md wave 4) —
 * applied to `app.routes.ts`'s grouping wrapper around every feature route array, deliberately
 * excluding `/login` itself (see that file's own comment for the wrapper).
 *
 * Awaits `AuthStore.ready` first — the store's own boot-time `loadMe()` — so the very first
 * navigation of a session is decided against the *resolved* session, never the transitional
 * `'loading'` status; this is what keeps a fresh page load from ever briefly activating a
 * protected route and then yanking the user to `/login` a beat later (see `AuthStore`'s own class
 * doc, "No boot-time render flash"). The actual decision is entirely `auth-logic.ts#needsLogin`'s,
 * kept pure and unit-tested there — this function is wiring only. `returnUrl` lets
 * `features/auth/login/login.ts` send the user back to whatever they originally asked for once
 * they sign in, instead of always landing on `/fly`.
 */
export const authGuard: CanActivateFn = async (_route, state) => {
  const auth = inject(AuthStore);
  const router = inject(Router);

  await auth.ready;

  if (needsLogin(auth.status(), auth.authEnabled(), auth.user())) {
    return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
  }
  return true;
};
