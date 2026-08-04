import type { Routes } from '@angular/router';

/**
 * The retired hub-launcher redirects + the remaining `ComingSoon` scaffold routes
 * (docs/NAV-IA-REDESIGN-PLAN.md Wave 1, docs/design/19-hubs.md). Split into its own file per
 * vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3 (B8), see `features/fly/fly.routes.ts`'s doc comment
 * for why. Spread inside `app.routes.ts`'s existing `authGuard`-wrapped children group like every
 * other feature.
 *
 * **1. The three hub launchers are gone — `/operate`/`/monitor`/`/manage` are now plain redirects**
 * (docs/NAV-IA-REDESIGN-PLAN.md F1: "Every task that starts from a mode costs one extra click and
 * one extra lazy-chunk load for zero information gained"; F10: `managerOnly` was honoured by
 * `ManageHub` and ignored by the top-bar dropdown — two renderers of one array drifting by
 * construction). `operate-hub.ts`/`monitor-hub.ts`/`manage-hub.ts` and their shared
 * `vision-nav-tile`/`vision-tile-grid` launcher shape are deleted outright, not just unrouted —
 * `shared/ui/app-sidebar/**` is now the **one** renderer of `nav-entries.ts#NAV_MODES` (grouping and
 * role-scoping applied once, closing F10 structurally). Each path redirects straight to its mode's
 * `primaryRoute` (`nav-entries.ts#NavMode.primaryRoute`) rather than to a page that no longer exists,
 * so an old bookmark or in-app link still lands somewhere real — the exact "fold a removed launcher
 * into its successor" precedent already set by `features/map/map.routes.ts` (`/map` → `/command`) and
 * `features/warehouse/warehouse.routes.ts` (`/warehouse` → `/assets`). `pathMatch: 'full'` is what
 * keeps these three from shadowing their own children (`/manage/categories`, `/manage/training`,
 * `/monitor/alerts`, `/operate/preflight`, …) — a full-match redirect only ever fires on the bare
 * parent path, so the routes below can spread in any order relative to those other feature route
 * arrays without a shadowing risk (see `app.routes.ts`'s own doc comment for the one ordering
 * constraint that *does* still matter, unrelated to this file).
 *
 * **2. Five `ComingSoon` scaffold routes** — the areas docs/NAV-IA-REDESIGN-PLAN.md's Additions table
 * (and each page's own `docs/design/*.md`) classifies as pure **SCAFFOLD**: `/operate/missions`,
 * `/monitor/replay`, `/monitor/layouts`, `/manage/health`, `/manage/firmware` — each a `data:` object
 * binding straight onto `ComingSoon`'s inputs via `withComponentInputBinding()` (see that component's
 * own class doc), never a new page file. `data: {preload: false}` on all five: a scaffold page is
 * exactly the kind of route not worth pre-fetching ahead of a real navigation (mirrors `**`'s own
 * `data: {preload: false}` in `app.routes.ts`).
 *
 * **`/monitor/replay` (docs/NAV-IA-REDESIGN-PLAN.md F8, docs/design/10-replay.md) used to redirect to
 * the flat `/replay` route, which — with no `?asset=`/`?usage=` — rendered `ReplayPage`'s own honest
 * "No usage specified." empty state: a real page advertising a feature ("Replay library — scrub any
 * finished flight") that has never existed. That is F8's own definition of the worst kind of dead
 * end: it reads as a bug in a working feature, not an unbuilt one. This route now points directly at
 * `ComingSoon` instead of at that redirect, so the only navigation path that ever reached the empty
 * "No usage specified." state is closed. **The underlying flat `features/replay/replay.routes.ts`
 * `'replay'` route itself is intentionally NOT deleted** — see that file's own doc comment for why
 * (it is a real, working deep-link target with query params, unrelated to this nav entry; grep-
 * verified against three live call sites before deciding this).
 */
export const HUBS_ROUTES: Routes = [
  // --- Retired hub launchers — redirect to the mode's primaryRoute, never a page of their own ----
  { path: 'operate', pathMatch: 'full', redirectTo: 'fly' },
  { path: 'monitor', pathMatch: 'full', redirectTo: 'command' },
  { path: 'manage', pathMatch: 'full', redirectTo: 'assets' },

  // --- ComingSoon scaffolds — every remaining badge: 'soon' entry in nav-entries.ts#NAV_MODES ------
  {
    path: 'operate/missions',
    title: 'Flight plans / missions · Vision',
    data: {
      preload: false,
      eyebrow: 'Operate',
      title: 'Flight plans / missions',
      description: "Saved, uploadable flight plans are coming — flight-controller mission upload isn't built yet either.",
    },
    loadComponent: () => import('./coming-soon').then((m) => m.ComingSoon),
  },
  {
    path: 'monitor/replay',
    title: 'Replay library · Vision',
    data: {
      preload: false,
      eyebrow: 'Monitor',
      title: 'Replay library',
      description:
        'A library listing every finished flight — asset, start time, duration — is coming. Today, open a specific flight’s replay from its asset page.',
      nearestLabel: 'Open Command',
      nearestTo: '/command',
    },
    loadComponent: () => import('./coming-soon').then((m) => m.ComingSoon),
  },
  {
    path: 'monitor/layouts',
    title: 'Saved Wall layouts · Vision',
    data: {
      preload: false,
      eyebrow: 'Monitor',
      title: 'Saved Wall layouts',
      description: 'Naming and saving Wall tile arrangements is coming — a client-side feature, no backend needed.',
      nearestLabel: 'Open Wall',
      nearestTo: '/wall',
    },
    loadComponent: () => import('./coming-soon').then((m) => m.ComingSoon),
  },
  {
    path: 'manage/health',
    title: 'Maintenance / health · Vision',
    data: {
      preload: false,
      eyebrow: 'Manage',
      title: 'Maintenance / health',
      description: "Maintenance records and health history are coming — Command's attention list already flags today's issues.",
      nearestLabel: 'Open Command',
      nearestTo: '/command',
    },
    loadComponent: () => import('./coming-soon').then((m) => m.ComingSoon),
  },
  {
    path: 'manage/firmware',
    title: 'Firmware · Vision',
    data: {
      preload: false,
      eyebrow: 'Manage',
      title: 'Firmware',
      description: 'A firmware inventory and update flow are coming — today firmware is only a reported telemetry string, nowhere to manage it yet.',
    },
    loadComponent: () => import('./coming-soon').then((m) => m.ComingSoon),
  },
];
