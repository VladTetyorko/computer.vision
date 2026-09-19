import { createFeature, createReducer, createSelector, on } from '@ngrx/store';
import { LiveSocketActions } from '../../live/state/live.actions';
import type { MapMark } from '../../api/models';
import { applyMarkEvents, DEFAULT_MARK_PALETTE, toTacticalMarks } from '../mark-logic';
import { MarksApiActions, MarksPageActions } from './marks.actions';
import { initialMarksState, marksAdapter, type MarksState } from './marks.model';

const { selectAll } = marksAdapter.getSelectors();

/** Folds one authoritative mark (a create/geolocate/patch/verify/promote response) into the list —
 *  reusing the same SSE fold `applyMarkEvents` runs, via a synthetic single-event "delta" the REST
 *  response stands in for. Mirrors `MarksStore#adopt` exactly, including the clear-deselects rule. */
function adoptMark(state: MarksState, mark: MapMark): MarksState {
  const action = mark.status === 'CLEARED' ? 'cleared' : 'updated';
  const folded = marksAdapter.setAll(
    [...applyMarkEvents(selectAll(state), [{ entity: 'mark', action, layerId: mark.layerId, mark }])],
    state,
  );
  return action === 'cleared' && folded.selectedMarkId === mark.markId ? { ...folded, selectedMarkId: undefined } : folded;
}

function incConsumers(state: MarksState): MarksState {
  return { ...state, activeConsumers: state.activeConsumers + 1 };
}

function decConsumers(state: MarksState): MarksState {
  return { ...state, activeConsumers: Math.max(0, state.activeConsumers - 1) };
}

export const marksFeature = createFeature({
  name: 'marks',
  reducer: createReducer(
    initialMarksState,
    on(MarksPageActions.activated, incConsumers),
    on(MarksPageActions.released, decConsumers),
    on(MarksApiActions.loaded, (state, { marks }) => marksAdapter.setAll([...marks], { ...state, loaded: true })),
    on(MarksApiActions.loadFailed, (state) => ({ ...state, loaded: true })),

    on(MarksPageActions.selected, (state, { id }) => ({
      ...state,
      selectedMarkId: state.selectedMarkId === id ? undefined : id,
    })),
    on(MarksPageActions.deselected, (state) => ({ ...state, selectedMarkId: undefined })),

    on(MarksPageActions.kindSet, (state, { kind }) => ({ ...state, palette: { ...state.palette, kind } })),
    on(MarksPageActions.affiliationSet, (state, { affiliation }) => ({ ...state, palette: { ...state.palette, affiliation } })),
    on(MarksPageActions.layerSet, (state, { layerId }) => ({ ...state, palette: { ...state.palette, layerId } })),
    on(MarksPageActions.paletteReconciled, (state, { palette }) => ({ ...state, palette })),

    on(MarksPageActions.armed, (state) => ({ ...state, armed: true, draft: null })),
    on(MarksPageActions.disarmed, (state) => ({ ...state, armed: false })),
    on(MarksPageActions.mapClicked, (state, { position }) =>
      state.armed ? { ...state, armed: false, draft: { palette: state.palette, position } } : state,
    ),
    on(MarksPageActions.draftCancelled, (state) => ({ ...state, draft: null })),

    on(MarksApiActions.confirmDraftSucceeded, (state, { mark }) => ({ ...adoptMark(state, mark), selectedMarkId: mark.markId, draft: null })),
    on(MarksApiActions.geolocateSucceeded, (state, { mark }) => ({ ...adoptMark(state, mark), selectedMarkId: mark.markId })),
    on(MarksApiActions.patchSucceeded, (state, { mark }) => adoptMark(state, mark)),
    on(MarksApiActions.verifySucceeded, (state, { mark }) => adoptMark(state, mark)),
    on(MarksApiActions.promoteSucceeded, (state, { mark }) => adoptMark(state, mark)),
    on(MarksApiActions.removeSucceeded, (state, { id }) => {
      const next = marksAdapter.removeOne(id, state);
      return next.selectedMarkId === id ? { ...next, selectedMarkId: undefined } : next;
    }),

    /**
     * BUG 3 (docs/plans/done/MAP-REWORK-PLAN.md's postmortem, ported forward): a genuine page change
     * disarms and drops the draft/palette; a same-page, query-param-only navigation must not. The
     * effect (`marks.effects.ts#resetOnRouteChange$`) fires this unconditionally on every
     * `ROUTER_NAVIGATED`; **this reducer** — not the effect — is what decides "is this actually a
     * transition", exactly reproducing the old `MarksStore#resetOnRouteChange`'s semantics: the
     * first-ever navigation after boot (`lastRoutePath === null`) never resets.
     */
    on(MarksPageActions.routeChanged, (state, { path }) => {
      if (state.lastRoutePath !== null && path !== state.lastRoutePath) {
        return { ...state, armed: false, draft: null, palette: DEFAULT_MARK_PALETTE, lastRoutePath: path };
      }
      return { ...state, lastRoutePath: path };
    }),

    on(LiveSocketActions.envelopeReceived, (state, { envelope }) =>
      envelope.type === 'map' ? marksAdapter.setAll([...applyMarkEvents(selectAll(state), [envelope.payload])], state) : state,
    ),
  ),
  extraSelectors: ({ selectMarksState }) => {
    const selectAllMarks = createSelector(selectMarksState, selectAll);
    const selectDisplayMarks = createSelector(selectAllMarks, toTacticalMarks);
    const selectSelectedMarkId = createSelector(selectMarksState, (s) => s.selectedMarkId);
    const selectSelected = createSelector(selectAllMarks, selectSelectedMarkId, (marks, id) => marks.find((m) => m.markId === id));
    const selectPendingPalette = createSelector(selectMarksState, (s) => (s.armed ? s.palette : null));
    return { selectAllMarks, selectDisplayMarks, selectSelected, selectPendingPalette };
  },
});
