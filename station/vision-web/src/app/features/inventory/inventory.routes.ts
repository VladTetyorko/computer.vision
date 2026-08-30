import type { Routes } from '@angular/router';

/**
 * `/assets` — the Inventory page (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 rule 3/§3.3, wave W4):
 * one page, four tabs (`?tab=vehicles|equipment|links|categories`, default `vehicles` — OQ4: the URL
 * itself is unchanged, `/assets` stays the canonical entry point, only its contents grew tabs).
 * Supersedes `features/assets/**` (deleted this wave — that page's own table/filters/two-pane moved
 * here as the Vehicles tab, `features/assets/assets-logic.ts` → `vehicles-logic.ts`) and absorbs
 * `features/devices/**`/`features/categories/**` as imported tab content (`/devices`,
 * `/manage/categories`, `/manage/reports` all now redirect here — see each of those features' own
 * `<name>.routes.ts`). No `canActivate` — `/assets` has always been open to every signed-in role;
 * the Links/Categories *tabs* are what's gated, inside the page itself
 * (`InventoryFacade#visibleTabs`, `core/fleet/inventory-logic.ts#visibleInventoryTabs`), same as the
 * old `orgGuard`-on-route pattern those two pages used, just moved one level down.
 */
export const INVENTORY_ROUTES: Routes = [
  {
    path: 'assets',
    title: 'Inventory · Vision',
    loadComponent: () => import('./inventory').then((m) => m.InventoryPage),
  },
];
