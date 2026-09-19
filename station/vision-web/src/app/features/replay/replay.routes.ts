import type { Routes } from '@angular/router';

/**
 * The replay routes. Only the per-usage player needs a `providers:` array (the `training` slice its
 * `ReplayFacade` reads), so only that one is a **lazy boundary** — see
 * `features/fly/fly.routes.ts`'s doc comment for why a slice may not be named in a
 * statically-imported route file (docs/plans/active/NGRX-MIGRATION-PLAN.md §9, wave N-split). The
 * library below keeps its plain `loadComponent`: it reads no page-scoped slice.
 *
 * This entry must keep sitting *before* `ASSET_DETAIL_ROUTES` in `app.routes.ts` — `assets/:assetId`
 * prefix-matches, and ordering is what guarantees the longer path wins.
 */
export const REPLAY_ROUTES: Routes = [
  {
    // Param names match `ReplayPage`'s `assetId`/`usageId` inputs exactly so
    // `withComponentInputBinding()` binds them — see that component's own doc comment. They sit on
    // the boundary rather than on the page, and reach the component through Angular's default
    // `paramsInheritanceStrategy: 'emptyOnly'`, which passes a parent's params down to an
    // empty-path child.
    path: 'assets/:assetId/replay/:usageId',
    loadChildren: () => import('./replay.page-routes').then((m) => m.REPLAY_PAGE_ROUTES),
  },
  {
    path: 'replay',
    title: 'Replay · Vision',
    // `?asset=`/`?usage=`/`?t=`/`?sel=` bind to `ReplayLibraryPage`'s own aliased inputs — see that
    // component's class doc comment.
    loadComponent: () => import('./replay-library').then((m) => m.ReplayLibraryPage),
  },
];
