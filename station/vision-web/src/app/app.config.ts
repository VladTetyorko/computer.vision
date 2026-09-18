import { ApplicationConfig, isDevMode, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import {
  provideRouter,
  withComponentInputBinding,
  withInMemoryScrolling,
  withPreloading,
} from '@angular/router';
import { MinimalRouterStateSerializer, provideRouterStore } from '@ngrx/router-store';
import { provideStoreDevtools } from '@ngrx/store-devtools';

import { routes } from './app.routes';
import { IdlePreload } from './core/idle-preload';
import { sessionInterceptor } from './core/auth/session-interceptor';
import { provideAppState } from './core/state/app-state';

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
    // The store itself, its slices and their effects (docs/plans/active/NGRX-MIGRATION-PLAN.md §2) —
    // shared verbatim with the specs, see `core/state/app-state.ts`.
    provideAppState(),
    // The minimal serializer, not the full one: a full `RouterStateSnapshot` carries component
    // classes and injectors that would trip `strictStateSerializability` on the first navigation.
    provideRouterStore({ serializer: MinimalRouterStateSerializer }),
    ...(isDevMode() ? [provideStoreDevtools({ maxAge: 50, connectInZone: false })] : []),
  ],
};
