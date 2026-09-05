import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { VisionApi } from '../api/vision-api';
import { describeHttpError } from '../api-error';
import { LiveStore } from '../live/live-store';
import type { AuthCapability, ChangePasswordRequest, MeResponse, ScopeKind } from '../api/models';
import type { AuthStatus } from './auth-logic';
import { hasCapability } from './auth-logic';

/** Console prefix mirroring `[fleet]`/`[weather]`/`[player]` — a stable per-file tag, no shared logging service. */
const LOG_PREFIX = '[auth]';

/**
 * The app's one source of truth for "who is logged in" (docs/plans/done/U-AUTH-PLAN.md wave 4) —
 * `providedIn: 'root'`, one instance app-wide, self-initializing: the constructor kicks off the
 * boot-time `GET /api/auth/me` check itself (mirrors `FleetStore`'s own "starts polling from its
 * own constructor" posture) rather than needing `app.ts` or an `APP_INITIALIZER` to remember to
 * call it — whichever consumer is injected first (in practice, `core/auth/auth-guard.ts` on the
 * very first navigation and `shared/ui/identity-chip.ts` in the header, at essentially the same
 * moment) triggers the one-time load.
 *
 * **No boot-time render flash**: `ready` (the constructor's own `loadMe()` promise) is what
 * `auth-guard.ts` awaits before deciding whether to redirect — the very first navigation of a
 * session is therefore always decided against the *resolved* session, never a page briefly
 * mounting behind a stale `'loading'`/optimistic guess and then getting yanked to `/login` a beat
 * later. The identity chip takes the complementary approach for its own, non-blocking corner of the
 * shell: it renders nothing at all while `user()` is `null` (`'loading'`, or a still-anonymous
 * session) rather than showing a placeholder that would have to be swapped out — see that
 * component's own doc comment.
 *
 * **Dev parity (`authEnabled === false`)**: every one of `AuthController`'s endpoints is then a
 * no-op that always reports the same fixed dev admin — `loadMe`/`login` both fold that response
 * into `status: 'authed'` exactly like a real session, which is what keeps the login screen
 * unreachable in that mode (see `auth-logic.ts#needsLogin`'s own doc comment) and the identity chip
 * showing a name+role badge even with auth off, per the plan's own "show the dev admin's name too,
 * for a consistent surface" call.
 */
@Injectable({ providedIn: 'root' })
export class AuthStore {
  private readonly api = inject(VisionApi);
  private readonly router = inject(Router);
  private readonly liveStore = inject(LiveStore);

  private readonly userSignal = signal<MeResponse | null>(null);
  private readonly authEnabledSignal = signal(false);
  private readonly statusSignal = signal<AuthStatus>('loading');
  private readonly loginBusySignal = signal(false);
  private readonly loginErrorSignal = signal<string | null>(null);
  /** Set by `core/auth/session-interceptor.ts` when a request 401s while on `/fly` — see that file's own doc comment for the frozen "never navigate away from /fly" rule this exists to satisfy. */
  private readonly reauthRequiredSignal = signal(false);

  readonly user = this.userSignal.asReadonly();
  /** `false` until the first `loadMe()` resolves and says otherwise — see class doc's "no render flash". */
  readonly authEnabled = this.authEnabledSignal.asReadonly();
  readonly status = this.statusSignal.asReadonly();
  /** Disables the login form's submit button and blocks a second in-flight submit — see `login()`. */
  readonly loginBusy = this.loginBusySignal.asReadonly();
  /** The login form's inline error text, or `null` — cleared at the start of every new attempt. */
  readonly loginError = this.loginErrorSignal.asReadonly();
  /** `shared/ui/reauth-overlay.ts`'s own gate — see `reauthRequiredSignal`'s doc comment. */
  readonly reauthRequired = this.reauthRequiredSignal.asReadonly();

  /**
   * The verbs this session holds, deployment/org-wide (docs/plans/active/AUTH-ROLES-PLAN.md §3.1/§3.2,
   * wave W1) — `[]` while there is no session, never a crash. `core/auth/auth-logic.ts#hasCapability`
   * is the one place this is tested; no page/component compares `topRole` to decide what it may do.
   */
  readonly capabilities = computed<readonly AuthCapability[]>(() => this.userSignal()?.capabilities ?? []);

