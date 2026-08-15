import type { Routes } from '@angular/router';

/**
 * `/manage/training` and its two drill-ins (docs/plans/done/CV-TRAINING-PLAN.md Wave T5) — own lazy chunks
 * each, split per vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3 (B8). Spread inside `app.routes.ts`'s
 * `authGuard`-wrapped children group, no extra role gate (mirrors `/assets`/`/manage/categories`) —
 * create/delete are hidden client-side (and 403 server-side) for a non-manager, but browsing,
 * capturing, and labeling samples are open to any signed-in user who can see the dataset, so the
 * routes themselves stay ungated (`DatasetsFacade`'s own doc comment).
 *
 * Param names (`datasetId`, `sampleId`) match each page's own route-bound `input.required<string>()`
 * exactly so `withComponentInputBinding()` binds them — see `ReplayPage`'s doc comment for the same
 * precedent.
 */
export const LABELING_ROUTES: Routes = [
  {
    path: 'manage/training',
    title: 'CV training datasets · Vision',
    loadComponent: () => import('./datasets').then((m) => m.DatasetsPage),
  },
  {
    path: 'manage/training/:datasetId',
    title: 'Dataset · Vision',
    loadComponent: () => import('./dataset-detail').then((m) => m.DatasetDetailPage),
  },
  {
    path: 'manage/training/:datasetId/samples/:sampleId',
    title: 'Label sample · Vision',
    loadComponent: () => import('./sample-editor').then((m) => m.SampleEditorPage),
  },
];
