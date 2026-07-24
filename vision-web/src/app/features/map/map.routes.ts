import type { Routes } from '@angular/router';

/**
 * The `/map` route (the fleet overview tab, docs/CYCLES-PLAN.md §6). Split into its own file per
 * vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3 (B8) — see `features/fly/fly.routes.ts`'s doc
 * comment for why.
 */
export const MAP_ROUTES: Routes = [
  {
    path: 'map',
    title: 'Map · Vision',
    // Every tab chunk is now idle-preloaded (docs/CYCLES-PLAN.md §9, CU-b item 2) — "fast" means
    // every tab click lands on a warm chunk, not just the ones visited first. The Leaflet chunk
    // this route pulls in on visit gets its own separate idle warmup, see `core/leaflet-warmup.ts`.
    loadComponent: () => import('./map').then((m) => m.MapPage),
  },
];
