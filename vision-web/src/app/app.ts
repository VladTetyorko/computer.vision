import { ChangeDetectionStrategy, Component, afterNextRender, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { FleetStore } from './core/fleet/fleet-store';
import { LeafletWarmup } from './core/leaflet-warmup';
import { NotificationBell } from './shared/ui/notification-bell';
import { ToastHost } from './shared/ui/toast-host';
import { UndoToast } from './shared/ui/undo-toast';

interface Tab {
  readonly path: string;
  readonly label: string;
}

/**
 * `<vision-notification-bell>` (`app.html`, next to the existing live-count/online status chips) is
 * a deliberate, named exception to docs/UX-REWORK-PLAN.md §U-b item 4's "zero action buttons in
 * persistent chrome" — the plan's own §U-c user-amendments blockquote carves this one out
 * explicitly ("Events become notifications (header bell + transient toasts …)"). It reads as one
 * more status affordance alongside the two chips already there (information access, not a mutation
 * trigger), not the return of a header action button. See that component's own doc comment for the
 * `EventsStore` cost-model change it introduces (the events poll is now effectively always-on, not
 * just while Wall/Command/an asset page is mounted).
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, NotificationBell, ToastHost, UndoToast],
  templateUrl: './app.html',
  styleUrl: './app.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  protected readonly fleet = inject(FleetStore);

  /**
   * The plan's own end-state IA (docs/MVP3-PLAN.md "Information architecture change"): **Fly ·
   * Command · Warehouse · Settings** — job-oriented, one tab per persona's own page (`Fly` the
   * operator's cockpit, `Command` the manager's dashboard, docs/MVP3-PLAN.md §C-c) plus the
   * warehouse (labeled **Warehouse**, docs/UX-REWORK-PLAN.md §U-d renamed it from C-c's own
   * "Assets" — the route underneath is still `/devices`, with `/warehouse` now an alias; every
   * existing `router.navigate(['/devices', ...])`/`routerLink="/devices"` call site across this app
   * keeps working verbatim) and `Settings`. No capability lost: `/wall` and
   * `/map` (and `/live/:deviceId`, never a tab to begin with) are all still fully reachable, just
   * demoted out of the primary tab row into the "More" overflow (`moreLinks` below, rendered as a
   * `<details>` dropdown in `app.html` — this app's existing disclosure idiom, see e.g.
   * `shared/player/stream-info-panel.ts`'s "Technical details", reused here rather than inventing a
   * new dropdown-menu component). **`/debug` is deliberately not in `moreLinks`**
   * (docs/UX-REWORK-PLAN.md U-a item 5): its route (`features/debug/debug.routes.ts`) is
   * untouched and still fully reachable by direct URL, just no longer advertised in any nav
   * surface — a raw API console isn't a link a pilot/manager should stumble into from "More".
   *
   * **`Map` is gone from `moreLinks` (docs/UX-REWORK-PLAN.md §U-c)**: `/map` now redirects into
   * `/command` (`features/map/map.routes.ts`) — Command absorbed the fleet map, the asset rail, and
   * the docked live preview, so a separate "Map" link would just be a second door to the same
   * screen. `Wall` stays (the plan's own user-amendments blockquote: "the Wall stays as its own
   * route and covers 'all video at once', so Command does NOT absorb Wall").
   */
  protected readonly tabs: readonly Tab[] = [
    { path: '/fly', label: 'Fly' },
    { path: '/command', label: 'Command' },
    { path: '/devices', label: 'Warehouse' },
    { path: '/settings', label: 'Settings' },
  ];

  /** The plan's "still reachable, not removed" routes — folded into the header's "More" overflow. */
  protected readonly moreLinks: readonly Tab[] = [{ path: '/wall', label: 'Wall' }];

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
