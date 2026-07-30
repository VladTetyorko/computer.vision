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
 * 3. **Nine `ComingSoon` scaffold routes** — every F4 area with no real page yet (`/operate/preflight`,
 *    `/operate/missions`, `/monitor/alerts`, `/monitor/layouts`, `/manage/categories`,
 *    `/manage/health`, `/manage/firmware`, `/manage/reports`, `/manage/roster`), each a `data:` object
 *    binding straight onto `ComingSoon`'s inputs via `withComponentInputBinding()` (see that
 *    component's own class doc) — never a new page file. `data: {preload: false}` on all nine: a
 *    scaffold page is exactly the kind of route not worth pre-fetching ahead of a real navigation
 *    (mirrors `**`'s own `data: {preload: false}` in `app.routes.ts`).
 *
 * **`Pilots / roster` and `Inventory reports` are scaffolded here even though F4 classifies their
 * backend as already-functional** (`AssignmentController`/`FleetController`+`AssetStatsController`
 * are both live) — there is simply no frontend page for either yet (a roster view, a fleet-wide
 * reports dashboard); building either is named Wave 4's job (docs/UI-REDESIGN-PLAN.md's own
 * Additions section), not this wave's. Routing them at `ComingSoon` now, with an honest "nearest
 * real capability" link (each asset's own Pilots card; Command's fleet summary), is what keeps this
 * wave from either 404ing on them or reaching ahead into Wave 4's scope to half-build a real page.
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
    path: 'operate/preflight',
    title: 'Pre-flight checklist · Vision',
    data: {
      preload: false,
      eyebrow: 'Operate',
      title: 'Pre-flight checklist',
      description: "Saved, editable checklist templates are coming — today's live status card (GPS fix, battery, link) already runs on the cockpit.",
      nearestLabel: 'Open the cockpit',
      nearestTo: '/fly',
    },
    loadComponent: () => import('./coming-soon').then((m) => m.ComingSoon),
  },
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
    path: 'monitor/alerts',
    title: 'Alerts center · Vision',
    data: {
      preload: false,
      eyebrow: 'Monitor',
      title: 'Alerts center',
      description: 'Saved alert thresholds and acknowledgement are coming — the live event feed already streams via the header bell and Wall.',
      nearestLabel: 'Open Wall',
      nearestTo: '/wall',
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
    path: 'manage/categories',
    title: 'Asset categories · Vision',
    data: {
      preload: false,
      eyebrow: 'Manage',
      title: 'Asset categories',
      description: 'Creating and editing categories is coming — browsing and filtering assets by category already works on Assets.',
      nearestLabel: 'Open Assets',
      nearestTo: '/assets',
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
  {
    path: 'manage/reports',
    title: 'Inventory reports · Vision',
    data: {
      preload: false,
      eyebrow: 'Manage',
      title: 'Inventory reports',
      description: "Exportable, generated reports are coming — Command's fleet summary already covers live counts and the attention queue.",
      nearestLabel: 'Open Command',
      nearestTo: '/command',
    },
    loadComponent: () => import('./coming-soon').then((m) => m.ComingSoon),
  },
  {
    path: 'manage/roster',
    title: 'Pilots / roster · Vision',
    data: {
      preload: false,
      eyebrow: 'Manage',
      title: 'Pilots / roster',
      description: "A dedicated roster across every asset is coming — pilot assignment already works from each asset's own Pilots card, reached from Assets.",
      nearestLabel: 'Open Assets',
      nearestTo: '/assets',
    },
    loadComponent: () => import('./coming-soon').then((m) => m.ComingSoon),
  },
];
