import { createFeature, createReducer, createSelector, on } from '@ngrx/store';
import { SidebarPageActions } from './sidebar.actions';
import { initialSidebarState, sidebarCollapsed } from './sidebar.model';

export const sidebarFeature = createFeature({
  name: 'sidebar',
  reducer: createReducer(
    initialSidebarState,
    // Clearing `override` on every navigation — not only when `fullBleed` changes — is what makes
    // each page start from its own honest default instead of inheriting an expand taken three
    // routes ago.
    on(SidebarPageActions.routeEntered, (state, { fullBleed }) => ({
      ...state,
      fullBleed,
      override: null,
    })),
    // A toggle taken *on* a full-bleed route deliberately does not restate the cross-session
    // default: expanding the nav over a video feed is a momentary act, not a statement about how
    // every future page should open.
    on(SidebarPageActions.toggled, (state) => {
      const next = !sidebarCollapsed(state);
      return { ...state, override: next, preference: state.fullBleed ? state.preference : next };
    }),
    on(SidebarPageActions.advancedToggled, (state) => ({ ...state, advancedOpen: !state.advancedOpen })),
    on(SidebarPageActions.advancedSet, (state, { open }) => ({ ...state, advancedOpen: open })),
    on(SidebarPageActions.upcomingToggled, (state) => ({ ...state, upcomingOpen: !state.upcomingOpen })),
    on(SidebarPageActions.upcomingSet, (state, { open }) => ({ ...state, upcomingOpen: open })),
  ),
  extraSelectors: ({ selectSidebarState }) => ({
    /** Whether the sidebar renders as a rail right now — see {@link sidebarCollapsed}. */
    selectCollapsed: createSelector(selectSidebarState, sidebarCollapsed),
  }),
});
