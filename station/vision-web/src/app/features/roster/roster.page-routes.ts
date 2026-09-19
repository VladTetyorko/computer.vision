import type { Routes } from '@angular/router';

import { provideOrgState } from '../../core/org/state/org.providers';

/**
 * `/manage/roster` — the crew page (docs/plans/active/WAREHOUSE-UX-PLAN.md §4 wave W7), behind
 * `roster.routes.ts`'s `loadChildren` boundary, which also owns the `orgGuard`.
 *
 * The `org` slice is registered here because `CrewPage` embeds `OrgSettingsPage`, which provides
 * `OrgFacade` in its own `providers:` (wave N4, docs/plans/active/NGRX-MIGRATION-PLAN.md §9). That
 * page is not routed on its own — `/org` is now just `orgToCrewGuard` redirecting here — so this is
 * the route that has to carry its slice. The five map surfaces register the identical slice for
 * `<vision-layer-manager>`; NgRx keys feature state by name, and only one route is ever active.
 */
export const ROSTER_PAGE_ROUTES: Routes = [
  {
    path: '',
    title: 'Crew · Vision',
    providers: [provideOrgState()],
    loadComponent: () => import('./crew').then((m) => m.CrewPage),
  },
];
