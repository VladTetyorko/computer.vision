import type { Routes } from '@angular/router';
import { flyRedirectGuard } from './fly-redirect-guard';

/**
 * The `/fly` route family (docs/plans/done/MVP3-PLAN.md §C-b: the operator cockpit, the app's default landing
 * page). Split into its own file per vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §2.3/§3 (B8) — every
 * feature owns its own route entry; `app.routes.ts` only composes them plus the shell-level
 * redirect/`**`.
 *
 * **Two routes now** (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.5 F12 — "the cockpit is not addressable"):
 * `fly` is the drone picker (`DronePickerPage`), `fly/:assetId` is the cockpit itself
 * (`CockpitPage`) — previously one component (`FlyPage`) switched between the two internally with
 * no URL change at all, so the cockpit could never be bookmarked, refreshed into, or shared, and
 * Back never left it. Splitting the *route* is what fixes that; splitting the *component* to match
 * (`drone-picker.ts`/`cockpit.ts`) is what `architecture.spec.ts`'s per-routed-page facade rule
 * (`ROUTED_PAGES`) then requires — see each component's own doc comment for the rest of the split.
 */
export const FLY_ROUTES: Routes = [
  {
    path: 'fly',
    title: 'Fly · Vision',
    // `fly-redirect-guard.ts` resolves `?asset=`/the remembered-and-still-streaming drone *before*
    // this route ever renders — a genuine "nothing to ask" visit never even flashes the picker.
    canActivate: [flyRedirectGuard],
    // `fullBleed` (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.1 rule 5, docs/extracts/design/00-shell.md): read by
    // `shared/ui/app-sidebar/**` to auto-collapse the sidebar to its icon rail on this full-screen
    // page, rather than reflowing the picker grid to make room for an expanded nav column. Kept on
    // the picker too (not just the cockpit below) — a picker visit that immediately redirects still
    // needs the sidebar to *start* collapsed, not flash-expand for one frame first.
    data: { fullBleed: true },
    loadComponent: () => import('./drone-picker').then((m) => m.DronePickerPage),
  },
  {
    // `:assetId` matches `CockpitPage#assetId`'s own input name exactly (`withComponentInputBinding()`
    // binds a route param to a component input only when the names match — mirrors `/live/:deviceId`'s
    // identical `deviceId` naming, MODULE.md's own Routes section).
    path: 'fly/:assetId',
    title: 'Fly · Vision',
    // `?watch=1` binds to `CockpitPage`'s own `watch` input — a query param, so no route pattern
    // change is needed for it (see that component's own doc comment).
    data: { fullBleed: true },
    loadComponent: () => import('./cockpit').then((m) => m.CockpitPage),
  },
];
