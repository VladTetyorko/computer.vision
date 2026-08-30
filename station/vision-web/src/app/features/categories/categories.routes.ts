import { inject } from '@angular/core';
import { Router, type Routes } from '@angular/router';

/**
 * `/manage/categories` — **folded into the Inventory page's Categories tab**
 * (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 rule 3/§3.3, wave W4). `CategoriesPage` itself is
 * unchanged in location — still `features/categories/**` — `InventoryPage`'s Categories tab imports
 * and mounts it. See `features/devices/devices.routes.ts`'s identical doc comment for why this is a
 * `RedirectFunction` and why `orgGuard` moved off the route (the tab bar is the gate now).
 */
export const CATEGORIES_ROUTES: Routes = [
  {
    path: 'manage/categories',
    redirectTo: () => inject(Router).createUrlTree(['/assets'], { queryParams: { tab: 'categories' } }),
  },
];
