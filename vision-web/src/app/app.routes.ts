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
    path: 'devices',
    title: 'Devices · Vision',
    loadComponent: () => import('./pages/devices/devices').then((m) => m.DevicesPage),
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
    // Rarely visited — don't spend an idle-time preload slot on it (core/idle-preload.ts).
    data: { preload: false },
    loadComponent: () => import('./pages/debug/debug').then((m) => m.DebugPage),
  },
  {
    path: '**',
    title: 'Not found · Vision',
    data: { preload: false },
    loadComponent: () => import('./pages/not-found/not-found').then((m) => m.NotFoundPage),
  },
];
