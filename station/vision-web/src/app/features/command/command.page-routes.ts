import type { Routes } from '@angular/router';

import { provideMapState } from '../../core/map/state/map.providers';
import { provideRouteState } from '../../core/map-data/state/route.providers';
import { provideWeatherState } from '../../core/weather/state/weather.providers';

/**
 * The `/command` route (docs/plans/done/MVP3-PLAN.md §C-c: the manager dashboard), behind
 * `command.routes.ts`'s `loadChildren` boundary — see that file, and
 * `features/fly/fly.page-routes.ts` for the fuller rationale
 * (docs/plans/active/NGRX-MIGRATION-PLAN.md §9, wave N-split).
 *
 * `map`/`route`/`weather` register here rather than at the root injector: `MapFacade`, `RouteFacade`
 * and `WeatherFacade` are all page-provided in `CommandPage`'s own `providers:` array, so slice and
 * facade share this route's lifetime. `geofence` and the `map-data` collection slices are **not**
 * here — their facades are `providedIn: 'root'` and outlive the route, so they stay in
 * `core/state/app-state.ts` (that file's doc comment explains the rule).
 */
export const COMMAND_PAGE_ROUTES: Routes = [
  {
    path: '',
    title: 'Command · Vision',
    providers: [provideMapState(), provideRouteState(), provideWeatherState()],
    // `fullBleed` (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.1 rule 5,
    // docs/extracts/design/00-shell.md): read by `shared/ui/app-sidebar/**` to auto-collapse the
    // sidebar to its icon rail on this map-first view, rather than reflowing the map to make room
    // for an expanded nav column.
    data: { fullBleed: true },
    loadComponent: () => import('./command').then((m) => m.CommandPage),
  },
];
