import type { GroupSummary, UserSummary } from '../api/models';
import { isPilot } from '../roster/roster-pivot-logic';

/**
 * Pure, Angular-free logic behind `/command`'s "Set up this station" checklist
 * (docs/plans/active/OPS-UX-PLAN.md §3 B2) — a one-time onboarding nudge for a brand-new install,
 * gone the moment the station has grown past it. Nothing here calls `VisionApi` itself;
 * `CommandFacade` loads `UserSummary[]`/`GroupSummary[]`/asset counts and hands them to these
 * functions, matching every other `core/*-logic.ts` file's "pure function, tested without Angular"
 * shape.
 */

/**
 * The three dev-only accounts `AuthSeedRunner` (`station/vision-app/.../bootstrap/AuthSeedRunner.java`)
 * creates on first boot — the literal definition of "nobody has touched user management yet".
 * `AuthSeedRunner` is a strict no-op once any user exists, so these three usernames only remain the
 * *complete* user list until the first real account is created; from then on `hasOnlySeededUsers`
 * below is false for good (nothing un-creates a user for this purpose, matching the plan's own "ticks
 * off, never back on" rule — see `isFreshStation`'s doc comment).
 */
const SEEDED_USERNAMES: ReadonlySet<string> = new Set(['admin', 'manager', 'pilot']);

/** True once nobody has ever created a user beyond `AuthSeedRunner`'s three dev accounts. */
export function hasOnlySeededUsers(users: readonly UserSummary[]): boolean {
  return users.every((user) => SEEDED_USERNAMES.has(user.username));
}

/**
 * Whether `/command` should show the setup checklist at all — the plan's own freshness test:
 * "no users beyond the seeded three, **or** zero assets" (docs/plans/active/OPS-UX-PLAN.md §3 B2). Either
 * half alone counts: a demo fleet spun up with Command's own "Add test drone" button before anyone
 * ever touched `/org` is `totalAssets > 0` with only-seeded users — still fresh, still worth the
 * nudge. A station with real users already onboarded but every asset since deleted is the mirror
 * case — still worth pointing at "add your first aircraft" again. Once *both* halves are false
 * (real users exist AND at least one asset exists) this returns false and stays false — nothing
 * later un-creates a user or un-adds every last asset just to bring the banner back, so in practice
 * this is a one-way "still setting up" → "in real use" transition, never a flicker.
 *
 * `CommandFacade` gates the ADMIN-only part itself (`AuthStore.user()?.topRole`, read once
 * `AuthStore.ready` resolves) — this function only knows about freshness, not about who's allowed
 * to see it, so it stays reusable/testable independent of auth.
 */
export function isFreshStation(users: readonly UserSummary[], totalAssets: number): boolean {
  return hasOnlySeededUsers(users) || totalAssets === 0;
}

/** One row of the checklist — `to` is where the row's own action already lives; nothing new. */
export interface SetupChecklistRow {
  readonly id: 'create-group' | 'add-pilots' | 'add-aircraft' | 'assign-pilot';
  readonly label: string;
  readonly done: boolean;
  readonly to: string;
}

/**
 * Builds the four rows from live data — every `done` a direct read of already-loaded state, never a
 * separate "completed" flag that could drift from reality (docs/plans/active/OPS-UX-PLAN.md §3 B2: "each
 * ticking itself off from live data"; "no dismiss button that hides an unfinished setup" — there is
 * nothing here *to* dismiss, a done row simply stops being actionable).
 *
 * - **Create a group**: `AuthSeedRunner` always creates exactly one ("Root"); more than one means an
 *   admin has acted. Links to `/org` (its Groups tab).
 * - **Add pilots**: the seed's one `pilot` user is the floor; a second PILOT-role user (any group)
 *   means someone beyond the seed can fly. Links to `/org` (its Users tab, where a role is chosen
 *   per membership).
 * - **Add your first aircraft**: `totalAssets > 0`. Links to `/add-source`, the same wizard the
 *   Manage nav's own "Add source" entry opens.
 * - **Assign a pilot**: `hasAnyPilotAssignment` — whether *any* asset anywhere already has at least
 *   one pilot assigned (`CommandFacade` derives this best-effort, the same per-asset
 *   `listAssetPilots` shape `RosterFacade` already uses). Links to `/manage/roster`, the one page
 *   that can actually make the assignment.
 */
export function buildSetupChecklist(
  users: readonly UserSummary[],
  groups: readonly GroupSummary[],
  totalAssets: number,
  hasAnyPilotAssignment: boolean,
): readonly SetupChecklistRow[] {
  const pilotUserCount = users.filter(isPilot).length;
  return [
    { id: 'create-group', label: 'Create a group', done: groups.length > 1, to: '/org' },
    { id: 'add-pilots', label: 'Add pilots', done: pilotUserCount > 1, to: '/org' },
    { id: 'add-aircraft', label: 'Add your first aircraft', done: totalAssets > 0, to: '/add-source' },
    { id: 'assign-pilot', label: 'Assign a pilot', done: hasAnyPilotAssignment, to: '/manage/roster' },
  ];
}
