import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { REPORTS_ROUTES } from './reports.routes';

/** See `features/devices/devices.routes.spec.ts`'s identical doc comment for why this is a real
 *  routing-integration test rather than `route-audit-logic.ts`'s pure path-registration check. */
@Component({ selector: 'vision-test-stub', template: '' })
class StubPage {}

describe('REPORTS_ROUTES (routing integration)', () => {
  it('/manage/reports redirects to plain /assets (no ?tab= — the KPI strip has no tab of its own)', async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([...REPORTS_ROUTES, { path: 'assets', component: StubPage }])],
    });
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/manage/reports');
    expect(router.url).toBe('/assets');
  });
});
