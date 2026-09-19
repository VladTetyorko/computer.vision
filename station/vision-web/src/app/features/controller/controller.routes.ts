import type { Routes } from '@angular/router';

/**
 * `/manage/controller`'s own route entry, kept to a **lazy boundary only** (wave N-split,
 * docs/plans/done/NGRX-MIGRATION-PLAN.md §9) — `app.routes.ts` imports every feature's own
 * `<feature>.routes.ts` statically, so a `providers:` array here would drag the `controlProfile`
 * slice it names into the initial bundle. See `features/fly/fly.routes.ts`'s doc comment for the
 * rule in full, and `controller.page-routes.ts` for the real route.
 */
export const CONTROLLER_ROUTES: Routes = [
  {
    path: 'manage/controller',
    loadChildren: () => import('./controller.page-routes').then((m) => m.CONTROLLER_PAGE_ROUTES),
  },
];
