import type { Routes } from '@angular/router';

/**
 * `/manage/controller` (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md C11) — own lazy chunk, split
 * per vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3 (B8). Spread inside `app.routes.ts`'s
 * `authGuard`-wrapped children group with **no role gate**, unlike its `/manage/**` neighbours: a
 * control profile is owned by the operator who made it (decision C6), so a pilot configuring their
 * own transmitter is not performing a management action, and `orgGuard` here would lock every pilot
 * out of the one page that is entirely about their own hardware.
 */
export const CONTROLLER_ROUTES: Routes = [
  {
    path: 'manage/controller',
    title: 'Controller · Vision',
    loadComponent: () => import('./controller-setup').then((m) => m.ControllerSetupPage),
  },
];
