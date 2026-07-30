import type { Routes } from '@angular/router';

/**
 * The `/devices` route — the raw device table/grid (this cycle's Assets/Devices/Warehouse inventory
 * restructure — no dedicated `docs/*-PLAN.md`, see `vision-web/MODULE.md`'s own changelog entry).
 * Used to also serve the asset-first list and answer to a `/warehouse` alias (see the git history of
 * this file — `docs/UX-REWORK-PLAN.md §U-d` renamed the page "Warehouse" and added that alias); both
 * moved out once Assets and Devices earned separate pages — the asset-first list is now
 * `features/assets/**`'s own `/assets` route, and `/warehouse` itself is a two-tile launcher
 * (`features/warehouse/**`). The path stays `/devices` for link-compatibility — every existing
 * `router.navigate(['/devices', …])`/`routerLink="/devices"` call site across this app (and any
 * bookmark) keeps working verbatim, still resolving to this same raw-device page. Split into its own
 * file per vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3 (B8) — see `features/fly/fly.routes.ts`'s
 * doc comment for why.
 */
export const DEVICES_ROUTES: Routes = [
  {
    path: 'devices',
    title: 'Devices · Vision',
    loadComponent: () => import('./devices').then((m) => m.DevicesPage),
  },
];
