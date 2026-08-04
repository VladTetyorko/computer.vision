import { ChangeDetectionStrategy, Component, ElementRef, computed, effect, inject, input, viewChild } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { AuthStore } from '../../../core/auth/auth-store';
import { FleetStore } from '../../../core/fleet/fleet-store';
import { canManageOrg } from '../../../core/org/org-logic';
import { SidebarStore } from '../../../core/shell/sidebar-store';
import { GlobalOverlayStore } from '../../../core/ui/overlay-store';
import { DemoButton } from '../../../features/demo/demo-button/demo-button';
import { NAV_MODES, navTiers, type NavMode } from '../../../features/hubs/nav-entries';
import { Icon } from '../icon';
import { IdentityChip } from '../identity-chip';
import { NotificationBell } from '../notification-bell';

/**
 * `<vision-app-sidebar>` — the persistent left sidebar that replaces the old top header's three mode
 * dropdowns **and** the three hub launcher pages (docs/NAV-IA-REDESIGN-PLAN.md F1/F2/F3/F10,
 * docs/design/00-shell.md). `NAV_MODES` (`features/hubs/nav-entries.ts`) used to feed two parallel
 * renderers; it now feeds exactly one — this component — which is what closes the specific gaps the
 * walkthrough behind NAV-IA-REDESIGN-PLAN found:
 *
 * - **F1** ("navigation is duplicated, and one of the two copies costs a page load") — the
 *   `/operate`/`/monitor`/`/manage` hub pages, whose only content was a `vision-tile-grid` of the
 *   same entries the header dropdown already listed, are deleted outright (a concurrent task's own
 *   scope). This component is the one renderer left; there is no second copy left to drift from it.
 * - **F2** ("you cannot see where you are, or what is next to you") — every leaf is on screen at
 *   once (no dropdown that closes on click and forgets what it showed); the active one carries a
 *   permanent 2px accent bar + `aria-current="page"`, not just its own page's `<h1>`.
 * - **F3** ("the dropdown is the only path to most pages, and it clips") — nothing here is a
 *   popover that can run out of viewport height; the body scrolls (`overflow-y: auto`) instead of
 *   silently cutting off Manage's ten entries the way a 961px-tall dropdown used to.
 * - **F10** ("role filtering is applied in one of the two navigation copies") — `managerOnly` is
 *   filtered exactly once, in `modes()` below. The old split — `ManageHub` honoured it, the header
 *   dropdown didn't — cannot recur because there is only one place left that reads `NAV_MODES`.
 *
 * **Group headers are labels, never links** (docs/NAV-IA-REDESIGN-PLAN.md §2.1 rule 1) — this
 * component renders no `/operate`/`/monitor`/`/manage` destination at all; those paths still resolve
 * (a concurrent task's own `app.routes.ts` redirect to each mode's `primaryRoute`), they are just no
 * longer reachable *from here*, which is the specific mechanism that makes the hub pages removable
 * without breaking an old bookmark.
 *
 * **Tiering** (primary / advanced / upcoming) is computed once per mode by
 * `nav-entries.ts#navTiers` — never re-derived here, for the same "one source of truth" reason F1
 * fixes for the entry list itself.
 *
 * **`effectiveCollapsed`** simply re-exposes `SidebarStore.collapsed`, which owns the whole
 * override/route/preference precedence (see that store's own class doc). This component deliberately
 * knows nothing about routing — it neither reads the router nor takes a `fullBleed` input any more;
 * `app.ts` owns the single router subscription and pushes the result into the store, keeping this a
 * dumb renderer like every other `shared/ui/**` component.
 *
 * The head chevron is a plain `(click)="sidebar.toggle()"` and is **never disabled**. An earlier
 * revision forced `collapsed() || fullBleed()` and disabled the chevron on full-bleed routes, which
 * made the sidebar impossible to expand on `/fly`, `/wall` and `/command` — the three screens an
 * operator lives on. Auto-collapse is a default, not a lock; the store's `override` layer is what
 * lets a toggle win on those routes without leaking into the next one.
 *
 * **Foot**: `<vision-identity-chip>`/`<vision-notification-bell>` move here **verbatim in
 * behaviour** (same two components, same session/event data, zero change to either file) from the
 * old header's `.status` block, alongside the live-stream-count/online status chips that used to
 * live there — this is simply the "N live" chip's new home, still next to the identity/notification
 * affordances it always sat beside. Both child components still position their own dropdown as
 * `position: absolute; right: 0` relative to *themselves* — a recipe written for a right-aligned
 * header trigger. Now that the trigger sits near screen-left instead, `app-sidebar.css` repositions
 * just those two popups to open rightward (`::ng-deep`, the same "reach into another component's own
 * DOM to adapt it to a new host" technique `features/fly/fly.css`'s `.stage-video vision-player
 * .frame` override already uses) — neither child component's own file is touched.
 *
 * **Responsive** (docs/NAV-IA-REDESIGN-PLAN.md §2.1, docs/design/00-shell.md): ≥1024px docked
 * (expanded or user-collapsed rail, pure CSS, no JS breakpoint tracking); 640–1024px forced to the
 * rail regardless of `collapsed` (`app-sidebar.css`'s own media query); below 640px the sidebar
 * becomes an off-canvas sheet (`mobileOpen`) behind a hamburger button rendered alongside it, since
 * Wave 1a has no page bar yet for a hamburger to live in (`docs/NAV-IA-REDESIGN-PLAN.md` §3 puts the
 * page bar in Wave 2) — this component owns its own trigger rather than waiting for one.
 *
 * **The mobile sheet joins `GlobalOverlayStore`** (docs/UI-STATE-PLAN.md §1/§2.2) as `'sidebar-mobile'`
 * — it used to be a plain local `signal(false)`, invisible to the identity menu/notification bell it
 * shares this always-mounted shell with, so opening one could leave a *second* thing open behind it
 * (§1 D1/D3, generalized past just the two `<details>`-turned-overlays the plan's own reproduction
 * names). Now opening any one of the three closes the other two, and the same `Escape`/outside-click/
 * navigation rules apply here too, for free. `mobileOpen` is `computed(() =>
 * overlays.isOpen('sidebar-mobile'))` rather than the store's own bare boolean — same template usage
 * as before (`[class.mobile-open]="mobileOpen()"`), no call-site churn. The hamburger button registers
 * itself as `'sidebar-mobile'`'s trigger the same way `identity-chip.ts`/`notification-bell.ts` do —
 * except this one is swapped out for the scrim while the sheet is open (`app-sidebar.html`'s own
 * `@if`/`@else`), so `GlobalOverlayStore.register`'s own doc comment on re-registration covers exactly
 * this component. The local `(keydown.escape)="closeMobile()"` binding this `<aside>` root used to
 * carry is removed — the store's one document-level listener (docs/UI-STATE-PLAN.md §2.2 rule 3: "one
 * listener pair … not one per component") now covers it, and covers strictly more (any focus anywhere
 * on the page, not just inside `.sidebar`).
 */
