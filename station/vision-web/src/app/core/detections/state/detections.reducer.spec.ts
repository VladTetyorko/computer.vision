import { describe, expect, it } from 'vitest';
import type { DetectionResult, StreamTracksResponse } from '../../api/models';
import { DetectionsApiActions, DetectionsPageActions } from './detections.actions';
import { initialDetectionsState } from './detections.model';
import { detectionsFeature, feedTransportSelectorFor, tracksTransportSelectorFor } from './detections.reducer';

const { reducer } = detectionsFeature;

function result(overrides: Partial<DetectionResult> = {}): DetectionResult {
  return {
    streamId: 's-1',
    frameSequence: 1,
    capturedAt: '2026-07-22T00:00:00Z',
    inferenceMillis: 5,
    detections: [],
    ...overrides,
  };
}

function tracksResponse(overrides: Partial<StreamTracksResponse> = {}): StreamTracksResponse {
  return { streamId: 's-1', lockedTrackId: 0, tracks: [], objects: [], ...overrides };
}

describe('detections reducer — feed (track()/reset())', () => {
  it('Tracked seeds a fresh entry keyed by streamId, storing the given assetId', () => {
    const state = reducer(initialDetectionsState, DetectionsPageActions.tracked({ streamId: 's-1', assetId: 'a-1' }));
    expect(state.byStreamId['s-1']).toEqual({
      feedActive: true,
      assetId: 'a-1',
      pollResults: [],
      liveResults: [],
      tracksWanted: false,
      tracksPollResponse: null,
    });
  });

  it('a re-Tracked key drops its previous session data rather than keeping stale results', () => {
    let state = reducer(initialDetectionsState, DetectionsPageActions.tracked({ streamId: 's-1', assetId: 'a-1' }));
    state = reducer(state, DetectionsApiActions.pollReceived({ streamId: 's-1', results: [result()] }));
    state = reducer(state, DetectionsPageActions.tracked({ streamId: 's-1', assetId: 'a-2' }));
    expect(state.byStreamId['s-1']).toEqual({
      feedActive: true,
      assetId: 'a-2',
      pollResults: [],
      liveResults: [],
      tracksWanted: false,
      tracksPollResponse: null,
    });
  });

  it('Reset clears the feed fields and prunes the entry when no tracks session wants it', () => {
    let state = reducer(initialDetectionsState, DetectionsPageActions.tracked({ streamId: 's-1', assetId: 'a-1' }));
    state = reducer(state, DetectionsPageActions.reset({ streamId: 's-1' }));
    expect(state.byStreamId['s-1']).toBeUndefined();
  });

  it('Reset leaves a still-wanted tracks session\'s entry in place, clearing only feed fields', () => {
    let state = reducer(initialDetectionsState, DetectionsPageActions.tracked({ streamId: 's-1', assetId: 'a-1' }));
    state = reducer(state, DetectionsPageActions.tracksTracked({ streamId: 's-1', assetId: 'a-1' }));
    state = reducer(state, DetectionsApiActions.pollReceived({ streamId: 's-1', results: [result()] }));
    state = reducer(state, DetectionsPageActions.reset({ streamId: 's-1' }));
    expect(state.byStreamId['s-1']).toEqual({
      feedActive: false,
      assetId: undefined,
      pollResults: [],
      liveResults: [],
      tracksWanted: true,
      tracksPollResponse: null,
    });
  });

  it('Poll Received replaces pollResults only', () => {
    let state = reducer(initialDetectionsState, DetectionsPageActions.tracked({ streamId: 's-1', assetId: 'a-1' }));
    const polled = result({ capturedAt: '2026-07-22T00:00:05Z' });
    state = reducer(state, DetectionsApiActions.pollReceived({ streamId: 's-1', results: [polled] }));
    expect(state.byStreamId['s-1']?.pollResults).toEqual([polled]);
    expect(state.byStreamId['s-1']?.liveResults).toEqual([]);
  });

  it('Poll Failed is a no-op — the last-known-good pollResults survive unchanged', () => {
    let state = reducer(initialDetectionsState, DetectionsPageActions.tracked({ streamId: 's-1', assetId: 'a-1' }));
    state = reducer(state, DetectionsApiActions.pollReceived({ streamId: 's-1', results: [result()] }));
    const before = state.byStreamId['s-1'];
    state = reducer(state, DetectionsApiActions.pollFailed({ streamId: 's-1' }));
    expect(state.byStreamId['s-1']).toEqual(before);
  });

  it('Live Result Received prepends, caps at DETECTIONS_LIMIT, and dedupes the same envelope by reference', () => {
    let state = reducer(initialDetectionsState, DetectionsPageActions.tracked({ streamId: 's-1', assetId: 'a-1' }));
    const first = result({ capturedAt: '2026-07-22T00:00:01Z' });
    state = reducer(state, DetectionsApiActions.liveResultReceived({ streamId: 's-1', result: first }));
    state = reducer(state, DetectionsApiActions.liveResultReceived({ streamId: 's-1', result: first }));
    expect(state.byStreamId['s-1']?.liveResults).toEqual([first]);

    const second = result({ capturedAt: '2026-07-22T00:00:02Z' });
    state = reducer(state, DetectionsApiActions.liveResultReceived({ streamId: 's-1', result: second }));
    expect(state.byStreamId['s-1']?.liveResults).toEqual([second, first]);
  });

  it('Live Result Received for an untracked streamId is a no-op', () => {
    const state = reducer(initialDetectionsState, DetectionsApiActions.liveResultReceived({ streamId: 's-1', result: result() }));
    expect(state.byStreamId['s-1']).toBeUndefined();
  });

  it('two streams are stored independently — one never disturbs the other', () => {
    let state = reducer(initialDetectionsState, DetectionsPageActions.tracked({ streamId: 's-1', assetId: 'a-1' }));
    state = reducer(state, DetectionsPageActions.tracked({ streamId: 's-2', assetId: 'a-2' }));
    state = reducer(state, DetectionsPageActions.reset({ streamId: 's-1' }));
    expect(state.byStreamId['s-1']).toBeUndefined();
    expect(state.byStreamId['s-2']?.assetId).toBe('a-2');
  });
});