  /**
   * Which `VisibilityScope.Kind` this session's visibility resolves to — `undefined` while there is
   * no session. `core/auth/auth-logic.ts#canAdminister` is the one place `'UNBOUNDED'` is tested;
   * `'ASSIGNED_ASSETS'` uniquely identifies a PILOT-only session post-B6 (see that type's own doc
   * comment in `core/api/models.ts`).
   */
  readonly scopeKind = computed<ScopeKind | undefined>(() => this.userSignal()?.scopeKind);

  /** Mirrors `User#mustChangePassword()` — `true` forces a change-password gate before anything else (`shared/ui/force-password-change.ts`, wave W3). `false` while there is no session. */
  readonly mustChangePassword = computed(() => this.userSignal()?.mustChangePassword ?? false);

  /** Resolves once the boot-time `loadMe()` settles — see class doc. Never rejects: every failure path inside `loadMe()` is caught and turned into `'anon'`. */
  readonly ready: Promise<void>;

  constructor() {
    this.ready = this.loadMe();
  }

  /** Convenience over `hasCapability(this.capabilities(), capability)` — reads exactly as `if (auth.can('MANAGE_ORG'))` at a call site that doesn't otherwise need the raw list. */
  can(capability: AuthCapability): boolean {
    return hasCapability(this.capabilities(), capability);
  }

  /**
   * `GET /api/auth/me`. A clean `401` (`VisionApi.authMe` already folds it into `null`) means "no
   * session, and auth must be enabled for that response to even be possible" — the one place
   * `authEnabled` can be safely inferred rather than read off a body, since there is no body.
   * Any other failure (network down, 5xx — `VisionApi.authMe` rethrows those) degrades to `'anon'`
   * without touching `authEnabled` at all: a backend this app can't currently reach must not trap
   * the user behind a login screen it also can't finish loading, and `authEnabled`'s own default
   * (`false`) is exactly the safe "let the app render, same as always" fallback either way.
   */
  async loadMe(): Promise<void> {
    try {
      const me = await this.api.authMe();
      this.applySession(me);
    } catch (error) {
      console.warn(`${LOG_PREFIX} GET /api/auth/me failed — treating this session as signed out`, { error });
      this.userSignal.set(null);
      this.statusSignal.set('anon');
    }
  }

  /**
   * `POST /api/auth/login`. Never throws — a failure (401 bad credentials, or anything else) is
   * turned into `loginError` and a `false` return, for the login form's own inline handling; a
   * caller that only cares about success/failure (not the message) can just check the return value.
   */
  async login(username: string, password: string): Promise<boolean> {
    this.loginErrorSignal.set(null);
    this.loginBusySignal.set(true);
    try {
      const me = await this.api.authLogin(username, password);
      this.applySession(me);
      // A fresh session supersedes whatever the live connection was opened under (a stale/anon one
      // pre-login, or a *different* user's after the mid-flight-401 reauth overlay) — reconnect so
      // its topic scope and any server-side session check start clean under the new session.
      this.liveStore.reconnect();
      return true;
    } catch (error) {
      this.loginErrorSignal.set(loginErrorMessage(error));
      return false;
    } finally {
      this.loginBusySignal.set(false);
    }
  }

