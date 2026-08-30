import type { Routes } from '@angular/router';
import { orgGuard } from '../../core/org/org-guard';

/**
 * `/manage/training` and its two drill-ins (docs/plans/done/CV-TRAINING-PLAN.md Wave T5) — own lazy chunks
 * each, split per vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3 (B8). Spread inside `app.routes.ts`'s
 * `authGuard`-wrapped children group. `orgGuard` (`core/org/org-guard.ts`) added to all three routes
 * per docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 wave W1 rule 5 — CV training moved into the new VISION
 * nav group this wave and every VISION entry is `managerOnly` (`nav-entries.ts`), which withdraws
 * this doc comment's old "no extra role gate ... browsing/capturing/labeling are open to any
 * signed-in user" note (`DatasetsFacade`'s own doc comment is stale on this point too until it's
 * next touched). create/delete still additionally hide client-side (and 403 server-side) for a
 * non-manager on top of this route-level gate.
 *
 * Param names (`datasetId`, `sampleId`) match each page's own route-bound `input.required<string>()`
 * exactly so `withComponentInputBinding()` binds them — see `ReplayPage`'s doc comment for the same
 * precedent.
 */
export const LABELING_ROUTES: Routes = [
  {
    path: 'manage/training',
    title: 'CV training datasets · Vision',
    canActivate: [orgGuard],
    loadComponent: () => import('./datasets').then((m) => m.DatasetsPage),
  },
  {
    path: 'manage/training/:datasetId',
    title: 'Dataset · Vision',
    canActivate: [orgGuard],
    loadComponent: () => import('./dataset-detail').then((m) => m.DatasetDetailPage),
  },
  {
    path: 'manage/training/:datasetId/samples/:sampleId',
    title: 'Label sample · Vision',
    canActivate: [orgGuard],
    loadComponent: () => import('./sample-editor').then((m) => m.SampleEditorPage),
  },
];
