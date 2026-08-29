import type { Routes } from '@angular/router';
import { orgGuard } from '../../core/org/org-guard';

/**
 * `/manage/training/models` (docs/plans/done/CV-TRAINING-PLAN.md Phase 2 T10) — own lazy chunk, split per
 * vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3 (B8). Spread inside `app.routes.ts`'s
 * `authGuard`-wrapped children group, **before** `LABELING_ROUTES` — that array's own
 * `manage/training/:datasetId` is a param route that would otherwise swallow the literal segment
 * `models` (Angular matches routes in array order; a static path must be registered ahead of a
 * sibling param route to win the match). `orgGuard` (`core/org/org-guard.ts`) added per
 * docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 wave W1 rule 5 — "CV model registry" moved into the new
 * VISION nav group this wave and every VISION entry is `managerOnly` (`nav-entries.ts`), which
 * withdraws this doc comment's old "no extra role gate — reading the registry is open to any
 * signed-in user" note; only Promote still additionally hides client-side for a non-manager on top
 * of this route-level gate (`ModelsFacade.canManage`).
 */
export const MODELS_ROUTES: Routes = [
  {
    path: 'manage/training/models',
    title: 'CV model registry · Vision',
    canActivate: [orgGuard],
    loadComponent: () => import('./models').then((m) => m.ModelsPage),
  },
];
