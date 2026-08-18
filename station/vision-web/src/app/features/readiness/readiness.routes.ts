import type { Routes } from '@angular/router';

/**
 * `/assets/:assetId/readiness` — the per-asset readiness report (docs/plans/active/DRONE-ONBOARDING-PLAN.md
 * §8.1, wave O6). Own lazy chunk, split per vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3
 * (B8) — see `features/fly/fly.routes.ts`'s doc comment for why. Spread inside `app.routes.ts`'s
 * `authGuard`-wrapped children group, alongside `ASSET_DETAIL_ROUTES`/`PREFLIGHT_ROUTES` — no extra
 * role gate, same openness as the fleet board it drills in from.
 */
export const READINESS_ROUTES: Routes = [
  {
    path: 'assets/:assetId/readiness',
    title: 'Readiness · Vision',
    // Param named `:assetId` (not `:id`) so it matches `ReadinessPage.assetId`'s own input name
    // exactly — `withComponentInputBinding()` binds a route param to a component input only when
    // the names match (`ASSET_DETAIL_ROUTES`'s own doc comment, docs/plans/done/MVP2-PLAN.md §V, V-b).
    loadComponent: () => import('./readiness').then((m) => m.ReadinessPage),
  },
];
