import type { Routes } from '@angular/router';

/**
 * The `/crew` route family (docs/plans/active/CREW-CONTROL-PLAN.md §3.4, wave W3) — the crew seat, a
 * second, camera-only station on an asset a pilot may or may not be flying from `/fly/:assetId` at
 * the same time. The real page is one route, `crew/:assetId` — unlike `/fly`, there is no picker of
 * its own: a crew member is always sent here for a specific asset (from the pilot's own dock line, a
 * Wall tile, or a direct link), never asked to choose one first.
 *
 * `:assetId` matches `CrewSeatPage#assetId`'s own input name exactly (`withComponentInputBinding()`),
 * mirroring `fly.routes.ts`'s/`live.routes.ts`'s identical naming convention.
 */
export const CREW_ROUTES: Routes = [
  {
    // The bare `/crew` path — only reachable today from `nav-entries.ts`'s new Operate-group entry
    // (§4 row W3: "one entry in the Operate group"), which needs *some* real, resolving destination
    // and cannot reuse `/wall`'s own `to` (`nav-entries.spec.ts`'s F1 dedup guard: one canonical
    // `NavEntry` per destination, app-wide). No crew-specific landing/picker exists yet — §0.3's own
    // journey names one ("landing → the assets assigned to them") but building it is out of this
    // wave's scope (the §4 W3 row lists only `crew/:assetId`) — so this bounces to the Wall, today's
    // closest "which live asset" surface, same honest-interim shape `fly-redirect-guard.ts` used
    // before `/fly`'s own picker existed. Revisit once a real crew landing page is built.
    path: 'crew',
    // `full`, not the prefix default: a prefix redirect on `crew` swallows `crew/:assetId` itself
    // and bounces every deep link to the Wall (found live in W5 verification).
    pathMatch: 'full',
    redirectTo: '/wall',
  },
  {
    path: 'crew/:assetId',
    title: 'Crew · Vision',
    // Full-bleed video surface, no `<vision-page-bar>` — mirrors `fly.routes.ts`'s identical `data`
    // (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.1 rule 5): the sidebar auto-collapses to its icon
    // rail rather than sharing width with this page's own on-glass header/dock/rail.
    data: { fullBleed: true },
    loadComponent: () => import('./crew').then((m) => m.CrewSeatPage),
  },
];
