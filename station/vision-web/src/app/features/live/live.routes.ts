import type { Routes } from '@angular/router';

/**
 * The `/live/:deviceId` route's own route entry, kept to a **lazy boundary only** (docs/plans/active/NGRX-MIGRATION-PLAN.md
 * §9, wave N-split). `app.routes.ts` imports every feature's own `<feature>.routes.ts` **statically**, so
 * anything named here lands in the initial bundle — including, transitively, any NgRx slice a
 * `providers:` array on this route would reference. The real routes, and the `provideState`/
 * `provideEffects` pair they need, therefore live in `live.page-routes.ts` behind this `loadChildren`.
 *
 * See `features/fly/fly.page-routes.ts` for what that file then looks like.
 */
export const LIVE_ROUTES: Routes = [
  {
    path: 'live/:deviceId',
    loadChildren: () => import('./live.page-routes').then((m) => m.LIVE_PAGE_ROUTES),
  },
];
