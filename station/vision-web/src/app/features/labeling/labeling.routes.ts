import type { Routes } from '@angular/router';
import { orgGuard } from '../../core/org/org-guard';

/**
 * The `/manage/training` dataset routes, kept to a **lazy boundary only** (wave N-split,
 * docs/plans/done/NGRX-MIGRATION-PLAN.md §9) — see `features/fly/fly.routes.ts`'s doc comment for
 * why a `providers:` array may not appear in a statically-imported route file.
 *
 * **`orgGuard` stays here, on the boundary**, rather than moving to each child: a visitor without
 * the capability is turned away before the chunk is fetched at all, which is both the cheaper and
 * the more honest order. `manage/training` prefix-matches, so this entry must keep sitting *after*
 * `MODELS_ROUTES` and `TRAINING_JOB_ROUTES` in `app.routes.ts` — they own the sibling
 * `manage/training/models`, `manage/training/runs` and `manage/training/jobs/:jobId` paths, and
 * ordering, not router backtracking, is what guarantees they win.
 */
export const LABELING_ROUTES: Routes = [
  {
    path: 'manage/training',
    canActivate: [orgGuard],
    loadChildren: () => import('./labeling.page-routes').then((m) => m.LABELING_PAGE_ROUTES),
  },
];
