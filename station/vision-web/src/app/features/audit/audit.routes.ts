import type { Routes } from '@angular/router';
import { orgGuard } from '../../core/org/org-guard';

/**
 * `/monitor/audit` (docs/plans/active/OPS-UX-PLAN.md §3 B1) — own lazy chunk, split per
 * vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3 (B8). Spread inside `app.routes.ts`'s
 * `authGuard`-wrapped children group like every other feature; `orgGuard` (`core/org/org-guard.ts`,
 * the same guard `/org` and `/manage/roster` use) adds the ADMIN/MANAGER role check on top — a pilot
 * is redirected to `/fly`, mirroring the backend's own `AuditController#list`
 * `VisibilityScope#canManageOrg()` gate so the two doors never disagree.
 */
export const AUDIT_ROUTES: Routes = [
  {
    path: 'monitor/audit',
    title: 'Audit trail · Vision',
    canActivate: [orgGuard],
    loadComponent: () => import('./audit').then((m) => m.AuditPage),
  },
];
