import type { Role } from '../api/models';

/**
 * Pure decision behind `core/shell/landing-guard.ts` (docs/plans/active/OPS-UX-PLAN.md §2 A1) — where `''`
 * resolves to. Before this, `app.routes.ts` had a static `redirectTo: 'fly'`: correct for a PILOT
 * (the cockpit is their whole job), but a MANAGER/ADMIN opening the app landed on a cockpit with
 * nothing assigned to fly rather than the command overview their role actually works from
 * (docs/conclusions/OPS-UX-REVIEW.md §U1).
 *
 * **`authEnabled` is checked first, and that is the whole subtlety here.** With
 * `vision.auth.enabled=false` — still the default — the backend reports a fixed dev principal whose
 * `topRole` is the literal string `ADMIN` (`MeResponse#devAdmin`). That is not a statement about who
 * is using the station; it is the *absence* of any statement, and reading it as "a manager is here"
 * would silently move every unsecured install, demo and dev run off the cockpit — reversing
 * docs/plans/done/MVP3-PLAN.md §C-b's deliberate choice of `/fly` as the landing page for the single
 * operator wearing every hat (docs/main/UX-DESIGN.md §1, "one human wearing different hats"). It
 * would also break the invariant OPS-UX-PLAN.md §1 froze for the backend half of this same task:
 * with auth off, nothing about the running system changes. So role-based landing is a
 * *secured-deployment* behaviour only.
 *
 * With auth on: ADMIN/MANAGER → `/command` (the fleet-wide overview their authority spans); PILOT,
 * or a role that cannot be resolved at all (`undefined`/`null` — a not-yet-loaded session) → `/fly`,
 * unchanged from before this task. One function, one place this mapping is written down — mirrors
 * `core/org/org-logic.ts#canManageOrg`'s own shape for the same ADMIN/MANAGER-vs-PILOT split.
 */
export function landingRouteFor(topRole: Role | null | undefined, authEnabled: boolean): string {
  if (!authEnabled) {
    return '/fly';
  }
  return topRole === 'ADMIN' || topRole === 'MANAGER' ? '/command' : '/fly';
}
