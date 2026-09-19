import type { Routes } from '@angular/router';

import { provideDetectionsState } from '../../core/detections/state/detections.providers';
import { provideTelemetryState } from '../../core/telemetry/state/telemetry.providers';
import { provideDrawingsState } from '../../core/map-data/state/drawings.providers';
import { provideGeofenceState } from '../../core/geofence/state/geofence.providers';
import { provideLayersState } from '../../core/map-data/state/layers.providers';
import { provideMarksState } from '../../core/map-data/state/marks.providers';
import { provideOrgState } from '../../core/org/state/org.providers';

/**
 * The `/live/:deviceId` route — the single-device, video-first watch page (docs/main/CYCLES-PLAN.md
 * §2, §9) — behind `live.routes.ts`'s `loadChildren` boundary; see
 * `features/fly/fly.page-routes.ts` for the rationale (docs/plans/done/NGRX-MIGRATION-PLAN.md §9,
 * wave N-split). The `:deviceId` param lives on the **parent** in `live.routes.ts`, so
 * `withComponentInputBinding()` still binds it to `LivePage#deviceId` by name.
 *
 * **The map-data slices (`marks`, `layers`, `drawings`, `geofence`, `org`) were added in wave N4**,
 * once their facades became page-provided. They are needed not only by this page's own facade but by
 * every control inside `<vision-map-tools>` (`shared/map/map-controls/**`), which injects them
 * directly. The same five are registered by each of the other map surfaces — that is safe, NgRx keys
 * feature state by name and only one of these routes is ever active.
 */
export const LIVE_PAGE_ROUTES: Routes = [
  {
    path: '',
    title: 'Live · Vision',
    providers: [
      provideTelemetryState(),
      provideDetectionsState(),
      provideMarksState(),
      provideLayersState(),
      provideDrawingsState(),
      provideGeofenceState(),
      provideOrgState(),
    ],
    loadComponent: () => import('./live').then((m) => m.LivePage),
  },
];
