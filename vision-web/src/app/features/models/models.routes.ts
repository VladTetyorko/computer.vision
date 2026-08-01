import type { Routes } from '@angular/router';

/**
 * `/manage/training/models` (docs/CV-TRAINING-PLAN.md Phase 2 T10) — own lazy chunk, split per
 * vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3 (B8). Spread inside `app.routes.ts`'s
 * `authGuard`-wrapped children group, **before** `LABELING_ROUTES` — that array's own
 * `manage/training/:datasetId` is a param route that would otherwise swallow the literal segment
 * `models` (Angular matches routes in array order; a static path must be registered ahead of a
 * sibling param route to win the match). No extra role gate — reading the registry is open to any
 * signed-in user, mirroring `ModelRegistryController#models()`'s own unscoped read; only Promote
 * hides client-side for a non-manager (`ModelsFacade.canManage`).
 */
export const MODELS_ROUTES: Routes = [
  {
    path: 'manage/training/models',
    title: 'CV model registry · Vision',
    loadComponent: () => import('./models').then((m) => m.ModelsPage),
  },
];
