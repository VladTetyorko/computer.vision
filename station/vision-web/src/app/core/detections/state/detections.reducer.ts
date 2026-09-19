import { createFeature, createReducer, createSelector, on } from '@ngrx/store';
import { resolveAssetScopedTransport, type AssetScopedTransport } from '../../live/live-fallback-logic';
import { liveFeature } from '../../live/state/live.reducer';
import { DetectionsApiActions, DetectionsPageActions } from './detections.actions';
import {
  DETECTIONS_LIMIT,
  detectionsSessionInactive,
  emptyDetectionsSession,
  initialDetectionsState,
  type DetectionsSessionState,
} from './detections.model';

/** Removes `streamId`'s entry once nothing wants it any more — see `detections.model.ts
 *  #detectionsSessionInactive`'s own doc comment. */
function pruneIfInactive(byStreamId: Record<string, DetectionsSessionState | undefined>, streamId: string) {
  const entry = byStreamId[streamId];
  if (entry !== undefined && detectionsSessionInactive(entry)) {
    const { [streamId]: _removed, ...rest } = byStreamId;
    return rest;
  }
  return byStreamId;
}

export const detectionsFeature = createFeature({
  name: 'detections',
  reducer: createReducer(
    initialDetectionsState,

    // --- Detections feed (track()/reset()) ---------------------------------------------------------

    on(DetectionsPageActions.tracked, (state, { streamId, assetId }) => {
      const existing = state.byStreamId[streamId] ?? emptyDetectionsSession();
      return {
        ...state,
        byStreamId: {
          ...state.byStreamId,
          [streamId]: { ...existing, feedActive: true, assetId, pollResults: [], liveResults: [] },
        },
      };
    }),
    on(DetectionsPageActions.reset, (state, { streamId }) => {
      const entry = state.byStreamId[streamId];
      if (entry === undefined) {
        return state;
      }
      const cleared: DetectionsSessionState = { ...entry, feedActive: false, assetId: undefined, pollResults: [], liveResults: [] };
      const byStreamId = { ...state.byStreamId, [streamId]: cleared };
      return { ...state, byStreamId: pruneIfInactive(byStreamId, streamId) };
    }),

    // Bridges a transport flip so the chip strip never blanks for a beat — mirrors
    // `DetectionsStore#applyTransport`'s own imperative seed. Fired once per distinct transport
    // decision (`detections.effects.ts#detectionsFeedGate`), including the very first — harmless
    // there since both lists already start empty.
    on(DetectionsApiActions.transportEntered, (state, { streamId, transport }) => {
      const entry = state.byStreamId[streamId];
      if (entry === undefined) {
        return state;
      }
      if (transport === 'live' && entry.liveResults.length === 0 && entry.pollResults.length > 0) {
        return { ...state, byStreamId: { ...state.byStreamId, [streamId]: { ...entry, liveResults: entry.pollResults } } };
      }
      if (transport === 'poll' && entry.pollResults.length === 0 && entry.liveResults.length > 0) {
        return { ...state, byStreamId: { ...state.byStreamId, [streamId]: { ...entry, pollResults: entry.liveResults } } };
      }
      return state;
    }),
    on(DetectionsApiActions.pollReceived, (state, { streamId, results }) => {
      const entry = state.byStreamId[streamId];
      if (entry === undefined) {
        return state;
      }
      return { ...state, byStreamId: { ...state.byStreamId, [streamId]: { ...entry, pollResults: results } } };
    }),
    // Silent-degrade: a missed poll just leaves `pollResults` (and the CV dot) at their last-known values.
    on(DetectionsApiActions.pollFailed, (state) => state),
    on(DetectionsApiActions.liveResultReceived, (state, { streamId, result }) => {
      const entry = state.byStreamId[streamId];
      if (entry === undefined || entry.liveResults[0] === result) {
        return state; // no entry to accumulate onto, or this exact envelope was already applied
      }
      return {
        ...state,
        byStreamId: {
          ...state.byStreamId,
          [streamId]: { ...entry, liveResults: [result, ...entry.liveResults].slice(0, DETECTIONS_LIMIT) },
        },
      };
    }),

    // --- Tracks poll (trackTracks()/untrackTracks()/followTracks()) --------------------------------

    on(DetectionsPageActions.tracksTracked, (state, { streamId, assetId }) => {
      const existing = state.byStreamId[streamId] ?? { ...emptyDetectionsSession(), assetId };
      return {
        ...state,
        byStreamId: { ...state.byStreamId, [streamId]: { ...existing, tracksWanted: true, tracksPollResponse: null } },
      };
    }),
    on(DetectionsPageActions.tracksUntracked, (state, { streamId }) => {
      const entry = state.byStreamId[streamId];
      if (entry === undefined) {
        return state;
      }
      const cleared: DetectionsSessionState = { ...entry, tracksWanted: false, tracksPollResponse: null };
      const byStreamId = { ...state.byStreamId, [streamId]: cleared };
      return { ...state, byStreamId: pruneIfInactive(byStreamId, streamId) };
    }),
    on(DetectionsApiActions.tracksPollReceived, (state, { streamId, response }) => {
      const entry = state.byStreamId[streamId];
      if (entry === undefined) {
        return state;
      }
      return { ...state, byStreamId: { ...state.byStreamId, [streamId]: { ...entry, tracksPollResponse: response } } };
    }),
    // Honest degrade to `null` — unlike `pollFailed` above, mirrors `DetectionsStore#pollTracksOnce`'s
    // own catch branch (see `detections.model.ts#DetectionsSessionState.tracksPollResponse`).
    on(DetectionsApiActions.tracksPollFailed, (state, { streamId }) => {
      const entry = state.byStreamId[streamId];
      if (entry === undefined) {
        return state;
      }
      return { ...state, byStreamId: { ...state.byStreamId, [streamId]: { ...entry, tracksPollResponse: null } } };
    }),
  ),
});

/**
 * The feed's poll-vs-live decision for `streamId` — the concrete answer to NGRX-MIGRATION-PLAN.md §3
 * convention 9, exactly like `telemetry.reducer.ts#transportSelectorFor`. `detections.effects.ts`'s
 * feed poll-gate is this selector's only subscriber.
 */
export function feedTransportSelectorFor(streamId: string) {
  return createSelector(
    detectionsFeature.selectByStreamId,
    liveFeature.selectConnectionState,
    (byStreamId, connectionState): AssetScopedTransport =>
      resolveAssetScopedTransport(connectionState, byStreamId[streamId]?.assetId),
  );
}

/**
 * The tracks lifecycle's own poll-vs-live decision for `streamId` — reuses whichever `assetId` the
 * feed lifecycle most recently wrote to this *same* entry (`detections.model.ts`'s own class doc),
 * never a second, independently-tracked asset id. Identical shape to {@link feedTransportSelectorFor}
 * by design — two selectors, not a shared parameterized one, because they read different fields'
 * worth of intent even though today they resolve from the same `assetId`.
 */
export function tracksTransportSelectorFor(streamId: string) {
  return createSelector(
    detectionsFeature.selectByStreamId,
    liveFeature.selectConnectionState,
    (byStreamId, connectionState): AssetScopedTransport =>
      resolveAssetScopedTransport(connectionState, byStreamId[streamId]?.assetId),
  );
}
