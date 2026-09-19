import { describe, expect, it } from 'vitest';
import type { CvTrace, FrameLedger, GateDecision, WorldObject } from '../../api/models';
import { CvTraceApiActions, CvTracePageActions } from './cv-trace.actions';
import { initialCvTraceState } from './cv-trace.model';
import { cvTraceFeature } from './cv-trace.reducer';

const { reducer } = cvTraceFeature;

function gateDecision(frameSequence: number): GateDecision {
  return {
    frameSequence,
    atMillis: 1_700_000_000_000,
    outcome: 'SENT',
    demand: { detectionEnabled: true, viewerDemand: true, policyAlwaysOn: false },
  };
}

function frameLedger(sequence: number): FrameLedger {
  return {
    streamId: 'stream-1',
    sequence,
    capturedAtMillis: 1_700_000_000_000 + sequence,
    levelServed: 2,
    detectorReason: 'FULL',
    eligible: [],
    entries: [],
    objects: {},
    dropsSinceLast: 0,
    gateWaitMillis: 0,
    totalMillis: 0,
    halted: false,
    detections: [],
    frameWidth: 0,
    frameHeight: 0,
  };
}

function worldObject(id: number): WorldObject {
  return {
    state: { id, lifecycle: 'CONFIRMED', streamId: 'stream-1' },
    operator: { followed: false, denied: false },
    event: {},
    render: { tier: 'HIDDEN' },
  };
}

function trace(overrides: Partial<CvTrace> = {}): CvTrace {
  return { streamId: 'stream-1', gate: [], frame: [], world: [], ...overrides };
}

describe('cv-trace reducer', () => {
  it('Tracked seeds a fresh, empty entry keyed by streamId, storing the given assetId and cap', () => {
    const state = reducer(initialCvTraceState, CvTracePageActions.tracked({ streamId: 's-1', assetId: 'a-1', last: 5 }));
    expect(state.byStreamId['s-1']).toEqual({ assetId: 'a-1', cap: 5, gate: [], frame: [], world: [] });
  });

  it('a re-Tracked key drops its previous session data rather than keeping stale ledgers', () => {
    let state = reducer(initialCvTraceState, CvTracePageActions.tracked({ streamId: 's-1', assetId: 'a-1', last: 50 }));
    state = reducer(state, CvTraceApiActions.pollReceived({ streamId: 's-1', trace: trace({ gate: [gateDecision(1)] }) }));
    state = reducer(state, CvTracePageActions.tracked({ streamId: 's-1', assetId: 'a-2', last: 10 }));
    expect(state.byStreamId['s-1']).toEqual({ assetId: 'a-2', cap: 10, gate: [], frame: [], world: [] });
  });

  it('Reset removes the entry entirely', () => {
    let state = reducer(initialCvTraceState, CvTracePageActions.tracked({ streamId: 's-1', assetId: 'a-1', last: 50 }));
    state = reducer(state, CvTracePageActions.reset({ streamId: 's-1' }));
    expect(state.byStreamId['s-1']).toBeUndefined();
  });

  it('Poll Received replaces gate/frame/world outright — the authoritative resync', () => {
    let state = reducer(initialCvTraceState, CvTracePageActions.tracked({ streamId: 's-1', assetId: undefined, last: 50 }));
    const seeded = trace({ gate: [gateDecision(1)], frame: [frameLedger(1)], world: [worldObject(7)] });
    state = reducer(state, CvTraceApiActions.pollReceived({ streamId: 's-1', trace: seeded }));
    expect(state.byStreamId['s-1']).toEqual({ assetId: undefined, cap: 50, gate: seeded.gate, frame: seeded.frame, world: seeded.world });
  });

  it('Poll Received for a since-reset stream is a no-op (superseded async lookup)', () => {
    const state = reducer(initialCvTraceState, CvTraceApiActions.pollReceived({ streamId: 's-1', trace: trace() }));
    expect(state.byStreamId['s-1']).toBeUndefined();
  });

  it('Poll Failed is a no-op — the last-known-good ledgers survive unchanged', () => {
    let state = reducer(initialCvTraceState, CvTracePageActions.tracked({ streamId: 's-1', assetId: undefined, last: 50 }));
    state = reducer(state, CvTraceApiActions.pollReceived({ streamId: 's-1', trace: trace({ gate: [gateDecision(1)] }) }));
    const before = state.byStreamId['s-1'];
    state = reducer(state, CvTraceApiActions.pollFailed({ streamId: 's-1' }));
    expect(state.byStreamId['s-1']).toEqual(before);
  });

  it('Live Frame Received merges into the ring via appendFrameLedger, capped at this entry\'s own cap', () => {
    let state = reducer(initialCvTraceState, CvTracePageActions.tracked({ streamId: 's-1', assetId: 'a-1', last: 2 }));
    state = reducer(state, CvTraceApiActions.liveFrameReceived({ streamId: 's-1', frame: frameLedger(1) }));
    state = reducer(state, CvTraceApiActions.liveFrameReceived({ streamId: 's-1', frame: frameLedger(2) }));
    expect(state.byStreamId['s-1']?.frame.map((f) => f.sequence)).toEqual([1, 2]);

    // A third arrival pushes the ring past its cap of 2 — the oldest frame drops, never the newest.
    state = reducer(state, CvTraceApiActions.liveFrameReceived({ streamId: 's-1', frame: frameLedger(3) }));
    expect(state.byStreamId['s-1']?.frame.map((f) => f.sequence)).toEqual([2, 3]);
  });

  it("the next poll's frame list authoritatively replaces the ring, never merges with it", () => {
    let state = reducer(initialCvTraceState, CvTracePageActions.tracked({ streamId: 's-1', assetId: 'a-1', last: 50 }));
    state = reducer(state, CvTraceApiActions.liveFrameReceived({ streamId: 's-1', frame: frameLedger(99) }));
    expect(state.byStreamId['s-1']?.frame.map((f) => f.sequence)).toEqual([99]);

    state = reducer(state, CvTraceApiActions.pollReceived({ streamId: 's-1', trace: trace({ frame: [frameLedger(1), frameLedger(2)] }) }));
    expect(state.byStreamId['s-1']?.frame.map((f) => f.sequence)).toEqual([1, 2]);
  });

  it('Live Frame Received for an untracked stream is a no-op', () => {
    const state = reducer(initialCvTraceState, CvTraceApiActions.liveFrameReceived({ streamId: 's-1', frame: frameLedger(1) }));
    expect(state.byStreamId['s-1']).toBeUndefined();
  });

  it('two streams are stored independently — one never disturbs the other', () => {
    let state = reducer(initialCvTraceState, CvTracePageActions.tracked({ streamId: 's-1', assetId: 'a-1', last: 50 }));
    state = reducer(state, CvTracePageActions.tracked({ streamId: 's-2', assetId: 'a-2', last: 50 }));
    state = reducer(state, CvTracePageActions.reset({ streamId: 's-1' }));
    expect(state.byStreamId['s-1']).toBeUndefined();
    expect(state.byStreamId['s-2']?.assetId).toBe('a-2');
  });
});
