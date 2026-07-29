import type { Routes } from '@angular/router';

/**
 * `/assets/:assetId/replay/:usageId` — the asset detail page's usage history "Replay" target for a
 * finished usage (docs/MVP2-PLAN.md §R, R-b) — and `/replay` — the event → replay deep link's own
 * flat, query-param route (docs/OPS-CORE-PLAN.md §Q1: `?asset=…&usage=…&t=…`). Both load the same
 * `ReplayPage`; see that component's own class doc comment for how it tells the two apart. Split
 * into its own file per vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3 (B8) — see
 * `features/fly/fly.routes.ts`'s doc comment for why.
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
