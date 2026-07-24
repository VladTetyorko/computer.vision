import type { Routes } from '@angular/router';

/**
 * The `/debug` route. Split into its own file per vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3
 * (B8) — see `features/fly/fly.routes.ts`'s doc comment for why.
 */
export const DEBUG_ROUTES: Routes = [
  {
    path: 'debug',
    title: 'Debug · Vision',
    // Idle-preloaded too as of docs/CYCLES-PLAN.md §9, CU-b item 2 — weighed against its own
    // small chunk size (~14 kB raw) and decided the same "every tab lands warm" way as `/map`.
    loadComponent: () => import('./debug').then((m) => m.DebugPage),
  },
];
