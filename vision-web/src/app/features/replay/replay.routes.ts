import type { Routes } from '@angular/router';

/**
 * The `/assets/:assetId/replay/:usageId` route — the asset detail page's usage history "Replay"
 * target for a finished usage (docs/MVP2-PLAN.md §R, R-b). Split into its own file per
 * vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3 (B8) — see `features/fly/fly.routes.ts`'s doc
 * comment for why.
 */
export const REPLAY_ROUTES: Routes = [
  {
    path: 'assets/:assetId/replay/:usageId',
    title: 'Replay · Vision',
    // Param names match `ReplayPage`'s input names exactly (`assetId`/`usageId`) so
    // `withComponentInputBinding()` binds them — see that component's own doc comment.
    loadComponent: () => import('./replay').then((m) => m.ReplayPage),
  },
];
