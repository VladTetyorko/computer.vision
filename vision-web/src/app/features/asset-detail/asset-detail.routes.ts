import type { Routes } from '@angular/router';

/**
 * The `/assets/:assetId` route (the Devices page's asset-first list "Open" target,
 * docs/CYCLES-PLAN.md §11, CD-b item 2). Split into its own file per
 * vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3 (B8) — see `features/fly/fly.routes.ts`'s doc
 * comment for why.
 */
export const ASSET_DETAIL_ROUTES: Routes = [
  {
    path: 'assets/:assetId',
    title: 'Asset · Vision',
    // Param named `:assetId` (not `:id`) so it matches `AssetDetailPage.assetId`'s own input name
    // exactly — `withComponentInputBinding()` binds a route param to a component input only when
    // the names match (docs/MVP2-PLAN.md §V, V-b — fixed a long-flagged Gotcha; see that entry in
    // vision-web/MODULE.md for how this was silently broken before and what fixing it restores).
    loadComponent: () => import('./asset-detail').then((m) => m.AssetDetailPage),
  },
];
