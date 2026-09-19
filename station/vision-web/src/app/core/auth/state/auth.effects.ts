import { inject } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { concatLatestFrom } from '@ngrx/operators';
import { Store } from '@ngrx/store';
import { catchError, from, map, mergeMap, of, switchMap } from 'rxjs';
import { describeHttpError } from '../../api-error';
import { VisionApi } from '../../api/vision-api';
import { AuthApiActions, AuthPageActions } from './auth.actions';
import { authFeature } from './auth.reducer';

/** Console prefix mirroring the old `AuthStore`'s own `[auth]` tag. */
const LOG_PREFIX = '[auth]';

/** Module-private — mirrors `AuthStore`'s own free function of the same name exactly: a 401 alone
 *  gets the deliberately-vague "bad credentials" copy, everything else goes through the shared
 *  `describeHttpError`. */
function loginErrorMessage(error: unknown): string {
  if (error instanceof HttpErrorResponse && error.status === 401) {
    return 'Incorrect username or password.';
  }
  return describeHttpError(error);
}

/**
 * `GET /api/auth/me` — triggered once, by `AuthFacade`'s own constructor dispatching
 * `AuthPageActions.bootRequested()` (never by `ROOT_EFFECTS_INIT` directly; see that class's doc
 * comment for why). A clean `401` (`VisionApi.authMe()` already folds it into `null`) resolves as an
 * ordinary `Me Loaded({ me: null })` — the reducer's `applySession` is what infers `authEnabled: true`
 * from that. Only a network/5xx failure reaches `catchError`, degrading to `'anon'` without touching
 * `authEnabled` at all — mirrors `AuthStore#loadMe`'s exact try/catch split.
 */
export const bootMe$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(AuthPageActions.bootRequested),
      switchMap(() =>
        from(api.authMe()).pipe(
          map((me) => AuthApiActions.meLoaded({ me })),
          catchError((error: unknown) => {
            console.warn(`${LOG_PREFIX} GET /api/auth/me failed — treating this session as signed out`, { error });
            return of(AuthApiActions.meLoadFailed());
          }),
        ),
      ),
    ),
  { functional: true },
);

/** `POST /api/auth/login` — never lets a rejection reach the stream; a 401 or any other failure both
 *  become `Login Failed` with a message, mirroring `AuthStore#login`'s own "never throws" contract. */
