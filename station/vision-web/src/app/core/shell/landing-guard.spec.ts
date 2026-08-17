import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { landingGuard } from './landing-guard';
import { AuthStore } from '../auth/auth-store';
import type { MeResponse } from '../api/models';

/**
 * `landingGuard` is otherwise thin wiring over `landing-logic.ts#landingRouteFor` (fully covered in
 * `landing-logic.spec.ts`), the same "no dedicated spec" precedent `core/auth/auth-guard.ts`'s own
 * doc comment states. This one file earns an exception: the routing *shape* it needs —
 * `{ path: '', canActivate: [...], children: [] }`, the standard trick for a guard whose redirect
 * target can only be known after an async `AuthStore.ready` (`redirectTo` alone can't be async or
 * DI-computed) — is itself the thing worth regression-covering, decoupled from the real app's heavy
 * feature routes (`app.routes.spec.ts` covers the real table's shape, not runtime navigation).
 */
function fakeAuthStore(user: Pick<MeResponse, 'topRole'> | null, authEnabled: boolean) {
  return {
    ready: Promise.resolve(),
    user: () => user,
    authEnabled: () => authEnabled,
  };
}

@Component({ selector: 'vision-test-stub', template: '' })
class StubPage {}

function configure(user: Pick<MeResponse, 'topRole'> | null, authEnabled = true) {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        { path: '', canActivate: [landingGuard], children: [] },
        { path: 'fly', component: StubPage },
        { path: 'command', component: StubPage },
      ]),
      { provide: AuthStore, useValue: fakeAuthStore(user, authEnabled) },
    ],
  });
}

describe('landingGuard (routing integration)', () => {
  it('redirects "" to /command for an ADMIN', async () => {
    configure({ topRole: 'ADMIN' });
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/');
    expect(router.url).toBe('/command');
  });

  it('redirects "" to /command for a MANAGER', async () => {
    configure({ topRole: 'MANAGER' });
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/');
    expect(router.url).toBe('/command');
  });

  it('redirects "" to /fly for a PILOT', async () => {
    configure({ topRole: 'PILOT' });
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/');
    expect(router.url).toBe('/fly');
  });

  it('redirects "" to /fly when no session has resolved (never dangles on an unresolved role)', async () => {
    configure(null);
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/');
    expect(router.url).toBe('/fly');
  });

  // The dev principal reports topRole ADMIN with auth off (`MeResponse#devAdmin`). Landing on
  // /command there would move every unsecured install and demo off the cockpit MVP3 §C-b chose.
  it('redirects "" to /fly with auth disabled, despite the dev principal reporting ADMIN', async () => {
    configure({ topRole: 'ADMIN' }, false);
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/');
    expect(router.url).toBe('/fly');
  });

  it('a deep link straight to /fly never touches the guard at all', async () => {
    configure({ topRole: 'ADMIN' }); // would redirect "" to /command — proves this path is untouched
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/fly');
    expect(router.url).toBe('/fly');
  });
});
