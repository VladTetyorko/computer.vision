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
 * Only two questions matter for that check: does a path terminate in an actual component
 * (`loadComponent`/`component`), or a redirect (`redirectTo`) — either means "no dead link"; neither
 * means the path was never registered at all (would 404 via the `**` catch-all).
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
    if (route.loadComponent || route.component) {
      out.push({ path: full || '/', kind: 'component' });
    } else if (typeof route.redirectTo === 'string') {
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
