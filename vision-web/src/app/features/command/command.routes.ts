import type { Routes } from '@angular/router';

/**
 * The `/command` route (docs/MVP3-PLAN.md §C-c: the manager dashboard). Split into its own file
 * per vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3 (B8) — see `features/fly/fly.routes.ts`'s doc
 * comment for why.
 */
export const COMMAND_ROUTES: Routes = [
  {
    path: 'command',
    title: 'Command · Vision',
    // A real top-level tab, so it's idle-preloaded like every other one (no `data: { preload: false }`).
    loadComponent: () => import('./command').then((m) => m.CommandPage),
  },
];
