import type { Routes } from '@angular/router';
import { orgToCrewGuard } from './org-to-crew-guard';

/**
 * `/org` (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2) used to load `OrgSettingsPage` directly. As of
 * docs/plans/active/WAREHOUSE-UX-PLAN.md §4 wave W7, `OrgSettingsPage` is mounted as `CrewPage`'s
 * "Organization" tab (`features/roster/crew.ts`, routed at `/manage/roster`) instead — this route is
 * now a guard-only redirect to `/manage/roster?tab=org`, so an old bookmark or in-app link still
 * lands somewhere real (`org-to-crew-guard.ts`'s own doc comment explains why a guard, not a plain
 * `redirectTo`). Still spread inside `app.routes.ts`'s `authGuard`-wrapped children group like every
 * other feature, so a signed-out user hits `/login` first.
 */
export const ORG_ROUTES: Routes = [
  {
    path: 'org',
    canActivate: [orgToCrewGuard],
    children: [],
  },
];
