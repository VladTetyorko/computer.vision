import { Routes } from '@angular/router';
import { HUBS_ROUTES } from './features/hubs/hubs.routes';
import { FLY_ROUTES } from './features/fly/fly.routes';
import { CREW_ROUTES } from './features/crew/crew.routes';
import { COMMAND_ROUTES } from './features/command/command.routes';
import { WALL_ROUTES } from './features/wall/wall.routes';
import { MAP_ROUTES } from './features/map/map.routes';
import { DEVICES_ROUTES } from './features/devices/devices.routes';
import { INVENTORY_ROUTES } from './features/inventory/inventory.routes';
import { WAREHOUSE_ROUTES } from './features/warehouse/warehouse.routes';
import { ONBOARDING_ROUTES } from './features/onboarding/onboarding.routes';
import { PROVISIONING_ROUTES } from './features/provisioning/provisioning.routes';
import { ASSET_DETAIL_ROUTES } from './features/asset-detail/asset-detail.routes';
import { REPLAY_ROUTES } from './features/replay/replay.routes';
import { LIVE_ROUTES } from './features/live/live.routes';
import { SETTINGS_ROUTES } from './features/settings/settings.routes';
import { DEBUG_ROUTES } from './features/debug/debug.routes';
import { ORG_ROUTES } from './features/org-settings/org-settings.routes';
import { ACTIVITY_ROUTES } from './features/activity/activity.routes';
import { LOGIN_ROUTES } from './features/auth/login/login.routes';
import { SETUP_ROUTES } from './features/setup/setup.routes';
import { PREFLIGHT_ROUTES } from './features/preflight/preflight.routes';
import { READINESS_ROUTES } from './features/readiness/readiness.routes';
import { ALERTS_ROUTES } from './features/alerts/alerts.routes';
import { ROSTER_ROUTES } from './features/roster/roster.routes';
import { AUDIT_ROUTES } from './features/audit/audit.routes';
import { CATEGORIES_ROUTES } from './features/categories/categories.routes';
import { REPORTS_ROUTES } from './features/reports/reports.routes';
import { SYSTEM_STATUS_ROUTES } from './features/system-status/system-status.routes';
import { LABELING_ROUTES } from './features/labeling/labeling.routes';
import { MODELS_ROUTES } from './features/models/models.routes';
import { TRAINING_JOB_ROUTES } from './features/training-jobs/training-jobs.routes';
import { GEO_ROUTES } from './features/geo/geo.routes';
import { CONTROLLER_ROUTES } from './features/controller/controller.routes';
import { VISION_PROFILES_ROUTES } from './features/vision-profiles/vision-profiles.routes';
import { authGuard } from './core/auth/auth-guard';
import { landingGuard } from './core/shell/landing-guard';

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
 * and `/setup` (docs/plans/active/AUTH-ROLES-PLAN.md wave W3) both sit **outside** that group — the two
 * routes that must stay reachable with no session at all.
 *
 * **`landingGuard` (docs/plans/done/OPS-UX-PLAN.md §2 A1)** resolves the bare `''` path by role — ADMIN/MANAGER
 * land on `/command` (the fleet-wide overview their authority spans), PILOT lands on `/fly` (the
 * cockpit is their whole job, unchanged from before this task). `redirectTo` alone can't express
 * this — it fires before any session is resolved and can only ever name one static target.
 * `children: []` is the standard trick for a guard-only leaf route: `canActivate` always returns a
 * `UrlTree` here, so nothing ever needs to match into `children`. Deliberately its own route, not
 * folded into the `authGuard` wrapper below — it only ever runs for the exact `''` path, so it can
 * never affect a deep link or browser-back to any real page (`core/shell/landing-guard.ts`'s own
 * doc comment).
 */
export const routes: Routes = [
  { path: '', canActivate: [landingGuard], children: [] },
  ...SETUP_ROUTES,
  ...LOGIN_ROUTES,
  {
    path: '',
    canActivate: [authGuard],
    children: [
      ...HUBS_ROUTES,
      ...PREFLIGHT_ROUTES,
      ...ALERTS_ROUTES,
      ...ROSTER_ROUTES,
      ...AUDIT_ROUTES,
      ...CATEGORIES_ROUTES,
      ...REPORTS_ROUTES,
      ...GEO_ROUTES,
      ...CONTROLLER_ROUTES,
      ...VISION_PROFILES_ROUTES,
      ...SYSTEM_STATUS_ROUTES,
      // MODELS_ROUTES' static 'manage/training/models' must precede LABELING_ROUTES' param route
      // 'manage/training/:datasetId' — see MODELS_ROUTES' own doc comment. TRAINING_JOB_ROUTES has
      // no such constraint (its own doc comment explains why — a different segment count than every
      // LABELING_ROUTES entry), placed alongside the other two training-loop route arrays anyway.
      ...MODELS_ROUTES,
      ...TRAINING_JOB_ROUTES,
      ...LABELING_ROUTES,
      ...FLY_ROUTES,
      ...CREW_ROUTES,
      ...COMMAND_ROUTES,
      ...WALL_ROUTES,
      ...MAP_ROUTES,
      ...DEVICES_ROUTES,
      ...INVENTORY_ROUTES,
      ...WAREHOUSE_ROUTES,
      ...ONBOARDING_ROUTES,
      ...PROVISIONING_ROUTES,
      ...ASSET_DETAIL_ROUTES,
      ...READINESS_ROUTES,
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
