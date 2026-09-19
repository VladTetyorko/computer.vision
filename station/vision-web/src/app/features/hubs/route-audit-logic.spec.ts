import type { Routes } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { flattenRoutes, flattenRoutesDeep, routeExists } from './route-audit-logic';

/** A trivial, synchronously-typed stand-in — `component` (not `loadComponent`) sidesteps the lazy
 * loader's own `Promise<Type<unknown> | DefaultExport<...>>` return-type contract, irrelevant here
 * since only the route *shape* (path/children/redirectTo), never any actual component, is exercised. */
class Dummy {}

describe('flattenRoutes', () => {
  it('flattens a plain top-level route to a leading-slash path', () => {
    const routes: Routes = [{ path: 'fly', component: Dummy }];
    expect(flattenRoutes(routes)).toEqual([{ path: '/fly', kind: 'component' }]);
  });

  it('treats an empty-path grouping route (the authGuard wrapper) as contributing no segment', () => {
    const routes: Routes = [
      { path: '', canActivate: [], children: [{ path: 'command', component: Dummy }] },
    ];
    expect(flattenRoutes(routes)).toEqual([{ path: '/command', kind: 'component' }]);
  });

  it('represents the bare root redirect as "/"', () => {
    const routes: Routes = [{ path: '', pathMatch: 'full', redirectTo: 'fly' }];
    expect(flattenRoutes(routes)).toEqual([{ path: '/', kind: 'redirect' }]);
  });

  it('treats a RedirectFunction (WAREHOUSE-UX W4\'s query-param-carrying redirects) the same as a static string redirect', () => {
    const routes: Routes = [{ path: 'devices', redirectTo: () => '/assets?tab=links' }];
    expect(flattenRoutes(routes)).toEqual([{ path: '/devices', kind: 'redirect' }]);
  });

  it('keeps a multi-segment literal path (e.g. assets/:assetId/replay/:usageId) intact', () => {
    const routes: Routes = [{ path: 'assets/:assetId/replay/:usageId', component: Dummy }];
    expect(flattenRoutes(routes)).toEqual([{ path: '/assets/:assetId/replay/:usageId', kind: 'component' }]);
  });

  it('a route with neither loadComponent/component nor redirectTo (a pure grouping route) contributes nothing itself', () => {
    const routes: Routes = [{ path: '', children: [{ path: 'x', component: Dummy }] }];
    expect(flattenRoutes(routes)).toEqual([{ path: '/x', kind: 'component' }]);
  });

  it('treats a guard-only leaf (canActivate + empty children, e.g. app.routes.ts\'s landingGuard) as a computed redirect', () => {
    const routes: Routes = [{ path: '', canActivate: [() => true], children: [] }];
    expect(flattenRoutes(routes)).toEqual([{ path: '/', kind: 'redirect' }]);
  });

  it('does NOT treat a canActivate wrapper with real children as a leaf of its own (children win)', () => {
    const routes: Routes = [
      { path: '', canActivate: [() => true], children: [{ path: 'fly', component: Dummy }] },
    ];
    expect(flattenRoutes(routes)).toEqual([{ path: '/fly', kind: 'component' }]);
  });
});

describe('flattenRoutesDeep — lazy boundaries (NGRX-MIGRATION wave N-split)', () => {
  it('resolves a loadChildren boundary and joins the parent segment onto its children', async () => {
    const routes: Routes = [
      {
        path: 'fly',
        loadChildren: () =>
          Promise.resolve([
            {
              path: '',
              children: [
                { path: '', loadComponent: () => Promise.resolve(class Picker {}) },
                { path: ':assetId', loadComponent: () => Promise.resolve(class Cockpit {}) },
              ],
            },
          ] as Routes),
      },
    ];
    expect(await flattenRoutesDeep(routes)).toEqual([
      { path: '/fly', kind: 'component' },
      { path: '/fly/:assetId', kind: 'component' },
    ]);
  });

  it('resolves a boundary nested inside ordinary children', async () => {
    const routes: Routes = [
      {
        path: '',
        children: [
          {
            path: 'manage/cv',
            loadChildren: () =>
              Promise.resolve([{ path: '', loadComponent: () => Promise.resolve(class Inspector {}) }] as Routes),
          },
        ],
      },
    ];
    expect(await flattenRoutesDeep(routes)).toEqual([{ path: '/manage/cv', kind: 'component' }]);
  });

  it('the sync walker reports nothing for an unresolved boundary — it must never be read as a dead link', () => {
    const routes: Routes = [{ path: 'fly', loadChildren: () => Promise.resolve([] as Routes) }];
    // Deliberately empty, not `{ path: '/fly', kind: ... }`: the sync walker cannot know what is
    // behind the boundary, so callers that care use `flattenRoutesDeep`. `app.routes.spec.ts` does.
    expect(flattenRoutes(routes)).toEqual([]);
  });
});

describe('routeExists', () => {
  const flat = flattenRoutes([
    { path: 'fly', component: Dummy },
    { path: 'assets/:assetId', component: Dummy },
    { path: 'assets/:assetId/replay/:usageId', component: Dummy },
    { path: 'map', redirectTo: 'command' },
    { path: '', pathMatch: 'full', redirectTo: 'fly' },
  ]);

  it('matches an exact literal path', () => {
    expect(routeExists(flat, '/fly')).toBe(true);
  });

  it('matches a :param segment against any concrete value', () => {
    expect(routeExists(flat, '/assets/probe-1')).toBe(true);
    expect(routeExists(flat, '/assets/probe-1/replay/usage-9')).toBe(true);
  });

  it('a redirect target counts as "resolves" (no dead link) — same as a component route', () => {
    expect(routeExists(flat, '/map')).toBe(true);
  });

  it('matches the bare root', () => {
    expect(routeExists(flat, '/')).toBe(true);
  });

  it('strips a query string before matching', () => {
    expect(routeExists(flat, '/fly?asset=probe-1&watch=1')).toBe(true);
  });

  it('rejects a segment-count mismatch (no partial-prefix false positive)', () => {
    expect(routeExists(flat, '/assets')).toBe(false);
    expect(routeExists(flat, '/assets/probe-1/extra')).toBe(false);
  });

  it('rejects a genuinely unregistered path', () => {
    expect(routeExists(flat, '/nope')).toBe(false);
  });
});
