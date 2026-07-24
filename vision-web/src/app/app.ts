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
   *
   * `Fly` (docs/MVP3-PLAN.md §C-b) leads the list — it's the default route now (see
   * `app.routes.ts`) and the operator's own single page. The full job-oriented nav this cycle's
   * own "Information architecture change" describes (**Fly · Command · Assets · Settings**,
   * replacing this whole array) is deliberately **not** done yet: `Command` doesn't exist until
   * C-c ships, and swapping `Devices`→`Assets`/dropping `Map`/`Debug` from top-level nav ahead of
   * that would strand their only entry point. This is the honest middle step — additive, no
   * capability lost, every existing tab/route still reachable — not the finished IA.
   */
  protected readonly tabs: readonly Tab[] = [
    { path: '/fly', label: 'Fly' },
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
