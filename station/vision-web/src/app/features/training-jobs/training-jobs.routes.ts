import type { Routes } from '@angular/router';
import { orgGuard } from '../../core/org/org-guard';

/**
 * Own lazy chunk for the `training-jobs` feature (docs/plans/done/CV-TRAINING-PLAN.md Phase 2's last
 * web wave; run-history/detail added docs/plans/active/CV-SETTINGS-PLAN.md §3.3/§6 wave W8), split per
 * vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3 (B8). Spread inside `app.routes.ts`'s
 * `authGuard`-wrapped children group, **before** `LABELING_ROUTES` (already true today —
 * `app.routes.ts`'s own ordering comment) — this array's new `manage/training/runs` is a static
 * 3-segment path that would otherwise be swallowed by `LABELING_ROUTES`' param route
 * `manage/training/:datasetId` (same segment count), the identical hazard `MODELS_ROUTES`' own doc
 * comment already documents for `manage/training/models`. `manage/training/jobs/:jobId` and
 * `manage/training/runs/:runId` face no such hazard — 4 segments, distinct from every
 * `LABELING_ROUTES` entry's 3 or 5.
 *
 * **Role gate differs per route.** `manage/training/jobs/:jobId` carries none — polling a job's live
 * progress is open to any signed-in user, mirroring `TrainingJobController#job()`'s own unscoped
 * read; only starting a run (`DatasetDetailPage`'s own "Train a model" card) is manager-gated.
 * `manage/training/runs` and `manage/training/runs/:runId` both carry `orgGuard` — the **persisted**
 * run history is `canManageOrg`-gated server-side (`TrainingJobService#runs`/`#run`), matching
 * `MODELS_ROUTES`' own `orgGuard` precedent.
 */
export const TRAINING_JOB_ROUTES: Routes = [
  {
    path: 'manage/training/jobs/:jobId',
    title: 'Training job · Vision',
    loadComponent: () => import('./training-job').then((m) => m.TrainingJobPage),
  },
  {
    path: 'manage/training/runs',
    title: 'Training run history · Vision',
    canActivate: [orgGuard],
    loadComponent: () => import('./run-history').then((m) => m.RunHistoryPage),
  },
  {
    path: 'manage/training/runs/:runId',
    title: 'Training run · Vision',
    canActivate: [orgGuard],
    loadComponent: () => import('./run-detail').then((m) => m.RunDetailPage),
  },
];