describe('detections reducer — Transport Entered (seed bridge)', () => {
  it('flipping to live seeds liveResults from pollResults when live is still empty', () => {
    let state = reducer(initialDetectionsState, DetectionsPageActions.tracked({ streamId: 's-1', assetId: 'a-1' }));
    state = reducer(state, DetectionsApiActions.pollReceived({ streamId: 's-1', results: [result()] }));
    state = reducer(state, DetectionsApiActions.transportEntered({ streamId: 's-1', transport: 'live' }));
    expect(state.byStreamId['s-1']?.liveResults).toEqual([result()]);
  });

  it('flipping to poll seeds pollResults from liveResults when poll is still empty', () => {
    let state = reducer(initialDetectionsState, DetectionsPageActions.tracked({ streamId: 's-1', assetId: 'a-1' }));
    state = reducer(state, DetectionsApiActions.liveResultReceived({ streamId: 's-1', result: result() }));
    state = reducer(state, DetectionsApiActions.transportEntered({ streamId: 's-1', transport: 'poll' }));
    expect(state.byStreamId['s-1']?.pollResults).toEqual([result()]);
  });

  it('never overwrites an already-populated destination signal', () => {
    let state = reducer(initialDetectionsState, DetectionsPageActions.tracked({ streamId: 's-1', assetId: 'a-1' }));
    const polled = result({ capturedAt: '2026-07-22T00:00:01Z' });
    const lived = result({ capturedAt: '2026-07-22T00:00:02Z' });
    state = reducer(state, DetectionsApiActions.pollReceived({ streamId: 's-1', results: [polled] }));
    state = reducer(state, DetectionsApiActions.liveResultReceived({ streamId: 's-1', result: lived }));
    state = reducer(state, DetectionsApiActions.transportEntered({ streamId: 's-1', transport: 'live' }));
    expect(state.byStreamId['s-1']?.liveResults).toEqual([lived]);
  });

  it('is a no-op for an untracked streamId', () => {
    const state = reducer(initialDetectionsState, DetectionsApiActions.transportEntered({ streamId: 's-1', transport: 'live' }));
    expect(state.byStreamId['s-1']).toBeUndefined();
  });
});

