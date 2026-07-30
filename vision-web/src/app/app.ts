import { ChangeDetectionStrategy, Component, afterNextRender, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { FleetStore } from './core/fleet/fleet-store';
import { LeafletWarmup } from './core/leaflet-warmup';
import { NAV_MODES } from './features/hubs/nav-entries';
import { Icon } from './shared/ui/icon';
import { IdentityChip } from './shared/ui/identity-chip';
import { NotificationBell } from './shared/ui/notification-bell';
import { ToastHost } from './shared/ui/toast-host';
import { UndoToast } from './shared/ui/undo-toast';

/**
 * `<vision-notification-bell>` (`app.html`, next to the existing live-count/online status chips) is
 * a deliberate, named exception to docs/UX-REWORK-PLAN.md §U-b item 4's "zero action buttons in
 * persistent chrome" — the plan's own §U-c user-amendments blockquote carves this one out
 * explicitly ("Events become notifications (header bell + transient toasts …)"). It reads as one
 * more status affordance alongside the two chips already there (information access, not a mutation
 * trigger), not the return of a header action button. See that component's own doc comment for the
 * `EventsStore` cost-model change it introduces (the events poll is now effectively always-on, not
 * just while Wall/Command/an asset page is mounted).
 *
 * **Hub-and-spoke nav (docs/UI-REDESIGN-PLAN.md Wave 1, replacing the flat Fly·Command·Warehouse·
 * Settings tab row + "More ▾" overflow this class used to render directly)**: `NAV_MODES`
 * (`features/hubs/nav-entries.ts`) is the **one** source of truth for both this header's three
 * mode triggers/dropdowns (`app.html`) and the three hub launcher pages
 * (`features/hubs/operate-hub.ts` etc.) — this class only re-exports it as `modes` for the template,
 * it does not own or duplicate the entry list. Every mode is rendered as **two** independent
 * affordances, per the Wave 1 task brief: (a) a plain `routerLink` straight to that mode's own hub
 * page (`/operate`/`/monitor`/`/manage`), and (b) a `<details>` dropdown listing the same mode's
 * entries — reusing the exact disclosure idiom this app already had for its old single "More ▾"
 * overflow (`.tab-more`/`.tab-more-menu` in the pre-Wave-1 `app.css`) and `identity-chip`'s own
 * menu, not a new dropdown component. `app.html` wraps both in one `routerLinkActive="active"`
 * container per mode — Angular's `RouterLinkActive` directive scans **every** descendant
 * `routerLink` when placed on an ancestor (the same trick the old `.tab-more` used, with no
 * `routerLink` of its own, only child links), so a mode lights up as active whenever the hub route
 * *or any of its own entries* is the current URL (e.g. visiting `/fly` highlights "Operate" even
 * though `/fly` isn't `/operate` itself) — no separate "which mode is active" logic needed here.
 *
 * **Responsive collapse (`--bp-sm`/640px, `app.css`)**: below that width the three per-mode
 * `<details>` triggers are replaced by a single combined "Menu" `<details>` (`app.html`'s
 * `.modes-narrow`) listing all three modes' entries, grouped and labeled — never a silent reduction
 * to icons-only; every existing capability stays one tap away, just relocated behind one disclosure
 * instead of three.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Icon, IdentityChip, NotificationBell, ToastHost, UndoToast],
  templateUrl: './app.html',
  styleUrl: './app.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  protected readonly fleet = inject(FleetStore);

  /** The frozen F4 route→mode map — see this class's own doc comment above. */
  protected readonly modes = NAV_MODES;

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
