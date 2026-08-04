import type { Routes } from '@angular/router';

/**
 * `/assets/:assetId/replay/:usageId` — the asset detail page's usage history "Replay" target for a
 * finished usage (docs/MVP2-PLAN.md §R, R-b) — and `/replay` — the event → replay deep link's own
 * flat, query-param route (docs/OPS-CORE-PLAN.md §Q1: `?asset=…&usage=…&t=…`). Both load the same
 * `ReplayPage`; see that component's own class doc comment for how it tells the two apart. Split
 * into its own file per vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3 (B8) — see
 * `features/fly/fly.routes.ts`'s doc comment for why.
 *
 * **The bare `'replay'` route is deliberately kept (docs/NAV-IA-REDESIGN-PLAN.md F8, docs/design/
 * 10-replay.md), despite that design doc's own Wave-1 refactor list literally saying "remove the bare
 * `/replay` route so the empty detail state is unreachable."** That instruction targets one specific
 * problem: the Monitor hub's "Replay library" tile used to redirect here with no params, landing on
 * `ReplayPage`'s own "No usage specified." empty state — a nav entry advertising a feature that
 * doesn't exist (F8). That path is now closed **without touching this file** —
 * `features/hubs/hubs.routes.ts`'s `monitor/replay` route points straight at `ComingSoon` instead of
 * redirecting here (see that file's own doc comment). **This route itself could not be deleted**:
 * `shared/ui/notification-bell.ts`, `features/wall/wall-facade.ts`, and `features/alerts/alerts-
 * facade.ts` all call `router.navigate(['/replay'], {queryParams: {asset, usage, t}})` — the real,
 * shipped event → replay deep link (docs/OPS-CORE-PLAN.md §Q1) — grep-verified live call sites, not a
 * hypothetical. Deleting the route would have 404'd all three. The only way to reach the bare,
 * param-less "No usage specified." state now is typing `/replay` directly into the address bar, which
 * is not a navigation path the app itself exposes — F8's acceptance ("no navigation path reaches
 * Replay unavailable") holds without removing a working feature.
 */
export const REPLAY_ROUTES: Routes = [
  {
    path: 'assets/:assetId/replay/:usageId',
    title: 'Replay · Vision',
    // Param names match `ReplayPage`'s `assetId`/`usageId` inputs exactly so
    // `withComponentInputBinding()` binds them — see that component's own doc comment.
    loadComponent: () => import('./replay').then((m) => m.ReplayPage),
  },
  {
    path: 'replay',
    title: 'Replay · Vision',
    // `?asset=`/`?usage=`/`?t=` bind to `ReplayPage`'s aliased `assetIdParam`/`usageIdParam`/
    // `deepLinkOffsetParam` inputs — see that component's own class doc comment.
    loadComponent: () => import('./replay').then((m) => m.ReplayPage),
  },
];
