import { HttpErrorResponse, type HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthStore } from './auth-store';

/**
 * URL prefixes this interceptor never acts on — each is either how a session *becomes* real (so a
 * 401 there is the expected, first-class "not logged in yet"/"bad credentials" outcome its own
 * caller already handles inline — `AuthStore.login`/`loadMe`, `features/setup/**`), or a caller
 * re-confirming a credential that can itself be wrong (`AuthPasswordController.changePassword`
 * genuinely 401s on a wrong *current* password — indistinguishable, on this status code alone, from
 * an actually-dead session, so this must stay the caller's own inline error, never a forced
 * navigation away from whatever settings page it was submitted from).
 */
const EXCLUDED_PREFIXES = ['/api/auth/login', '/api/auth/me', '/api/auth/bootstrap', '/api/auth/password'];

/**
 * The app's first `HttpInterceptorFn` (docs/plans/active/AUTH-ROLES-PLAN.md §3.6/§3.7, wave W1) — the one
 * place a session-death `401` (any endpoint not in {@link EXCLUDED_PREFIXES} above) is turned into
 * either an in-place reauth or a redirect, so no individual store/facade has to special-case it.
 *
 * **The frozen `/fly` rule (§3.7 clause 2), and why this is the whole of it**: a 401 while on `/fly`
 * must present as an in-place overlay, never a navigation away, and must never touch the manual-control
 * RC websocket. This function satisfies that by construction — it calls exactly one method
 * (`AuthStore.sessionExpired`) and, for the `/fly` branch, nothing else at all: no route change, no
 * reach into `ManualControlClient` (which is component-tree-scoped, provided inside `FlyHud`'s own
 * `providers` array — architecturally unreachable from a root-level interceptor without hoisting it to
 * `providedIn: 'root'`, a change to that already-shipped, heavily-tested socket client this wave
 * deliberately avoids). The plan's own wording asks specifically for "never touch/close the RC
 * websocket" — satisfied trivially by touching nothing RC-related in the first place, a strict
 * superset of the narrower "only skip it while a control session is actually engaged" reading.
 *
 * Off `/fly`, the session is cleared locally (`AuthStore.sessionExpired(false)`) and the browser is
 * routed to `/login?returnUrl=<the page that 401'd>` — mirrors `auth-guard.ts`'s own `returnUrl`
 * convention exactly, so signing back in from here lands the user back where they were, not always
 * `/fly`. Clearing the local session (not just navigating) matters: `app.html`'s sidebar and
 * `shared/ui/identity-chip.ts` both render off `AuthStore.user()` regardless of route, so leaving a
 * stale non-null session in place while parked on `/login` would show the shell around a login form
 * that has no session backing it.
 */
export const sessionInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthStore);
  const router = inject(Router);

  if (EXCLUDED_PREFIXES.some((prefix) => req.url.startsWith(prefix))) {
    return next(req);
  }

  return next(req).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        const onFly = router.url.startsWith('/fly');
        auth.sessionExpired(onFly);
        if (!onFly) {
          const target = router.createUrlTree(['/login'], { queryParams: { returnUrl: router.url } });
          void router.navigateByUrl(target);
        }
      }
      return throwError(() => error);
    }),
  );
};
