import type { Routes } from '@angular/router';

import { provideTrainingState } from '../../core/training/state/training.providers';

/**
 * The CV-training dataset pages (docs/plans/done/CV-TRAINING-PLAN.md), behind
 * `labeling.routes.ts`'s `loadChildren` boundary. The `training` slice below is registered on the
 * pathless parent so the list → detail → sample-editor drill-down keeps one registration across all
 * three pages instead of tearing it down and refetching at every step
 * (docs/plans/active/NGRX-MIGRATION-PLAN.md §9, wave N-split).
 *
 * Paths are relative to `manage/training`, so `''` is the dataset list. `orgGuard` is not repeated
 * here — `labeling.routes.ts` runs it once on the boundary, which covers every child.
 */
export const LABELING_PAGE_ROUTES: Routes = [
  {
    path: '',
    providers: [provideTrainingState()],
    children: [
      {
        path: '',
        title: 'CV training datasets · Vision',
        loadComponent: () => import('./datasets').then((m) => m.DatasetsPage),
      },
      {
        path: ':datasetId',
        title: 'Dataset · Vision',
        loadComponent: () => import('./dataset-detail').then((m) => m.DatasetDetailPage),
      },
      {
        path: ':datasetId/samples/:sampleId',
        title: 'Label sample · Vision',
        loadComponent: () => import('./sample-editor').then((m) => m.SampleEditorPage),
      },
    ],
  },
];
