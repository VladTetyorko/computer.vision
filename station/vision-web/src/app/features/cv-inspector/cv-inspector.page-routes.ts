import type { Routes } from '@angular/router';

import { provideCvTraceState } from '../../core/cv-trace/state/cv-trace.providers';
import { orgGuard } from '../../core/org/org-guard';

/**
 * The `/manage/cv` engineer inspector (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.4/§4.8, wave
 * W5.3) behind `cv-inspector.routes.ts`'s `loadChildren` boundary; see
 * `features/fly/fly.page-routes.ts` for the rationale (docs/plans/done/NGRX-MIGRATION-PLAN.md §9,
 * wave N-split).
 *
 * This is the clearest case the split exists for: `cv-trace` is a ~10 kB slice with exactly one
 * consumer — an org-gated inspector most operators never open — and it used to load for all of them.
 */
export const CV_INSPECTOR_PAGE_ROUTES: Routes = [
  {
    path: '',
    title: 'CV inspector · Vision',
    canActivate: [orgGuard],
    providers: [provideCvTraceState()],
    loadComponent: () => import('./cv-inspector').then((m) => m.CvInspectorPage),
  },
];
