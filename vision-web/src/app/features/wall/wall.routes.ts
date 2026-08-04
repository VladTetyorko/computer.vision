import type { Routes } from '@angular/router';

/**
 * The `/wall` route (the many-tiles overview). Split into its own file per
 * vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3 (B8) — see `features/fly/fly.routes.ts`'s doc
 * comment for why.
 */
export const WALL_ROUTES: Routes = [
  {
    path: 'wall',
    title: 'Wall · Vision',
    // `fullBleed` (docs/NAV-IA-REDESIGN-PLAN.md §2.1 rule 5, docs/design/00-shell.md): read by
    // `shared/ui/app-sidebar/**` to auto-collapse the sidebar to its icon rail on this full-screen
    // many-tiles view, rather than reflowing the video to make room for an expanded nav column.
    data: { fullBleed: true },
    loadComponent: () => import('./wall').then((m) => m.WallPage),
  },
];
