import { ChangeDetectionStrategy, Component, ElementRef, computed, effect, inject, input, viewChild } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { AuthStore } from '../../../core/auth/auth-store';
import { FleetStore } from '../../../core/fleet/fleet-store';
import { LiveStore } from '../../../core/live/live-store';
import { canManageOrg } from '../../../core/org/org-logic';
import { SidebarStore } from '../../../core/shell/sidebar-store';
import { ThemeStore } from '../../../core/shell/theme-store';
import { shellStatusLabel, shellStatusSeverity } from '../../../core/system-status/system-status-logic';
import { SystemStatusStore } from '../../../core/system-status/system-status-store';
import { GlobalOverlayStore } from '../../../core/ui/overlay-store';
import { DemoButton } from '../../../features/demo/demo-button/demo-button';
import { NAV_MODES, navTiers, type NavMode } from '../../../features/hubs/nav-entries';
import { Icon } from '../icon';
import { IdentityChip } from '../identity-chip';
import { NotificationBell } from '../notification-bell';

/**
 * `<vision-app-sidebar>` — the persistent left sidebar that replaces the old top header's three mode
 * dropdowns **and** the three hub launcher pages (docs/plans/done/NAV-IA-REDESIGN-PLAN.md F1/F2/F3/F10,
 * docs/extracts/design/00-shell.md). `NAV_MODES` (`features/hubs/nav-entries.ts`) used to feed two parallel
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
 * - **F10** ("role filtering is applied in one of the two navigation copies") — `managerOnly` (and,
 *   since docs/plans/done/OPS-UX-PLAN.md §2 A5, `badge: 'soon'`) is filtered exactly once, in `modes()`
 *   below. The old split — `ManageHub` honoured it, the header dropdown didn't — cannot recur
 *   because there is only one place left that reads `NAV_MODES`.
 *
 * **Group headers are labels, never links** (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.1 rule 1) — this
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
 * **Interest-point simplification (docs/plans/done/VISUAL-REFRESH-PLAN.md Wave 1)** — the sidebar's own ranking
 * ("where am I" then "one-click switch" then "quiet ambient status") drove three trims: group labels
 * drop their `mode.icon` glyph entirely (it competed with each row's own icon two rows down; the
 * uppercase/tracked/muted label text already reads as a label without one); the Upcoming disclosure's
 * rows drop their per-row `soon` chip (the disclosure's own `<summary>Upcoming</summary>` already says
 * it); and the foot's old two-`.chip` "N live" / "ONLINE"/"OFFLINE" pair collapses to an "N live" chip
 * (when > 0) plus one bare online/offline `.dot` — see the theme-toggle paragraph below for what now
 * shares that row. The active-row treatment (F4: 2px `--color-info` inset bar) keeps its mechanism;
 * only its resting background moved from `--panel-raised` to `--color-info-soft` in `app-sidebar.css`,
 * per F4's own "left bar + soft tint" pairing.
 *
 * **Theme toggle** (docs/plans/done/VISUAL-REFRESH-PLAN.md F3/Wave 1) — a plain icon `<button>` in the foot's
 * status row, sized/styled like `notification-bell.ts`'s own trigger (same box, same one-off inline
 * `<svg>` idiom — `shared/ui/icon-registry.ts` is out of this task's file scope, so this follows the
 * pre-existing "a bespoke inline svg is fine for a one-off glyph" precedent that file itself names,
 * rather than adding a name to the frozen registry from a file this task cannot touch). Calls
 * `ThemeStore.toggle()` directly, no facade indirection (see the `theme` field's own doc comment for
 * why that's fine here but not on a routed page). Shows the *current* theme's glyph — sun while
 * light is active, moon while dark is active — with `title`/`aria-label` describing the action
 * ("Switch to dark theme" while showing the sun, and vice versa). Identical markup at every width;
 * the collapsed-rail CSS only ever hides text and the live chip, never this button. Markup-wise it
 * sits *inside* the brand `<a routerLink="/fly">` (so it shares that corner's layout); its own click
 * handler calls `$event.stopPropagation()` after `theme.toggle()` for exactly that reason — without
 * it, the click bubbles to the anchor and the router navigates to `/fly`, which (being full-bleed)
 * then auto-collapses the sidebar via `SidebarStore.enterRoute()`. A theme click must never double as
 * a navigation.
 *
 * **Responsive** (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.1, docs/extracts/design/00-shell.md): ≥1024px docked
 * (expanded or user-collapsed rail, pure CSS, no JS breakpoint tracking); 640–1024px forced to the
 * rail regardless of `collapsed` (`app-sidebar.css`'s own media query); below 640px the sidebar
 * becomes an off-canvas sheet (`mobileOpen`) behind a hamburger button rendered alongside it, since
 * Wave 1a has no page bar yet for a hamburger to live in (`docs/plans/done/NAV-IA-REDESIGN-PLAN.md` §3 puts the
 * page bar in Wave 2) — this component owns its own trigger rather than waiting for one.
 *
 * **The mobile sheet joins `GlobalOverlayStore`** (docs/plans/done/UI-STATE-PLAN.md §1/§2.2) as `'sidebar-mobile'`
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
 * carry is removed — the store's one document-level listener (docs/plans/done/UI-STATE-PLAN.md §2.2 rule 3: "one
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
  /** Backs the foot's theme-toggle button (docs/plans/done/VISUAL-REFRESH-PLAN.md Wave 1) — a shared shell
   *  component, not a routed feature page, so `core/ui/architecture.spec.ts`'s "routed page injects
   *  only its facade" guard doesn't scan this file at all (it globs `features/**` only); the
   *  `Settings › Appearance` control (`features/settings/account-settings.ts`) IS a routed page and
   *  goes through `AccountSettingsFacade` instead for exactly that reason. */
  protected readonly theme = inject(ThemeStore);
  private readonly auth = inject(AuthStore);
  private readonly liveStore = inject(LiveStore);
  /** Backs the shell rollup dot below (docs/plans/done/SYSTEM-STATUS-PLAN.md §5.2) — the same "shared
   *  shell component, not a routed page" carve-out `theme`'s own doc comment above explains; the
   *  singleton store is already warm app-wide (see that store's own class doc), this just reads it. */
  private readonly systemStatus = inject(SystemStatusStore);
  private readonly overlays = inject(GlobalOverlayStore);
  private readonly hostRef = inject(ElementRef<HTMLElement>);
  /** Optional — only present while the `@else` branch (closed) renders it; see class doc's mobile-sheet paragraph. */
  private readonly hamburgerEl = viewChild<ElementRef<HTMLButtonElement>>('hamburger');

  /** `nav-entries.ts#navTiers`, re-exposed as a protected field so the template can call it per mode — same idiom as `identity-chip.ts`'s `protected readonly roleLabel = topRoleLabel`. */
  protected readonly tiersFor = navTiers;

  /** Same ADMIN/MANAGER gate `identity-chip`/the former `ManageHub` used — applied exactly once, here (F10). */
  private readonly canManage = computed(() => canManageOrg(this.auth.user()?.topRole));

  /**
   * `NAV_MODES` with every `managerOnly` entry dropped for anyone who isn't ADMIN/MANAGER, **and**
   * (docs/plans/done/OPS-UX-PLAN.md §2 A5) every `badge: 'soon'` scaffold entry dropped for the same
   * audience. A `badge: 'soon'` row is a roadmap preview, not a working page (`nav-entries.ts`'s own
   * class doc: "every remaining pure-SCAFFOLD entry") — genuinely useful context for ADMIN/MANAGER
   * sizing up what's coming, but a dead end for a PILOT, who has no manage-facing reason to browse
   * it and no `managerOnly` gate of its own to hide behind (several `upcoming` entries, e.g. Operate's
   * "Flight plans", are plain functional-role rows once shipped, not manager tools). Reuses the exact
   * `canManage()` gate rather than a second predicate — same "safe default for an unresolved role"
   * posture the `managerOnly` filter already has: a not-yet-loaded session hides both, never shows
   * either speculatively.
   */
  protected readonly modes = computed<readonly NavMode[]>(() =>
    NAV_MODES.map((mode) => ({
      ...mode,
      entries: mode.entries.filter(
        (entry) => (!entry.managerOnly || this.canManage()) && (entry.badge !== 'soon' || this.canManage()),
      ),
    })),
  );

  /** See class doc — the store owns the precedence; this is just the template's handle on it. */
  protected readonly effectiveCollapsed = this.sidebar.collapsed;


  /** The header's old "N live" chip — same `FleetStore` read, same condition, just relocated. */
  protected readonly liveCount = computed(() => this.fleet.streams().length);

  /**
   * The shell rollup dot (docs/plans/done/SYSTEM-STATUS-PLAN.md §5.2) — the direct fix for §1.2's
   * finding that this dot answered only "did the last device poll succeed" (`FleetStore.reachable()`
   * alone) and stayed green through a closed live-transport connection or a degraded platform
   * subsystem. Now the worst of three independent axes — backend REST reachability, the SSE
   * live-transport connection (the *same* signal {@link liveTransportSeverity} below reads, just
   * folded into one combined verdict here rather than left as two dots a manager has to reconcile
   * themselves), and `GET /api/system/status`'s own self-reported `overall` — via
   * `system-status-logic.ts#shellStatusSeverity`. Replaces the old bare `offline` computed.
   */
  protected readonly shellSeverity = computed(() =>
    shellStatusSeverity(this.fleet.reachable(), this.liveStore.connectionState(), this.systemStatus.overall()),
  );

  /** The rollup dot's `title`/`aria-label` — one honest sentence, never a bare colour. */
  protected readonly shellLabel = computed(() => shellStatusLabel(this.shellSeverity()));

  /**
   * The foot's live-transport dot+text (docs/plans/done/SYSTEM-STATUS-PLAN.md §3.1) — a **different axis**
   * than `offline`/`fleet.reachable()` above: that dot answers "can we reach the backend's REST API
   * at all"; this one answers "is the SSE `/api/live` connection actually open right now", which is
   * exactly the gap `SYSTEM-STATUS-PLAN.md §1` names — a closed SSE connection on a perfectly reachable
   * backend silently degrades every "live" surface to `PollScheduler`'s 5s polling floor with no
   * visible sign anywhere in the app. Deliberately a quiet `.dot` + plain text here, not a second
   * `.chip` (`.claude/skills/frontend-style/SKILL.md` §5 "one chip per row max" — the sidebar foot is a
   * row too) — the loud, actionable version of this same signal is `app.ts`'s own
   * `<vision-notice variant="warn">` banner for the one case (`closed` + backend reachable) that is
   * actually a silent-degradation problem worth interrupting for; this dot is the ambient, always-on
   * status a manager can glance at, mirroring the backend-reachable dot right beside it.
   */
  protected readonly liveTransportLabel = computed(() => {
    switch (this.liveStore.connectionState()) {
      case 'open':
        return 'Live';
      case 'connecting':
        return 'Connecting';
      case 'closed':
        return 'Polling';
    }
  });

  /** `'ok'` → `.dot.ok`, `'warn'` → `.dot.warn`, `'neutral'` → the bare default `.dot` (already a
   *  quiet `--text-faint` grey — `connecting` is a normal, brief, non-alarming transient, not a
   *  problem worth colouring). */
  protected readonly liveTransportSeverity = computed<'ok' | 'warn' | 'neutral'>(() => {
    switch (this.liveStore.connectionState()) {
      case 'open':
        return 'ok';
      case 'closed':
        return 'warn';
      case 'connecting':
        return 'neutral';
    }
  });

  /** The <640px off-canvas sheet's own open state — `GlobalOverlayStore`-backed (see class doc), so
   *  it shares exclusivity/Escape/outside-click/close-on-navigation with the identity menu and
   *  notification bell. Still transient, never persisted — same reasoning as before this moved:
   *  docs/extracts/design/00-shell.md's responsive table only persists the docked/rail choice, not "was the
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
