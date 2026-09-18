import type { AuthCapability, MeResponse, Role, ScopeKind } from '../api/models';

/**
 * Pure decision logic behind `core/auth/auth-facade.ts`/`auth-guard.ts` and the login/identity-chip
 * components (docs/plans/done/U-AUTH-PLAN.md wave 4) — kept framework-free and unit-tested here so those
 * consumers stay dumb: a component/guard only ever reads a signal and calls one of these.
 */

/**
 * The slice's own boot lifecycle: `'loading'` until the first `GET /api/auth/me` settles, then
 * either `'anon'` (no session, or the backend was unreachable — see `core/auth/state/auth.effects.ts`'s
 * `bootMe$` doc comment) or `'authed'` (a real session, or the dev-disabled admin — see `needsLogin`
 * below for why the two are treated identically from here on).
 */
export type AuthStatus = 'loading' | 'anon' | 'authed';

const ROLE_LABELS: Record<Role, string> = {
  VIEWER: 'Viewer',
  PILOT: 'Pilot',
  MANAGER: 'Manager',
  ADMIN: 'Admin',
};

/** A `Role` rendered for a human — the identity chip's role badge, the login error path never needs this. */
export function roleLabel(role: Role): string {
  return ROLE_LABELS[role];
}

/** `MeResponse#topRole` rendered for a human — the identity chip's one call site, so it never has to spell out `roleLabel(user.topRole)` itself. Takes only the one field it reads (`Pick`), not a full `MeResponse`, per this app's DTO-boundary convention. */
export function topRoleLabel(user: Pick<MeResponse, 'topRole'>): string {
  return roleLabel(user.topRole);
}

/**
 * A compact 1–2 letter avatar label from a display name: first+last initial for a multi-word name
 * ("Pat Pilot" → "PP"), the first two letters for a single word ("admin" → "AD"), `'?'` for an
 * empty/whitespace-only name (never a crash, never a blank avatar with no glyph at all). Always
 * upper-cased — the identity chip's avatar circle is the one place this app renders initials.
 */
export function initialsFor(displayName: string): string {
  const words = displayName.trim().split(/\s+/).filter((word) => word.length > 0);
  if (words.length === 0) {
    return '?';
  }
  if (words.length === 1) {
    return words[0].slice(0, 2).toUpperCase();
  }
  return (words[0][0] + words[words.length - 1][0]).toUpperCase();
}

/**
 * Should the login screen be showing right now? `core/auth/auth-guard.ts`'s entire decision, and
 * the one place that decision is written down.
 *
 * `false` whenever `authEnabled` is `false` — **always**, regardless of `status`/`user` — dev
 * parity (docs/plans/done/U-AUTH-PLAN.md: "the app works with zero auth exactly as today") means the login
 * screen must never be reachable at all in that mode, not just usually skipped. Otherwise `true`
 * only once the boot check has actually concluded there is no session (`status === 'anon'` *and*
 * `user === null` — both, not either, so a transitional or inconsistent state never triggers a
 * redirect): `'loading'` must not bounce the very first navigation of a session to `/login` before
 * `GET /api/auth/me` has even answered, and `'authed'` obviously never needs the login screen.
 */
export function needsLogin(status: AuthStatus, authEnabled: boolean, user: MeResponse | null): boolean {
  return authEnabled && status === 'anon' && user === null;
}

/**
 * Where an anonymous visitor who cannot proceed belongs — the one decision every bootstrap-aware
 * guard (`core/auth/auth-guard.ts`'s `authGuard`/`loginGuard`/`setupGuard`) defers to
 * (docs/plans/active/AUTH-ROLES-PLAN.md §3.5, wave W3). `/setup` while the one-way
 * `GET /api/auth/bootstrap` latch still reads `required` — a fresh station has nobody to sign in
 * as at all, so sending it to `/login` would show a form with no account behind it — `/login`
 * otherwise, the ordinary case for the rest of this station's life.
 */
export function anonymousDestination(bootstrapRequired: boolean): '/setup' | '/login' {
  return bootstrapRequired ? '/setup' : '/login';
}

/**
 * Does this session hold `capability`? (docs/plans/active/AUTH-ROLES-PLAN.md §3.1/§3.2, wave W1/W2 — the
 * one place every "what may I *do*" gate in this app now reads, replacing a `topRole ===`
 * comparison — see `core/org/org-logic.ts#canManageOrg`/`features/models/models-logic.ts`'s own
 * doc comments for why `topRole` alone was never a reliable stand-in once `VIEWER` existed.)
 *
 * Tolerates `capabilities` being `null`/`undefined` (a not-yet-loaded `MeResponse`) by answering
 * `false` rather than throwing — same "read it as it actually arrives" convention `canManageOrg`'s
 * own `topRole` parameter already followed.
 */
export function hasCapability(
  capabilities: readonly AuthCapability[] | null | undefined,
  capability: AuthCapability,
): boolean {
  return (capabilities ?? []).includes(capability);
}

/**
 * Is this session's visibility `UNBOUNDED` — the server's own `VisibilityScope#canAdminister()`
 * (docs/plans/active/AUTH-ROLES-PLAN.md §3.1, `DefaultScopeResolver`: only an ADMIN session ever
 * resolves to `UNBOUNDED`). Strictly narrower than `hasCapability(…, 'MANAGE_ORG')` — a MANAGER's
 * `GROUPS` scope administers their own subtree, not the whole deployment (`ModelRegistryController`'s
 * own javadoc, mirrored by `features/models/models-logic.ts#canAdministerRegistry`,
 * `features/geo/region-manager-facade.ts`, `features/command/command-facade.ts`'s setup checklist,
 * and `shared/map/map-controls/layer-manager.ts`'s all-groups fetch).
 */
export function canAdminister(scopeKind: ScopeKind | null | undefined): boolean {
  return scopeKind === 'UNBOUNDED';
}
