import type { Route, Routes } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { routes } from './app.routes';
import { flattenRoutes, routeExists } from './features/hubs/route-audit-logic';
import { NAV_MODES } from './features/hubs/nav-entries';

/**
 * Recursively finds the route object whose joined `path` (parent segments included, mirroring
 * `route-audit-logic.ts#flattenRoutes`' own walk) equals `target` — used by the Wave 4 "resolves to
 * its own component, not ComingSoon" regression check below, which needs the actual route object
 * (to call its `loadComponent`), not just a path-shape match.
 */
function findRouteByPath(list: Routes, target: string, prefix = ''): Route | undefined {
  for (const route of list) {
    const segment = route.path ?? '';
    const full = segment ? `${prefix}/${segment}` : prefix;
    if (full.replace(/^\//, '') === target && (route.loadComponent || route.component)) {
      return route;
    }
    if (route.children) {
      const found = findRouteByPath(route.children, target, full);
      if (found) {
        return found;
      }
    }
  }
  return undefined;
}

/**
 * The Wave 1 "no dead link" regression test (docs/plans/done/UI-REDESIGN-PLAN.md Wave 1's own Verify bullet:
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

  it("/operate, /monitor, /manage no longer have their own page — each redirects to its legacy target (docs/extracts/design/19-hubs.md, docs/plans/done/NAV-IA-REDESIGN-PLAN.md F1/F10)", () => {
    // Five NAV_MODES groups exist since WAREHOUSE-UX wave W1 (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1), but
    // only three ever had their own hub route — 'fleet'/'vision'/'system' are pure sidebar groupings with
    // no `/fleet`, `/vision`, `/system` path at all (system's primaryRoute, /manage/system, is a real page
    // in its own right, not a legacy hub redirect) — so this checks the three legacy hub paths directly
    // rather than deriving them from every mode id.
    expect(NAV_MODES.map((mode) => mode.primaryRoute)).toEqual([
      '/fly',
      '/command',
      '/assets',
      '/manage/training',
      '/manage/system',
    ]);
    const legacyHubs = ['/operate', '/monitor', '/manage'];
    for (const hubPath of legacyHubs) {
      expect(routeExists(flat, hubPath), hubPath).toBe(true);
      expect(flattenRoutes(routes).find((route) => route.path === hubPath)?.kind, hubPath).toBe('redirect');
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

  it('/assets (the Inventory page, WAREHOUSE-UX W4) resolves, as a sibling of /assets/:assetId', () => {
    expect(routeExists(flat, '/assets')).toBe(true);
    // Distinct segment counts — one does not shadow the other.
    expect(routeExists(flat, '/assets/probe-1')).toBe(true);
  });

  it('/warehouse redirects to /assets (docs/conclusions/UX-SIMPLIFY-REVIEW.md F2 — Warehouse deleted, not just unrouted); /devices redirects into the Inventory page\'s Links tab (WAREHOUSE-UX W4), off the primary nav but not removed', () => {
    expect(routeExists(flat, '/warehouse')).toBe(true);
    expect(flattenRoutes(routes).find((route) => route.path === '/warehouse')?.kind).toBe('redirect');
    expect(routeExists(flat, '/devices')).toBe(true);
    // `/devices`' own `redirectTo` is a `RedirectFunction` (carries `?tab=links`) — see
    // `features/devices/devices.routes.spec.ts` for the actual query-param assertion; this file's
    // `route-audit-logic.ts` helper only proves the path is registered as *some* redirect.
    expect(flattenRoutes(routes).find((route) => route.path === '/devices')?.kind).toBe('redirect');
  });

  it('every remaining pure-scaffold ComingSoon path resolves', () => {
    const scaffolds = ['/operate/missions', '/monitor/layouts', '/manage/firmware'];
    for (const path of scaffolds) {
      expect(routeExists(flat, path), path).toBe(true);
    }
  });

  it('WAREHOUSE-UX wave W4 folded four pages into redirects — /devices, /manage/categories, /manage/reports into the Inventory page\'s tabs/KPI strip, /manage/health (W7\'s exit criterion) into /fleet/maintenance — none of the four keep their own component route any more', () => {
    const folded = ['/devices', '/manage/categories', '/manage/reports', '/manage/health'];
    for (const path of folded) {
      expect(routeExists(flat, path), path).toBe(true);
      expect(flattenRoutes(routes).find((route) => route.path === path)?.kind, path).toBe('redirect');
    }
  });

  it('every Wave 4 functional/split page resolves at its pinned path (docs/plans/done/UI-REDESIGN-PLAN.md Wave 4)', () => {
    const wave4Pages = ['/operate/preflight', '/monitor/alerts', '/manage/roster'];
    for (const path of wave4Pages) {
      expect(routeExists(flat, path), path).toBe(true);
    }
  });

  it('the Inventory page (WAREHOUSE-UX W4 — supersedes the old /assets grid, now tabbed) resolves at /assets, as a sibling of /assets/:assetId', async () => {
    const route = findRouteByPath(routes, 'assets');
    expect(route?.loadComponent, '/assets').toBeDefined();
    const component = (await route!.loadComponent!()) as { name: string };
    expect(component.name.endsWith('InventoryPage'), `/assets: got "${component.name}"`).toBe(true);
  });

  it('Wave 4 pages resolve to their own real component, not the ComingSoon scaffold', async () => {
    const wave4 = [
      { path: 'operate/preflight', className: 'PreflightPage' },
      { path: 'monitor/alerts', className: 'AlertsPage' },
      { path: 'manage/roster', className: 'CrewPage' },
      { path: 'fleet/maintenance', className: 'MaintenancePage' },
    ];
    for (const { path, className } of wave4) {
      const route = findRouteByPath(routes, path);
      expect(route?.loadComponent, path).toBeDefined();
      // Every `loadComponent` in this app already unwraps to the component class itself
      // (`() => import('./x').then((m) => m.XPage)`, not the raw module namespace) — see
      // `app.routes.ts`'s own doc comment. The test build's decorator transform prefixes the
      // runtime class name with `_` (`_PreflightPage`), so this checks by suffix, not equality.
      const component = (await route!.loadComponent!()) as { name: string };
      expect(component.name.endsWith(className), `${path}: got "${component.name}"`).toBe(true);
      expect(component.name, path).not.toContain('ComingSoon');
    }
  });

  it('an actually-unregistered path does not resolve (sanity check on the matcher itself)', () => {
    expect(routeExists(flat, '/definitely-not-a-real-route')).toBe(false);
  });
});
