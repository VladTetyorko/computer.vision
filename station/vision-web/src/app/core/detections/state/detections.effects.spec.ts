import { TestBed } from '@angular/core/testing';
import { provideMockActions } from '@ngrx/effects/testing';
import { Store, provideState, provideStore } from '@ngrx/store';
import type { Action } from '@ngrx/store';
import { ReplaySubject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { DetectionResult, StreamTracksResponse } from '../../api/models';
import { VisionApi } from '../../api/vision-api';
import { LiveSocketActions } from '../../live/state/live.actions';
import { liveFeature } from '../../live/state/live.reducer';
import { PollScheduler } from '../../poll-scheduler';
import { DetectionsApiActions, DetectionsPageActions } from './detections.actions';
import { feedSession$, liveResultAccumulator$, tracksSession$ } from './detections.effects';
import { detectionsFeature } from './detections.reducer';

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

/** Two separate channels — see `telemetry.effects.spec.ts#emit`'s identical doc comment. */
function emit(store: Store, actions: ReplaySubject<Action>, action: Action): void {
  store.dispatch(action);
  actions.next(action);
}

function stubScheduler() {
  const calls: { periodMs: number; callback: () => void | Promise<void>; stop: ReturnType<typeof vi.fn> }[] = [];
  const schedule = vi.fn((periodMs: number, callback: () => void | Promise<void>) => {
    const stop = vi.fn();
    calls.push({ periodMs, callback, stop });
    return stop;
  });
  const lastFor = (periodMs: number) => [...calls].reverse().find((call) => call.periodMs === periodMs);
  return { schedule, calls, lastFor };
}

function detectionResult(overrides: Partial<DetectionResult> = {}): DetectionResult {
  return { streamId: 's-1', frameSequence: 1, capturedAt: '2026-07-22T00:00:00Z', inferenceMillis: 5, detections: [], ...overrides };
}

function tracksResponse(overrides: Partial<StreamTracksResponse> = {}): StreamTracksResponse {
  return { streamId: 's-1', lockedTrackId: 0, tracks: [], objects: [], ...overrides };
}

function setup(apiOverrides: Partial<VisionApi> = {}, scheduler = stubScheduler()) {
  const actions = new ReplaySubject<Action>(1);
  const api = {
    streamDetections: vi.fn().mockResolvedValue([]),
    getStreamTracks: vi.fn().mockResolvedValue(tracksResponse()),
    ...apiOverrides,
  };
  TestBed.configureTestingModule({
    providers: [
      provideMockActions(() => actions),
      provideStore(),
      provideState(detectionsFeature),
      provideState(liveFeature),
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: scheduler },
    ],
  });
  const store = TestBed.inject(Store);
  return { actions, api, scheduler, store };
}

