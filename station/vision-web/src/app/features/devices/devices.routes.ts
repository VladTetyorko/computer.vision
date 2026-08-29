import type { Routes } from '@angular/router';
import { orgGuard } from '../../core/org/org-guard';

/**
 * The `/devices` route — the raw device table/grid (this cycle's Assets/Devices/Warehouse inventory
 * restructure — no dedicated `docs/*-PLAN.md`, see `vision-web/MODULE.md`'s own changelog entry).
 * Used to also serve the asset-first list and answer to a `/warehouse` alias (see the git history of
 * this file — `docs/plans/done/UX-REWORK-PLAN.md §U-d` renamed the page "Warehouse" and added that alias); both
 * moved out once Assets and Devices earned separate pages — the asset-first list is now
 * `features/assets/**`'s own `/assets` route, and `/warehouse` itself is a two-tile launcher
 * (`features/warehouse/**`). The path stays `/devices` for link-compatibility — every existing
 * `router.navigate(['/devices', …])`/`routerLink="/devices"` call site across this app (and any
 * bookmark) keeps working verbatim, still resolving to this same raw-device page. Split into its own
 * file per vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3 (B8) — see `features/fly/fly.routes.ts`'s
 * doc comment for why. `orgGuard` (`core/org/org-guard.ts`) added per
 * docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 wave W1 rule 5 — the nav entry (`nav-entries.ts`'s "Devices",
 * fleet group) is `managerOnly`, so the route now agrees: a pilot who guesses/bookmarks the URL is
 * redirected to `/fly` instead of reaching a page the rail never shows them.
 */
export const DEVICES_ROUTES: Routes = [
  {
    path: 'devices',
    title: 'Devices · Vision',
    canActivate: [orgGuard],
    loadComponent: () => import('./devices').then((m) => m.DevicesPage),
  },
];
