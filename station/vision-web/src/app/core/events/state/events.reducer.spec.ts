import { describe, expect, it } from 'vitest';
import type { DetectionEvent } from '../../api/models';
import { LiveSocketActions } from '../../live/state/live.actions';
import { EventsApiActions, EventsPageActions } from './events.actions';
import { initialEventsState } from './events.model';
import { eventsFeature } from './events.reducer';

const { reducer } = eventsFeature;

function event(overrides: Partial<DetectionEvent> = {}): DetectionEvent {
  return {
    id: 'e-1',
    streamId: 's-1',
    label: 'person',
    peakConfidence: 0.8,
    firstSeen: '2026-07-23T10:00:00.000Z',
    lastSeen: '2026-07-23T10:00:05.000Z',
    state: 'OPEN',
    ...overrides,
  };
}

describe('eventsFeature reducer', () => {
  it('starts empty, with no cursor and no active consumers', () => {
    expect(initialEventsState).toEqual({ events: [], sinceMs: undefined, activeConsumers: 0 });
  });

  describe('activated / released — the ref-count', () => {
    it('activated increments, released decrements', () => {
      const one = reducer(initialEventsState, EventsPageActions.activated());
      expect(one.activeConsumers).toBe(1);
      const two = reducer(one, EventsPageActions.activated());
      expect(two.activeConsumers).toBe(2);
      const back = reducer(two, EventsPageActions.released());
      expect(back.activeConsumers).toBe(1);
    });

    it('an unmatched released() is a defensive no-op, never going negative', () => {
      const state = reducer(initialEventsState, EventsPageActions.released());
      expect(state.activeConsumers).toBe(0);
    });
  });

  describe('pollSucceeded', () => {
    it('merges an oldest-first batch into events and advances sinceMs to the newest lastSeen', () => {
      const state = reducer(
        initialEventsState,
        EventsApiActions.pollSucceeded({ events: [event({ id: 'e-1', lastSeen: '2026-07-23T10:00:05.000Z' })] }),
      );
      expect(state.events.map((e) => e.id)).toEqual(['e-1']);
      expect(state.sinceMs).toBe(Date.parse('2026-07-23T10:00:05.000Z'));
    });

    it('merges rather than duplicates a repeated id across polls, later batch wins', () => {
      const first = reducer(initialEventsState, EventsApiActions.pollSucceeded({ events: [event({ id: 'e-1', peakConfidence: 0.5 })] }));
      const second = reducer(first, EventsApiActions.pollSucceeded({ events: [event({ id: 'e-1', peakConfidence: 0.9 })] }));
      expect(second.events).toHaveLength(1);
      expect(second.events[0].peakConfidence).toBe(0.9);
    });
  });

  describe('pollFailed', () => {
    it('is a silent degrade — an identity no-op, keeping the last-known list', () => {
      const seeded = reducer(initialEventsState, EventsApiActions.pollSucceeded({ events: [event()] }));
      const state = reducer(seeded, EventsApiActions.pollFailed());
      expect(state).toBe(seeded);
    });
  });

  describe('the detection-events live topic (one DetectionEvent per envelope)', () => {
    function envelope(payload: DetectionEvent) {
      return LiveSocketActions.envelopeReceived({ envelope: { seq: 1, type: 'detection-events', payload } });
    }

    it('folds a single live arrival into events and advances sinceMs', () => {
      const state = reducer(initialEventsState, envelope(event({ id: 'e-live' })));
      expect(state.events.map((e) => e.id)).toEqual(['e-live']);
      expect(state.sinceMs).toBe(Date.parse(event().lastSeen));
    });

    it('an OPEN then a later CLOSED for the same id upserts to CLOSED, chronologically', () => {
      const opened = reducer(initialEventsState, envelope(event({ id: 'e-lifecycle', state: 'OPEN', lastSeen: '2026-07-23T10:00:00.000Z' })));
      const closed = reducer(opened, envelope(event({ id: 'e-lifecycle', state: 'CLOSED', lastSeen: '2026-07-23T10:00:05.000Z' })));
      expect(closed.events).toHaveLength(1);
      expect(closed.events[0].state).toBe('CLOSED');
    });

    it('an envelope of any other type is a true no-op — same state reference', () => {
      const state = reducer(initialEventsState, LiveSocketActions.envelopeReceived({ envelope: { seq: 1, type: 'fleet', payload: [] } }));
      expect(state).toBe(initialEventsState);
    });

    it('runs regardless of activeConsumers — the fold has no gate of its own', () => {
      // deliberately never dispatch `activated()` — activeConsumers stays 0
      const state = reducer(initialEventsState, envelope(event()));
      expect(state.events).toEqual([event()]);
      expect(state.activeConsumers).toBe(0);
    });
  });
});