describe('detections effects — feedSession$', () => {
  it('polls immediately and on the configured cadence when no assetId is given', async () => {
    const results = [detectionResult()];
    const { actions, api, scheduler, store } = setup({ streamDetections: vi.fn().mockResolvedValue(results) });
    TestBed.runInInjectionContext(() => feedSession$()).subscribe((a) => store.dispatch(a));

    emit(store, actions, DetectionsPageActions.tracked({ streamId: 's-1', assetId: undefined }));
    await flush();

    expect(api.streamDetections).toHaveBeenCalledWith('s-1', 50);
    expect(store.selectSignal(detectionsFeature.selectByStreamId)()['s-1']?.pollResults).toEqual(results);
    expect(scheduler.lastFor(2_000)).toBeDefined();
  });

  it('subscribes live and never polls when the connection is already open and an assetId is given', async () => {
    const { actions, api, scheduler, store } = setup();
    const dispatchSpy = vi.spyOn(store, 'dispatch');
    store.dispatch(LiveSocketActions.opened());
    TestBed.runInInjectionContext(() => feedSession$()).subscribe((a) => store.dispatch(a));

    emit(store, actions, DetectionsPageActions.tracked({ streamId: 's-9', assetId: 'a-9' }));
    await flush();

    expect(dispatchSpy).toHaveBeenCalledWith(expect.objectContaining({ type: '[Live Page] Detections Tracked', assetId: 'a-9' }));
    expect(dispatchSpy).toHaveBeenCalledWith(expect.objectContaining({ type: '[Live Page] World Objects Tracked', assetId: 'a-9' }));
    expect(scheduler.lastFor(2_000)).toBeUndefined();
    expect(api.streamDetections).not.toHaveBeenCalled();
  });

  it('falls back to polling immediately when the connection drops mid-session', async () => {
    const { actions, api, scheduler, store } = setup();
    store.dispatch(LiveSocketActions.opened());
    TestBed.runInInjectionContext(() => feedSession$()).subscribe((a) => store.dispatch(a));

    emit(store, actions, DetectionsPageActions.tracked({ streamId: 's-13', assetId: 'a-13' }));
    await flush();
    expect(scheduler.lastFor(2_000)).toBeUndefined();

    store.dispatch(LiveSocketActions.closed());
    await flush();

    expect(scheduler.lastFor(2_000)).toBeDefined();
    expect(api.streamDetections).toHaveBeenCalledWith('s-13', 50);
  });

  it('two streams poll independently — one never cancels the other (groupBy isolation)', async () => {
    const { actions, api, scheduler, store } = setup();
    TestBed.runInInjectionContext(() => feedSession$()).subscribe((a) => store.dispatch(a));

    emit(store, actions, DetectionsPageActions.tracked({ streamId: 's-a', assetId: undefined }));
    await flush();
    emit(store, actions, DetectionsPageActions.tracked({ streamId: 's-b', assetId: undefined }));
    await flush();

    expect(api.streamDetections).toHaveBeenCalledWith('s-a', 50);
    expect(api.streamDetections).toHaveBeenCalledWith('s-b', 50);
    const polls = scheduler.calls.filter((c) => c.periodMs === 2_000);
    expect(polls).toHaveLength(2);
    expect(polls.every((c) => !c.stop.mock.calls.length)).toBe(true);
  });

  it('Reset for the tracked stream stops its poll and releases its live subscription', async () => {
    const { actions, scheduler, store } = setup();
    const dispatchSpy = vi.spyOn(store, 'dispatch');
    store.dispatch(LiveSocketActions.opened());
    TestBed.runInInjectionContext(() => feedSession$()).subscribe((a) => store.dispatch(a));

    emit(store, actions, DetectionsPageActions.tracked({ streamId: 's-14', assetId: 'a-14' }));
    await flush();

    emit(store, actions, DetectionsPageActions.reset({ streamId: 's-14' }));
    await flush();

    expect(dispatchSpy).toHaveBeenCalledWith(expect.objectContaining({ type: '[Live Page] Detections Untracked', assetId: 'a-14' }));
    expect(dispatchSpy).toHaveBeenCalledWith(expect.objectContaining({ type: '[Live Page] World Objects Untracked', assetId: 'a-14' }));
  });

  it('a poll failure dispatches Poll Failed without throwing', async () => {
    const { actions, store } = setup({ streamDetections: vi.fn().mockRejectedValue(new Error('down')) });
    const seen: Action[] = [];
    TestBed.runInInjectionContext(() => feedSession$()).subscribe((a) => {
      seen.push(a);
      store.dispatch(a);
    });

    emit(store, actions, DetectionsPageActions.tracked({ streamId: 's-1', assetId: undefined }));
    await flush();

    expect(seen.some((a) => a.type === DetectionsApiActions.pollFailed.type)).toBe(true);
  });
});

describe('detections effects — liveResultAccumulator$', () => {
  function driveAccumulator(store: Store, actions: ReplaySubject<Action>) {
    TestBed.runInInjectionContext(() => liveResultAccumulator$()).subscribe((a) => store.dispatch(a));
  }

  it('accumulates a live detections envelope onto every stream whose active feed matches the assetId', async () => {
    const { actions, store } = setup();
    store.dispatch(DetectionsPageActions.tracked({ streamId: 's-1', assetId: 'a-1' }));
    driveAccumulator(store, actions);

    const payload = detectionResult({ streamId: 's-1' });
    const envelope = { seq: 1, assetId: 'a-1', type: 'detections' as const, payload };
    actions.next(LiveSocketActions.envelopeReceived({ envelope }));
    await flush();

    expect(store.selectSignal(detectionsFeature.selectByStreamId)()['s-1']?.liveResults).toEqual([payload]);
  });

  it('ignores an envelope for an asset no active feed is tracking', async () => {
    const { actions, store } = setup();
    store.dispatch(DetectionsPageActions.tracked({ streamId: 's-1', assetId: 'a-1' }));
    driveAccumulator(store, actions);

    const envelope = { seq: 1, assetId: 'a-other', type: 'detections' as const, payload: detectionResult() };
    actions.next(LiveSocketActions.envelopeReceived({ envelope }));
    await flush();

    expect(store.selectSignal(detectionsFeature.selectByStreamId)()['s-1']?.liveResults).toEqual([]);
  });

  it('ignores a non-detections envelope', async () => {
    const { actions, store } = setup();
    store.dispatch(DetectionsPageActions.tracked({ streamId: 's-1', assetId: 'a-1' }));
    driveAccumulator(store, actions);

    const envelope = { seq: 1, assetId: 'a-1', type: 'telemetry' as const, payload: [] };
    actions.next(LiveSocketActions.envelopeReceived({ envelope }));
    await flush();

    expect(store.selectSignal(detectionsFeature.selectByStreamId)()['s-1']?.liveResults).toEqual([]);
  });
});

