import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthStore } from './auth-store';
import { LiveStore } from '../live/live-store';
import { sessionInterceptor } from './session-interceptor';

/**
 * Cold-boot regression coverage for the one wiring every unit spec structurally skips: the REAL
 * interceptor chain running inside `AuthStore`'s own constructor-time `GET /api/auth/me`.
 *
 * The original interceptor called `inject(AuthStore)` at entry, before its exclusion check. The
 * first request the app ever makes is issued from `AuthStore`'s constructor, so the interceptor
 * asked DI for a token still mid-construction — a synchronous circular-dependency throw, no
 * network I/O, silently folded into `loadMe`'s catch. Every capability-gated page then bounced on
 * cold boot (auth degraded to anon, capabilities `[]`) while all tests stayed green, because they
 * always built `AuthStore` against a stubbed api, never through the real `HttpClient` pipeline.
 * This spec builds exactly that pipeline and asserts the request reaches the backend.
 */
describe('sessionInterceptor cold boot', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    TestBed.resetTestingModule();
  });

  function setup() {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'login', children: [] },
          { path: 'fly', children: [] },
        ]),
        provideHttpClient(withInterceptors([sessionInterceptor])),
        provideHttpClientTesting(),
        { provide: LiveStore, useValue: { reconnect: vi.fn(), stop: vi.fn() } },
      ],
    });
  }

  it('lets AuthStore’s constructor-time GET /api/auth/me reach the wire (no circular injection)', async () => {
    setup();

    const store = TestBed.inject(AuthStore);
    const http = TestBed.inject(HttpTestingController);

    // With the eager inject(AuthStore) bug, no request ever left the client — expectOne throws.
    const req = http.expectOne('/api/auth/me');
    req.flush(null, { status: 401, statusText: 'Unauthorized' });

    await store.ready;
    // 401 on /me is the honest signed-out answer, not a degraded error path.
    expect(store.status()).toBe('anon');
  });

  it('still redirects a non-excluded 401 through the session-expired flow', async () => {
    setup();

    const store = TestBed.inject(AuthStore);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/auth/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    await store.ready;

    const expired = vi.spyOn(store, 'sessionExpired');
    const client = TestBed.inject(HttpTestingController);
    const { HttpClient } = await import('@angular/common/http');
    const httpClient = TestBed.inject(HttpClient);

    const call = httpClient.get('/api/assets').toPromise().catch((e: unknown) => e);
    client.expectOne('/api/assets').flush(null, { status: 401, statusText: 'Unauthorized' });
    await call;

    expect(expired).toHaveBeenCalledWith(false);
  });
});
