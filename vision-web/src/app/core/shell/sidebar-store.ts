import { Injectable, computed, signal } from '@angular/core';
import { readPersistedFlag, writePersistedFlag } from '../panel-state';

const COLLAPSED_KEY = 'vision.sidebar.collapsed';
const ADVANCED_OPEN_KEY = 'vision.sidebar.advancedOpen';
const UPCOMING_OPEN_KEY = 'vision.sidebar.upcomingOpen';

/**
 * `SidebarStore` — the persisted view-state behind `shared/ui/app-sidebar/**` (docs/NAV-IA-REDESIGN-PLAN.md
 * §2.1, docs/design/00-shell.md). Three independent, `localStorage`-backed booleans:
 *
 * - **`collapsed`** — whether the sidebar currently renders as a rail. Derived, not stored:
 *   `override ?? (preference || fullBleed)`. See "Auto-collapse is a default, not a lock" below.
 * - **`advancedOpen`** / **`upcomingOpen`** — the two collapsed-by-default disclosures inside each
 *   mode's own entry list (docs/NAV-IA-REDESIGN-PLAN.md §2.1 rule 7: "`soon` entries collapse under
 *   a `⌄ Upcoming` disclosure … the `advanced`/`diagnostics` Manage groups collapse under
 *   `⌄ Advanced`"). One flag each, shared across all three modes rather than one per mode: today
 *   only Manage ever has an `advanced` tier at all (`features/hubs/nav-entries.ts#navTiers` — Operate
 *   and Monitor have no `group: 'advanced'|'diagnostics'` entries), so per-mode state would just
 *   track something that can't yet differ; `upcoming` does render in all three modes, and one shared
 *   flag means "I've already looked at what isn't built yet" is a single, once-and-done preference
 *   rather than three separately-remembered ones for a scaffold section nobody needs open twice.
 *
 * **Auto-collapse is a default, not a lock.** A full-bleed route (`/fly`, `/wall`, `/command`) wants
 * the whole viewport, so entering one collapses the sidebar to its rail. The first implementation
 * expressed that as a hard `collapsed() || fullBleed()` and additionally disabled the head chevron
 * while `fullBleed()` was true — which made the sidebar **impossible to expand on those three
 * routes**, the app's three most-used screens. That was the wrong shape: an operator on the cockpit
 * who wants to jump to Assets must be able to open the nav, and "the video is important" is a reason
 * to *start* collapsed, never a reason to take the control away.
 *
 * The three inputs are therefore layered by precedence, most specific first:
 * 1. **`override`** — an explicit toggle on *this* visit (`null` when untouched). Always wins.
 * 2. **`fullBleed`** — the current route's own preference for the rail.
 * 3. **`preference`** — the persisted, cross-session default (the only one written to `localStorage`).
 *
 * `enterRoute()` clears `override` on every navigation, so each page starts from its own honest
 * default and a manual expand never silently leaks into the next route. A toggle taken *on* a
 * full-bleed route deliberately does **not** write `preference` — expanding the nav over a video
 * feed is a momentary act, not a statement about how every future page should open — which is also
 * what keeps the original "mysteriously collapsed on the next normal page" bug closed without
 * resorting to disabling the control.
 *
 * Reuses `core/panel-state.ts#readPersistedFlag`/`writePersistedFlag` — the exact same per-key
 * `localStorage` boolean helper `features/live/live.ts`/`features/fly/fly.ts` already use for their
 * own collapse toggles — rather than inventing a second persistence mechanism for this one.
 *
 * **`providedIn: 'root'`**, unlike `core/ui/ui-store.ts#UiStore` (deliberately "provided per host" —
 * a plain `new UiStore()` per independent overlay group) or `WeatherStore` (page-provided — Command
 * and Fly each care about a different position). There is exactly **one** sidebar, mounted once by
 * `app.ts` for the whole session, and both `App` (the `[` shortcut, feeding the full-bleed read into
 * `AppSidebar`) and `AppSidebar` itself (the head chevron, the two disclosures) need to observe and
 * mutate the *same* instance — the one-shared-instance shape `AuthStore`/`FleetStore` already use for
 * exactly this reason.
 */
@Injectable({ providedIn: 'root' })
export class SidebarStore {
  /** Layer 3 — the persisted cross-session default; the only one of the three written to storage. */
  private readonly preference = signal(readPersistedFlag(COLLAPSED_KEY, false));
  /** Layer 2 — whether the current route asked for the rail. */
  private readonly fullBleed = signal(false);
  /** Layer 1 — an explicit toggle on this visit; `null` means "no opinion, follow the layers below". */
  private readonly override = signal<boolean | null>(null);

  private readonly advancedOpenSignal = signal(readPersistedFlag(ADVANCED_OPEN_KEY, false));
  private readonly upcomingOpenSignal = signal(readPersistedFlag(UPCOMING_OPEN_KEY, false));

  /** Whether the sidebar renders as a rail right now — see the class doc's precedence list. */
  readonly collapsed = computed(() => this.override() ?? (this.preference() || this.fullBleed()));

  readonly advancedOpen = this.advancedOpenSignal.asReadonly();
  readonly upcomingOpen = this.upcomingOpenSignal.asReadonly();

  /**
   * Called by `app.ts` on every `NavigationEnd`. Clearing `override` unconditionally — not just when
   * `fullBleed` changes — is what makes each page start from its own default rather than inheriting
   * a manual expand taken three routes ago.
   */
  enterRoute(fullBleed: boolean): void {
    this.fullBleed.set(fullBleed);
    this.override.set(null);
  }

  /** Flips the sidebar — the head chevron and the global `[` shortcut both call this, on every route. */
  toggle(): void {
    const next = !this.collapsed();
    this.override.set(next);
    // Only a toggle on an ordinary page restates the cross-session default; see the class doc.
    if (!this.fullBleed()) {
      this.preference.set(next);
      writePersistedFlag(COLLAPSED_KEY, next);
    }
  }

  toggleAdvanced(): void {
    this.setAdvancedOpen(!this.advancedOpenSignal());
  }

  setAdvancedOpen(value: boolean): void {
    this.advancedOpenSignal.set(value);
    writePersistedFlag(ADVANCED_OPEN_KEY, value);
  }

  toggleUpcoming(): void {
    this.setUpcomingOpen(!this.upcomingOpenSignal());
  }

  setUpcomingOpen(value: boolean): void {
    this.upcomingOpenSignal.set(value);
    writePersistedFlag(UPCOMING_OPEN_KEY, value);
  }
}
