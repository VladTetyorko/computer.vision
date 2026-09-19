import type { Routes } from '@angular/router';

import { provideDiscoveryState } from '../../core/discovery/state/discovery.providers';

/**
 * The Inventory page (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 rule 3/§3.3, wave W4): one page,
 * four tabs (`?tab=vehicles|equipment|links|categories`, default `vehicles` — OQ4: the URL itself is
 * unchanged, `/assets` stays the canonical entry point, only its contents grew tabs). Supersedes
 * `features/assets/**` (that page's own table/filters/two-pane became the Vehicles tab,
 * `features/assets/assets-logic.ts` → `vehicles-logic.ts`) and absorbs `features/devices/**` and
 * `features/categories/**` as imported tab content (`/devices`, `/manage/categories`,
 * `/manage/reports` all redirect here — see each of those features' own route file). No
 * `canActivate` — `/assets` has always been open to every signed-in role; the Links/Categories
 * *tabs* are what's gated, inside the page itself (`InventoryFacade#visibleTabs`,
 * `core/fleet/inventory-logic.ts#visibleInventoryTabs`), same as the old `orgGuard`-on-route pattern
 * those two pages used, just moved one level down.
 *
 * The `discoveryInbox` slice is registered here rather than in `core/state/app-state.ts` because
 * `<vision-found-devices>` (this page's own child) reads it through `DiscoveryInboxFacade`, which
 * `InventoryPage` now provides — wave N-split, docs/plans/done/NGRX-MIGRATION-PLAN.md §9.
 * `/add-source` registers the identical slice on its own route.
 */
export const INVENTORY_PAGE_ROUTES: Routes = [
  {
    path: '',
    title: 'Inventory · Vision',
    providers: [provideDiscoveryState()],
    loadComponent: () => import('./inventory').then((m) => m.InventoryPage),
  },
];
