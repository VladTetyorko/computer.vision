import type { Route, Routes } from '@angular/router';
import { beforeAll, describe, expect, it } from 'vitest';
import { FLY_ROUTES } from './fly.routes';
import { flyRedirectGuard } from './fly-redirect-guard';

/**
 * `/fly` route-table shape (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.5 F12 — "the cockpit is not
 * addressable"). A pure, no-`TestBed` structural check on the real exported route arrays, mirroring
 * `app.routes.spec.ts`'s own "resolves to its own component, not X" precedent, scoped to this one
 * feature. Exercises `loadChildren`/`loadComponent` directly rather than booting a router — the
 * concern here is "did the split wire the right component to the right path", not runtime navigation
 * behavior.
 *
 * **Two splits are asserted here, not one.** The original (F12) split one component across two
 * routes. NGRX-MIGRATION wave N-split then put both behind a `loadChildren` boundary so this
 * feature's five NgRx slices stay out of the initial bundle — which changes the *shape* of this
 * table (`'fly'` + children `''`/`':assetId'`) while deliberately changing neither URL. The URLs are
 * what `app.routes.spec.ts` asserts end-to-end; this file asserts the shape that produces them.
 */
describe('FLY_ROUTES', () => {
  let children: Routes;

  beforeAll(async () => {
    const loaded = await (FLY_ROUTES[0].loadChildren as () => Promise<Routes>)();
    // One pathless parent carries the `providers:`; the two real routes are its children.
    children = loaded[0].children!;
  });

  it('is a single lazy boundary at `fly` — nothing else may live in the statically-imported file', () => {
    expect(FLY_ROUTES.map((route) => route.path)).toEqual(['fly']);
    expect(FLY_ROUTES[0].loadChildren, 'must be loadChildren, not loadComponent').toBeDefined();
    expect(FLY_ROUTES[0].loadComponent).toBeUndefined();
  });

  it('registers its twelve page-scoped NgRx slices on the pathless parent, so picker → cockpit keeps one registration', async () => {
    const loaded = await (FLY_ROUTES[0].loadChildren as () => Promise<Routes>)();
    expect(loaded.map((route) => route.path)).toEqual(['']);
    expect(loaded[0].providers, 'the slices must be provided once, above both routes').toHaveLength(12);
  });

  it('/fly (the picker) resolves to DronePickerPage, carries fullBleed, and is gated by flyRedirectGuard', async () => {
    const picker = children.find((route: Route) => route.path === '')!;
    expect(picker.data?.['fullBleed']).toBe(true);
    expect(picker.canActivate).toEqual([flyRedirectGuard]);
    const component = (await picker.loadComponent!()) as { name: string };
    expect(component.name.endsWith('DronePickerPage'), `got "${component.name}"`).toBe(true);
  });

  it('/fly/:assetId (the cockpit) resolves to CockpitPage and carries fullBleed, with no redirect guard', async () => {
    const cockpit = children.find((route: Route) => route.path === ':assetId')!;
    expect(cockpit.data?.['fullBleed']).toBe(true);
    expect(cockpit.canActivate).toBeUndefined();
    const component = (await cockpit.loadComponent!()) as { name: string };
    expect(component.name.endsWith('CockpitPage'), `got "${component.name}"`).toBe(true);
  });

  it("the cockpit's own param is named assetId — withComponentInputBinding() only binds a route param to a component input when the names match exactly", () => {
    expect(children.some((route: Route) => route.path === ':assetId')).toBe(true);
  });
});
