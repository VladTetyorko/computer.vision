import type { Routes } from '@angular/router';

import { provideControlProfileState } from '../../core/rc/state/control-profile.providers';

/**
 * The controller setup page (docs/plans/active/CONTROLLER-UX-PLAN.md), behind
 * `controller.routes.ts`'s `loadChildren` boundary so that the `controlProfile` slice below is
 * fetched on first visit to `/manage/controller` rather than shipped to every visitor
 * (docs/plans/done/NGRX-MIGRATION-PLAN.md §9, wave N-split).
 *
 * The slice lives here, not in `core/state/app-state.ts`, because `ControlProfileFacade` became
 * page-provided in the same wave — `ControllerSetupPage` lists it in its own `providers:`, so facade
 * and slice share one lifetime. `/fly` registers the identical slice on its own route
 * (`features/fly/fly.page-routes.ts`); see the facade's doc comment for the one behaviour that
 * changes when two pages each load the profile list for themselves.
 */
export const CONTROLLER_PAGE_ROUTES: Routes = [
  {
    path: '',
    title: 'Controller · Vision',
    providers: [provideControlProfileState()],
    loadComponent: () => import('./controller-setup').then((m) => m.ControllerSetupPage),
  },
];
