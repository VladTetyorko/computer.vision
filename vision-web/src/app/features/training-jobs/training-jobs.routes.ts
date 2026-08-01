import type { Routes } from '@angular/router';

/**
 * `/manage/training/jobs/:jobId` (docs/CV-TRAINING-PLAN.md Phase 2's last web wave) — own lazy
 * chunk, split per vision-web/docs/UI-STRUCTURE-PLAN.md §2.3/§3 (B8). Spread inside `app.routes.ts`'s
 * `authGuard`-wrapped children group. **No ordering constraint against `LABELING_ROUTES`/
 * `MODELS_ROUTES`, unlike `MODELS_ROUTES`'s own note** — this path has 4 segments
 * (`manage`/`training`/`jobs`/`:jobId`), while every `LABELING_ROUTES` entry has either 3
 * (`manage/training/:datasetId`) or 5 (`manage/training/:datasetId/samples/:sampleId`) segments, so
 * Angular's segment-count matching can never confuse the two regardless of array order (verified by
 * navigating to `/manage/training/jobs/<id>` in the built app). No extra role gate — polling a job's
 * progress is open to any signed-in user, mirroring `TrainingJobController#job()`'s own unscoped
 * read; only starting a run (`DatasetDetailPage`'s own "Train a model" card) is manager-gated.
 */
export const TRAINING_JOB_ROUTES: Routes = [
  {
    path: 'manage/training/jobs/:jobId',
    title: 'Training job · Vision',
    loadComponent: () => import('./training-job').then((m) => m.TrainingJobPage),
  },
];
