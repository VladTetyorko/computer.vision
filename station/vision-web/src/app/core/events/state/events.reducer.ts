import { createFeature, createReducer, on } from '@ngrx/store';
import type { DetectionEvent } from '../../api/models';
import { LiveSocketActions } from '../../live/state/live.actions';
import { MAX_RETAINED_EVENTS, advanceCursor, mergeEvents } from '../events-logic';
import { EventsApiActions, EventsPageActions } from './events.actions';
import { type EventsState, initialEventsState } from './events.model';

/**
 * Folds a fresh batch into `state.events`/`state.sinceMs` — the one place either transport's batch
 * is merged (`EventsStore#applyIncoming`'s own data half; the *notify* half is deliberately not
 * here, see `events.model.ts`'s own doc comment). `incoming` must be oldest-first, exactly like that
 * private method's own contract.
 */
function applyIncoming(state: EventsState, incoming: readonly DetectionEvent[]): EventsState {
  return {
    ...state,
    events: mergeEvents(state.events, incoming, MAX_RETAINED_EVENTS),
    sinceMs: advanceCursor(state.sinceMs, [...incoming].reverse()),
  };
}

export const eventsFeature = createFeature({
  name: 'events',
  reducer: createReducer(
    initialEventsState,
    on(EventsPageActions.activated, (state): EventsState => ({ ...state, activeConsumers: state.activeConsumers + 1 })),
    on(
      EventsPageActions.released,
      (state): EventsState => ({ ...state, activeConsumers: Math.max(0, state.activeConsumers - 1) }),
    ),
    on(EventsApiActions.pollSucceeded, (state, { events }) => applyIncoming(state, events)),
    // `EventsApiActions.pollFailed` needs no handler — a silent degrade keeps the last-known list,
    // i.e. an identity no-op, exactly like `EventsStore#pollOnce`'s bare `catch {}`.
    on(LiveSocketActions.envelopeReceived, (state, { envelope }) =>
      // The `detection-events` topic carries exactly one `DetectionEvent` per envelope — see
      // `live.reducer.ts`'s own identical `case 'detection-events':` handler, which appends the same
      // single payload to `LiveState.detectionEvents`. Every other envelope type is a true no-op
      // (same state reference), mirroring `discovery.reducer.ts`'s identical cross-slice fold.
      envelope.type !== 'detection-events' ? state : applyIncoming(state, [envelope.payload]),
    ),
  ),
});
