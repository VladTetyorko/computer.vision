import { inject } from '@angular/core';
import { Router, type CanActivateFn } from '@angular/router';
import { AuthFacade } from './auth-facade';
import { anonymousDestination, needsLogin } from './auth-logic';

/**
 * Gates every real page behind a session once auth is enabled (docs/plans/done/U-AUTH-PLAN.md wave 4;
 * bootstrap-aware since docs/plans/active/AUTH-ROLES-PLAN.md wave W3) — applied to `app.routes.ts`'s
 * grouping wrapper around every feature route array, deliberately excluding `/login` and `/setup`
 * themselves (see that file's own comment for the wrapper; those two get `loginGuard`/`setupGuard`
 * below instead).
 *
 * Awaits `AuthFacade.ready` first — the store's own boot-time `loadMe()` — so the very first
 * navigation of a session is decided against the *resolved* session, never the transitional
 * `'loading'` status; this is what keeps a fresh page load from ever briefly activating a
 * protected route and then yanking the user to `/login` a beat later (see `AuthFacade`'s own class
 * doc, "No boot-time render flash"). The actual "needs login at all" decision is entirely
 * `auth-logic.ts#needsLogin`'s, kept pure and unit-tested there — this function is wiring only.
 *
 * Once it's clear the visitor needs to go somewhere, `AuthFacade.bootstrapRequired()` is checked
 * (and only then — a resolved, already-anonymous visitor is the one case that ever needs the
 * extra round trip, not every navigation) so a fresh station with nobody to sign in as sends the
 * visitor to `/setup` instead of a login form with no account behind it
 * (`auth-logic.ts#anonymousDestination`). `returnUrl` is only meaningful for the `/login`
 * destination — `features/auth/login/login.ts` sends the user back to whatever they originally
 * asked for once they sign in; `/setup` has no equivalent (the freshly-created admin always lands
 * on `/`, since "whatever page a fresh station's first visitor happened to ask for" carries no
 * useful meaning).
 */
export const authGuard: CanActivateFn = async (_route, state) => {
  const auth = inject(AuthFacade);
  const router = inject(Router);

  await auth.ready;

  if (!needsLogin(auth.status(), auth.authEnabled(), auth.user())) {
    return true;
  }

  const destination = anonymousDestination(await auth.bootstrapRequired());
  if (destination === '/setup') {
    return router.createUrlTree(['/setup']);
  }
  return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};

/**
 * Protects `/login` itself (docs/plans/active/AUTH-ROLES-PLAN.md wave W3) — a fresh station with no
 * administrator yet has no account for this form to sign in as, so a visitor who lands here
 * directly is bounced to `/setup` instead. Unconditional otherwise: this route sits outside
 * `authGuard`'s group by design (an already-anonymous visitor must be able to reach it at all),
 * so it has no session-based check of its own beyond the bootstrap latch.
 */
export const loginGuard: CanActivateFn = async () => {
  const auth = inject(AuthFacade);
  const router = inject(Router);

  if (await auth.bootstrapRequired()) {
    return router.createUrlTree(['/setup']);
  }
  return true;
};

/**
 * Protects `/setup` itself (docs/plans/active/AUTH-ROLES-PLAN.md wave W3) — the one-way
 * `GET /api/auth/bootstrap` latch means this page must stop being reachable the instant the first
 * admin exists (re-running the create-first-admin form against a station that already has one
 * would otherwise just 409); once the latch has closed, this redirects home rather than leaving a
 * stale "create the first admin" form sitting on screen for anyone who still has the URL.
 */
export const setupGuard: CanActivateFn = async () => {
  const auth = inject(AuthFacade);
  const router = inject(Router);

  if (await auth.bootstrapRequired()) {
    return true;
  }
  return router.createUrlTree(['/']);
};
