import { createFeature, createReducer, createSelector, on } from '@ngrx/store';
import { applyTrackEvent } from '../../camera-geo/camera-geo-logic';
import { LiveSocketActions } from '../../live/state/live.actions';
import { TracksApiActions, TracksPageActions } from './tracks.actions';
import { initialTracksState, tracksAdapter } from './tracks.model';

const { selectAll } = tracksAdapter.getSelectors();

export const tracksFeature = createFeature({
  name: 'tracks',
  reducer: createReducer(
    initialTracksState,
    on(TracksPageActions.activated, (state) => ({ ...state, activeConsumers: state.activeConsumers + 1 })),
    on(TracksPageActions.released, (state) =>
      state.activeConsumers === 0 ? state : { ...state, activeConsumers: state.activeConsumers - 1 },
    ),
    on(TracksApiActions.loaded, (state, { tracks }) => tracksAdapter.setAll([...tracks], { ...state, loaded: true })),
    // Flag-off (409) degrades to an empty list, not an error — `TracksStore`'s own class doc.
    on(TracksApiActions.loadFailed, (state) => ({ ...state, loaded: true })),
    // The fold runs unconditionally, with no active consumer required — mirrors `GeofenceStore`'s
    // identical "the SSE topic" describe block. Cross-slice action consumption (not a cross-slice
    // *state* read, which selectors alone must do per NGRX-MIGRATION-PLAN.md §3 rule 9) — every
    // `core/map-data/**` slice listens to the same `map` envelope this way.
    on(LiveSocketActions.envelopeReceived, (state, { envelope }) =>
      // `applyTrackEvent` itself no-ops for any `entity` but `'track'` — see its own doc comment.
      envelope.type === 'map' ? tracksAdapter.setAll([...applyTrackEvent(selectAll(state), envelope.payload)], state) : state,
    ),
  ),
  extraSelectors: ({ selectTracksState }) => ({
    selectAllTracks: createSelector(selectTracksState, selectAll),
  }),
});
