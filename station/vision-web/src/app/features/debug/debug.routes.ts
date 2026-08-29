import type { Routes } from '@angular/router';
import { orgGuard } from '../../core/org/org-guard';

/**
 * The `/debug` route. Split into its own file per vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3
 * (B8) — see `features/fly/fly.routes.ts`'s doc comment for why. `orgGuard` (`core/org/org-guard.ts`)
 * added per docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 wave W1 rule 5 — the nav entry (`nav-entries.ts`'s
 * "Debug", system group) is `managerOnly`; the raw API console is not something a pilot should reach
 * even by URL guess.
 */
export const DEBUG_ROUTES: Routes = [
  {
    path: 'debug',
    title: 'Debug · Vision',
    canActivate: [orgGuard],
    // Idle-preloaded too as of docs/main/CYCLES-PLAN.md §9, CU-b item 2 — weighed against its own
    // small chunk size (~14 kB raw) and decided the same "every tab lands warm" way as `/map`.
    loadComponent: () => import('./debug').then((m) => m.DebugPage),
  },
];
