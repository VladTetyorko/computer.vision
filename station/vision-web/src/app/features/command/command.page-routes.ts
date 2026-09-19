import type { Routes } from '@angular/router';

import { provideMapState } from '../../core/map/state/map.providers';
import { provideRouteState } from '../../core/map-data/state/route.providers';
import { provideWeatherState } from '../../core/weather/state/weather.providers';
import { provideDrawingsState } from '../../core/map-data/state/drawings.providers';
import { provideGeofenceState } from '../../core/geofence/state/geofence.providers';
import { provideLayersState } from '../../core/map-data/state/layers.providers';
import { provideMarksState } from '../../core/map-data/state/marks.providers';
import { provideOrgState } from '../../core/org/state/org.providers';

/**
 * The `/command` route (docs/plans/done/MVP3-PLAN.md §C-c: the manager dashboard), behind
 * `command.routes.ts`'s `loadChildren` boundary — see that file, and
 * `features/fly/fly.page-routes.ts` for the fuller rationale
 * (docs/plans/done/NGRX-MIGRATION-PLAN.md §9, wave N-split).
 *
 * `map`/`route`/`weather` register here rather than at the root injector: `MapFacade`, `RouteFacade`
 * and `WeatherFacade` are all page-provided in `CommandPage`'s own `providers:` array, so slice and
 * facade share this route's lifetime. `geofence` and the `map-data` collection slices were the
 * exception until wave N4 — their facades were `providedIn: 'root'` and outlived the route — and
 * joined this array once that changed (`core/state/app-state.ts`'s doc comment explains the rule).
 *
 * **The map-data slices (`marks`, `layers`, `drawings`, `geofence`, `org`) were added in wave N4**,
 * once their facades became page-provided. They are needed not only by this page's own facade but by
 * every control inside `<vision-map-tools>` (`shared/map/map-controls/**`), which injects them
 * directly. The same five are registered by each of the other map surfaces — that is safe, NgRx keys
 * feature state by name and only one of these routes is ever active.
 */
export const COMMAND_PAGE_ROUTES: Routes = [
  {
    path: '',
    title: 'Command · Vision',
    providers: [
      provideMapState(),
      provideRouteState(),
      provideWeatherState(),
      provideMarksState(),
      provideLayersState(),
      provideDrawingsState(),
      provideGeofenceState(),
      provideOrgState(),
    ],
    // `fullBleed` (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.1 rule 5,
    // docs/extracts/design/00-shell.md): read by `shared/ui/app-sidebar/**` to auto-collapse the
    // sidebar to its icon rail on this map-first view, rather than reflowing the map to make room
    // for an expanded nav column.
    data: { fullBleed: true },
    loadComponent: () => import('./command').then((m) => m.CommandPage),
  },
];
