import type { AssignedPilot, Membership, Role, UserSummary } from '../api/models';
import { shortIdLabel } from '../fleet/inventory-logic';

/**
 * Who counts as a pilot, and how a "hand this to somebody" picker groups them — the one rule two
 * surfaces now ask for: the onboarding wizard's **Hand over** step
 * (docs/plans/done/OPS-UX-PLAN.md §2 A3) and the Inventory page's **Issue to…** dialog
 * (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.5, wave W4).
 *
 * {@link creatorOwnershipGroup} and {@link pilotsInGroup} moved here verbatim from
 * `features/onboarding/onboarding-logic.ts` the moment that second consumer appeared — this
 * codebase's standing "a second consumer moves shared logic to `core/`" precedent
 * (`core/fleet/device-logic.ts`'s own doc comment), not a copy: the wizard imports them from here
 * now, so the two pickers cannot answer "is Anna a pilot?" differently.
 */

// --- Whose group is this? (docs/conclusions/OPS-UX-REVIEW.md §A4) --------------------------------

/** Least→most privileged, mirroring the domain's own `Role` ordinal — used only to find the *highest* of a set of memberships below. `VIEWER` (docs/plans/active/AUTH-ROLES-PLAN.md, wave B0a/B6) ranks below `PILOT`, same as the real enum's own ordinal order. */
const ROLE_RANK: Readonly<Record<Role, number>> = { VIEWER: 0, PILOT: 1, MANAGER: 2, ADMIN: 3 };

/**
 * The group a newly-created asset silently belongs to (docs/conclusions/OPS-UX-REVIEW.md §A4 — `POST
 * /api/assets` "sets `Ownership` from the creator"). Mirrors the backend's own rule byte-for-byte
 * (`VisionUserDetails#ownershipOf`, station/vision-app): the group tied to the creator's **highest**
 * `Role` membership, ties broken by encounter order (the backend's own tie-break is undocumented as
 * stable either — see that method's own comment) — never a group the caller has to pick, since the
 * wizard's Source/Identify/Prove/Attach steps never ask for one. Returns `undefined` only for a
 * membership-less account (the "couldn't determine your group" honest-degrade case downstream).
 *
 * **Known dev-parity gap** (`vision.auth.enabled=false`): the fixed dev-admin principal's own
 * `MeResponse.memberships` carries a synthetic group id that does not match the real seeded
 * admin/manager/pilot users' own "Root" group id (two different, unrelated ids that merely share a
 * display name) — so this function resolves *a* group correctly, but {@link pilotsInGroup} will
 * never find a match against it in that mode. That gap is precisely why
 * {@link custodianPickerGroups} carries a documented second rung rather than trusting this one
 * lookup: it degrades to a *less sorted* picker, never an empty one.
 */
export function creatorOwnershipGroup(memberships: readonly Membership[]): Membership | undefined {
  return memberships.reduce<Membership | undefined>((best, candidate) => {
    if (!best || ROLE_RANK[candidate.role] > ROLE_RANK[best.role]) {
      return candidate;
    }
    return best;
  }, undefined);
}

// --- Who is a pilot? ----------------------------------------------------------------------------

/**
 * Every enabled user holding a `PILOT` membership in `groupId` — the Hand-over step's own custodian
 * candidate list, same `enabled`-only filter `features/asset-detail/pilots-card.ts#assignable`
 * already applies (a disabled account can't sign in to fly anything). `undefined`/unresolved
 * `groupId` yields no candidates at all, never every pilot app-wide — offering the wrong team's
 * roster would be worse than offering none.
 */
export function pilotsInGroup(users: readonly UserSummary[], groupId: string | undefined): readonly UserSummary[] {
  if (!groupId) {
    return [];
  }
  return users.filter(
    (user) => user.enabled && user.memberships.some((m) => m.groupId === groupId && m.role === 'PILOT'),
  );
}

/**
 * Every enabled user holding a `PILOT` membership in **any** group of the list handed in — the
 * deliberately wider second rung {@link custodianPickerGroups} falls back to. It widens *grouping*,
 * never *visibility*: the only list any caller can pass is `GET /api/users`, which the server has
 * already narrowed to the session's own scope (`UserAdminController` → `userService.list(scope)`),
 * so "a pilot somewhere you can already see" is a true statement about every user it returns.
 */
export function pilotsAnywhere(users: readonly UserSummary[]): readonly UserSummary[] {
  return users.filter((user) => user.enabled && user.memberships.some((m) => m.role === 'PILOT'));
}

/**
 * The picker's default selection (docs/plans/done/OPS-UX-PLAN.md §2 A3 — "Default selection: the creator
 * when they are a pilot in that group, else none"). Deliberately checks the creator's *own* role
 * within the resolved ownership group, not their global `topRole`: a MANAGER/ADMIN's ownership
 * group is by construction the group of their own highest-role membership (see
 * {@link creatorOwnershipGroup}'s own doc comment), so their role *there* is never `PILOT` — this
 * only ever preselects the creator for the solo-pilot self-registration case (a plain PILOT's own
 * single membership).
 */
export function defaultPilotSelection(creatorUserId: string, ownershipGroup: Membership | undefined): readonly string[] {
  return ownershipGroup?.role === 'PILOT' ? [creatorUserId] : [];
}

/**
 * One assigned pilot, named the best way the data allows (docs/plans/active/INVENTORY-REWORK-PLAN.md
 * §5.3's `Pilots` section): the name the wire resolved server-side, then the account's own username,
 * then the org user-list join (empty for a pilot's own `ASSIGNED_ASSETS` scope, which is why it is
 * not the first rung), and finally a truncated id. The exact ladder
 * `features/inventory/vehicles-logic.ts#custodianLabel` walks for a custodian, so the same person
 * never reads as "Anna Petrenko" in one line of a drawer and "3f2a91c4…" in the next.
 */
