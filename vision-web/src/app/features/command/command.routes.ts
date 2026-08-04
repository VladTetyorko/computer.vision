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
    // `fullBleed` (docs/NAV-IA-REDESIGN-PLAN.md §2.1 rule 5, docs/design/00-shell.md): read by
    // `shared/ui/app-sidebar/**` to auto-collapse the sidebar to its icon rail on this map-first
    // view, rather than reflowing the map to make room for an expanded nav column.
    data: { fullBleed: true },
    loadComponent: () => import('./command').then((m) => m.CommandPage),
  },
];
