import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { DEVICES_ROUTES } from './devices.routes';

/**
 * `DEVICES_ROUTES`' `redirectTo` is a `RedirectFunction` (`inject(Router).createUrlTree(...)`), not
 * a plain string — `features/hubs/route-audit-logic.ts#flattenRoutes`'s pure walker only proves the
 * path is *registered* (no dead link), it can't inspect what a computed redirect resolves to. This
 * is a real routing-integration test (`TestBed` + `provideRouter`, same shape as
 * `core/shell/landing-guard.spec.ts`) proving the query param actually lands.
 */
@Component({ selector: 'vision-test-stub', template: '' })
class StubPage {}

describe('DEVICES_ROUTES (routing integration)', () => {
  it('/devices redirects to /assets?tab=links', async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([...DEVICES_ROUTES, { path: 'assets', component: StubPage }])],
    });
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/devices');
    expect(router.url).toBe('/assets?tab=links');
  });
});
