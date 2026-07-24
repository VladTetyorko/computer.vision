import type { Routes } from '@angular/router';

/**
 * The `/devices` route — the Warehouse page (docs/UX-REWORK-PLAN.md §U-d renamed it from "Devices";
 * `app.ts`'s own nav tab label followed). The path itself stays `/devices` for link-compatibility —
 * every existing `router.navigate(['/devices', ...])`/`routerLink="/devices"` call site across this
 * app (and any bookmark) keeps working verbatim — with `/warehouse` added as an alias so a fresh
 * link can use the name that actually appears in the UI. Split into its own file per
 * vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3 (B8) — see `features/fly/fly.routes.ts`'s doc
 * comment for why.
 */
export const DEVICES_ROUTES: Routes = [
  {
    path: 'devices',
    title: 'Warehouse · Vision',
    loadComponent: () => import('./devices').then((m) => m.DevicesPage),
  },
  {
    path: 'warehouse',
    title: 'Warehouse · Vision',
    data: { preload: false },
    loadComponent: () => import('./devices').then((m) => m.DevicesPage),
  },
];