describe('detections effects — tracksSession$', () => {
  it('polls immediately and on the configured cadence while not live', async () => {
    const response = tracksResponse({ lockedTrackId: 7 });
    const { actions, api, scheduler, store } = setup({ getStreamTracks: vi.fn().mockResolvedValue(response) });
    TestBed.runInInjectionContext(() => tracksSession$()).subscribe((a) => store.dispatch(a));

    emit(store, actions, DetectionsPageActions.tracksTracked({ streamId: 't-1', assetId: undefined }));
    await flush();

    expect(api.getStreamTracks).toHaveBeenCalledWith('t-1');
    expect(store.selectSignal(detectionsFeature.selectByStreamId)()['t-1']?.tracksPollResponse).toEqual(response);
    expect(scheduler.lastFor(2_000)).toBeDefined();
  });

  it('never polls when the connection is open and an assetId is in scope', async () => {
    const { actions, api, store } = setup();
    store.dispatch(LiveSocketActions.opened());
    store.dispatch(DetectionsPageActions.tracked({ streamId: 't-9', assetId: 'a-9' }));
    TestBed.runInInjectionContext(() => tracksSession$()).subscribe((a) => store.dispatch(a));

    emit(store, actions, DetectionsPageActions.tracksTracked({ streamId: 't-9', assetId: 'a-9' }));
    await flush();

    expect(api.getStreamTracks).not.toHaveBeenCalled();
  });

  it('a tracks poll failure sets tracksPollResponse to null — honest degrade, unlike the feed poll', async () => {
    const { actions, store } = setup({ getStreamTracks: vi.fn().mockRejectedValue(new Error('down')) });
    TestBed.runInInjectionContext(() => tracksSession$()).subscribe((a) => store.dispatch(a));

    emit(store, actions, DetectionsPageActions.tracksTracked({ streamId: 't-1', assetId: undefined }));
    await flush();

    expect(store.selectSignal(detectionsFeature.selectByStreamId)()['t-1']?.tracksPollResponse).toBeNull();
  });

  it('Tracks Untracked stops the poll', async () => {
    const { actions, scheduler, store } = setup();
    TestBed.runInInjectionContext(() => tracksSession$()).subscribe((a) => store.dispatch(a));

    emit(store, actions, DetectionsPageActions.tracksTracked({ streamId: 't-14', assetId: undefined }));
    await flush();
    const poll = scheduler.lastFor(2_000);

    emit(store, actions, DetectionsPageActions.tracksUntracked({ streamId: 't-14' }));
    await flush();

    expect(poll?.stop).toHaveBeenCalledOnce();
  });

  it('two streams poll independently (groupBy isolation)', async () => {
    const { actions, api, scheduler, store } = setup();
    TestBed.runInInjectionContext(() => tracksSession$()).subscribe((a) => store.dispatch(a));

    emit(store, actions, DetectionsPageActions.tracksTracked({ streamId: 't-a', assetId: undefined }));
    await flush();
    emit(store, actions, DetectionsPageActions.tracksTracked({ streamId: 't-b', assetId: undefined }));
    await flush();

    expect(api.getStreamTracks).toHaveBeenCalledWith('t-a');
    expect(api.getStreamTracks).toHaveBeenCalledWith('t-b');
    const polls = scheduler.calls.filter((c) => c.periodMs === 2_000);
    expect(polls).toHaveLength(2);
  });
});
