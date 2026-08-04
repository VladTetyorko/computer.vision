import type { Routes } from '@angular/router';

/**
 * The `/fly` route (docs/MVP3-PLAN.md §C-b: the operator cockpit, the app's default landing page).
 * Split into its own file per vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3 (B8) — every feature
 * owns its own route entry; `app.routes.ts` only composes them plus the shell-level redirect/`**`.
 */
export const FLY_ROUTES: Routes = [
  {
    path: 'fly',
    title: 'Fly · Vision',
    // `?asset=`/`?watch=1` bind to `FlyPage`'s own inputs by name — query params, so no route
    // pattern change is needed for either (see `FlyPage`'s own doc comment).
    // `fullBleed` (docs/NAV-IA-REDESIGN-PLAN.md §2.1 rule 5, docs/design/00-shell.md): read by
    // `shared/ui/app-sidebar/**` to auto-collapse the sidebar to its icon rail on this full-screen
    // cockpit view, rather than reflowing the video to make room for an expanded nav column.
    data: { fullBleed: true },
    loadComponent: () => import('./fly').then((m) => m.FlyPage),
  },
];
