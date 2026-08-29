import type { AssetSummary, UserSummary } from '../api/models';

/**
 * Pure, Angular-free "make a raw audit id readable" logic (docs/plans/active/OPERATOR-UX-5-PLAN.md finding U2,
 * §2 U2) — the history itself is immutable (`AuditEntry.summary` is written once, server-side, at the
 * moment of the action, mixing in whatever ids the acting service had on hand:
 * `DefaultFlightCommandService`/`DefaultManualControlService` both write literally
 * `"… for asset " + assetId.value()`), so this is presentation only, never a backend change. Shared by
 * `features/activity/activity-facade.ts` (the acting user's own history) and
 * `features/audit/audit-facade.ts`/`core/audit/audit-logic.ts` (the fleet-wide trail) — the second
 * consumer that earns this its own `core/` home rather than staying feature-local, this codebase's
 * usual "moves to `core/` once a second consumer needs it" rule (see `core/fleet/triage-logic.ts`'s
 * own doc comment for the most recent precedent).
 */

/** Matches a canonical `UUID#toString()` (8-4-4-4-12 lowercase hex, the only shape this backend ever
 *  writes) anywhere in free text — case-insensitive since nothing here can assume a caller normalized
 *  casing first, even though every id this app's own backend serializes already is lowercase. */
const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-fA-F]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;

/**
 * The synthetic system/root principal (`station/vision-app/…/DevPrincipal#USER_ID`,
 * `vision-map`'s own `LayerResolver#SYSTEM_USER_ID` — both wrap Java's `new UUID(0, 0)`, whose
 * `toString()` is this literal string) — every action recorded with no real human actor, including
 * every dev-mode action when `vision.auth.enabled=false` (the dev principal *is* this id, so "Station"
 * is the honest, dev-parity-preserving label for it, not a special case that only fires with auth on).
 */
export const ROOT_ACTOR_ID = '00000000-0000-0000-0000-000000000000';

/**
 * Merges an id→display-name lookup out of whatever asset/user lists a page already has loaded —
 * `features/audit/audit-facade.ts` already fetches both (best-effort, for exactly this purpose);
 * `features/activity/activity-facade.ts` only has assets, so `users` defaults to none rather than
 * forcing a page with no use for it to pass an empty array at every call site. Assets first, then
 * users — the two live in disjoint UUID spaces, so ordering is never actually a collision, only a
 * predictable one if this ever changes.
 */
export function buildNameMap(
  assets: readonly Pick<AssetSummary, 'assetId' | 'displayName'>[],
  users: readonly Pick<UserSummary, 'userId' | 'displayName'>[] = [],
): ReadonlyMap<string, string> {
  const names = new Map<string, string>();
  for (const asset of assets) {
    names.set(asset.assetId, asset.displayName);
  }
  for (const user of users) {
    names.set(user.userId, user.displayName);
  }
  return names;
}

/**
 * Replaces every UUID in `summary` that resolves in `names` with its display name; an id this page
 * has no name for is shortened to its first 8 characters (mirrors `core/fleet/triage-logic.ts`'s own
 * "unresolved → short id fragment, never a guess" rule) rather than left as a 36-character UUID no
 * operator can act on. Everything else in the string — punctuation, the surrounding sentence, ids
 * that don't look like a UUID at all — is untouched. Callers keep the raw `summary` around for a
 * `title` tooltip (frontend-style: never destroy the underlying fact, only how it's first read).
 */
export function humanizeSummary(summary: string, names: ReadonlyMap<string, string>): string {
  return summary.replace(UUID_PATTERN, (id) => names.get(id.toLowerCase()) ?? id.slice(0, 8));
}

/**
 * The audit table's own ACTOR column (docs/plans/active/OPERATOR-UX-5-PLAN.md finding U2) — the root
 * principal ({@link ROOT_ACTOR_ID}) reads `Station`, a known user reads their display name, anything
 * else shortens to its first 8 characters, same fallback `humanizeSummary` uses for an unresolved id
 * inside prose.
 */
export function actorLabel(actorId: string, names: ReadonlyMap<string, string>): string {
  if (actorId === ROOT_ACTOR_ID) {
    return 'Station';
  }
  return names.get(actorId) ?? actorId.slice(0, 8);
}
