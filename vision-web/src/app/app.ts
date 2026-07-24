import { ChangeDetectionStrategy, Component, afterNextRender, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { FleetStore } from './core/fleet/fleet-store';
import { LeafletWarmup } from './core/leaflet-warmup';
import { ToastHost } from './shared/ui/toast-host';

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
   * The plan's own end-state IA (docs/MVP3-PLAN.md "Information architecture change"): **Fly ·
   * Command · Assets · Settings** — job-oriented, one tab per persona's own page (`Fly` the
   * operator's cockpit, `Command` the manager's dashboard, docs/MVP3-PLAN.md §C-c) plus the
   * warehouse (`Assets` — the tab label C-c renames; the route underneath is still `/devices`,
   * unchanged, so every existing `router.navigate(['/devices', ...])`/`routerLink="/devices"` call
   * site across this app keeps working verbatim) and `Settings`. No capability lost: `/wall`,
   * `/map`, `/debug`, and `/live/:deviceId` (never a tab to begin with) are all still fully
   * reachable, just demoted out of the primary tab row into the "More" overflow (`moreLinks`
   * below, rendered as a `<details>` dropdown in `app.html` — this app's existing disclosure idiom,
   * see e.g. `shared/player/stream-info-panel.ts`'s "Technical details", reused here rather than inventing a
   * new dropdown-menu component).
   */
  protected readonly tabs: readonly Tab[] = [
    { path: '/fly', label: 'Fly' },
    { path: '/command', label: 'Command' },
    { path: '/devices', label: 'Assets' },
    { path: '/settings', label: 'Settings' },
  ];

  /** The plan's "still reachable, not removed" routes — folded into the header's "More" overflow. */
  protected readonly moreLinks: readonly Tab[] = [
    { path: '/wall', label: 'Wall' },
    { path: '/map', label: 'Map' },
    { path: '/debug', label: 'Debug' },
  ];

  protected readonly liveCount = computed(() => this.fleet.streams().length);
  protected readonly offline = computed(() => this.fleet.reachable() === false);

  constructor() {
    // Warms the Leaflet chunk on idle (docs/CYCLES-PLAN.md §9, CU-b item 2) — after render so it
    // never competes with first paint or the initial fleet fetch. `inject()` is called here, in
    // the constructor's own injection context, and the resolved instance captured in a const —
    // NOT inside the `afterNextRender` callback itself, which runs *after* render completes and is
    // therefore no longer an injection context (calling `inject()` there throws NG0203 at runtime,
    // confirmed live: every page load logged an uncaught `RuntimeError: NG0203` from this exact
    // line before this fix, on every route, since `App` is the root component and always mounts).
    const leafletWarmup = inject(LeafletWarmup);
    afterNextRender(() => leafletWarmup.schedule());
  }
}
