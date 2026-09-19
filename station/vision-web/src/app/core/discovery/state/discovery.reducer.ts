import { createFeature, createReducer, on } from '@ngrx/store';
import { LiveSocketActions } from '../../live/state/live.actions';
import { applyDiscoveryEvents } from '../discovery-inbox-logic';
import { DiscoveryApiActions, DiscoveryPageActions } from './discovery.actions';
import { initialDiscoveryInboxState } from './discovery.model';

export const discoveryInboxFeature = createFeature({
  name: 'discoveryInbox',
  reducer: createReducer(
    initialDiscoveryInboxState,

    on(DiscoveryPageActions.activated, (state) => ({ ...state, activeConsumers: state.activeConsumers + 1 })),
    on(DiscoveryPageActions.released, (state) => ({
      ...state,
      activeConsumers: Math.max(0, state.activeConsumers - 1), // defensive — an unmatched release must never go negative
    })),

    on(DiscoveryPageActions.registerRequested, (state, { id }) => ({ ...state, busyId: id })),
    on(DiscoveryPageActions.attachRequested, (state, { id }) => ({ ...state, busyId: id })),
    on(DiscoveryPageActions.attachCandidateRequested, (state, { id }) => ({ ...state, busyId: id })),
    on(DiscoveryPageActions.dismissRequested, (state, { id }) => ({ ...state, busyId: id })),
    on(DiscoveryPageActions.restoreRequested, (state, { id }) => ({ ...state, busyId: id })),

    on(DiscoveryApiActions.pollStarted, (state) => ({ ...state, loading: true })),
    on(DiscoveryApiActions.pollSucceeded, (state, { candidates, sources }) => ({
      ...state,
      candidates,
      sources,
      loading: false,
    })),
    // Silent-degrade (CLAUDE.md "degrade honestly") — a failed poll/reconcile keeps the last-known
    // list rather than blanking it or fabricating one; only `loading` ever moves.
    on(DiscoveryApiActions.pollFailed, (state) => ({ ...state, loading: false })),

    // `register`/`attach`/`attachCandidate` never patch `candidates` from their own *Failed action —
    // only a *Succeeded one, mirroring `DiscoveryInboxStore`'s own `if (result) { … }` guard.
    on(DiscoveryApiActions.registerSucceeded, (state) => ({ ...state, busyId: null })),
    on(DiscoveryApiActions.registerFailed, (state) => ({ ...state, busyId: null })),
    on(DiscoveryApiActions.attachSucceeded, (state) => ({ ...state, busyId: null })),
    on(DiscoveryApiActions.attachFailed, (state) => ({ ...state, busyId: null })),
    on(DiscoveryApiActions.attachCandidateSucceeded, (state, { result }) => ({
      ...state,
      busyId: null,
      candidates: state.candidates.map((candidate) => (candidate.id === result.id ? result : candidate)),
    })),
    on(DiscoveryApiActions.attachCandidateFailed, (state) => ({ ...state, busyId: null })),
    on(DiscoveryApiActions.dismissSucceeded, (state, { result }) => ({
      ...state,
      busyId: null,
      candidates: state.candidates.map((candidate) => (candidate.id === result.id ? result : candidate)),
    })),
    on(DiscoveryApiActions.dismissFailed, (state) => ({ ...state, busyId: null })),
    on(DiscoveryApiActions.restoreSucceeded, (state, { result }) => ({
      ...state,
      busyId: null,
      candidates: state.candidates.map((candidate) => (candidate.id === result.id ? result : candidate)),
    })),
    on(DiscoveryApiActions.restoreFailed, (state) => ({ ...state, busyId: null })),

    // The `discovery` SSE topic's own candidate-only fold (never `sources` — see `discovery-inbox-
    // store.ts`'s own `POLL_INTERVAL_MS` doc comment for the L2b trap this exists to cover) — runs
    // unconditionally, for every envelope, regardless of `activeConsumers`; exactly one
    // `Envelope Received` action per SSE payload (`live.effects.ts`'s own dispatch site) means no
    // cursor bookkeeping is needed here, unlike the original class's `processedLiveEventCount` — this
    // reducer simply reacts to the one action every other slice's own envelope fold already reacts to.
    on(LiveSocketActions.envelopeReceived, (state, { envelope }) => {
      if (envelope.type !== 'discovery') {
        return state;
      }
      return { ...state, candidates: applyDiscoveryEvents(state.candidates, [envelope.payload]) };
    }),
  ),
});