describe('detections reducer — tracks (trackTracks()/untrackTracks())', () => {
  it('Tracks Tracked seeds a fresh entry when the feed has never tracked this stream', () => {
    const state = reducer(initialDetectionsState, DetectionsPageActions.tracksTracked({ streamId: 's-1', assetId: 'a-1' }));
    expect(state.byStreamId['s-1']).toEqual({
      feedActive: false,
      assetId: 'a-1',
      pollResults: [],
      liveResults: [],
      tracksWanted: true,
      tracksPollResponse: null,
    });
  });

  it('Tracks Tracked on an existing feed entry preserves the feed fields, including its own assetId', () => {
    let state = reducer(initialDetectionsState, DetectionsPageActions.tracked({ streamId: 's-1', assetId: 'a-1' }));
    state = reducer(state, DetectionsApiActions.pollReceived({ streamId: 's-1', results: [result()] }));
    state = reducer(state, DetectionsPageActions.tracksTracked({ streamId: 's-1', assetId: 'a-999' }));
    expect(state.byStreamId['s-1']).toEqual({
      feedActive: true,
      assetId: 'a-1', // the feed's own assetId survives — the action's assetId is ignored for an existing entry
      pollResults: [result()],
      liveResults: [],
      tracksWanted: true,
      tracksPollResponse: null,
    });
  });

  it('Tracks Untracked clears tracks fields only and prunes when the feed is also inactive', () => {
    let state = reducer(initialDetectionsState, DetectionsPageActions.tracksTracked({ streamId: 's-1', assetId: 'a-1' }));
    state = reducer(state, DetectionsApiActions.tracksPollReceived({ streamId: 's-1', response: tracksResponse() }));
    state = reducer(state, DetectionsPageActions.tracksUntracked({ streamId: 's-1' }));
    expect(state.byStreamId['s-1']).toBeUndefined();
  });

  it('Tracks Untracked with an active feed leaves the feed fields in place', () => {
    let state = reducer(initialDetectionsState, DetectionsPageActions.tracked({ streamId: 's-1', assetId: 'a-1' }));
    state = reducer(state, DetectionsPageActions.tracksTracked({ streamId: 's-1', assetId: 'a-1' }));
    state = reducer(state, DetectionsPageActions.tracksUntracked({ streamId: 's-1' }));
    expect(state.byStreamId['s-1']).toEqual({
      feedActive: true,
      assetId: 'a-1',
      pollResults: [],
      liveResults: [],
      tracksWanted: false,
      tracksPollResponse: null,
    });
  });

  it('Tracks Poll Received sets tracksPollResponse', () => {
    let state = reducer(initialDetectionsState, DetectionsPageActions.tracksTracked({ streamId: 's-1', assetId: 'a-1' }));
    const response = tracksResponse();
    state = reducer(state, DetectionsApiActions.tracksPollReceived({ streamId: 's-1', response }));
    expect(state.byStreamId['s-1']?.tracksPollResponse).toBe(response);
  });

  it('Tracks Poll Failed hides rather than keeping a stale response — unlike the feed\'s Poll Failed', () => {
    let state = reducer(initialDetectionsState, DetectionsPageActions.tracksTracked({ streamId: 's-1', assetId: 'a-1' }));
    state = reducer(state, DetectionsApiActions.tracksPollReceived({ streamId: 's-1', response: tracksResponse() }));
    state = reducer(state, DetectionsApiActions.tracksPollFailed({ streamId: 's-1' }));
    expect(state.byStreamId['s-1']?.tracksPollResponse).toBeNull();
  });
});

describe('feedTransportSelectorFor', () => {
  it('resolves "poll" when the stream has no assetId, regardless of connection state', () => {
    const state = reducer(initialDetectionsState, DetectionsPageActions.tracked({ streamId: 's-1', assetId: undefined }));
    const projector = feedTransportSelectorFor('s-1').projector;
    expect(projector(state.byStreamId, 'open')).toBe('poll');
  });

  it('resolves "live" only when the connection is open and the stream has an assetId', () => {
    const state = reducer(initialDetectionsState, DetectionsPageActions.tracked({ streamId: 's-1', assetId: 'a-1' }));
    const projector = feedTransportSelectorFor('s-1').projector;
    expect(projector(state.byStreamId, 'open')).toBe('live');
    expect(projector(state.byStreamId, 'connecting')).toBe('poll');
  });

  it('resolves "poll" for a stream with no entry at all', () => {
    const projector = feedTransportSelectorFor('unknown').projector;
    expect(projector(initialDetectionsState.byStreamId, 'open')).toBe('poll');
  });
});

describe('tracksTransportSelectorFor', () => {
  it('reuses the shared entry\'s assetId — the same one the feed lifecycle wrote', () => {
    let state = reducer(initialDetectionsState, DetectionsPageActions.tracked({ streamId: 's-1', assetId: 'a-1' }));
    state = reducer(state, DetectionsPageActions.tracksTracked({ streamId: 's-1', assetId: 'a-1' }));
    const projector = tracksTransportSelectorFor('s-1').projector;
    expect(projector(state.byStreamId, 'open')).toBe('live');
    expect(projector(state.byStreamId, 'connecting')).toBe('poll');
  });
});
