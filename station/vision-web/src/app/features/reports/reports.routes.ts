import { inject } from '@angular/core';
import { Router, type Routes } from '@angular/router';

/**
 * `/manage/reports` — **deleted**, its one surviving section (the "Fleet at a glance" KPI strip)
 * folded into the Inventory page, above the Vehicles tab (docs/plans/active/WAREHOUSE-UX-PLAN.md
 * §3.3, wave W4; `features/inventory/inventory-page-logic.ts`'s own doc comment has the full "what
 * wasn't carried forward" note). No `?tab=` param — the KPI strip has no tab of its own, it sits
 * above whichever tab is active, so this redirects to plain `/assets` (defaulting to Vehicles, same
 * as a bare `/assets` always has). See `features/devices/devices.routes.ts`'s identical doc comment
 * for why this is a `RedirectFunction` rather than a plain string, and why `orgGuard` moved off the
 * route.
 */
export const REPORTS_ROUTES: Routes = [
  {
    path: 'manage/reports',
    redirectTo: () => inject(Router).createUrlTree(['/assets']),
  },
];
