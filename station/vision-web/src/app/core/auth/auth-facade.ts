import { Injectable, computed, inject } from '@angular/core';
import { Actions, ofType } from '@ngrx/effects';
import { Store } from '@ngrx/store';
import { firstValueFrom } from 'rxjs';
import { take } from 'rxjs/operators';
import type { AuthCapability, BootstrapRequest, ScopeKind } from '../api/models';
import { dispatchAndAwait } from '../state/dispatch-bridge';
import { hasCapability } from './auth-logic';
import { AuthApiActions, AuthPageActions } from './state/auth.actions';
import { authFeature } from './state/auth.reducer';

/**
 * The app's one source of truth for "who is logged in" (docs/plans/done/NGRX-MIGRATION-PLAN.md
 * wave N2, replacing the old `AuthStore`) — `providedIn: 'root'`, one instance app-wide,
 * self-initializing: the constructor dispatches `AuthPageActions.bootRequested()` itself rather than
 * needing `app.ts` or an `APP_INITIALIZER` to remember to call it.
 *
 * **Why the constructor dispatches directly, instead of a `ROOT_EFFECTS_INIT`-triggered boot
 * effect** (a corrected design from this wave's original sketch): Angular's `ENVIRONMENT_INITIALIZER`
 * ordering guarantee — every `ENVIRONMENT_INITIALIZER` on an environment injector runs to completion
 * *before* that injector services any other injection request — is what makes NgRx's
 * `provideEffects()` eagerly invoke every registered effect's factory (subscribing it to the shared
 * `Actions` stream) before any lazily-`providedIn: 'root'` service, this facade included, can ever be
 * the *first* thing injected. So by the time this constructor runs, `bootMe$` (an ordinary
 * `ofType(AuthPageActions.bootRequested)` effect, not itself special-cased to `ROOT_EFFECTS_INIT`) is
 * already subscribed — dispatching here, then racing `ready`'s own listener against it via
 * {@link dispatchAndAwait}'s "subscribe before dispatch" rule, is structurally race-free without
 * needing the boot effect to run any earlier than any other effect.
 *
 * **No boot-time render flash**: `ready` is what `core/auth/auth-guard.ts` awaits before deciding
 * whether to redirect — the very first navigation of a session is therefore always decided against
 * the *resolved* session, never a page briefly mounting behind a stale `'loading'` guess.
 *
 * **Dev parity (`authEnabled === false`)**: every backend auth endpoint is then a no-op that always
 * reports the same fixed dev admin — `bootMe$`/`login$` both fold that response into `status:
 * 'authed'` exactly like a real session (see `auth-logic.ts#needsLogin`'s own doc comment).
 */
@Injectable({ providedIn: 'root' })
export class AuthFacade {
  private readonly store = inject(Store);
  private readonly actions$ = inject(Actions);

  readonly user = this.store.selectSignal(authFeature.selectUser);
  readonly authEnabled = this.store.selectSignal(authFeature.selectAuthEnabled);
  readonly status = this.store.selectSignal(authFeature.selectStatus);
  /** Disables the login form's submit button and blocks a second in-flight submit. */
  readonly loginBusy = this.store.selectSignal(authFeature.selectLoginBusy);
  /** The login form's inline error text, or `null` — cleared at the start of every new attempt. */
  readonly loginError = this.store.selectSignal(authFeature.selectLoginError);
  /** `shared/ui/reauth-overlay.ts`'s own gate — see `state/auth.actions.ts`'s `Session Expired` doc. */
  readonly reauthRequired = this.store.selectSignal(authFeature.selectReauthRequired);

  /** The verbs this session holds, deployment/org-wide — `[]` while there is no session. */
  readonly capabilities = computed<readonly AuthCapability[]>(() => this.user()?.capabilities ?? []);

  /** Which `VisibilityScope.Kind` this session's visibility resolves to — `undefined` while there is no session. */
  readonly scopeKind = computed<ScopeKind | undefined>(() => this.user()?.scopeKind);

  /** Mirrors `User#mustChangePassword()` — `false` while there is no session. */
  readonly mustChangePassword = computed(() => this.user()?.mustChangePassword ?? false);

  /** Resolves once the boot-time load settles — see class doc. Never rejects: `bootMe$` catches
   *  every failure path internally and always emits a resolved action. */
  readonly ready: Promise<void> = dispatchAndAwait(
    this.store,
    this.actions$,
    AuthPageActions.bootRequested(),
    AuthApiActions.meLoaded,
    AuthApiActions.meLoadFailed,
    () => undefined,
    () => undefined,
  );

  /** Convenience over `hasCapability(this.capabilities(), capability)`. */
  can(capability: AuthCapability): boolean {
    return hasCapability(this.capabilities(), capability);
  }

  /** `POST /api/auth/login`. Never throws — a failure is turned into `loginError` (via the reducer)
   *  and a `false` return; a caller that only cares about success/failure can just check the return value. */
  async login(username: string, password: string): Promise<boolean> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      AuthPageActions.loginRequested({ username, password }),
      AuthApiActions.loginSucceeded,
      AuthApiActions.loginFailed,
      () => true,
      () => false,
    );
  }

  /** A request 401'd — called only by `core/auth/session-interceptor.ts`. Plain dispatch, no bridge:
   *  this is a pure local state transition, never awaited by its one caller. */
  sessionExpired(onFlyRoute: boolean): void {
    this.store.dispatch(AuthPageActions.sessionExpired({ onFlyRoute }));
  }

  /** Clears the session and navigates away. Resolves once the whole two-stage effect chain
   *  (`logout$` then `logoutSideEffects$`) has finished, mirroring `AuthStore#logout`'s own
   *  `await this.router.navigateByUrl(...)` as its last line before returning. */
  async logout(): Promise<void> {
    const finished = firstValueFrom(this.actions$.pipe(ofType(AuthApiActions.logoutFinished), take(1)));
    this.store.dispatch(AuthPageActions.logoutRequested());
    await finished;
  }

  /** Self-service password change. Never throws — a failure (401 wrong current password, 400 weak
   *  new password) is turned into a returned message. On success, state has already been refreshed
   *  from `/api/auth/me` by the time this resolves (see `auth.effects.ts#changePassword$`'s doc). */
  async changePassword(currentPassword: string, newPassword: string): Promise<string | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      AuthPageActions.changePasswordRequested({ currentPassword, newPassword }),
      AuthApiActions.changePasswordSucceeded,
      AuthApiActions.changePasswordFailed,
      () => null,
      (action) => action.message,
    );
  }

  /** `GET /api/auth/bootstrap` — deliberately never cached; see `auth.effects.ts#bootstrapRequired$`'s
   *  doc. There is no failure counterpart to listen for — the effect always resolves. */
  async bootstrapRequired(): Promise<boolean> {
    const resolved = firstValueFrom(this.actions$.pipe(ofType(AuthApiActions.bootstrapRequiredResolved), take(1)));
    this.store.dispatch(AuthPageActions.bootstrapRequiredRequested());
    return (await resolved).required;
  }

  /** `POST /api/auth/bootstrap` — creates this station's first administrator and establishes their
   *  session. Never throws — mirrors `login()`'s own "turn a failure into a message" contract. */
  async bootstrap(request: BootstrapRequest): Promise<string | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      AuthPageActions.bootstrapRequested({ request }),
      AuthApiActions.bootstrapSucceeded,
      AuthApiActions.bootstrapFailed,
      () => null,
      (action) => action.message,
    );
  }
}
