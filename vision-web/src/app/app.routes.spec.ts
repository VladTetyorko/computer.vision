import { describe, expect, it } from 'vitest';
import { routes } from './app.routes';
import { flattenRoutes, routeExists } from './features/hubs/route-audit-logic';
import { NAV_MODES } from './features/hubs/nav-entries';

/**
 * The Wave 1 "no dead link" regression test (docs/UI-REDESIGN-PLAN.md Wave 1's own Verify bullet:
 * "a route-resolution vitest asserting every URL in the F4 table resolves (existing + hubs)").
 * Walks the **real** `routes` array `app.routes.ts` exports (via `route-audit-logic.ts`'s pure
 * flattener — see that file's own doc comment) and checks every URL either table below actually
 * resolves to something (a component or a redirect) — never silently falling through to the `**`
 * catch-all.
 */
describe('app.routes — every URL in the F4 route table resolves (no dead link)', () => {
  const flat = flattenRoutes(routes);

  it('every NAV_MODES entry (every hub tile + dropdown row) resolves to a real route', () => {
    for (const mode of NAV_MODES) {
      for (const entry of mode.entries) {
        expect(routeExists(flat, entry.to), `${mode.id} → "${entry.name}" (${entry.to})`).toBe(true);
      }
    }
  });

  it('every hub launcher route itself resolves', () => {
    for (const mode of NAV_MODES) {
      expect(routeExists(flat, mode.hubRoute), mode.hubRoute).toBe(true);
    }
  });

  it('every F4 "(shell)" profile-menu route resolves', () => {
    for (const path of ['/settings', '/org', '/activity']) {
      expect(routeExists(flat, path), path).toBe(true);
    }
  });

  it('every pre-existing route is preserved — none removed or renamed by this wave', () => {
    const preserved = [
      '/',
      '/login',
      '/fly',
      '/command',
      '/wall',
      '/map',
      '/devices',
      '/warehouse',
      '/add-source',
      '/assets/probe-1',
      '/assets/probe-1/replay/usage-1',
      '/replay',
      '/live/dev-1',
      '/settings',
      '/org',
      '/activity',
      '/debug',
    ];
    for (const path of preserved) {
      expect(routeExists(flat, path), path).toBe(true);
    }
  });

  it('the new /monitor/replay path (F4-pinned "Replay library" target) resolves', () => {
    expect(routeExists(flat, '/monitor/replay')).toBe(true);
  });

  it('the new /assets grid (split out of the old combined Devices page) resolves, as a sibling of /assets/:assetId', () => {
    expect(routeExists(flat, '/assets')).toBe(true);
    // Distinct segment counts — one does not shadow the other.
    expect(routeExists(flat, '/assets/probe-1')).toBe(true);
  });

  it('/warehouse resolves to the two-tile launcher, /devices to the raw device page — distinct pages now', () => {
    expect(routeExists(flat, '/warehouse')).toBe(true);
    expect(routeExists(flat, '/devices')).toBe(true);
  });

  it('every scaffold ComingSoon path resolves', () => {
    const scaffolds = [
      '/operate/preflight',
      '/operate/missions',
      '/monitor/alerts',
      '/monitor/layouts',
      '/manage/categories',
      '/manage/health',
      '/manage/firmware',
      '/manage/reports',
      '/manage/roster',
    ];
    for (const path of scaffolds) {
      expect(routeExists(flat, path), path).toBe(true);
    }
  });

  it('an actually-unregistered path does not resolve (sanity check on the matcher itself)', () => {
    expect(routeExists(flat, '/definitely-not-a-real-route')).toBe(false);
  });
});
