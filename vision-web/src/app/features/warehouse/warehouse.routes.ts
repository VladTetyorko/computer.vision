import type { Routes } from '@angular/router';

/**
 * The `/warehouse` route — a two-tile launcher between the two halves of inventory (People, Assets),
 * replacing what used to be a plain alias onto the combined Devices/Warehouse page
 * (`features/devices/devices.routes.ts` before this split — see that file's own doc comment for the
 * link-compatibility history). `/devices` itself is untouched and keeps resolving to the raw device
 * table (`features/devices/**`) — this route only changes what `/warehouse` itself renders. Split
 * into its own file per vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3 (B8) — see
 * `features/fly/fly.routes.ts`'s doc comment for why.
 */
export const WAREHOUSE_ROUTES: Routes = [
  {
    path: 'warehouse',
    title: 'Warehouse · Vision',
    loadComponent: () => import('./warehouse').then((m) => m.WarehousePage),
  },
];