export function assignedPilotName(pilot: AssignedPilot, nameById: ReadonlyMap<string, string>): string {
  return pilot.displayName ?? pilot.username ?? nameById.get(pilot.userId) ?? shortIdLabel(pilot.userId);
}

// --- The Issue dialog's grouped picker (INVENTORY-REWORK-PLAN.md §5.5, context §3 defect E) ------

/** One selectable person in a custodian picker — `name` is already the best label the data allows. */
export interface CustodianCandidate {
  readonly userId: string;
  readonly name: string;
  /** The seat this person already holds *on this asset*, when they hold one — rendered beside the name so "Assigned pilots" isn't an unexplained group. */
  readonly seat?: string;
}

/**
 * The three groups §5.5 prints, in the order it prints them. Each is already sorted by name and
 * disjoint from the ones before it, so a template renders them as three `<optgroup>`s without
 * de-duplicating anything itself.
 */
export interface CustodianPickerGroups {
  /** This asset's own assigned pilots — the people who can already fly it. */
  readonly assigned: readonly CustodianCandidate[];
  /** Other pilots (see {@link custodianPickerGroups} for the two-rung rule). */
  readonly pilots: readonly CustodianCandidate[];
  /** Everyone else the session can see — behind the dialog's own "Show everyone" disclosure. */
  readonly everyone: readonly CustodianCandidate[];
}

/** What {@link custodianPickerGroups} joins — a record, never a positional list (CLAUDE.md rule 10). */
export interface CustodianPickerInput {
  /** `GET /api/users`, already scope-filtered by the server; `[]` for a session that may not list users. */
  readonly users: readonly UserSummary[];
  /** `GET /api/assets/{id}/pilots` for the asset being issued; `[]` while it is still loading or failed. */
  readonly assignedPilots: readonly AssignedPilot[];
  /** The asset's owning group — see the two-rung rule below for what an unresolved one costs. */
  readonly groupId: string | undefined;
}

const NAME_ORDER = (a: CustodianCandidate, b: CustodianCandidate) => a.name.localeCompare(b.name);

/**
 * "Who takes this?", grouped so a manager stops scrolling a 36-row roster to find the one pilot
 * (docs/plans/active/INVENTORY-REWORK-CONTEXT.md §3 defect E). Pure; the dialog renders the three
 * lists verbatim.
 *
 * **Assigned pilots** are this asset's `PILOT`-seat assignees only. A `CREW` seat-holder is
 * deliberately *not* listed here: issuing is custody **plus** a `PILOT` assignment, and W1's
 * `HandoverService` skips the grant whenever any seat already exists — so handing the aircraft to a
 * CREW member would leave them holding something they still may not fly. They stay reachable under
 * "Show everyone", where nothing about them is claimed.
 *
 * **Other pilots** is a two-rung rule, and the second rung is the honest half:
 * 1. every enabled `PILOT` of the asset's own group ({@link pilotsInGroup}) — the rule §5.5 names;
 * 2. **only when rung 1 finds nobody**, every enabled `PILOT` anywhere in the already-scoped user
 *    list ({@link pilotsAnywhere}).
 *
 * Rung 2 exists because the client cannot always know an asset's group: `AssetSummaryResponse.owner`
 * is the owning *user's* id, and no endpoint returns `Ownership#groupId`, so the caller passes the
 * best stand-in it has (the session's own ownership group) — which misses an asset owned by a child
 * group, and matches nothing at all in dev-parity mode (see {@link creatorOwnershipGroup}'s gap
 * note). Rung 1 alone would therefore hand a live manager an empty "Other pilots" and leave defect E
 * exactly where it was. Neither rung hides anybody — "Show everyone" always holds the remainder — so
 * this is a sorting aid that degrades to *less sorted*, never a visibility decision.
 */
export function custodianPickerGroups(input: CustodianPickerInput): CustodianPickerGroups {
  const byId = new Map(input.users.map((user) => [user.userId, user]));

  const assigned = input.assignedPilots
    .filter((pilot) => pilot.role === 'PILOT')
    .map<CustodianCandidate>((pilot) => ({
      userId: pilot.userId,
      name: pilot.displayName ?? pilot.username ?? byId.get(pilot.userId)?.displayName ?? shortIdLabel(pilot.userId),
      seat: 'Assigned',
    }))
    .sort(NAME_ORDER);

  const claimed = new Set(assigned.map((candidate) => candidate.userId));

  const inGroup = pilotsInGroup(input.users, input.groupId).filter((user) => !claimed.has(user.userId));
  const otherPilots = inGroup.length > 0 ? inGroup : pilotsAnywhere(input.users).filter((user) => !claimed.has(user.userId));
  const pilots = otherPilots.map(toCandidate).sort(NAME_ORDER);

  for (const candidate of pilots) {
    claimed.add(candidate.userId);
  }

  const everyone = input.users
    .filter((user) => user.enabled && !claimed.has(user.userId))
    .map(toCandidate)
    .sort(NAME_ORDER);

  return { assigned, pilots, everyone };
}

function toCandidate(user: UserSummary): CustodianCandidate {
  return { userId: user.userId, name: user.displayName };
}

/** The name a picked custodian id reads as, across all three groups — `undefined` for an id no group holds (a stale pick after the roster reloaded). */
export function custodianCandidateName(groups: CustodianPickerGroups, userId: string): string | undefined {
  return [...groups.assigned, ...groups.pilots, ...groups.everyone].find((candidate) => candidate.userId === userId)?.name;
}
