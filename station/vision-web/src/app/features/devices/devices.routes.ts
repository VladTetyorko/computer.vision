import { inject } from '@angular/core';
import { Router, type Routes } from '@angular/router';

/**
 * `/devices` — **folded into the Inventory page's Links tab** (docs/plans/active/WAREHOUSE-UX-PLAN.md
 * §3.1 rule 3/§3.3, wave W4). `DevicesPage` itself is unchanged and still lives at
 * `features/devices/**` — `InventoryPage`'s Links tab imports and mounts it verbatim, this route
 * just forwards here so every existing `router.navigate(['/devices', …])`/`routerLink="/devices"`
 * call site and bookmark keeps landing somewhere real. A `RedirectFunction` (not a plain string) so
 * the query param survives the hop — `Route.redirectTo` accepts `(redirectData) =>
 * MaybeAsync<string | UrlTree>`, run in an injection context, exactly like a `canActivate` guard.
 *
 * No `orgGuard` here (unlike before this wave): a pilot who lands on `?tab=links` is not bounced to
 * `/fly` — `InventoryFacade#setTabFromQueryParam` clamps an unauthorized tab pick back to `vehicles`
 * once inside the page, the same "a user without rights never sees the affordance" rule applied at
 * the tab-content level rather than the route level (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 rule
 * 6's "keep `orgGuard` on tab content for links/categories" — the tab bar itself is the gate now,
 * `visibleInventoryTabs`/`isInventoryTabVisible` in `core/fleet/inventory-logic.ts`).
 */
export const DEVICES_ROUTES: Routes = [
  {
    path: 'devices',
    redirectTo: () => inject(Router).createUrlTree(['/assets'], { queryParams: { tab: 'links' } }),
  },
];
