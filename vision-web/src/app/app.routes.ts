import { Routes } from '@angular/router';
import { FLY_ROUTES } from './features/fly/fly.routes';
import { COMMAND_ROUTES } from './features/command/command.routes';
import { WALL_ROUTES } from './features/wall/wall.routes';
import { MAP_ROUTES } from './features/map/map.routes';
import { DEVICES_ROUTES } from './features/devices/devices.routes';
import { ASSET_DETAIL_ROUTES } from './features/asset-detail/asset-detail.routes';
import { REPLAY_ROUTES } from './features/replay/replay.routes';
import { LIVE_ROUTES } from './features/live/live.routes';
import { SETTINGS_ROUTES } from './features/settings/settings.routes';
import { DEBUG_ROUTES } from './features/debug/debug.routes';

/**
 * One lazy chunk per page.
 *
 * Client-side routing (rather than separate documents) is what lets a live player and its
 * HLS buffer survive a tab switch — a full reload would cost another ~6 s of buffering
 * every time (docs/UX-DESIGN.md §2 T1).
 *
 * Per-feature route arrays (vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3, B8): every routed
 * feature under `features/` owns its own `<name>.routes.ts` (one line to import it here); this
 * file only composes them plus the two shell-level entries that don't belong to any one feature —
 * the `/` redirect and the `**` not-found catch-all.
 */
export const routes: Routes = [
  // docs/MVP3-PLAN.md §C-b: the operator cockpit is now the default landing page — an operator
  // opens the app and is flying-aware in one click (or zero, once a drone is remembered). `/wall`
  // keeps working unchanged for anyone who still wants the many-tiles overview.
  { path: '', pathMatch: 'full', redirectTo: 'fly' },
  ...FLY_ROUTES,
  ...COMMAND_ROUTES,
  ...WALL_ROUTES,
  ...MAP_ROUTES,
  ...DEVICES_ROUTES,
  ...ASSET_DETAIL_ROUTES,
  ...REPLAY_ROUTES,
  ...LIVE_ROUTES,
  ...SETTINGS_ROUTES,
  ...DEBUG_ROUTES,
  {
    path: '**',
    title: 'Not found · Vision',
    data: { preload: false },
    loadComponent: () => import('./features/not-found/not-found').then((m) => m.NotFoundPage),
  },
];
