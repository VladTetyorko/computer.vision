import type { Routes } from '@angular/router';

import { provideDetectionsState } from '../../core/detections/state/detections.providers';
import { provideTelemetryState } from '../../core/telemetry/state/telemetry.providers';

/**
 * The `/live/:deviceId` route — the single-device, video-first watch page (docs/main/CYCLES-PLAN.md
 * §2, §9) — behind `live.routes.ts`'s `loadChildren` boundary; see
 * `features/fly/fly.page-routes.ts` for the rationale (docs/plans/active/NGRX-MIGRATION-PLAN.md §9,
 * wave N-split). The `:deviceId` param lives on the **parent** in `live.routes.ts`, so
 * `withComponentInputBinding()` still binds it to `LivePage#deviceId` by name.
 */
export const LIVE_PAGE_ROUTES: Routes = [
  {
    path: '',
    title: 'Live · Vision',
    providers: [provideTelemetryState(), provideDetectionsState()],
    loadComponent: () => import('./live').then((m) => m.LivePage),
  },
];
