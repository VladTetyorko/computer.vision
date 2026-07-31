import type { Routes } from '@angular/router';

/**
 * The hub-and-spoke routes (docs/UI-REDESIGN-PLAN.md Wave 1, F4) — split into its own file per
 * vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3 (B8), see `features/fly/fly.routes.ts`'s doc comment
 * for why. Three groups, all spread inside `app.routes.ts`'s existing `authGuard`-wrapped children
 * group like every other feature:
 *
 * 1. **The three hub launchers** (`/operate`/`/monitor`/`/manage`) — real top-level tabs now, so
 *    idle-preloaded like `/fly`/`/command` (no `data: {preload: false}`).
 * 2. **`/monitor/replay`** — a plain redirect to the existing flat `/replay` route, mirroring
 *    `features/map/map.routes.ts`'s `/map` → `/command` precedent exactly (a path redirect, not a
 *    component route, so it also preserves any query string a caller appends). F4 pins this exact
 *    path as the Monitor hub's "Replay library" tile target; `/replay` with no `?asset=`/`?usage=`
 *    already renders `ReplayPage`'s own honest "No usage specified." empty state (see that
 *    component's own class doc — `errorMessage.set('No usage specified.')`), never a blocked page —
 *    there is no cross-fleet "all recordings" aggregation endpoint yet (F4's own named follow-up),
 *    so this is genuinely the most honest "real route that exists today" for that tile, not a fake
 *    library index.
 * 3. **Four `ComingSoon` scaffold routes** — the areas docs/UI-REDESIGN-PLAN.md Wave 4's own
 *    Additions table classifies as pure **SCAFFOLD** (`/operate/missions`, `/monitor/layouts`,
 *    `/manage/health`, `/manage/firmware`) — each a `data:` object binding straight onto
 *    `ComingSoon`'s inputs via `withComponentInputBinding()` (see that component's own class doc),
 *    never a new page file. `data: {preload: false}` on all four: a scaffold page is exactly the
 *    kind of route not worth pre-fetching ahead of a real navigation (mirrors `**`'s own
 *    `data: {preload: false}` in `app.routes.ts`).
 *
 * **Wave 4 (docs/UI-REDESIGN-PLAN.md) replaced the other five scaffold routes with real pages** —
 * `/operate/preflight` (`features/preflight/**`), `/monitor/alerts` (`features/alerts/**`),
 * `/manage/roster` (`features/roster/**`), `/manage/categories` (`features/categories/**`), and
 * `/manage/reports` (`features/reports/**`) each own their own `<name>.routes.ts` now, spread
 * directly into `app.routes.ts` alongside this file's own export (not listed here any more) — see
 * each feature's own routes file for its doc comment, and `nav-entries.ts` for the matching
 * `badge: 'soon'` removal. This file's own doc comment (and Wave 1's original nine-scaffold count)
 * is updated to match; the four routes still listed below are the ones that are still honestly
 * `ComingSoon`.
 */
export const HUBS_ROUTES: Routes = [
  {
    path: 'operate',
    title: 'Operate · Vision',
    loadComponent: () => import('./operate-hub').then((m) => m.OperateHub),
  },
  {
    path: 'monitor',
    title: 'Monitor · Vision',
    loadComponent: () => import('./monitor-hub').then((m) => m.MonitorHub),
  },
  {
    path: 'manage',
    title: 'Manage · Vision',
    loadComponent: () => import('./manage-hub').then((m) => m.ManageHub),
  },
  { path: 'monitor/replay', redirectTo: 'replay', pathMatch: 'full' },
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
