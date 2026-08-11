import { Injectable, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { VisionApi } from '../api/vision-api';
import { describeHttpError } from '../api-error';
import type { MeResponse } from '../api/models';
import type { AuthStatus } from './auth-logic';

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

  private readonly userSignal = signal<MeResponse | null>(null);
  private readonly authEnabledSignal = signal(false);
  private readonly statusSignal = signal<AuthStatus>('loading');
  private readonly loginBusySignal = signal(false);
  private readonly loginErrorSignal = signal<string | null>(null);

  readonly user = this.userSignal.asReadonly();
  /** `false` until the first `loadMe()` resolves and says otherwise — see class doc's "no render flash". */
  readonly authEnabled = this.authEnabledSignal.asReadonly();
  readonly status = this.statusSignal.asReadonly();
  /** Disables the login form's submit button and blocks a second in-flight submit — see `login()`. */
  readonly loginBusy = this.loginBusySignal.asReadonly();
  /** The login form's inline error text, or `null` — cleared at the start of every new attempt. */
  readonly loginError = this.loginErrorSignal.asReadonly();

  /** Resolves once the boot-time `loadMe()` settles — see class doc. Never rejects: every failure path inside `loadMe()` is caught and turned into `'anon'`. */
  readonly ready: Promise<void>;

  constructor() {
    this.ready = this.loadMe();
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
      return true;
    } catch (error) {
      this.loginErrorSignal.set(loginErrorMessage(error));
      return false;
    } finally {
      this.loginBusySignal.set(false);
    }
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
    await this.router.navigateByUrl(wasAuthEnabled ? '/login' : '/fly');
  }

  /** Shared by `loadMe`/`login` — both resolve to the same `MeResponse | null` shape and apply it identically. */
  private applySession(me: MeResponse | null): void {
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
