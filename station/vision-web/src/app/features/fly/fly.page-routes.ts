import type { Routes } from '@angular/router';

import { provideDetectionsState } from '../../core/detections/state/detections.providers';
import { provideGeoState } from '../../core/geo/state/geo.providers';
import { provideControlProfileState } from '../../core/rc/state/control-profile.providers';
import { provideDrawingsState } from '../../core/map-data/state/drawings.providers';
import { provideGeofenceState } from '../../core/geofence/state/geofence.providers';
import { provideLayersState } from '../../core/map-data/state/layers.providers';
import { provideMarksState } from '../../core/map-data/state/marks.providers';
import { provideOrgState } from '../../core/org/state/org.providers';
import { provideSeatState } from '../../core/seat/state/seat.providers';
import { provideThresholdsState } from '../../core/ops/state/thresholds.providers';
import { provideTelemetryState } from '../../core/telemetry/state/telemetry.providers';
import { provideWeatherState } from '../../core/weather/state/weather.providers';
import { flyRedirectGuard } from './fly-redirect-guard';

/**
 * The `/fly` route family (docs/plans/done/MVP3-PLAN.md §C-b: the operator cockpit, the app's default
 * landing page), behind `fly.routes.ts`'s `loadChildren` boundary so that everything named here —
 * the two page components *and* the seven NgRx slices below — is fetched on first visit to `/fly`
 * rather than shipped to every visitor (docs/plans/active/NGRX-MIGRATION-PLAN.md §9, wave N-split).
 *
 * **Two routes** (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.5 F12 — "the cockpit is not addressable"):
 * `''` (i.e. `/fly`) is the drone picker (`DronePickerPage`), `':assetId'` (`/fly/:assetId`) is the
 * cockpit itself (`CockpitPage`) — previously one component (`FlyPage`) switched between the two
 * internally with no URL change at all, so the cockpit could never be bookmarked, refreshed into, or
 * shared, and Back never left it. Splitting the *route* is what fixes that; splitting the
 * *component* to match (`drone-picker.ts`/`cockpit.ts`) is what `architecture.spec.ts`'s
 * per-routed-page facade rule (`ROUTED_PAGES`) then requires — see each component's own doc comment.
 *
 * **The `providers:` array sits on the pathless parent, not on each child**, so the picker → cockpit
 * navigation keeps one slice registration rather than tearing it down and standing it up again
 * mid-flow: the cockpit's first telemetry read then finds whatever the picker already fetched.
 * `telemetry`/`detections`/`seat`/`weather`/`geo`/`thresholds`/`controlProfile` are registered here
 * rather than in `core/state/app-state.ts` because every facade reading them is page-provided — see
 * that file's own doc comment for why a `providedIn: 'root'` facade's slice may **not** move. The
 * last two joined in the same wave, once `CockpitPage` took `ThresholdsFacade`/`ControlProfileFacade`
 * into its own `providers:`; both are read only from inside the cockpit's own component tree
 * (`FlyHud`/`FlyOsd`/`RcMonitor`), never from the picker, so registering them on the shared parent
 * costs the picker a registration but no fetch — nothing constructs either facade there.
 *
 * **Wave N4 added the five map-data slices** (`marks`, `layers`, `drawings`, `geofence`, `org`)
 * for the cockpit's map inset — needed by `CockpitFacade` and by every control inside
 * `<vision-map-tools>`, which injects those facades directly.
 */
export const FLY_PAGE_ROUTES: Routes = [
  {
    path: '',
    providers: [
      provideTelemetryState(),
      provideDetectionsState(),
      provideSeatState(),
      provideWeatherState(),
      provideGeoState(),
      provideThresholdsState(),
      provideControlProfileState(),
      provideMarksState(),
      provideLayersState(),
      provideDrawingsState(),
      provideGeofenceState(),
      provideOrgState(),
    ],
    children: [
      {
        path: '',
        title: 'Fly · Vision',
        // `fly-redirect-guard.ts` resolves `?asset=`/the remembered-and-still-streaming drone
        // *before* this route ever renders — a genuine "nothing to ask" visit never even flashes the
        // picker.
        canActivate: [flyRedirectGuard],
        // `fullBleed` (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.1 rule 5,
        // docs/extracts/design/00-shell.md): read by `shared/ui/app-sidebar/**` to auto-collapse the
        // sidebar to its icon rail on this full-screen page, rather than reflowing the picker grid
        // to make room for an expanded nav column. Kept on the picker too (not just the cockpit
        // below) — a picker visit that immediately redirects still needs the sidebar to *start*
        // collapsed, not flash-expand for one frame first.
        data: { fullBleed: true },
        loadComponent: () => import('./drone-picker').then((m) => m.DronePickerPage),
      },
      {
        // `:assetId` matches `CockpitPage#assetId`'s own input name exactly
        // (`withComponentInputBinding()` binds a route param to a component input only when the
        // names match — mirrors `/live/:deviceId`'s identical `deviceId` naming).
        path: ':assetId',
        title: 'Fly · Vision',
        // `?watch=1` binds to `CockpitPage`'s own `watch` input — a query param, so no route pattern
        // change is needed for it (see that component's own doc comment).
        data: { fullBleed: true },
        loadComponent: () => import('./cockpit').then((m) => m.CockpitPage),
      },
    ],
  },
];
