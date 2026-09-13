import type { Routes } from '@angular/router';
import { orgGuard } from '../../core/org/org-guard';

/**
 * `/manage/cv` (docs/plans/active/CV-ORCHESTRATION-PLAN.md §9 decision 3, wave W5.3) — own lazy
 * chunk, same `loadComponent` split every other feature route uses (`system-status.routes.ts`'s
 * identical shape). Spread inside `app.routes.ts`'s `authGuard`-wrapped children group, alongside
 * `VISION_PROFILES_ROUTES` — this is the `vision` nav group's fourth entry (`features/hubs/nav-entries.ts`).
 *
 * `orgGuard` (WAREHOUSE-UX-PLAN.md §3.1 rule 6) — every `vision` nav entry is `requires: 'MANAGE_ORG'`
 * (the engineer/ops audience §4.8 names for this surface, same tier as CV training/model registry/
 * Geo regions), so its route carries the matching `canActivate: [orgGuard]` gate; unlike
 * `/manage/system`, this page has no "an operator needs to see why" carve-out — it is the full
 * per-frame ledger, not the one honest status line the fly cockpit already gives every operator.
 */
export const CV_INSPECTOR_ROUTES: Routes = [
  {
    path: 'manage/cv',
    title: 'CV inspector · Vision',
    canActivate: [orgGuard],
    loadComponent: () => import('./cv-inspector').then((m) => m.CvInspectorPage),
  },
];
