import type { Routes } from '@angular/router';

/**
 * The `/fly` route family's own route entry, kept to a **lazy boundary only** (docs/plans/done/NGRX-MIGRATION-PLAN.md
 * §9, wave N-split). `app.routes.ts` imports every feature's own `<feature>.routes.ts` **statically**, so
 * anything named here lands in the initial bundle — including, transitively, any NgRx slice a
 * `providers:` array on this route would reference. The real routes, and the `provideState`/
 * `provideEffects` pair they need, therefore live in `fly.page-routes.ts` behind this `loadChildren`.
 *
 * See `features/fly/fly.page-routes.ts` for what that file then looks like.
 */
export const FLY_ROUTES: Routes = [
  {
    path: 'fly',
    loadChildren: () => import('./fly.page-routes').then((m) => m.FLY_PAGE_ROUTES),
  },
];
