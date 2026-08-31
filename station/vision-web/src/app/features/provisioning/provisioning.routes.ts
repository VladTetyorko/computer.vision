import type { Routes } from '@angular/router';
import { orgGuard } from '../../core/org/org-guard';

/**
 * The `/provision-wifi` route (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §4/§8, wave Z5).
 * `orgGuard` (`core/org/org-guard.ts`) — same gate as `ONBOARDING_ROUTES`'s own `/add-source`: this
 * page is reached from, and does the same fleet-onboarding job as, that manager-only wizard, so a
 * pilot who cannot reach `/add-source` must not reach this by URL guess either. **Dev parity**: with
 * `vision.auth.enabled=false` the dev principal resolves to ADMIN, so `orgGuard` passes exactly as
 * it does for `/add-source` — see that guard's own doc comment.
 */
export const PROVISIONING_ROUTES: Routes = [
  {
    path: 'provision-wifi',
    title: 'Provision Wi-Fi · Vision',
    canActivate: [orgGuard],
    loadComponent: () => import('./provisioning').then((m) => m.ProvisioningPage),
  },
];
