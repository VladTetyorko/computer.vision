import type { Routes } from '@angular/router';
import { orgGuard } from '../../core/org/org-guard';

/**
 * `/playground` (docs/plans/active/LINK-PAIRING-PLAN.md §3.7/§4 row L4, wave L4) — own lazy chunk,
 * split per vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3 (B8). Spread inside
 * `app.routes.ts`'s `authGuard`-wrapped children group like every other feature; `orgGuard`
 * (`core/org/org-guard.ts`) added because creating a simulated asset is the same "fleet management"
 * action `features/onboarding/onboarding.routes.ts#ONBOARDING_ROUTES` already gates the same way —
 * the nav entry (`features/hubs/nav-entries.ts`, `fleet` group) is `requires: 'MANAGE_ORG'`, so the
 * route now agrees (WAREHOUSE-UX-PLAN.md §3.1 rule 6's "gated nav entry needs a matching route
 * guard" rule, carried over from that file's own precedent).
 */
export const PLAYGROUND_ROUTES: Routes = [
  {
    path: 'playground',
    title: 'Playground · Vision',
    canActivate: [orgGuard],
    loadComponent: () => import('./playground').then((m) => m.PlaygroundPage),
  },
];
