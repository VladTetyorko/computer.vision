import { Routes } from '@angular/router';
import { HUBS_ROUTES } from './features/hubs/hubs.routes';
import { FLY_ROUTES } from './features/fly/fly.routes';
import { COMMAND_ROUTES } from './features/command/command.routes';
import { WALL_ROUTES } from './features/wall/wall.routes';
import { MAP_ROUTES } from './features/map/map.routes';
import { DEVICES_ROUTES } from './features/devices/devices.routes';
import { ASSETS_ROUTES } from './features/assets/assets.routes';
import { WAREHOUSE_ROUTES } from './features/warehouse/warehouse.routes';
import { ONBOARDING_ROUTES } from './features/onboarding/onboarding.routes';
import { ASSET_DETAIL_ROUTES } from './features/asset-detail/asset-detail.routes';
import { REPLAY_ROUTES } from './features/replay/replay.routes';
import { LIVE_ROUTES } from './features/live/live.routes';
import { SETTINGS_ROUTES } from './features/settings/settings.routes';
import { DEBUG_ROUTES } from './features/debug/debug.routes';
import { ORG_ROUTES } from './features/org-settings/org-settings.routes';
import { ACTIVITY_ROUTES } from './features/activity/activity.routes';
import { LOGIN_ROUTES } from './features/auth/login/login.routes';
import { PREFLIGHT_ROUTES } from './features/preflight/preflight.routes';
import { ALERTS_ROUTES } from './features/alerts/alerts.routes';
import { ROSTER_ROUTES } from './features/roster/roster.routes';
import { CATEGORIES_ROUTES } from './features/categories/categories.routes';
import { REPORTS_ROUTES } from './features/reports/reports.routes';
import { SYSTEM_STATUS_ROUTES } from './features/system-status/system-status.routes';
import { LABELING_ROUTES } from './features/labeling/labeling.routes';
import { MODELS_ROUTES } from './features/models/models.routes';
import { TRAINING_JOB_ROUTES } from './features/training-jobs/training-jobs.routes';
import { authGuard } from './core/auth/auth-guard';

/**
 * One lazy chunk per page.
 *
 * Client-side routing (rather than separate documents) is what lets a live player and its
 * HLS buffer survive a tab switch — a full reload would cost another ~6 s of buffering
 * every time (docs/main/UX-DESIGN.md §2 T1).
 *
 * Per-feature route arrays (vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3, B8): every routed
 * feature under `features/` owns its own `<name>.routes.ts` (one line to import it here); this
 * file only composes them plus the two shell-level entries that don't belong to any one feature —
 * the `/` redirect and the `**` not-found catch-all.
 *
 * **`authGuard` (docs/plans/done/U-AUTH-PLAN.md wave 4)** wraps every real page in one path-less, component-less
 * parent route — the standard Angular way to apply one `canActivate` to a whole group without
 * touching each feature's own `<name>.routes.ts` file or changing any URL (an empty path segment
 * contributes nothing to a child's own path, so `/fly`, `/command`, etc. are unchanged). `/login`
 * itself sits **outside** that group — the one route that must stay reachable with no session.
 */
export const routes: Routes = [
  // docs/plans/done/MVP3-PLAN.md §C-b: the operator cockpit is now the default landing page — an operator
  // opens the app and is flying-aware in one click (or zero, once a drone is remembered). `/wall`
  // keeps working unchanged for anyone who still wants the many-tiles overview.
  { path: '', pathMatch: 'full', redirectTo: 'fly' },
  ...LOGIN_ROUTES,
  {
    path: '',
    canActivate: [authGuard],
    children: [
      ...HUBS_ROUTES,
      ...PREFLIGHT_ROUTES,
      ...ALERTS_ROUTES,
      ...ROSTER_ROUTES,
      ...CATEGORIES_ROUTES,
      ...REPORTS_ROUTES,
      ...SYSTEM_STATUS_ROUTES,
      // MODELS_ROUTES' static 'manage/training/models' must precede LABELING_ROUTES' param route
      // 'manage/training/:datasetId' — see MODELS_ROUTES' own doc comment. TRAINING_JOB_ROUTES has
      // no such constraint (its own doc comment explains why — a different segment count than every
      // LABELING_ROUTES entry), placed alongside the other two training-loop route arrays anyway.
      ...MODELS_ROUTES,
      ...TRAINING_JOB_ROUTES,
      ...LABELING_ROUTES,
      ...FLY_ROUTES,
      ...COMMAND_ROUTES,
      ...WALL_ROUTES,
      ...MAP_ROUTES,
      ...DEVICES_ROUTES,
      ...ASSETS_ROUTES,
      ...WAREHOUSE_ROUTES,
      ...ONBOARDING_ROUTES,
      ...ASSET_DETAIL_ROUTES,
      ...REPLAY_ROUTES,
      ...LIVE_ROUTES,
      ...SETTINGS_ROUTES,
      ...ORG_ROUTES,
      ...ACTIVITY_ROUTES,
      ...DEBUG_ROUTES,
    ],
  },
  {
    path: '**',
    title: 'Not found · Vision',
    data: { preload: false },
    loadComponent: () => import('./features/not-found/not-found').then((m) => m.NotFoundPage),
  },
];
