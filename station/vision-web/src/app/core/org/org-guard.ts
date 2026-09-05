import { inject } from '@angular/core';
import { Router, type CanActivateFn } from '@angular/router';
import { AuthStore } from '../auth/auth-store';
import { canManageOrg } from './org-logic';

/**
 * Role-gates the org-settings route (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2) — only ADMIN/MANAGER may
 * reach `/org`; a PILOT is redirected to `/fly`. Sits **alongside** `core/auth/auth-guard.ts` (the
 * org route is still inside that guard's children wrapper, so a signed-out user hits `/login`
 * first): this guard only adds the role check on top.
 *
 * Awaits `AuthStore.ready` first — the boot-time `GET /api/auth/me` — so the decision runs against
 * the *resolved* session's `capabilities`, never a transitional `undefined` that would bounce a
 * manager away on a cold navigation. **Dev parity**: when `authEnabled === false` the dev principal
 * resolves to the full capability set (`MeResponse#devAdmin`), so `canManageOrg` is `true` and the
 * surface stays reachable exactly as before this slice. The real decision is entirely
 * `org-logic.ts#canManageOrg`'s (pure, unit-tested); this is wiring only, mirroring `auth-guard.ts`'s
 * own shape.
 */
export const orgGuard: CanActivateFn = async () => {
  const auth = inject(AuthStore);
  const router = inject(Router);

  await auth.ready;

  if (canManageOrg(auth.capabilities())) {
    return true;
  }
  return router.createUrlTree(['/fly']);
};
