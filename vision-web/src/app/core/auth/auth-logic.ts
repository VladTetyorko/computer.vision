import type { MeResponse, Role } from '../api/models';

/**
 * Pure decision logic behind `core/auth/auth-store.ts`/`auth-guard.ts` and the login/identity-chip
 * components (docs/U-AUTH-PLAN.md wave 4) — kept framework-free and unit-tested here so those
 * consumers stay dumb: a component/guard only ever reads a signal and calls one of these.
 */

/**
 * The store's own boot lifecycle: `'loading'` until the first `GET /api/auth/me` settles, then
 * either `'anon'` (no session, or the backend was unreachable — see `AuthStore.loadMe`'s own doc
 * comment) or `'authed'` (a real session, or the dev-disabled admin — see `needsLogin` below for
 * why the two are treated identically from here on).
 */
export type AuthStatus = 'loading' | 'anon' | 'authed';

const ROLE_LABELS: Record<Role, string> = {
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
 * parity (docs/U-AUTH-PLAN.md: "the app works with zero auth exactly as today") means the login
 * screen must never be reachable at all in that mode, not just usually skipped. Otherwise `true`
 * only once the boot check has actually concluded there is no session (`status === 'anon'` *and*
 * `user === null` — both, not either, so a transitional or inconsistent state never triggers a
 * redirect): `'loading'` must not bounce the very first navigation of a session to `/login` before
 * `GET /api/auth/me` has even answered, and `'authed'` obviously never needs the login screen.
 */
export function needsLogin(status: AuthStatus, authEnabled: boolean, user: MeResponse | null): boolean {
  return authEnabled && status === 'anon' && user === null;
}
