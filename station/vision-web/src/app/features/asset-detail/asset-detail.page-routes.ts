import type { Routes } from '@angular/router';

import { provideLinksState } from '../../core/pairing/state/links.providers';
import { provideTelemetryState } from '../../core/telemetry/state/telemetry.providers';
import { provideDrawingsState } from '../../core/map-data/state/drawings.providers';
import { provideGeofenceState } from '../../core/geofence/state/geofence.providers';
import { provideLayersState } from '../../core/map-data/state/layers.providers';
import { provideMarksState } from '../../core/map-data/state/marks.providers';
import { provideOrgState } from '../../core/org/state/org.providers';
import { provideTracksState } from '../../core/map-data/state/tracks.providers';

/**
 * The `/assets/:assetId` route behind `asset-detail.routes.ts`'s `loadChildren` boundary; see
 * `features/fly/fly.page-routes.ts` for the rationale (docs/plans/active/NGRX-MIGRATION-PLAN.md §9,
 * wave N-split). `:assetId` lives on the **parent**, so `withComponentInputBinding()` still binds it
 * to `AssetDetailPage#assetId` by name.
 *
 * `links` is registered here and nowhere else — the Links panel (LINK-PAIRING-PLAN.md §3.4/§3.7,
 * wave L4) is `LinksFacade`'s only consumer in the app.
 *
 * **The map-data slices (`marks`, `layers`, `drawings`, `geofence`, `org`) were added in wave N4**,
 * once their facades became page-provided. They are needed not only by this page's own facade but by
 * every control inside `<vision-map-tools>` (`shared/map/map-controls/**`), which injects them
 * directly. The same five are registered by each of the other map surfaces — that is safe, NgRx keys
 * feature state by name and only one of these routes is ever active. `tracks` is registered **here only** — `AssetDetailFacade` is its
 * sole injector across the whole app.
 */
export const ASSET_DETAIL_PAGE_ROUTES: Routes = [
  {
    path: '',
    title: 'Asset · Vision',
    providers: [
      provideTelemetryState(),
      provideLinksState(),
      provideMarksState(),
      provideLayersState(),
      provideDrawingsState(),
      provideGeofenceState(),
      provideOrgState(),
      provideTracksState(),
    ],
    loadComponent: () => import('./asset-detail').then((m) => m.AssetDetailPage),
  },
];
