import { createFeature, createReducer, createSelector, on } from '@ngrx/store';
import { LiveSocketActions } from '../../live/state/live.actions';
import type { MapDrawingResponse } from '../../api/models';
import { applyDrawingEvents, interactionModeForDrawKind, toMapDrawings } from '../drawings-logic';
import { DrawingsApiActions, DrawingsPageActions } from './drawings.actions';
import { drawingsAdapter, initialDrawingsState, type DrawingsState } from './drawings.model';

const { selectAll } = drawingsAdapter.getSelectors();

/** Folds one authoritative drawing (a create/patch response) in — reusing the same SSE fold
 *  `applyDrawingEvents` runs, via a synthetic single-event "delta". Mirrors `DrawingsStore#adopt`. */
function adoptDrawing(state: DrawingsState, drawing: MapDrawingResponse): DrawingsState {
  return drawingsAdapter.setAll(
    [...applyDrawingEvents(selectAll(state), [{ entity: 'drawing', action: 'updated', layerId: drawing.layerId, drawing }])],
    state,
  );
}

function incConsumers(state: DrawingsState): DrawingsState {
  return { ...state, activeConsumers: state.activeConsumers + 1 };
}

function decConsumers(state: DrawingsState): DrawingsState {
  return { ...state, activeConsumers: Math.max(0, state.activeConsumers - 1) };
}

export const drawingsFeature = createFeature({
  name: 'drawings',
  reducer: createReducer(
    initialDrawingsState,
    on(DrawingsPageActions.activated, incConsumers),
    on(DrawingsPageActions.released, decConsumers),
    on(DrawingsApiActions.loaded, (state, { drawings }) => drawingsAdapter.setAll([...drawings], { ...state, loaded: true })),
    on(DrawingsApiActions.loadFailed, (state) => ({ ...state, loaded: true })),

    on(DrawingsPageActions.modeSet, (state, { kind }) => ({ ...state, mode: state.mode === kind ? null : kind })),
    on(DrawingsPageActions.drawingStopped, (state) => ({ ...state, mode: null })),
    on(DrawingsPageActions.colorTokenSet, (state, { token }) => ({ ...state, colorToken: token })),

    on(DrawingsPageActions.selected, (state, { id }) => ({
      ...state,
      selectedDrawingId: state.selectedDrawingId === id ? undefined : id,
    })),
    on(DrawingsPageActions.deselected, (state) => ({ ...state, selectedDrawingId: undefined })),

    on(DrawingsApiActions.completeDraftSucceeded, (state, { drawing }) => ({
      ...adoptDrawing(state, drawing),
      selectedDrawingId: drawing.drawingId,
    })),
    on(DrawingsApiActions.patchSucceeded, (state, { drawing }) => adoptDrawing(state, drawing)),
    on(DrawingsApiActions.removeSucceeded, (state, { id }) => {
      const next = drawingsAdapter.removeOne(id, state);
      return next.selectedDrawingId === id ? { ...next, selectedDrawingId: undefined } : next;
    }),

    /**
     * BUG 3 (docs/plans/done/MAP-REWORK-PLAN.md's postmortem, ported forward): a genuine page
     * change stops an in-progress draw mode; a same-page, query-param-only navigation must not. The
     * effect (`drawings.effects.ts#resetOnRouteChange$`) fires this unconditionally on every
     * `ROUTER_NAVIGATED`; this reducer decides whether the path actually changed — same split as
     * `marks.reducer.ts#routeChanged`, only `mode` resets here (drawing *data* stays shared).
     */
    on(DrawingsPageActions.routeChanged, (state, { path }) => {
      if (state.lastRoutePath !== null && path !== state.lastRoutePath) {
        return { ...state, mode: null, lastRoutePath: path };
      }
      return { ...state, lastRoutePath: path };
    }),

    on(LiveSocketActions.envelopeReceived, (state, { envelope }) =>
      envelope.type === 'map' ? drawingsAdapter.setAll([...applyDrawingEvents(selectAll(state), [envelope.payload])], state) : state,
    ),
  ),
  extraSelectors: ({ selectDrawingsState }) => {
    const selectAllDrawings = createSelector(selectDrawingsState, selectAll);
    const selectDisplayDrawings = createSelector(selectAllDrawings, toMapDrawings);
    const selectSelectedDrawingId = createSelector(selectDrawingsState, (s) => s.selectedDrawingId);
    const selectSelected = createSelector(selectAllDrawings, selectSelectedDrawingId, (drawings, id) => drawings.find((d) => d.drawingId === id));
    const selectInteractionMode = createSelector(selectDrawingsState, (s) => interactionModeForDrawKind(s.mode));
    return { selectAllDrawings, selectDisplayDrawings, selectSelected, selectInteractionMode };
  },
});
