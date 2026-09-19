import type { Routes } from '@angular/router';

import { provideLinksState } from '../../core/pairing/state/links.providers';
import { provideTelemetryState } from '../../core/telemetry/state/telemetry.providers';

/**
 * The `/assets/:assetId` route behind `asset-detail.routes.ts`'s `loadChildren` boundary; see
 * `features/fly/fly.page-routes.ts` for the rationale (docs/plans/active/NGRX-MIGRATION-PLAN.md §9,
 * wave N-split). `:assetId` lives on the **parent**, so `withComponentInputBinding()` still binds it
 * to `AssetDetailPage#assetId` by name.
 *
 * `links` is registered here and nowhere else — the Links panel (LINK-PAIRING-PLAN.md §3.4/§3.7,
 * wave L4) is `LinksFacade`'s only consumer in the app.
 */
export const ASSET_DETAIL_PAGE_ROUTES: Routes = [
  {
    path: '',
    title: 'Asset · Vision',
    providers: [provideTelemetryState(), provideLinksState()],
    loadComponent: () => import('./asset-detail').then((m) => m.AssetDetailPage),
  },
];