@Component({
  selector: 'vision-app-sidebar',
  imports: [RouterLink, RouterLinkActive, Icon, IdentityChip, NotificationBell, DemoButton],
  templateUrl: './app-sidebar.html',
  styleUrl: './app-sidebar.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppSidebar {
  protected readonly sidebar = inject(SidebarStore);
  protected readonly fleet = inject(FleetStore);
  private readonly auth = inject(AuthStore);
  private readonly overlays = inject(GlobalOverlayStore);
  private readonly hostRef = inject(ElementRef<HTMLElement>);
  /** Optional — only present while the `@else` branch (closed) renders it; see class doc's mobile-sheet paragraph. */
  private readonly hamburgerEl = viewChild<ElementRef<HTMLButtonElement>>('hamburger');

  /** `nav-entries.ts#navTiers`, re-exposed as a protected field so the template can call it per mode — same idiom as `identity-chip.ts`'s `protected readonly roleLabel = topRoleLabel`. */
  protected readonly tiersFor = navTiers;

  /** Same ADMIN/MANAGER gate `identity-chip`/the former `ManageHub` used — applied exactly once, here (F10). */
  private readonly canManage = computed(() => canManageOrg(this.auth.user()?.topRole));

  /** `NAV_MODES` with every `managerOnly` entry dropped for anyone who isn't ADMIN/MANAGER. */
  protected readonly modes = computed<readonly NavMode[]>(() =>
    NAV_MODES.map((mode) => ({
      ...mode,
      entries: mode.entries.filter((entry) => !entry.managerOnly || this.canManage()),
    })),
  );

  /** See class doc — the store owns the precedence; this is just the template's handle on it. */
  protected readonly effectiveCollapsed = this.sidebar.collapsed;


  /** The header's old "N live" chip — same `FleetStore` read, same condition, just relocated. */
  protected readonly liveCount = computed(() => this.fleet.streams().length);
  protected readonly offline = computed(() => this.fleet.reachable() === false);

  /** The <640px off-canvas sheet's own open state — `GlobalOverlayStore`-backed (see class doc), so
   *  it shares exclusivity/Escape/outside-click/close-on-navigation with the identity menu and
   *  notification bell. Still transient, never persisted — same reasoning as before this moved:
   *  docs/design/00-shell.md's responsive table only persists the docked/rail choice, not "was the
   *  phone sheet open". */
  protected readonly mobileOpen = computed(() => this.overlays.isOpen('sidebar-mobile'));

  constructor() {
    // Registers the hamburger as `'sidebar-mobile'`'s trigger — see class doc's mobile-sheet
    // paragraph for why this is the one shell overlay whose trigger element gets swapped out (for the
    // scrim) while open, and why that's still safe.
    effect(() => {
      const hamburger = this.hamburgerEl();
      if (hamburger) {
        this.overlays.register('sidebar-mobile', this.hostRef.nativeElement, hamburger.nativeElement);
      }
    });
  }

  protected openMobile(): void {
    this.overlays.open('sidebar-mobile');
  }

  protected closeMobile(): void {
    this.overlays.close('sidebar-mobile');
  }

  /** A native `<details>`'s own `toggle` event carries whether it just opened or closed — read straight off the element rather than tracked separately. */
  protected onAdvancedToggle(event: Event): void {
    this.sidebar.setAdvancedOpen((event.target as HTMLDetailsElement).open);
  }

  protected onUpcomingToggle(event: Event): void {
    this.sidebar.setUpcomingOpen((event.target as HTMLDetailsElement).open);
  }
}
