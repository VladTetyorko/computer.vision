import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthFacade } from './auth-facade';
import { provideAppState } from '../state/app-state';
import { sessionInterceptor } from './session-interceptor';

/**
 * Cold-boot regression coverage for the one wiring every unit spec structurally skips: the REAL
 * interceptor chain running inside `AuthFacade`'s own constructor-time `GET /api/auth/me` (now
 * `bootMe$`'s effect, triggered by the constructor's own `AuthPageActions.bootRequested()` dispatch —
 * see that class's doc comment).
 *
 * The original interceptor called `inject(AuthFacade)` (then `AuthStore`) at entry, before its
 * exclusion check. The first request the app ever makes is issued from the facade's own
 * constructor, so the interceptor asked DI for a token still mid-construction — a synchronous
 * circular-dependency throw, no network I/O, silently folded into the boot effect's `catchError`.
 * Every capability-gated page then bounced on cold boot (auth degraded to anon, capabilities `[]`)
 * while all tests stayed green, because they always built the facade against a stubbed api, never
 * through the real `HttpClient` pipeline. This spec builds exactly that pipeline (real `VisionApi`,
 * real `provideAppState()`) and asserts the request reaches the backend.
 *
 * <h2>wave N4b — `fleet`/`systemStatus` are root-registered too</h2>
 * `provideAppState()` now also root-registers `fleet`/`systemStatus` and their effects
 * (`core/state/app-state.ts`'s own doc comment) — a root NgRx effect starts the moment the
 * environment injector realizes (this file's own first `TestBed.inject(...)` call), independent of
 * whether anything ever injects `FleetFacade`/`SystemStatusFacade` (see `core/live/poll-rate.spec.ts`'s
 * identical "wave N4b" doc comment for the mechanism). That is exactly what the *real* app does at
 * cold boot too (`AppSidebar` mounts both unconditionally), so `GET /api/devices`/`GET /api/streams`/
 * `GET /api/system/status` are now genuine, expected requests here — `flushFleetBoot` answers them so
 * `HttpTestingController.verify()` in `afterEach` sees no unhandled traffic.
 */
describe('sessionInterceptor cold boot', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    TestBed.resetTestingModule();
  });

  function setup() {
    TestBed.configureTestingModule({
      providers: [
        provideAppState(),
        provideRouter([
          { path: 'login', children: [] },
          { path: 'fly', children: [] },
        ]),
        provideHttpClient(withInterceptors([sessionInterceptor])),
        provideHttpClientTesting(),
      ],
    });
  }

  /** Answers the three requests `fleet`/`systemStatus`'s own root-registered `gate$` effects issue
   *  unconditionally at boot — see this file's own "wave N4b" doc comment. */
  function flushFleetBoot(http: HttpTestingController): void {
    http.expectOne('/api/devices').flush([]);
    http.expectOne('/api/streams').flush([]);
    http.expectOne('/api/system/status').flush({ overall: 'OK', checkedAt: '2026-08-15T00:00:00Z', subsystems: [] });
  }

  it('lets AuthFacade’s constructor-time GET /api/auth/me reach the wire (no circular injection)', async () => {
    setup();

    const facade = TestBed.inject(AuthFacade);
    const http = TestBed.inject(HttpTestingController);

    // With the eager inject(AuthFacade) bug, no request ever left the client — expectOne throws.
    const req = http.expectOne('/api/auth/me');
    req.flush(null, { status: 401, statusText: 'Unauthorized' });
    flushFleetBoot(http);

    await facade.ready;
    // 401 on /me is the honest signed-out answer, not a degraded error path.
    expect(facade.status()).toBe('anon');
  });

  it('still redirects a non-excluded 401 through the session-expired flow', async () => {
    setup();

    const facade = TestBed.inject(AuthFacade);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/auth/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    flushFleetBoot(http);
    await facade.ready;

    const expired = vi.spyOn(facade, 'sessionExpired');
    const client = TestBed.inject(HttpTestingController);
    const { HttpClient } = await import('@angular/common/http');
    const httpClient = TestBed.inject(HttpClient);

    const call = httpClient.get('/api/assets').toPromise().catch((e: unknown) => e);
    client.expectOne('/api/assets').flush(null, { status: 401, statusText: 'Unauthorized' });
    await call;

    expect(expired).toHaveBeenCalledWith(false);
  });
});
