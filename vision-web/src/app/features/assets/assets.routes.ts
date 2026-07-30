import type { Routes } from '@angular/router';

/**
 * The `/assets` route — the asset-first grid (search + filter + card grid), split out of the old
 * combined Devices page (docs/CYCLES-PLAN.md §11's "asset-first list" moved here wholesale; see
 * `features/devices/**` for what stayed behind — the raw device table). A sibling of
 * `features/asset-detail/asset-detail.routes.ts`'s `assets/:assetId` — distinct path-segment count,
 * no ambiguity with the detail route. Split into its own file per
 * vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3 (B8) — see `features/fly/fly.routes.ts`'s doc comment
 * for why.
 */
export const ASSETS_ROUTES: Routes = [
  {
    path: 'assets',
    title: 'Assets · Vision',
    loadComponent: () => import('./assets').then((m) => m.AssetsPage),
  },
];