export const login$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(AuthPageActions.loginRequested),
      switchMap(({ username, password }) =>
        from(api.authLogin(username, password)).pipe(
          map((me) => AuthApiActions.loginSucceeded({ me })),
          catchError((error: unknown) => of(AuthApiActions.loginFailed({ message: loginErrorMessage(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/** `POST /api/auth/bootstrap` — mirrors `login$`'s shape exactly, per `AuthStore#bootstrap`'s own doc
 *  ("the same `applySession`/`liveStore.reconnect()` sequence `login()` follows"). */
export const bootstrap$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(AuthPageActions.bootstrapRequested),
      switchMap(({ request }) =>
        from(api.bootstrap(request)).pipe(
          map((me) => AuthApiActions.bootstrapSucceeded({ me })),
          catchError((error: unknown) => of(AuthApiActions.bootstrapFailed({ message: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/**
 * Logout, stage 1 — reads `wasAuthEnabled` *before* the reducer clears it (`concatLatestFrom`, the
 * same "read current state alongside the triggering action" idiom `settings.effects.ts` uses),
 * best-effort calls `POST /api/auth/logout` (clears local state even if the request itself fails —
 * there is no server-side state left to reconcile against either way), then hands `wasAuthEnabled`
 * forward to stage 2 via `Logout Completed`.
 */
export const logout$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi), store = inject(Store)) =>
    actions$.pipe(
      ofType(AuthPageActions.logoutRequested),
      concatLatestFrom(() => store.select(authFeature.selectAuthEnabled)),
      switchMap(([, wasAuthEnabled]) =>
        from(
          (async () => {
            try {
              await api.authLogout();
            } catch (error) {
              console.warn(`${LOG_PREFIX} POST /api/auth/logout failed — clearing the local session anyway`, {
                error,
              });
            }
            return AuthApiActions.logoutCompleted({ wasAuthEnabled });
          })(),
        ),
      ),
    ),
  { functional: true },
);

/**
 * Logout, stage 2 — the navigation `AuthStore#logout` used to do inline, now split out because it is
 * a side effect the reducer can't own. Routes to `/login` when auth is enabled, `/fly` when it isn't
 * — `AuthFacade#logout()` is the one caller that awaits `Logout Finished`.
 *
 * **This file no longer knows that a live connection exists** (NGRX-MIGRATION-PLAN.md wave N9). The
 * `LiveFacade.stop()` that used to run here — behind a lazy `Injector.get`, to dodge an
 * effects-registration-order hazard — is now `core/live/state/live.effects.ts#stopOnLogout$`,
 * reacting to this stage's own `Logout Completed` action. See that effect's doc comment for why the
 * dependency belongs in that direction.
 */
export const logoutSideEffects$ = createEffect(
  (actions$ = inject(Actions), router = inject(Router)) =>
    actions$.pipe(
      ofType(AuthApiActions.logoutCompleted),
      switchMap(({ wasAuthEnabled }) =>
        from(
          (async () => {
            await router.navigateByUrl(wasAuthEnabled ? '/login' : '/fly');
            return AuthApiActions.logoutFinished();
          })(),
        ),
      ),
    ),
  { functional: true },
);

/**
 * `POST /api/auth/password` — re-fetches `/api/auth/me` as part of the *same* chain on success so
 * `mustChangePassword` (and anything else recomputed) is already reflected in state by the time
 * `AuthFacade#changePassword()`'s promise resolves (mirrors `AuthStore#changePassword`'s own
 * `await this.loadMe()` before returning). Emits `Me Loaded` then `Change Password Succeeded`, in
 * that order, from one source action — `mergeMap(actions => from(actions))` flattens whichever
 * 1-or-2-element outcome array the inner async call produced. A 401 here means "wrong current
 * password" (excluded from `session-interceptor.ts`'s generic handling for that reason, so the
 * generic session-death copy would be actively misleading).
 */
export const changePassword$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(AuthPageActions.changePasswordRequested),
      switchMap(({ currentPassword, newPassword }) =>
        from(
          (async () => {
            try {
              await api.changePassword({ currentPassword, newPassword });
              const me = await api.authMe();
              return [AuthApiActions.meLoaded({ me }), AuthApiActions.changePasswordSucceeded()];
            } catch (error) {
              const message =
                error instanceof HttpErrorResponse && error.status === 401
                  ? 'Incorrect current password.'
                  : describeHttpError(error);
              return [AuthApiActions.changePasswordFailed({ message })];
            }
          })(),
        ).pipe(mergeMap((actions) => from(actions))),
      ),
    ),
  { functional: true },
);

/**
 * `GET /api/auth/bootstrap` — deliberately never cached (see `AuthStore#bootstrapRequired`'s own doc
 * comment: "a one-way latch server-side"). Fails safe to `required: false` on any error, with a
 * console warning, never a `catchError` that lets the stream die — mirrors the original's own
 * "never throws" contract exactly, just re-homed from a `try/catch` to this effect.
 */
export const bootstrapRequired$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(AuthPageActions.bootstrapRequiredRequested),
      switchMap(() =>
        from(api.bootstrapStatus()).pipe(
          map((status) => AuthApiActions.bootstrapRequiredResolved({ required: status.required })),
          catchError((error: unknown) => {
            console.warn(`${LOG_PREFIX} GET /api/auth/bootstrap failed — assuming no bootstrap is needed`, {
              error,
            });
            return of(AuthApiActions.bootstrapRequiredResolved({ required: false }));
          }),
        ),
      ),
    ),
  { functional: true },
);

export const authEffects = {
  bootMe$,
  login$,
  bootstrap$,
  logout$,
  logoutSideEffects$,
  changePassword$,
  bootstrapRequired$,
};
