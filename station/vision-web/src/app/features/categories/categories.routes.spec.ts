import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { CATEGORIES_ROUTES } from './categories.routes';

/** See `features/devices/devices.routes.spec.ts`'s identical doc comment for why this is a real
 *  routing-integration test rather than `route-audit-logic.ts`'s pure path-registration check. */
@Component({ selector: 'vision-test-stub', template: '' })
class StubPage {}

describe('CATEGORIES_ROUTES (routing integration)', () => {
  it('/manage/categories redirects to /assets?tab=categories', async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([...CATEGORIES_ROUTES, { path: 'assets', component: StubPage }])],
    });
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/manage/categories');
    expect(router.url).toBe('/assets?tab=categories');
  });
});
