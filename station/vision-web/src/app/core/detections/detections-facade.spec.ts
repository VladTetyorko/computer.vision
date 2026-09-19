import { TestBed } from '@angular/core/testing';
import { Store } from '@ngrx/store';
import { describe, expect, it, vi } from 'vitest';
import type { DetectionResult, StreamTracksResponse } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { LiveSocketActions } from '../live/state/live.actions';
import { PollScheduler } from '../poll-scheduler';
import { provideAppState } from '../state/app-state';
import { provideDetectionsState } from './state/detections.providers';
import { DetectionsFacade } from './detections-facade';

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
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
  return { streamId: 's-1', frameSequence: 1, capturedAt: new Date().toISOString(), inferenceMillis: 5, detections: [], ...overrides };
}

function tracksResponse(overrides: Partial<StreamTracksResponse> = {}): StreamTracksResponse {
  return { streamId: 's-1', lockedTrackId: 0, tracks: [], objects: [], ...overrides };
}

function setup(apiOverrides: Partial<VisionApi> = {}, scheduler = stubScheduler()) {
  const api = {
    streamDetections: vi.fn().mockResolvedValue([]),
    getStreamTracks: vi.fn().mockResolvedValue(tracksResponse()),
    ...apiOverrides,
  };
  TestBed.configureTestingModule({
    providers: [
      provideAppState(), provideDetectionsState(),
      DetectionsFacade,
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: scheduler },
    ],
  });
  return { facade: TestBed.inject(DetectionsFacade), api, scheduler, store: TestBed.inject(Store) };
}

describe('DetectionsFacade', () => {
  it('has no results before track() is ever called', () => {
    const { facade } = setup();
    expect(facade.results()).toEqual([]);
    expect(facade.status()).toBe('off');
    expect(facade.pausedNotice()).toBeNull();
  });

  it('polls immediately and on the configured cadence when no assetId is given', async () => {
    const result = detectionResult();
    const { facade, api, scheduler } = setup({ streamDetections: vi.fn().mockResolvedValue([result]) });

    facade.track('s-1');
    await flush();

    expect(api.streamDetections).toHaveBeenCalledWith('s-1', 50);
    expect(facade.results()).toEqual([result]);
    expect(facade.status()).toBe('on');
    expect(scheduler.lastFor(2_000)).toBeDefined();
  });

  it('derives chips from the freshest distinct labels', async () => {
    const result: DetectionResult = {
      ...detectionResult(),
      detections: [
        { label: 'person', confidence: 0.9, box: { x: 0, y: 0, width: 1, height: 1 }, modelId: 'm', modelVersion: '1' },
        { label: 'car', confidence: 0.5, box: { x: 0, y: 0, width: 1, height: 1 }, modelId: 'm', modelVersion: '1' },
      ],
    };
    const { facade } = setup({ streamDetections: vi.fn().mockResolvedValue([result]) });

    facade.track('s-1');
    await flush();

    expect(facade.chips()).toEqual([
      { label: 'person', confidence: 0.9 },
      { label: 'car', confidence: 0.5 },
    ]);
  });

  it('re-entering track() with the same (streamId, assetId) is a no-op', async () => {
    const { facade, api } = setup({ streamDetections: vi.fn().mockResolvedValue([detectionResult()]) });

    for (let i = 0; i < 5; i++) {
      facade.track('s-7', 'a-7');
    }
    await flush();

    expect(api.streamDetections).toHaveBeenCalledOnce();
  });

  it('reset() clears results and stops polling', async () => {
    const result = detectionResult();
    const { facade, scheduler } = setup({ streamDetections: vi.fn().mockResolvedValue([result]) });

    facade.track('s-4');
    await flush();
    expect(facade.results()).toEqual([result]);
    const poll = scheduler.lastFor(2_000);

    facade.reset();

    expect(facade.results()).toEqual([]);
    expect(poll?.stop).toHaveBeenCalledOnce();
  });

  it('subscribes live and accumulates arrivals when the connection is open and an assetId is given', async () => {
    const { facade, store } = setup();
    store.dispatch(LiveSocketActions.opened());

    facade.track('s-9', 'a-9');
    await flush();

    const pushed = detectionResult({ streamId: 's-9' });
    store.dispatch(LiveSocketActions.envelopeReceived({ envelope: { seq: 1, assetId: 'a-9', type: 'detections', payload: pushed } }));

    expect(facade.results()).toEqual([pushed]);
  });

  it('worldObjects reflects the live world-object snapshot for the tracked asset', async () => {
    const { facade, store } = setup();
    store.dispatch(LiveSocketActions.opened());

    facade.track('s-9', 'a-9');
    await flush();

    const object = { objectId: 'o-1' } as never;
    store.dispatch(
      LiveSocketActions.envelopeReceived({
        envelope: { seq: 1, assetId: 'a-9', type: 'tracks', payload: tracksResponse({ streamId: 's-9', objects: [object] }) },
      }),
    );

    expect(facade.worldObjects()).toEqual([object]);
  });

  it('trackTracks() polls immediately and on the configured cadence', async () => {
    const response = tracksResponse({ lockedTrackId: 7 });
    const { facade, api, scheduler } = setup({ getStreamTracks: vi.fn().mockResolvedValue(response) });

    facade.trackTracks('t-1');
    await flush();

    expect(api.getStreamTracks).toHaveBeenCalledWith('t-1');
    expect(facade.tracks()).toEqual(response);
    expect(scheduler.lastFor(2_000)).toBeDefined();
  });

  it('untrackTracks() clears tracks and stops its poll', async () => {
    const { facade, scheduler } = setup({ getStreamTracks: vi.fn().mockResolvedValue(tracksResponse()) });

    facade.trackTracks('t-2');
    await flush();
    const poll = scheduler.lastFor(2_000);

    facade.untrackTracks();

    expect(facade.tracks()).toBeNull();
    expect(poll?.stop).toHaveBeenCalledOnce();
  });

  it('followTracks(streamId, false) is equivalent to untrackTracks()', async () => {
    const { facade, scheduler } = setup({ getStreamTracks: vi.fn().mockResolvedValue(tracksResponse()) });

    facade.followTracks('t-3', true);
    await flush();
    const poll = scheduler.lastFor(2_000);

    facade.followTracks('t-3', false);

    expect(facade.tracks()).toBeNull();
    expect(poll?.stop).toHaveBeenCalledOnce();
  });

  it('the tracks lifecycle reuses the feed\'s assetId for its own transport decision', async () => {
    const { facade, store, api } = setup();
    store.dispatch(LiveSocketActions.opened());

    facade.track('s-shared', 'a-shared'); // feed establishes the assetId
    await flush();
    facade.trackTracks('s-shared'); // tracks lifecycle, same streamId — should go live, no poll
    await flush();

    expect(api.getStreamTracks).not.toHaveBeenCalled();
  });

  it('pausedNotice reports once the feed has gone stale', async () => {
    const stale = detectionResult({ capturedAt: '2020-01-01T00:00:00Z' });
    const { facade } = setup({ streamDetections: vi.fn().mockResolvedValue([stale]) });

    facade.track('s-stale');
    await flush();

    expect(facade.pausedNotice()).not.toBeNull();
  });
});
