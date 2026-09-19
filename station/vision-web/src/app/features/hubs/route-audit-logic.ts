import type { Routes } from '@angular/router';

/**
 * Pure, Angular-free route-table walker — backs `app.routes.spec.ts`'s "every URL in the F4 route
 * table resolves, no dead link" check (docs/plans/done/UI-REDESIGN-PLAN.md Wave 1's own Verify bullet). Takes
 * the real `Routes` array `app.routes.ts` exports (a plain nested-array-of-objects data structure —
 * only its **shape**, not any Angular runtime behavior, is inspected here) and flattens it into
 * concrete path strings, the same way the Router itself would resolve a URL against nested
 * `children`, so a spec can assert "this path resolves to *something*" without booting a
 * `RouterTestingHarness`/`TestBed` for every candidate URL.
 *
 * Three questions matter for that check: does a path terminate in an actual component
 * (`loadComponent`/`component`), a redirect — static (`redirectTo: string`) or computed
 * (`redirectTo: RedirectFunction`, docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 wave W4's
 * query-param-carrying redirects, e.g. `features/devices/devices.routes.ts`'s `/devices` →
 * `/assets?tab=links`) — or a guard-only leaf (`canActivate` with no `children` of its own —
 * `app.routes.ts`'s `landingGuard` route, docs/plans/done/OPS-UX-PLAN.md §2 A1, which resolves its
 * destination by role at navigation time rather than naming one statically) — any of the three means
 * "no dead link"; none of them means the path was never registered at all (would 404 via the `**`
 * catch-all).
 */
export interface FlatRoute {
  /** Normalized, leading-slash path, e.g. `/assets/:assetId`; the bare root is `/`. */
  readonly path: string;
  readonly kind: 'component' | 'redirect';
}

/**
 * Recursively walks `routes`, joining each `path` segment onto `prefix` — mirrors how Angular's own
 * router concatenates a parent route's path with its `children`'s (an empty `path: ''` grouping
 * route, like `app.routes.ts`'s `authGuard` wrapper, contributes no segment of its own, exactly as
 * the real router treats it).
 */
export function flattenRoutes(routes: Routes, prefix = ''): FlatRoute[] {
  const out: FlatRoute[] = [];
  for (const route of routes) {
    const segment = route.path ?? '';
    const full = segment ? `${prefix}/${segment}` : prefix;

    if (route.children) {
      out.push(...flattenRoutes(route.children, full));
    }
    if (route.loadChildren && !route.children) {
      // A lazy boundary whose children were never resolved. Emitting nothing here would make the
      // path read as "never registered" — a false 404 — so it is *skipped*, not flattened: callers
      // that care about what lies behind it use `flattenRoutesDeep` instead. `resolveLazyRoutes`
      // below is what turns one into the `children` branch above.
      continue;
    }
    if (route.loadComponent || route.component) {
      out.push({ path: full || '/', kind: 'component' });
    } else if (typeof route.redirectTo === 'string' || typeof route.redirectTo === 'function') {
      out.push({ path: full || '/', kind: 'redirect' });
    } else if (route.canActivate && route.children?.length === 0) {
      // A guard-only leaf (`{ path, canActivate, children: [] }`) — its `canActivate` always
      // returns a `UrlTree` at navigation time (e.g. `app.routes.ts`'s `landingGuard`), so it
      // resolves exactly like a static redirect for "does this URL 404" purposes, just computed
      // rather than named.
      out.push({ path: full || '/', kind: 'redirect' });
    }
  }
  return out;
}

/**
 * `true` when `candidate` (a concrete app URL, e.g. `/fly`, `/assets/probe-1`, query strings
 * stripped automatically) matches some flattened route, treating a `:param` segment as a wildcard.
 * Segment-count must match exactly — `/assets/probe-1` matches `/assets/:assetId` but not the bare
 * `/assets`.
 */
export function routeExists(flat: readonly FlatRoute[], candidate: string): boolean {
  const candidateSegments = segmentsOf(candidate);
  return flat.some((route) => {
    const routeSegments = segmentsOf(route.path);
    if (routeSegments.length !== candidateSegments.length) {
      return false;
    }
    return routeSegments.every((segment, i) => segment.startsWith(':') || segment === candidateSegments[i]);
  });
}

function segmentsOf(path: string): string[] {
  return path.split('?')[0].split('/').filter((segment) => segment.length > 0);
}

/**
 * `flattenRoutes`, but resolving every `loadChildren` boundary first.
 *
 * Since NGRX-MIGRATION wave N-split (docs/plans/active/NGRX-MIGRATION-PLAN.md §9) seven features —
 * `/fly`, `/command`, `/crew`, `/wall`, `/live/:deviceId`, `/assets/:assetId`, `/manage/cv` — are
 * `loadChildren` routes rather than `loadComponent` ones, so that the NgRx slices their
 * `providers:` arrays register stay out of the initial bundle. Their real paths therefore only
 * exist inside a dynamically-imported module, and the synchronous walker above cannot see them: an
 * audit that used `flattenRoutes` alone would report `/fly` as a dead link.
 *
 * Async because `loadChildren` is, and recursive because a resolved child may itself be one. The
 * sync walker stays exported and unchanged — it is still the right tool for asserting the *shape*
 * of a single feature's own route array, where nothing is lazy.
 */
export async function flattenRoutesDeep(routes: Routes, prefix = ''): Promise<FlatRoute[]> {
  return flattenRoutes(await resolveLazyRoutes(routes), prefix);
}

/**
 * Recursively replaces every `loadChildren` with the `children` it resolves to, leaving the rest of
 * the route object untouched, so the pure walker above can treat the result as an ordinary nested
 * table. A `loadChildren` that resolves to a `Routes` array is used as-is; Angular also permits one
 * resolving to a module with a `ROUTES`-shaped default export, which this does not handle because
 * this app has none — it would need adding alongside a route that uses it, never speculatively.
 */
async function resolveLazyRoutes(routes: Routes): Promise<Routes> {
  return Promise.all(
    routes.map(async (route) => {
      if (route.children) {
        return { ...route, children: await resolveLazyRoutes(route.children) };
      }
      if (route.loadChildren) {
        const loaded = await (route.loadChildren as () => Promise<Routes>)();
        return { ...route, loadChildren: undefined, children: await resolveLazyRoutes(loaded) };
      }
      return route;
    }),
  );
}
