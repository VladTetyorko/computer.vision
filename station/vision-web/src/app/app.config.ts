import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import {
  provideRouter,
  withComponentInputBinding,
  withInMemoryScrolling,
  withPreloading,
} from '@angular/router';

import { routes } from './app.routes';
import { IdlePreload } from './core/idle-preload';
import { sessionInterceptor } from './core/auth/session-interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'top' }),
      withPreloading(IdlePreload),
    ),
    // `sessionInterceptor` (docs/plans/active/AUTH-ROLES-PLAN.md §3.6/§3.7, wave W1) — this app's first
    // `HttpInterceptorFn`; see that file's own doc comment for what it does with a session-death 401.
    provideHttpClient(withFetch(), withInterceptors([sessionInterceptor])),
  ],
};
