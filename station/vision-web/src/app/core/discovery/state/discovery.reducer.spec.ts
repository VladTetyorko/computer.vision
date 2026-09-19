import { describe, expect, it } from 'vitest';
import type { DiscoveryCandidate, DiscoveryEventPayload, DiscoverySource } from '../../api/models';
import { LiveSocketActions } from '../../live/state/live.actions';
import { DiscoveryApiActions, DiscoveryPageActions } from './discovery.actions';
import { initialDiscoveryInboxState } from './discovery.model';
import { discoveryInboxFeature } from './discovery.reducer';

const { reducer } = discoveryInboxFeature;

function candidate(overrides: Partial<DiscoveryCandidate> = {}): DiscoveryCandidate {
  return {
    id: 'cand-1',
    method: 'onvif',
    name: 'Camera 1',
    address: '192.168.0.10',
    details: {},
    firstSeen: '2026-09-06T09:00:00Z',
    lastSeen: '2026-09-06T09:59:00Z',
    status: 'NEW',
    ...overrides,
  };
}

function source(overrides: Partial<DiscoverySource> = {}): DiscoverySource {
  return { id: 'onvif', status: 'OK', lastScanAt: '2026-09-06T09:59:00Z', ...overrides };
}

describe('discoveryInboxFeature reducer', () => {
  it('starts idle, empty, with no active consumers', () => {
    expect(initialDiscoveryInboxState).toEqual({
      candidates: [],
      sources: [],
      loading: false,
      busyId: null,
      activeConsumers: 0,
    });
  });

  describe('activated / released — the ref-count', () => {
    it('activated increments, released decrements', () => {
      const one = reducer(initialDiscoveryInboxState, DiscoveryPageActions.activated());
      expect(one.activeConsumers).toBe(1);
      const two = reducer(one, DiscoveryPageActions.activated());
      expect(two.activeConsumers).toBe(2);
      const back = reducer(two, DiscoveryPageActions.released());
      expect(back.activeConsumers).toBe(1);
    });

    it('an unmatched released() is a defensive no-op, never going negative', () => {
      const state = reducer(initialDiscoveryInboxState, DiscoveryPageActions.released());
      expect(state.activeConsumers).toBe(0);
    });
  });

  describe('busyId', () => {
    it('every *Requested action sets busyId to its own candidate id, cleared on the matching Succeeded/Failed', () => {
      const requested = reducer(initialDiscoveryInboxState, DiscoveryPageActions.dismissRequested({ id: 'cand-7' }));
      expect(requested.busyId).toBe('cand-7');
      const settled = reducer(requested, DiscoveryApiActions.dismissSucceeded({ id: 'cand-7', result: candidate({ id: 'cand-7' }) }));
      expect(settled.busyId).toBeNull();
    });

    it('registerRequested/attachRequested/attachCandidateRequested/restoreRequested all set busyId too', () => {
      expect(reducer(initialDiscoveryInboxState, DiscoveryPageActions.registerRequested({ id: 'a', request: { displayName: 'x', category: 'y' } })).busyId).toBe('a');
      expect(reducer(initialDiscoveryInboxState, DiscoveryPageActions.attachRequested({ id: 'b', deviceSpec: { name: 'n', protocol: 'rtsp', uri: 'u', options: {} }, assetId: 'asset-1' })).busyId).toBe('b');
      expect(reducer(initialDiscoveryInboxState, DiscoveryPageActions.attachCandidateRequested({ id: 'c', assetId: 'asset-1' })).busyId).toBe('c');
      expect(reducer(initialDiscoveryInboxState, DiscoveryPageActions.restoreRequested({ id: 'd' })).busyId).toBe('d');
    });

    it('every *Failed action clears busyId without touching candidates', () => {
      const requested = reducer(initialDiscoveryInboxState, DiscoveryPageActions.registerRequested({ id: 'a', request: { displayName: 'x', category: 'y' } }));
      const failed = reducer(requested, DiscoveryApiActions.registerFailed({ id: 'a', error: 'boom' }));
      expect(failed.busyId).toBeNull();
      expect(failed.candidates).toEqual([]);
    });
  });

  describe('poll outcomes', () => {
    it('pollStarted sets loading', () => {
      expect(reducer(initialDiscoveryInboxState, DiscoveryApiActions.pollStarted()).loading).toBe(true);
    });

    it('pollSucceeded stores candidates and sources, clears loading', () => {
      const started = reducer(initialDiscoveryInboxState, DiscoveryApiActions.pollStarted());
      const state = reducer(started, DiscoveryApiActions.pollSucceeded({ candidates: [candidate()], sources: [source()] }));
      expect(state.candidates).toEqual([candidate()]);
      expect(state.sources).toEqual([source()]);
      expect(state.loading).toBe(false);
    });

    it('pollFailed is a silent degrade — keeps the last-known list, only clears loading', () => {
      const seeded = reducer(initialDiscoveryInboxState, DiscoveryApiActions.pollSucceeded({ candidates: [candidate()], sources: [source()] }));
      const started = reducer(seeded, DiscoveryApiActions.pollStarted());
      const state = reducer(started, DiscoveryApiActions.pollFailed());
      expect(state.candidates).toEqual([candidate()]);
      expect(state.sources).toEqual([source()]);
      expect(state.loading).toBe(false);
    });
  });

  describe('mutation successes patch the matching candidate by id', () => {
    it('attachCandidateSucceeded replaces just that one candidate', () => {
      const seeded = reducer(initialDiscoveryInboxState, DiscoveryApiActions.pollSucceeded({ candidates: [candidate({ id: 'a' }), candidate({ id: 'b' })], sources: [] }));
      const updated = candidate({ id: 'a', status: 'REGISTERED' });
      const state = reducer(seeded, DiscoveryApiActions.attachCandidateSucceeded({ id: 'a', result: updated }));
      expect(state.candidates).toEqual([updated, candidate({ id: 'b' })]);
    });

    it('dismissSucceeded and restoreSucceeded do the same', () => {
      const seeded = reducer(initialDiscoveryInboxState, DiscoveryApiActions.pollSucceeded({ candidates: [candidate({ id: 'a' })], sources: [] }));
      const dismissed = candidate({ id: 'a', status: 'DISMISSED' });
      const afterDismiss = reducer(seeded, DiscoveryApiActions.dismissSucceeded({ id: 'a', result: dismissed }));
      expect(afterDismiss.candidates).toEqual([dismissed]);

      const restored = candidate({ id: 'a', status: 'NEW' });
      const afterRestore = reducer(afterDismiss, DiscoveryApiActions.restoreSucceeded({ id: 'a', result: restored }));
      expect(afterRestore.candidates).toEqual([restored]);
    });
  });

  describe('the discovery SSE topic — candidates only, never sources (L2b)', () => {
    function envelope(payload: DiscoveryEventPayload) {
      return LiveSocketActions.envelopeReceived({ envelope: { seq: 1, type: 'discovery', payload } });
    }

    it('folds a discovery envelope into candidates via applyDiscoveryEvents', () => {
      const state = reducer(initialDiscoveryInboxState, envelope({ action: 'REPORTED', candidate: candidate({ id: 'cand-live' }) }));
      expect(state.candidates.map((c) => c.id)).toEqual(['cand-live']);
    });

    it('never touches sources — this envelope type carries none', () => {
      const seeded = reducer(initialDiscoveryInboxState, DiscoveryApiActions.pollSucceeded({ candidates: [], sources: [source({ status: 'UNREACHABLE' })] }));
      const state = reducer(seeded, envelope({ action: 'REPORTED', candidate: candidate() }));
      expect(state.sources).toEqual([source({ status: 'UNREACHABLE' })]);
    });

    it('an envelope of any other type is a true no-op — same state reference', () => {
      const state = reducer(initialDiscoveryInboxState, LiveSocketActions.envelopeReceived({ envelope: { seq: 1, type: 'fleet', payload: [] } }));
      expect(state).toBe(initialDiscoveryInboxState);
    });

    it('runs regardless of activeConsumers — the fold has no gate of its own', () => {
      // deliberately never dispatch `activated()` — activeConsumers stays 0
      const state = reducer(initialDiscoveryInboxState, envelope({ action: 'REPORTED', candidate: candidate() }));
      expect(state.candidates).toEqual([candidate()]);
      expect(state.activeConsumers).toBe(0);
    });
  });
});
