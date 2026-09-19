import type { Routes } from '@angular/router';

import { provideDetectionsState } from '../../core/detections/state/detections.providers';
import { provideSeatState } from '../../core/seat/state/seat.providers';
import { provideTelemetryState } from '../../core/telemetry/state/telemetry.providers';
import { provideDrawingsState } from '../../core/map-data/state/drawings.providers';
import { provideGeofenceState } from '../../core/geofence/state/geofence.providers';
import { provideLayersState } from '../../core/map-data/state/layers.providers';
import { provideMarksState } from '../../core/map-data/state/marks.providers';
import { provideOrgState } from '../../core/org/state/org.providers';

/**
 * The `/crew` route family (docs/plans/done/CREW-CONTROL-PLAN.md — the second seat on an asset),
 * behind `crew.routes.ts`'s `loadChildren` boundary; see `features/fly/fly.page-routes.ts` for the
 * rationale (docs/plans/active/NGRX-MIGRATION-PLAN.md §9, wave N-split).
 *
 * **`''` keeps `pathMatch: 'full'`.** Without it the bare `/crew` redirect would also swallow
 * `/crew/:assetId`, since an empty child path prefix-matches everything — the exact bug
 * OPERATOR-UX's own pathMatch finding recorded. The redirect target stays absolute (`/wall`), not
 * relative, so it resolves the same whether reached from here or from a deep link.
 *
 * **The map-data slices (`marks`, `layers`, `drawings`, `geofence`, `org`) were added in wave N4**,
 * once their facades became page-provided. They are needed not only by this page's own facade but by
 * every control inside `<vision-map-tools>` (`shared/map/map-controls/**`), which injects them
 * directly. The same five are registered by each of the other map surfaces — that is safe, NgRx keys
 * feature state by name and only one of these routes is ever active.
 */
export const CREW_PAGE_ROUTES: Routes = [
  {
    path: '',
    providers: [
      provideTelemetryState(),
      provideDetectionsState(),
      provideSeatState(),
      provideMarksState(),
      provideLayersState(),
      provideDrawingsState(),
      provideGeofenceState(),
      provideOrgState(),
    ],
    children: [
      {
        path: '',
        pathMatch: 'full',
        redirectTo: '/wall',
      },
      {
        path: ':assetId',
        title: 'Crew · Vision',
        data: { fullBleed: true },
        loadComponent: () => import('./crew').then((m) => m.CrewSeatPage),
      },
    ],
  },
];
