import type { Routes } from '@angular/router';

/**
 * `/manage/system` (docs/plans/done/SYSTEM-STATUS-PLAN.md §5.1, wave S3) — own lazy chunk, same
 * `loadComponent` split every other feature route uses (`reports.routes.ts`'s identical shape).
 * Spread inside `app.routes.ts`'s `authGuard`-wrapped children group. **Deliberately not
 * `managerOnly`** (§4.3/§5.1's own call — an operator whose CV died needs to see why, same
 * reasoning `/command` already applies) — there is no extra role gate here, unlike the rest of the
 * `diagnostics` nav group it sits beside.
 */
export const SYSTEM_STATUS_ROUTES: Routes = [
  {
    path: 'manage/system',
    title: 'System status · Vision',
    loadComponent: () => import('./system-status').then((m) => m.SystemStatusPage),
  },
];
