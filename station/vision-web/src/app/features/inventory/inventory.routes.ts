import type { Routes } from '@angular/router';

/**
 * `/assets`'s own route entry, kept to a **lazy boundary only** (wave N-split,
 * docs/plans/done/NGRX-MIGRATION-PLAN.md §9) — see `features/fly/fly.routes.ts`'s doc comment for
 * why a `providers:` array may not appear in a statically-imported route file, and
 * `inventory.page-routes.ts` for what the page itself is.
 *
 * **Ordering is load-bearing.** A route with `loadChildren` prefix-matches, so this `assets` entry
 * now also matches the first segment of `/assets/:assetId`, `/assets/:assetId/readiness` and
 * `/assets/:assetId/replay/:usageId`. It therefore sits *after* `READINESS_ROUTES`,
 * `REPLAY_ROUTES` and `ASSET_DETAIL_ROUTES` in `app.routes.ts`, which own those longer paths —
 * exactly the guarantee `ASSET_DETAIL_ROUTES` itself relies on, and the same prefix-matching trap
 * CREW-CONTROL's own `pathMatch` finding recorded. Before this wave the plain `loadComponent` here
 * could not swallow them: a childless route only matches when it consumes the whole URL.
 */
export const INVENTORY_ROUTES: Routes = [
  {
    path: 'assets',
    loadChildren: () => import('./inventory.page-routes').then((m) => m.INVENTORY_PAGE_ROUTES),
  },
];
