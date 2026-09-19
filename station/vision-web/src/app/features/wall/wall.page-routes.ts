import type { Routes } from '@angular/router';

import { provideDetectionsState } from '../../core/detections/state/detections.providers';

/**
 * The `/wall` route (docs/plans/active/WALL-FLOW-PLAN.md) behind `wall.routes.ts`'s `loadChildren`
 * boundary; see `features/fly/fly.page-routes.ts` for the rationale
 * (docs/plans/done/NGRX-MIGRATION-PLAN.md §9, wave N-split).
 *
 * `detections` only: `WallTile`/`WallFocus` each own a private `providers: [DetectionsFacade]` for
 * live per-frame boxes. The wall reads telemetry through the fleet-summary fetch, not the
 * `telemetry` slice, so that one is deliberately absent.
 */
export const WALL_PAGE_ROUTES: Routes = [
  {
    path: '',
    title: 'Wall · Vision',
    providers: [provideDetectionsState()],
    // `fullBleed` (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.1 rule 5): the wall is a full-screen grid.
    data: { fullBleed: true },
    loadComponent: () => import('./wall').then((m) => m.WallPage),
  },
];
