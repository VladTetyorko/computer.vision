import type { Routes } from '@angular/router';

/**
 * `/assets/:assetId/replay/:usageId` — the asset detail page's usage history "Replay" target for a
 * finished usage (docs/plans/done/MVP2-PLAN.md §R, R-b), unchanged — and `/replay` — the replay library
 * (docs/extracts/design/10-replay.md, Wave 4, F8) **and** the event → replay deep link's own flat,
 * query-param route (docs/plans/done/OPS-CORE-PLAN.md §Q1: `?asset=…&usage=…&t=…`), both at once: `/replay`
 * now loads `ReplayLibraryPage`, which renders the library when no `?usage=` is given and defers
 * straight to `ReplayPage` (embedded, not routed) when one is — see that component's own class doc
 * comment for the full "how the two are told apart" writeup. Split into its own file per
 * vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3 (B8) — see `features/fly/fly.routes.ts`'s doc
 * comment for why.
 *
 * **Wave 1's own "why the bare route can't be deleted" reasoning still holds, updated for who
 * answers it.** `shared/ui/notification-bell.ts`, `features/wall/wall-facade.ts`, and
 * `features/alerts/alerts-facade.ts` all call `router.navigate(['/replay'], {queryParams: {asset,
 * usage, t}})` — the real, shipped event → replay deep link — grep-verified live call sites, not a
 * hypothetical. Before Wave 4 this route pointed at `ReplayPage` directly, which answered a
 * param-less visit with "No usage specified."; Wave 1 closed the one navigation path that could
 * reach that (`features/hubs/hubs.routes.ts`'s `monitor/replay` → `ComingSoon`) without touching
 * this route, since deleting it outright would have 404'd all three deep-link callers. Wave 4 goes
 * one step further and makes the param-less case itself honest: it now answers with an actual
 * library instead of an error state that merely became unreachable.
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
    // `?asset=`/`?usage=`/`?t=`/`?sel=` bind to `ReplayLibraryPage`'s own aliased inputs — see that
    // component's class doc comment.
    loadComponent: () => import('./replay-library').then((m) => m.ReplayLibraryPage),
  },
];
