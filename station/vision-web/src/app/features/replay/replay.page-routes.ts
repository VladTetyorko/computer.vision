import type { Routes } from '@angular/router';

import { provideTrainingState } from '../../core/training/state/training.providers';

/**
 * The per-usage replay player, behind `replay.routes.ts`'s `loadChildren` boundary
 * (docs/plans/active/NGRX-MIGRATION-PLAN.md §9, wave N-split). `ReplayPage` provides
 * `TrainingFacade` — replay is where an operator sends a frame to a dataset — so the `training`
 * slice is registered here rather than in `core/state/app-state.ts`.
 *
 * The route is empty-pathed: `assets/:assetId/replay/:usageId` is consumed by the boundary, and
 * `paramsInheritanceStrategy: 'emptyOnly'` (Angular's default) hands both params down to this child,
 * so `withComponentInputBinding()` still binds `ReplayPage`'s `assetId`/`usageId` inputs.
 */
export const REPLAY_PAGE_ROUTES: Routes = [
  {
    path: '',
    title: 'Replay · Vision',
    providers: [provideTrainingState()],
    loadComponent: () => import('./replay').then((m) => m.ReplayPage),
  },
];
