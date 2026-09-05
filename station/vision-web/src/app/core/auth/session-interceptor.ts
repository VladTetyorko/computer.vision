import { HttpErrorResponse, type HttpInterceptorFn } from '@angular/common/http';
import { Injector, inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthStore } from './auth-store';

const EXCLUDED_PREFIXES = ['/api/auth/login', '/api/auth/me', '/api/auth/bootstrap', '/api/auth/password'];

/**
 * Folds any 401 outside the auth endpoints themselves into the session-expired flow: on /fly an
 * in-place overlay (never a navigation — a live cockpit must not be torn down under the operator),
 * everywhere else a redirect to /login carrying the interrupted URL.
 *
 * {@link AuthStore} is resolved lazily through {@link Injector}, and only inside the 401 handler —
 * never at interceptor entry. The very first request this app makes is `AuthStore`'s own
 * constructor-time GET /api/auth/me, and this interceptor runs inside that call: an eager
 * `inject(AuthStore)` here asks DI for a token that is still mid-construction, which throws a
 * circular-dependency error before any network I/O. `loadMe`'s catch then silently degraded the
 * whole session to anon ("dev parity"), so every capability-gated page bounced on cold boot while
 * every test stayed green (tests construct AuthStore and the interceptor chain separately, never
 * one inside the other). The exclusion check runs first for the same reason.
 */
export const sessionInterceptor: HttpInterceptorFn = (req, next) => {
  if (EXCLUDED_PREFIXES.some((prefix) => req.url.startsWith(prefix))) {
    return next(req);
  }
  const injector = inject(Injector);

  return next(req).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        const auth = injector.get(AuthStore);
        const router = injector.get(Router);
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
