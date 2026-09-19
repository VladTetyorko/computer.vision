import type { Routes } from '@angular/router';

import { provideDetectionsState } from '../../core/detections/state/detections.providers';
import { provideSeatState } from '../../core/seat/state/seat.providers';
import { provideTelemetryState } from '../../core/telemetry/state/telemetry.providers';

/**
 * The `/crew` route family (docs/plans/done/CREW-CONTROL-PLAN.md — the second seat on an asset),
 * behind `crew.routes.ts`'s `loadChildren` boundary; see `features/fly/fly.page-routes.ts` for the
 * rationale (docs/plans/active/NGRX-MIGRATION-PLAN.md §9, wave N-split).
 *
 * **`''` keeps `pathMatch: 'full'`.** Without it the bare `/crew` redirect would also swallow
 * `/crew/:assetId`, since an empty child path prefix-matches everything — the exact bug
 * OPERATOR-UX's own pathMatch finding recorded. The redirect target stays absolute (`/wall`), not
 * relative, so it resolves the same whether reached from here or from a deep link.
 */
export const CREW_PAGE_ROUTES: Routes = [
  {
    path: '',
    providers: [provideTelemetryState(), provideDetectionsState(), provideSeatState()],
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
