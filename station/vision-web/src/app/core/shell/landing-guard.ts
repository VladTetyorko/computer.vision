import { inject } from '@angular/core';
import { Router, type CanActivateFn } from '@angular/router';
import { AuthStore } from '../auth/auth-store';
import { landingRouteFor } from './landing-logic';

/**
 * Resolves `''` by role (docs/plans/done/OPS-UX-PLAN.md §2 A1) — replaces the old static
 * `redirectTo: 'fly'` in `app.routes.ts`, which sent every role, including a MANAGER/ADMIN, to the
 * cockpit. A **guard**, not `redirectTo`, on purpose: `redirectTo` fires before any session is
 * resolved, so a static redirect can only ever encode one destination — it cannot wait for
 * `GET /api/auth/me` to know which one applies. This mirrors `core/org/org-guard.ts`'s own shape
 * for the identical reason.
 *
 * Awaits `AuthStore.ready` first, same as `auth-guard.ts`/`org-guard.ts` — the decision runs
 * against the *resolved* session's `topRole`, never a transitional `undefined` mid-boot. Only ever
 * reached at the exact `''` path (see `app.routes.ts`'s own routing comment for why): a deep link
 * to `/fly`, `/command`, `/assets/...` etc. never touches this guard at all, so it cannot affect
 * deep-link or browser-back behaviour — this guard's only job is what happens when a session opens
 * the app with no destination named yet. **Dev parity**: `authEnabled === false` resolves the dev
 * principal to `topRole: 'ADMIN'` (`AuthStore`'s own fixed dev-admin shape) — which is the absence
 * of a role signal, not a manager, so `landingRouteFor` deliberately ignores it and keeps `/fly`
 * for that mode. See its own javadoc for why: honouring it would move every unsecured install off
 * the cockpit and break the same "with auth off nothing changes" invariant the backend half of this
 * task froze (docs/plans/done/OPS-UX-PLAN.md §1).
 */
export const landingGuard: CanActivateFn = async () => {
  const auth = inject(AuthStore);
  const router = inject(Router);

  await auth.ready;

  return router.parseUrl(landingRouteFor(auth.user()?.topRole, auth.authEnabled()));
};
