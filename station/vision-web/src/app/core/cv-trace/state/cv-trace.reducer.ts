import { createFeature, createReducer, on } from '@ngrx/store';
import { appendFrameLedger } from '../cv-trace-logic';
import { CvTraceApiActions, CvTracePageActions } from './cv-trace.actions';
import { initialCvTraceState } from './cv-trace.model';

export const cvTraceFeature = createFeature({
  name: 'cvTrace',
  reducer: createReducer(
    initialCvTraceState,

    on(CvTracePageActions.tracked, (state, { streamId, assetId, last }) => ({
      ...state,
      byStreamId: { ...state.byStreamId, [streamId]: { assetId, cap: last, gate: [], frame: [], world: [] } },
    })),
    on(CvTracePageActions.reset, (state, { streamId }) => {
      if (state.byStreamId[streamId] === undefined) {
        return state;
      }
      const { [streamId]: _removed, ...rest } = state.byStreamId;
      return { ...state, byStreamId: rest };
    }),

    // The poll's authoritative resync — replaces gate/frame/world outright, never merges (class doc
    // on `cv-trace.model.ts#CvTraceSessionState.frame`).
    on(CvTraceApiActions.pollReceived, (state, { streamId, trace }) => {
      const entry = state.byStreamId[streamId];
      if (entry === undefined) {
        return state;
      }
      return {
        ...state,
        byStreamId: { ...state.byStreamId, [streamId]: { ...entry, gate: trace.gate, frame: trace.frame, world: trace.world } },
      };
    }),
    // Silent-degrade: a missed poll just leaves every ledger at its last-known value.
    on(CvTraceApiActions.pollFailed, (state) => state),
    on(CvTraceApiActions.liveFrameReceived, (state, { streamId, frame }) => {
      const entry = state.byStreamId[streamId];
      if (entry === undefined) {
        return state;
      }
      return { ...state, byStreamId: { ...state.byStreamId, [streamId]: { ...entry, frame: appendFrameLedger(entry.frame, frame, entry.cap) } } };
    }),
  ),
});
