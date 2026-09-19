/**
 * The sidebar's view state, layered by precedence (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.1,
 * docs/extracts/design/00-shell.md). Auto-collapse is a *default*, not a lock: a full-bleed route
 * (`/fly`, `/wall`, `/command`) wants the viewport, but an operator who needs the nav must always be
 * able to open it — the shipped bug this shape exists to keep closed was a hard
 * `collapsed() || fullBleed()` that made the nav impossible to expand on the app's three most-used
 * screens.
 */
export interface SidebarState {
  /** Layer 3 — the persisted cross-session default; the only field written to storage. */
  readonly preference: boolean;
  /** Layer 2 — whether the current route asked for the rail. */
  readonly fullBleed: boolean;
  /** Layer 1 — an explicit toggle on this visit; `null` means "no opinion, follow the layers below". */
  readonly override: boolean | null;
  readonly advancedOpen: boolean;
  readonly upcomingOpen: boolean;
}

export const initialSidebarState: SidebarState = {
  preference: false,
  fullBleed: false,
  override: null,
  advancedOpen: false,
  upcomingOpen: false,
};

export const SIDEBAR_COLLAPSED_KEY = 'vision.sidebar.collapsed';
export const SIDEBAR_ADVANCED_OPEN_KEY = 'vision.sidebar.advancedOpen';
export const SIDEBAR_UPCOMING_OPEN_KEY = 'vision.sidebar.upcomingOpen';

/** The precedence rule itself, as one pure function — the reducer needs it to answer "what does a
 * toggle flip *to*", and the feature's `selectCollapsed` projects it for readers. */
export function sidebarCollapsed(state: SidebarState): boolean {
  return state.override ?? (state.preference || state.fullBleed);
}
