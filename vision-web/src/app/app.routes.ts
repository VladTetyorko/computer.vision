import { Routes } from '@angular/router';

/**
 * One lazy chunk per page.
 *
 * Client-side routing (rather than separate documents) is what lets a live player and its
 * HLS buffer survive a tab switch — a full reload would cost another ~6 s of buffering
 * every time (docs/UX-DESIGN.md §2 T1).
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'wall' },
  {
    path: 'wall',
    title: 'Wall · Vision',
    loadComponent: () => import('./pages/wall/wall').then((m) => m.WallPage),
  },
  {
    path: 'map',
    title: 'Map · Vision',
    // Every tab chunk is now idle-preloaded (docs/CYCLES-PLAN.md §9, CU-b item 2) — "fast" means
    // every tab click lands on a warm chunk, not just the ones visited first. The Leaflet chunk
    // this route pulls in on visit gets its own separate idle warmup, see `core/leaflet-warmup.ts`.
    loadComponent: () => import('./pages/map/map').then((m) => m.MapPage),
  },
  {
    path: 'devices',
    title: 'Devices · Vision',
    loadComponent: () => import('./pages/devices/devices').then((m) => m.DevicesPage),
  },
  {
    path: 'assets/:assetId',
    title: 'Asset · Vision',
    // The Devices page's asset-first list "Open" target (docs/CYCLES-PLAN.md §11, CD-b item 2).
    // Param named `:assetId` (not `:id`) so it matches `AssetDetailPage.assetId`'s own input name
    // exactly — `withComponentInputBinding()` binds a route param to a component input only when
    // the names match (docs/MVP2-PLAN.md §V, V-b — fixed a long-flagged Gotcha; see that entry in
    // vision-web/MODULE.md for how this was silently broken before and what fixing it restores).
    loadComponent: () => import('./pages/asset-detail/asset-detail').then((m) => m.AssetDetailPage),
  },
  {
    path: 'assets/:assetId/replay/:usageId',
    title: 'Replay · Vision',
    // The asset detail page's usage history "Replay" target for a finished usage
    // (docs/MVP2-PLAN.md §R, R-b) — its own lazy chunk, not a nav tab. Param names match
    // `ReplayPage`'s input names exactly (`assetId`/`usageId`) so `withComponentInputBinding()`
    // binds them — see that component's own doc comment.
    loadComponent: () => import('./pages/replay/replay').then((m) => m.ReplayPage),
  },
  {
    path: 'live/:deviceId',
    title: 'Live · Vision',
    loadComponent: () => import('./pages/live/live').then((m) => m.LivePage),
  },
  {
    path: 'settings',
    title: 'Settings · Vision',
    loadComponent: () => import('./pages/settings/settings').then((m) => m.SettingsPage),
  },
  {
    path: 'debug',
    title: 'Debug · Vision',
    // Idle-preloaded too as of docs/CYCLES-PLAN.md §9, CU-b item 2 — weighed against its own
    // small chunk size (~14 kB raw) and decided the same "every tab lands warm" way as `/map`.
    loadComponent: () => import('./pages/debug/debug').then((m) => m.DebugPage),
  },
  {
    path: '**',
    title: 'Not found · Vision',
    data: { preload: false },
    loadComponent: () => import('./pages/not-found/not-found').then((m) => m.NotFoundPage),
  },
];
