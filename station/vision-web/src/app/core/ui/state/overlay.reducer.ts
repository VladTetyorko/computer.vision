import { createFeature, createReducer, on } from '@ngrx/store';
import { OverlayPageActions } from './overlay.actions';
import { initialOverlayState } from './overlay.model';

/**
 * The shell's one-open-overlay-at-a-time rule (docs/plans/done/NGRX-MIGRATION-PLAN.md §8), pure:
 * opening any id closes whichever other was open by construction (a plain assignment, not a
 * composed `UiStore` — see this slice's `MODULE.md` entry for why `UiStore` itself is not composed
 * here). `Escape`/an outside click/a navigation all reduce to the same "close whatever is open" as
 * a bare close — the effects that dispatch them already decided *whether* to fire, this reducer only
 * has to decide *what changes*.
 */
export const overlayFeature = createFeature({
  name: 'overlay',
  reducer: createReducer(
    initialOverlayState,
    on(OverlayPageActions.opened, (state, { id }) => ({ ...state, active: id })),
    on(OverlayPageActions.closed, (state, { id }) =>
      id === undefined || state.active === id ? { ...state, active: null } : state,
    ),
    on(OverlayPageActions.toggled, (state, { id }) => ({
      ...state,
      active: state.active === id ? null : id,
    })),
    on(
      OverlayPageActions.escapePressed,
      OverlayPageActions.outsideClicked,
      OverlayPageActions.navigated,
      (state) => (state.active === null ? state : { ...state, active: null }),
    ),
  ),
});
