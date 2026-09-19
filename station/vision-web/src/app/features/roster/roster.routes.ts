import type { Routes } from '@angular/router';
import { orgGuard } from '../../core/org/org-guard';

/**
 * `/manage/roster` and `/fleet/maintenance` (docs/plans/active/WAREHOUSE-UX-PLAN.md §4 wave W7) — own
 * lazy chunks, split per vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3 (B8). Spread inside
 * `app.routes.ts`'s `authGuard`-wrapped children group like every other feature; `orgGuard`
 * (`core/org/org-guard.ts`, the same guard `/org` uses) adds the ADMIN/MANAGER role check on top — a
 * pilot is redirected to `/fly`.
 *
 * `manage/roster` now loads `CrewPage` (`./crew`), not `RosterPage` directly — **Crew** wraps the
 * former roster page and the former `/org` page as two tabs (`?tab=roster|org`); `RosterPage` itself
 * is unchanged and reused wholesale as the `roster` tab's content (`crew.ts`'s own doc comment).
 *
 * `fleet/maintenance` is new this wave — the Maintenance page (`features/maintenance/maintenance.page.ts`,
 * grounded/inspection-due/in-repair/retired fleet triage). It is registered here, not in
 * `features/hubs/hubs.routes.ts` (out of this task's file scope — owned by wave W4's nav/rail work),
 * so it sits beside this file's sibling `manage/roster` entry rather than in the hubs route group;
 * `app.routes.ts` already spreads `ROSTER_ROUTES` into the same `authGuard` children array every
 * other feature route group joins, so this needs no `app.routes.ts` edit either.
 *
 * **Handoff to W4:** `hubs.routes.ts` still has a `ComingSoon` stub at `manage/health` — W7's exit
 * criterion (WAREHOUSE-UX-PLAN.md §4) is "`/manage/health` redirects to a real page", i.e. to
 * `/fleet/maintenance`. That edit belongs in `hubs.routes.ts`, which this task does not own; see
 * `docs/plans/active/WAREHOUSE-UX-CONTEXT.md`'s W7 row for the explicit handoff note.
 */
export const ROSTER_ROUTES: Routes = [
  {
    // A **lazy boundary only** since wave N4 (NGRX-MIGRATION-PLAN.md §9): this page embeds
    // `OrgSettingsPage`, whose `OrgFacade` is page-provided, so the `org` slice is registered on the
    // route in `roster.page-routes.ts`. It cannot be named here — `app.routes.ts` imports this file
    // statically, which would pull the slice back into the initial bundle. `orgGuard` stays on the
    // boundary so an unauthorised visitor never fetches the chunk. See `features/fly/fly.routes.ts`.
    path: 'manage/roster',
    canActivate: [orgGuard],
    loadChildren: () => import('./roster.page-routes').then((m) => m.ROSTER_PAGE_ROUTES),
  },
  {
    path: 'fleet/maintenance',
    title: 'Maintenance · Vision',
    canActivate: [orgGuard],
    loadComponent: () => import('../maintenance/maintenance.page').then((m) => m.MaintenancePage),
  },
];
