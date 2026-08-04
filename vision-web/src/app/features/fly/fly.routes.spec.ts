import { describe, expect, it } from 'vitest';
import { FLY_ROUTES } from './fly.routes';
import { flyRedirectGuard } from './fly-redirect-guard';

/**
 * `/fly` route-table shape (docs/NAV-IA-REDESIGN-PLAN.md §2.5 F12 — "the cockpit is not
 * addressable"). A pure, no-`TestBed` structural check on the real `FLY_ROUTES` array, mirroring
 * `app.routes.spec.ts`'s own "resolves to its own component, not X" precedent, scoped to this one
 * feature's own route file (in-scope per this cycle's own file boundaries, unlike the app-wide
 * table). Exercises `loadComponent()` directly rather than booting a router/`TestBed` — the concern
 * here is "did the split wire the right component to the right path", not runtime navigation
 * behavior (covered by the required manual browser verification pass instead, per this cycle's own
 * task brief — refresh-survival and the guard's redirect behavior aren't meaningfully unit-testable
 * without a real `HttpClient`/`Router`, and this codebase's own established convention is pure-logic
 * vitest over heavier component/integration specs — see `rememberedStreamingAssetId`'s own spec in
 * `fly-logic.spec.ts` for the guard's actual decision logic under test).
 */
describe('FLY_ROUTES', () => {
  it('has exactly the picker and the cockpit, in that order', () => {
    expect(FLY_ROUTES.map((route) => route.path)).toEqual(['fly', 'fly/:assetId']);
  });

  it('/fly (the picker) resolves to DronePickerPage, carries fullBleed, and is gated by flyRedirectGuard', async () => {
    const picker = FLY_ROUTES.find((route) => route.path === 'fly')!;
    expect(picker.data?.['fullBleed']).toBe(true);
    expect(picker.canActivate).toEqual([flyRedirectGuard]);
    const component = (await picker.loadComponent!()) as { name: string };
    expect(component.name.endsWith('DronePickerPage'), `got "${component.name}"`).toBe(true);
  });

  it('/fly/:assetId (the cockpit) resolves to CockpitPage and carries fullBleed, with no redirect guard', async () => {
    const cockpit = FLY_ROUTES.find((route) => route.path === 'fly/:assetId')!;
    expect(cockpit.data?.['fullBleed']).toBe(true);
    expect(cockpit.canActivate).toBeUndefined();
    const component = (await cockpit.loadComponent!()) as { name: string };
    expect(component.name.endsWith('CockpitPage'), `got "${component.name}"`).toBe(true);
  });

  it("the cockpit's own param is named assetId — withComponentInputBinding() only binds a route param to a component input when the names match exactly", () => {
    expect(FLY_ROUTES.find((route) => route.path === 'fly/:assetId')?.path).toContain(':assetId');
  });
});