  /**
   * A request 401'd — called only by `core/auth/session-interceptor.ts`, which alone knows whether
   * the current route is `/fly` (see that file's own doc comment for the full frozen-rule reasoning).
   *
   * **`onFlyRoute: true`** sets {@link reauthRequired} and does *nothing else* — no navigation, no
   * local state cleared — so `shared/ui/reauth-overlay.ts` can present in place while everything
   * already on screen (including a live manual-control session) stays exactly as it was.
   *
   * **`onFlyRoute: false`** clears the session locally (`userSignal`/`statusSignal`) so the shell
   * stops rendering around a session that no longer exists — `authEnabledSignal` is left untouched
   * at `true`, since this can only ever fire once auth was already enabled (every endpoint capable
   * of a genuine session-death 401 requires one). The interceptor itself performs the actual
   * navigation to `/login`; this method only updates state.
   *
   * Either branch is superseded the instant `login()`/`logout()` next resolves a session
   * (`applySession` always clears {@link reauthRequiredSignal}), so a successful reauth — through
   * the overlay or through `/login` itself — closes this out exactly like an ordinary login would.
   */
  sessionExpired(onFlyRoute: boolean): void {
    if (onFlyRoute) {
      this.reauthRequiredSignal.set(true);
      return;
    }
    this.userSignal.set(null);
    this.statusSignal.set('anon');
  }

  /**
   * Clears the session and navigates away — the one method on this store that owns navigation
   * itself, unlike `FleetStore`'s own convention of leaving that to the caller (its class doc:
   * a caller "knows whether a streamId came back and wants a Watch action, which only it can
   * construct"). Logout has no such caller-specific follow-up — there is exactly one sane
   * destination — so there is nothing a page-level caller could usefully decide instead. Routes to
   * `/login` when auth is enabled, `/fly` when it isn't (dev parity — logging out of a session that
   * was never real just returns to the app, unchanged). Clears local state even if the request
   * itself fails (best-effort — there is no server-side state left to reconcile against either way).
   */
  async logout(): Promise<void> {
    try {
      await this.api.authLogout();
    } catch (error) {
      console.warn(`${LOG_PREFIX} POST /api/auth/logout failed — clearing the local session anyway`, { error });
    }
    const wasAuthEnabled = this.authEnabledSignal();
    this.userSignal.set(null);
    this.statusSignal.set('anon');
    if (wasAuthEnabled) {
      // Only when there was a real session to invalidate — dev parity (`authEnabled === false`)
      // never had a session change happen at all, so leaving the live connection alone is correct.
      this.liveStore.stop();
    }
    await this.router.navigateByUrl(wasAuthEnabled ? '/login' : '/fly');
  }

  /**
   * Self-service password change (`POST /api/auth/password`, docs/plans/active/AUTH-ROLES-PLAN.md §3.5,
   * wave B3) — re-confirms `currentPassword` rather than re-authenticating. On success, re-fetches
   * `/api/auth/me` so `mustChangePassword` (and anything else the backend recomputed) reflects
   * reality immediately, rather than this store guessing `false` locally. Never throws — a failure
   * (401 wrong current password, 400 weak new password) is turned into a returned message, for
   * `shared/ui/force-password-change.ts`/the account-settings Security section's own inline handling.
   */
  async changePassword(currentPassword: string, newPassword: string): Promise<string | null> {
    const request: ChangePasswordRequest = { currentPassword, newPassword };
    try {
      await this.api.changePassword(request);
      await this.loadMe();
      return null;
    } catch (error) {
      // A 401 here means "wrong current password" (`AuthPasswordController`'s own javadoc) — this
      // endpoint is excluded from `session-interceptor.ts`'s generic handling for exactly this
      // reason, so `describeHttpError`'s generic session-death copy would be actively misleading.
      if (error instanceof HttpErrorResponse && error.status === 401) {
        return 'Incorrect current password.';
      }
      return describeHttpError(error);
    }
  }

  /** Shared by `loadMe`/`login` — both resolve to the same `MeResponse | null` shape and apply it identically. */
  private applySession(me: MeResponse | null): void {
    this.reauthRequiredSignal.set(false); // a resolved session (real or anon) always supersedes it.
    if (me === null) {
      this.userSignal.set(null);
      this.authEnabledSignal.set(true); // see `loadMe`'s own doc comment for why this is safe here.
      this.statusSignal.set('anon');
      return;
    }
    this.userSignal.set(me);
    this.authEnabledSignal.set(me.authEnabled);
    this.statusSignal.set('authed');
  }
}

function loginErrorMessage(error: unknown): string {
  if (error instanceof HttpErrorResponse && error.status === 401) {
    return 'Incorrect username or password.';
  }
  return describeHttpError(error);
}
