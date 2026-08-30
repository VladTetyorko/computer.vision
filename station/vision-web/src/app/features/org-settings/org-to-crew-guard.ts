import { inject } from '@angular/core';
import { Router, type CanActivateFn } from '@angular/router';

/**
 * `/org` is no longer its own page (docs/plans/active/WAREHOUSE-UX-PLAN.md §4 wave W7) — `OrgSettingsPage`
 * is now `CrewPage`'s "Organization" tab (`features/roster/crew.ts`), reached at
 * `/manage/roster?tab=org`. A **guard-only leaf**, not a static `redirectTo`, for the same reason
 * `core/shell/landing-guard.ts` gives for its own root route: a plain string `redirectTo` can only
 * rewrite the path template (optionally forwarding whatever query params the incoming URL already
 * had) — it cannot *inject* a brand-new `?tab=org` that wasn't on the request. `features/hubs/route-audit-logic.ts#flattenRoutes`
 * already classifies a guard-only leaf (`canActivate` + empty `children`) as a `'redirect'` for
 * exactly this reason, so `app.routes.spec.ts`'s "no dead link" audit still sees `/org` resolve.
 *
 * No role check here — the ultimate destination (`/manage/roster`, `features/roster/roster.routes.ts`)
 * already carries `orgGuard`, so a pilot following an old `/org` bookmark still lands on `/fly`, just
 * one hop later than before. Duplicating the same role check on this leaf would only be two places
 * that have to agree instead of one.
 */
export const orgToCrewGuard: CanActivateFn = () => {
  const router = inject(Router);
  return router.createUrlTree(['/manage/roster'], { queryParams: { tab: 'org' } });
};
