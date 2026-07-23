import { ChangeDetectionStrategy, Component, afterNextRender, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { FleetStore } from './core/fleet-store';
import { LeafletWarmup } from './core/leaflet-warmup';
import { ToastHost } from './ui/toast-host';

interface Tab {
  readonly path: string;
  readonly label: string;
}

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ToastHost],
  templateUrl: './app.html',
  styleUrl: './app.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  protected readonly fleet = inject(FleetStore);

  /**
   * Tabs appear as their backend lands — no "coming soon" pages.
   * Events (Phase 2) and Studio (Phase 3) join here when they have something to show.
   */
  protected readonly tabs: readonly Tab[] = [
    { path: '/wall', label: 'Wall' },
    { path: '/map', label: 'Map' },
    { path: '/devices', label: 'Devices' },
    { path: '/settings', label: 'Settings' },
    { path: '/debug', label: 'Debug' },
  ];

  protected readonly liveCount = computed(() => this.fleet.streams().length);
  protected readonly offline = computed(() => this.fleet.reachable() === false);

  constructor() {
    // Warms the Leaflet chunk on idle (docs/CYCLES-PLAN.md §9, CU-b item 2) — after render so it
    // never competes with first paint or the initial fleet fetch.
    afterNextRender(() => inject(LeafletWarmup).schedule());
  }
}
