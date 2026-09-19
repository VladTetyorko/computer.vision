import type { Routes } from '@angular/router';
import { orgGuard } from '../../core/org/org-guard';

/**
 * `/add-source`'s own route entry, kept to a **lazy boundary only** (wave N-split,
 * docs/plans/done/NGRX-MIGRATION-PLAN.md §9) — see `features/fly/fly.routes.ts`'s doc comment for
 * why a `providers:` array may not appear in a statically-imported route file.
 *
 * `orgGuard` (`core/org/org-guard.ts`) stays here on the boundary, so a visitor without the
 * capability is turned away before the chunk is fetched: added per
 * docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 wave W1 rule 5 — the nav entry (`nav-entries.ts`'s
 * "Add vehicle", fleet group) is `managerOnly`, so the route agrees.
 */
export const ONBOARDING_ROUTES: Routes = [
  {
    path: 'add-source',
    canActivate: [orgGuard],
    loadChildren: () => import('./onboarding.page-routes').then((m) => m.ONBOARDING_PAGE_ROUTES),
  },
];
