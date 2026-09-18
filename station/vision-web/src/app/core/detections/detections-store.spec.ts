import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import { DetectionsStore } from './detections-store';
import { VisionApi } from '../api/vision-api';
import { LiveFacade, type LiveConnectionState } from '../live/live-facade';
import { PollScheduler } from '../poll-scheduler';
import type { DetectionResult, StreamTracksResponse, WorldObject } from '../api/models';
import { CV_STATUS_FRESH_SECONDS } from './detections-logic';

/** Lets the fire-and-forget promise chain inside `track()` settle before asserting. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function stubApi(
  streamDetections: ReturnType<typeof vi.fn> = vi.fn().mockResolvedValue([]),
  getStreamTracks: ReturnType<typeof vi.fn> = vi.fn().mockResolvedValue({ streamId: '', lockedTrackId: 0, tracks: [] }),
) {
  return { streamDetections, getStreamTracks };
}

/**
 * A minimal `LiveFacade` test double (docs/plans/done/REALTIME-PLAN.md §4, Phase R-c) — mirrors
 * `telemetry-store.spec.ts`'s own stub exactly (see that file's doc comment for the full
 * reasoning); defaults to `'closed'`, matching the real class under jsdom, so every pre-existing
 * test above keeps exercising the poll-only path unmodified.
 */
function stubLiveFacade(initialState: LiveConnectionState = 'closed') {
  const stateSignal = signal<LiveConnectionState>(initialState);
  const perAsset = new Map<string, ReturnType<typeof signal<DetectionResult | undefined>>>();
  const signalFor = (assetId: string) => {
    let existing = perAsset.get(assetId);
    if (existing === undefined) {
      existing = signal<DetectionResult | undefined>(undefined);
      perAsset.set(assetId, existing);
    }
    return existing;
  };
  const perAssetWorldObjects = new Map<string, ReturnType<typeof signal<readonly WorldObject[]>>>();
  const worldObjectSignalFor = (assetId: string) => {
    let existing = perAssetWorldObjects.get(assetId);
    if (existing === undefined) {
      existing = signal<readonly WorldObject[]>([]);
      perAssetWorldObjects.set(assetId, existing);
    }
    return existing;
  };
  // Deliberately its own independent map, not derived from `worldObjectSignalFor` above — this is a
  // fake, and `DetectionsStore.worldObjects`/`.tracks` each only ever call their own one accessor
  // (`worldObjectsFor`/`tracksFor` respectively), so the two never need to agree here the way the
  // real `LiveFacade` now makes them (wave W9, decision E25 — `worldObjectsFor` derives from
  // `tracksFor`'s own `.objects` field there; see that class's own doc comment).
  const perAssetTracks = new Map<string, ReturnType<typeof signal<StreamTracksResponse | null>>>();
  const tracksSignalFor = (assetId: string) => {
    let existing = perAssetTracks.get(assetId);
    if (existing === undefined) {
      existing = signal<StreamTracksResponse | null>(null);
      perAssetTracks.set(assetId, existing);
    }
    return existing;
  };
  return {
    connectionState: stateSignal.asReadonly(),
    detectionsFor: vi.fn((assetId: string) => signalFor(assetId)),
    trackDetections: vi.fn(),
    untrackDetections: vi.fn(),
    worldObjectsFor: vi.fn((assetId: string) => worldObjectSignalFor(assetId)),
    trackWorldObjects: vi.fn(),
    untrackWorldObjects: vi.fn(),
    tracksFor: vi.fn((assetId: string) => tracksSignalFor(assetId)),
    setState: (state: LiveConnectionState) => stateSignal.set(state),
    pushResult: (assetId: string, result: DetectionResult) => signalFor(assetId).set(result),
    pushWorldObjects: (assetId: string, objects: readonly WorldObject[]) => worldObjectSignalFor(assetId).set(objects),
    pushTracks: (assetId: string, response: StreamTracksResponse) => tracksSignalFor(assetId).set(response),
  };
}

/** Captures every `PollScheduler.schedule` registration so a test can assert on/off without real timers. */
function stubScheduler() {
  const calls: { periodMs: number; stop: ReturnType<typeof vi.fn> }[] = [];
  const schedule = vi.fn((periodMs: number) => {
    const stop = vi.fn();
    calls.push({ periodMs, stop });
    return stop;
  });
  const lastFor = (periodMs: number) => [...calls].reverse().find((call) => call.periodMs === periodMs);
  return { schedule, calls, lastFor };
}

function inject(
  api: ReturnType<typeof stubApi>,
  options: { live?: ReturnType<typeof stubLiveFacade>; scheduler?: ReturnType<typeof stubScheduler> } = {},
): DetectionsStore {
  const providers: unknown[] = [DetectionsStore, { provide: VisionApi, useValue: api }];
  providers.push({ provide: LiveFacade, useValue: options.live ?? stubLiveFacade() });
  if (options.scheduler) {
    providers.push({ provide: PollScheduler, useValue: options.scheduler });
  }
  TestBed.configureTestingModule({ providers });
  return TestBed.inject(DetectionsStore);
}

describe('DetectionsStore', () => {
  it('polls the stream and derives chips/status from the results', async () => {
    const result: DetectionResult = {
      streamId: 's-1',
      frameSequence: 3,
      capturedAt: new Date().toISOString(),
      inferenceMillis: 5,
      detections: [
        { label: 'person', confidence: 0.87, box: { x: 0, y: 0, width: 0.1, height: 0.1 }, modelId: 'yolo', modelVersion: 'latest' },
      ],
    };
    const api = stubApi(vi.fn().mockResolvedValue([result]));

    const store = inject(api);
    store.track('s-1');
    await flush();

    expect(api.streamDetections).toHaveBeenCalledWith('s-1', 50);
    expect(store.results()).toEqual([result]);
    expect(store.chips()).toEqual([{ label: 'person', confidence: 0.87 }]);
    expect(store.status()).toBe('on');

    store.reset();
  });

  it('silently degrades when the poll fails, rather than throwing', async () => {
    const api = stubApi(vi.fn().mockRejectedValue(new Error('network down')));

    const store = inject(api);
    store.track('s-2');
    await flush();

    expect(store.results()).toEqual([]);
    expect(store.chips()).toEqual([]);
    expect(store.status()).toBe('off');
  });

  it('ages a stale poll result out of results(), even though the server returned it', async () => {
    // Reproduces the reported defect for the poll transport: the server can (today) keep serving
    // an old detection — or a future fix could stop clearing it — and the client must not draw it
    // regardless. `capturedAt` is already outside the freshness window by the time this arrives.
    const stale: DetectionResult = {
      streamId: 's-poll-stale',
      frameSequence: 1,
      capturedAt: new Date(Date.now() - (CV_STATUS_FRESH_SECONDS * 1000 + 1_000)).toISOString(),
      inferenceMillis: 5,
      detections: [
        { label: 'person', confidence: 0.9, box: { x: 0, y: 0, width: 0.1, height: 0.1 }, modelId: 'yolo', modelVersion: 'latest' },
      ],
    };
    const api = stubApi(vi.fn().mockResolvedValue([stale]));

    const store = inject(api);
    store.track('s-poll-stale');
    await flush();

    expect(store.results()).toEqual([]);
    expect(store.chips()).toEqual([]);
    expect(store.status()).toBe('off');
    // pausedNotice reads the *raw* last-seen capturedAt (docs/plans/active/CV-FLY-INTERACTION-
    // RESEARCH.md §3.4, D7) — unlike results()/chips()/status() above, it still has something to
    // report even once the entry has aged out of the freshness-filtered list entirely.
    expect(store.pausedNotice()).toMatch(/^Detections paused — last seen \d+s ago$/);

    store.reset();
  });

  it('reset() clears results and stops polling', async () => {
    const result: DetectionResult = {
      streamId: 's-3',
      frameSequence: 1,
      capturedAt: new Date().toISOString(),
      inferenceMillis: 5,
      detections: [
        { label: 'car', confidence: 0.7, box: { x: 0, y: 0, width: 0.1, height: 0.1 }, modelId: 'yolo', modelVersion: 'latest' },
      ],
    };
    const api = stubApi(vi.fn().mockResolvedValue([result]));

    const store = inject(api);
    store.track('s-3');
    await flush();
    expect(store.results()).toEqual([result]);

    store.reset();
    expect(store.results()).toEqual([]);
    expect(store.chips()).toEqual([]);
    expect(store.status()).toBe('off');
  });

  it('a stale in-flight poll from a superseded track() never overwrites the newer stream', async () => {
    let resolveFirst!: (value: DetectionResult[]) => void;
    const firstCall = new Promise<DetectionResult[]>((resolve) => {
      resolveFirst = resolve;
    });
    const secondResult: DetectionResult = {
      streamId: 's-5',
      frameSequence: 1,
      capturedAt: new Date().toISOString(),
      inferenceMillis: 5,
      detections: [],
    };
    const streamDetections = vi
      .fn()
      .mockImplementationOnce(() => firstCall)
      .mockResolvedValueOnce([secondResult]);
    const api = stubApi(streamDetections);

    const store = inject(api);
    store.track('s-4'); // in flight, not yet resolved
    store.track('s-5'); // supersedes it before the first poll settles
    await flush();
    resolveFirst([
      {
        streamId: 's-4',
        frameSequence: 9,
        capturedAt: new Date().toISOString(),
        inferenceMillis: 5,
        detections: [],
      },
    ]);
    await flush();

    expect(store.results()).toEqual([secondResult]);
    store.reset();
  });

  // --- LiveFacade projection (docs/plans/done/REALTIME-PLAN.md §4, Phase R-c) ----------------------------

  function detectionResult(streamId: string, frameSequence: number): DetectionResult {
    return { streamId, frameSequence, capturedAt: new Date().toISOString(), inferenceMillis: 5, detections: [] };
  }

  it('subscribes live when LiveFacade is open and an assetId is given, accumulating latest-frame results', () => {
    const api = stubApi();
    const live = stubLiveFacade('open');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('s-9', 'a-9');

    expect(live.trackDetections).toHaveBeenCalledExactlyOnceWith('a-9');
    expect(scheduler.lastFor(2_000)).toBeUndefined(); // no poll registered while live

    const first = detectionResult('s-9', 1);
    live.pushResult('a-9', first);
    TestBed.tick(); // flushes the live-accumulator effect
    expect(store.results()).toEqual([first]);

    const second = detectionResult('s-9', 2);
    live.pushResult('a-9', second);
    TestBed.tick();
    expect(store.results()).toEqual([second, first]); // newest first, accumulated — not replaced
    store.reset();
  });

  it('ages a stale live-accumulated result out of results() — the fix for boxes lingering after detection stops', () => {
    // The defect this task fixes: `liveResultsSignal` only ever grows via the accumulator effect
    // (see class doc) — nothing removes an entry once envelopes stop arriving. Detection switching
    // off, no viewers, or cv-service crashing all look the same here: no *new* envelope arrives, so
    // the last one just sits there aging. `results()` must stop surfacing it anyway.
    const api = stubApi();
    const live = stubLiveFacade('open');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('s-live-stale', 'a-live-stale');

    const stale: DetectionResult = {
      ...detectionResult('s-live-stale', 1),
      capturedAt: new Date(Date.now() - (CV_STATUS_FRESH_SECONDS * 1000 + 1_000)).toISOString(),
    };
    live.pushResult('a-live-stale', stale);
    TestBed.tick(); // flushes the live-accumulator effect

    expect(store.results()).toEqual([]); // still sitting in liveResultsSignal, but no longer fresh
    expect(store.chips()).toEqual([]);
    expect(store.status()).toBe('off');
    store.reset();
  });

  it('never subscribes live without an assetId, even when LiveFacade is open — always polls', async () => {
    const api = stubApi();
    const live = stubLiveFacade('open');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('s-10'); // no assetId
    await flush();

    expect(live.trackDetections).not.toHaveBeenCalled();
    expect(scheduler.lastFor(2_000)).toBeDefined();
    store.reset();
  });

  it('falls back to polling while LiveFacade is not open, even with an assetId — still subscribes for later', async () => {
    const api = stubApi();
    const live = stubLiveFacade('connecting');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('s-11', 'a-11');
    await flush();

    expect(live.trackDetections).toHaveBeenCalledExactlyOnceWith('a-11'); // subscribed regardless of transport
    expect(scheduler.lastFor(2_000)).toBeDefined();
    store.reset();
  });

  it('switches from poll to live, stopping the poll, when LiveFacade opens mid-session', async () => {
    const api = stubApi();
    const live = stubLiveFacade('connecting');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('s-12', 'a-12');
    await flush();
    const poll = scheduler.lastFor(2_000);
    expect(poll?.stop).not.toHaveBeenCalled();

    live.setState('open');
    TestBed.tick(); // flushes the transport-switch effect

    expect(poll?.stop).toHaveBeenCalledOnce();
    store.reset();
  });

  it('falls back to polling again immediately when LiveFacade drops mid-session, keeping the last-visible result', async () => {
    const streamDetections = vi.fn().mockResolvedValue([]);
    const api = stubApi(streamDetections);
    const live = stubLiveFacade('open');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('s-13', 'a-13');
    const liveResult = detectionResult('s-13', 1);
    live.pushResult('a-13', liveResult);
    TestBed.tick(); // flushes the live-accumulator effect
    expect(store.results()).toEqual([liveResult]);

    live.setState('connecting');
    TestBed.tick();

    expect(scheduler.lastFor(2_000)).toBeDefined(); // now polling
    expect(store.results()).toEqual([liveResult]); // seeded from live — no blank beat
    expect(streamDetections).toHaveBeenCalledOnce(); // fetched fresh once, immediately
    store.reset();
  });

  it('reset() releases the live subscription', () => {
    const api = stubApi();
    const live = stubLiveFacade('open');

    const store = inject(api, { live });
    store.track('s-14', 'a-14');
    expect(live.trackDetections).toHaveBeenCalledExactlyOnceWith('a-14');

    store.reset();
    expect(live.untrackDetections).toHaveBeenCalledExactlyOnceWith('a-14');
  });

  it('N successive track() calls with the same (streamId, assetId) — even with no caller-side guard — subscribe live exactly once and never untrack', () => {
    // Mirrors `TelemetryStore`'s identical churn-simulation test (docs/plans/done/REALTIME-PLAN.md §4 Phase
    // R-c follow-up) — this store's own `track()` has no `await` at all, so an unguarded caller
    // re-entering with the same id risked an even *tighter* same-tick re-notify loop.
    const api = stubApi();
    const live = stubLiveFacade('open');

    const store = inject(api, { live });
    for (let i = 0; i < 5; i++) {
      store.track('s-17', 'a-17'); // a fresh call each time — mirrors a poll-refreshed `stream()` object
    }

    expect(live.trackDetections).toHaveBeenCalledExactlyOnceWith('a-17');
    expect(live.untrackDetections).not.toHaveBeenCalled();

    store.reset();
    expect(live.untrackDetections).toHaveBeenCalledExactlyOnceWith('a-17'); // reset() still releases it
  });

  it('re-tracking a different assetId releases the old live subscription and subscribes to the new one', () => {
    const api = stubApi();
    const live = stubLiveFacade('open');

    const store = inject(api, { live });
    store.track('s-15', 'a-15');
    store.track('s-16', 'a-16');

    expect(live.untrackDetections).toHaveBeenCalledExactlyOnceWith('a-15');
    expect(live.trackDetections).toHaveBeenCalledWith('a-16');
    store.reset();
  });

  // --- worldObjects() / tracks:<assetId> live topic (docs/plans/active/CV-ORCHESTRATION-PLAN.md
  // §4.6, wave W3.1) — wiring only, piggybacked on the detections-feed subscription lifecycle above,
  // never the separate tracks-POLL lifecycle below. ------------------------------------------------

  function worldObject(id: number): WorldObject {
    return {
      state: { id, lifecycle: 'CONFIRMED', streamId: 's-wo' },
      operator: { followed: false, denied: false },
      event: {},
      render: { tier: 'T1' },
    };
  }

  it('track(streamId, assetId) subscribes to world objects, and a pushed array reflects on worldObjects()', () => {
    const api = stubApi();
    const live = stubLiveFacade('open');

    const store = inject(api, { live });
    store.track('s-18', 'a-18');

    expect(live.trackWorldObjects).toHaveBeenCalledExactlyOnceWith('a-18');
    expect(store.worldObjects()).toEqual([]);

    const objects = [worldObject(1), worldObject(2)];
    live.pushWorldObjects('a-18', objects);
    expect(store.worldObjects()).toEqual(objects);
    store.reset();
  });

  it('track(streamId) with no assetId never subscribes to world objects — worldObjects() stays []', () => {
    const api = stubApi();
    const live = stubLiveFacade('open');

    const store = inject(api, { live });
    store.track('s-19'); // no assetId

    expect(live.trackWorldObjects).not.toHaveBeenCalled();
    expect(store.worldObjects()).toEqual([]);
    store.reset();
  });

  it('reset() releases the world-objects subscription', () => {
    const api = stubApi();
    const live = stubLiveFacade('open');

    const store = inject(api, { live });
    store.track('s-20', 'a-20');
    expect(live.trackWorldObjects).toHaveBeenCalledExactlyOnceWith('a-20');

    store.reset();
    expect(live.untrackWorldObjects).toHaveBeenCalledExactlyOnceWith('a-20');
  });

  it('re-tracking a different assetId releases the old world-objects subscription and subscribes to the new one', () => {
    const api = stubApi();
    const live = stubLiveFacade('open');

    const store = inject(api, { live });
    store.track('s-21', 'a-21');
    store.track('s-22', 'a-22');

    expect(live.untrackWorldObjects).toHaveBeenCalledExactlyOnceWith('a-21');
    expect(live.trackWorldObjects).toHaveBeenCalledWith('a-22');
    store.reset();
  });

  it('worldObjects() is fully independent of the tracks-poll lifecycle — trackTracks()/untrackTracks() never touch trackWorldObjects()/untrackWorldObjects()', async () => {
    const getStreamTracks = vi.fn().mockResolvedValue({ streamId: 's-23', lockedTrackId: 0, tracks: [], objects: [] });
    const api = stubApi(undefined, getStreamTracks);
    const live = stubLiveFacade('open');

    const store = inject(api, { live });
    store.trackTracks('s-23');
    await flush();

    expect(live.trackWorldObjects).not.toHaveBeenCalled();
    expect(live.untrackWorldObjects).not.toHaveBeenCalled();

    store.track('s-23', 'a-23'); // the detections-feed session — the one that actually owns worldObjects()
    expect(live.trackWorldObjects).toHaveBeenCalledExactlyOnceWith('a-23');

    store.untrackTracks();
    expect(live.untrackWorldObjects).not.toHaveBeenCalled(); // the poll teardown never touches it

    store.reset();
    expect(live.untrackWorldObjects).toHaveBeenCalledExactlyOnceWith('a-23'); // only reset() releases it
  });

  // --- Tracks poll (docs/plans/done/TRACKING-PLAN.md §4.E, folded in from CvControlPanel — wave W5,
  // docs/plans/done/CV-CLEAN-FEED-PLAN.md D-3) ---------------------------------------------------

  function tracksResponse(partial: Partial<StreamTracksResponse> = {}): StreamTracksResponse {
    return { streamId: 't-1', lockedTrackId: 0, tracks: [], objects: [], ...partial };
  }

  it('trackTracks() polls immediately and on the configured cadence', async () => {
    const response = tracksResponse({ streamId: 't-1', lockedTrackId: 7 });
    const getStreamTracks = vi.fn().mockResolvedValue(response);
    const api = stubApi(undefined, getStreamTracks);
    const scheduler = stubScheduler();

    const store = inject(api, { scheduler });
    store.trackTracks('t-1');
    await flush();

    expect(getStreamTracks).toHaveBeenCalledWith('t-1');
    expect(scheduler.lastFor(2_000)).toBeDefined();
    expect(store.tracks()).toEqual(response);
    store.untrackTracks();
  });

  it('trackTracks() is a no-op when called again with the same streamId — mirrors track()\'s own dedupe', async () => {
    const getStreamTracks = vi.fn().mockResolvedValue(tracksResponse());
    const api = stubApi(undefined, getStreamTracks);

    const store = inject(api);
    store.trackTracks('t-2');
    await flush();
    store.trackTracks('t-2');
    await flush();

    expect(getStreamTracks).toHaveBeenCalledOnce();
    store.untrackTracks();
  });

  it('trackTracks() with a new streamId supersedes the previous session and clears its stale response immediately', async () => {
    const getStreamTracks = vi.fn().mockResolvedValue(tracksResponse({ streamId: 't-3', lockedTrackId: 3 }));
    const api = stubApi(undefined, getStreamTracks);

    const store = inject(api);
    store.trackTracks('t-3');
    await flush();
    expect(store.tracks()?.lockedTrackId).toBe(3);

    getStreamTracks.mockResolvedValue(tracksResponse({ streamId: 't-4', lockedTrackId: 0 }));
    store.trackTracks('t-4');
    expect(store.tracks()).toBeNull(); // cleared synchronously — never shows t-3's stale lock while t-4's poll is in flight
    await flush();
    expect(store.tracks()?.streamId).toBe('t-4');
    store.untrackTracks();
  });

  it('untrackTracks() stops the poll and clears tracks()', async () => {
    const getStreamTracks = vi.fn().mockResolvedValue(tracksResponse());
    const api = stubApi(undefined, getStreamTracks);
    const scheduler = stubScheduler();

    const store = inject(api, { scheduler });
    store.trackTracks('t-5');
    await flush();
    expect(store.tracks()).not.toBeNull();
    const poll = scheduler.lastFor(2_000);

    store.untrackTracks();
    expect(store.tracks()).toBeNull();
    expect(poll?.stop).toHaveBeenCalledOnce();
  });

  it('a stale in-flight tracks poll from a superseded trackTracks() never overwrites the newer stream', async () => {
    let resolveFirst!: (value: StreamTracksResponse) => void;
    const firstCall = new Promise<StreamTracksResponse>((resolve) => {
      resolveFirst = resolve;
    });
    const getStreamTracks = vi
      .fn()
      .mockImplementationOnce(() => firstCall)
      .mockResolvedValueOnce(tracksResponse({ streamId: 't-7', lockedTrackId: 9 }));
    const api = stubApi(undefined, getStreamTracks);

    const store = inject(api);
    store.trackTracks('t-6'); // in flight, not yet resolved
    store.trackTracks('t-7'); // supersedes it before the first poll settles
    await flush();
    resolveFirst(tracksResponse({ streamId: 't-6', lockedTrackId: 2 }));
    await flush();

    expect(store.tracks()?.streamId).toBe('t-7');
    store.untrackTracks();
  });

  it('silently degrades (tracks() -> null) when the tracks poll fails, rather than throwing', async () => {
    const getStreamTracks = vi.fn().mockRejectedValue(new Error('network down'));
    const api = stubApi(undefined, getStreamTracks);

    const store = inject(api);
    store.trackTracks('t-8');
    await flush();

    expect(store.tracks()).toBeNull();
    store.untrackTracks();
  });

  it('the tracks poll is fully independent of the detections feed — track()/reset() never touch tracks()', async () => {
    const streamDetections = vi.fn().mockResolvedValue([]);
    const getStreamTracks = vi.fn().mockResolvedValue(tracksResponse({ lockedTrackId: 4 }));
    const api = stubApi(streamDetections, getStreamTracks);

    const store = inject(api);
    store.trackTracks('t-9');
    store.track('t-9');
    await flush();

    expect(store.tracks()?.lockedTrackId).toBe(4);
    store.reset(); // detections feed teardown
    expect(store.tracks()?.lockedTrackId).toBe(4); // untouched by reset()

    store.untrackTracks();
    expect(store.tracks()).toBeNull();
  });

  // --- followTracks() (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.5, wave W4) ----------------------

  it('followTracks(streamId, true) starts the poll, mirroring trackTracks()', async () => {
    const getStreamTracks = vi.fn().mockResolvedValue(tracksResponse({ streamId: 't-10', lockedTrackId: 5 }));
    const api = stubApi(undefined, getStreamTracks);

    const store = inject(api);
    store.followTracks('t-10', true);
    await flush();

    expect(getStreamTracks).toHaveBeenCalledWith('t-10');
    expect(store.tracks()?.lockedTrackId).toBe(5);
    store.followTracks('t-10', false);
  });

  it('followTracks(streamId, false) stops the poll, mirroring untrackTracks()', async () => {
    const getStreamTracks = vi.fn().mockResolvedValue(tracksResponse());
    const api = stubApi(undefined, getStreamTracks);
    const scheduler = stubScheduler();

    const store = inject(api, { scheduler });
    store.followTracks('t-11', true);
    await flush();
    expect(store.tracks()).not.toBeNull();
    const poll = scheduler.lastFor(2_000);

    store.followTracks('t-11', false);
    expect(store.tracks()).toBeNull();
    expect(poll?.stop).toHaveBeenCalledOnce();
  });

  // --- Tracks transport flip (CV-ORCHESTRATION wave W9, decision E25): tracks() now follows the
  // same live/poll split as results(), reusing the store's already-known assetId (`track(streamId,
  // assetId?)`) rather than a parameter on followTracks() itself. --------------------------------

  it('followTracks resolves to the live tracks:<assetId> envelope and never polls once an assetId is in scope and LiveFacade is open', async () => {
    const getStreamTracks = vi.fn().mockResolvedValue(tracksResponse());
    const api = stubApi(undefined, getStreamTracks);
    const live = stubLiveFacade('open');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('s-24', 'a-24'); // the detections-feed session establishes the live tracks:<assetId> subscription
    store.followTracks('s-24', true);
    await flush();

    expect(getStreamTracks).not.toHaveBeenCalled(); // the poll fallback never runs while live resolves
    expect(store.tracks()).toBeNull(); // nothing has arrived on the live topic yet — honest, not fabricated

    const response = tracksResponse({ streamId: 's-24', lockedTrackId: 9 });
    live.pushTracks('a-24', response);
    expect(store.tracks()).toEqual(response);

    store.followTracks('s-24', false);
    store.reset();
  });

  it('followTracks still polls, unchanged, when no assetId is in scope even though LiveFacade is open', async () => {
    const response = tracksResponse({ streamId: 's-25', lockedTrackId: 3 });
    const getStreamTracks = vi.fn().mockResolvedValue(response);
    const api = stubApi(undefined, getStreamTracks);
    const live = stubLiveFacade('open');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('s-25'); // no assetId — LivePage/WallTile/CrewFacade-shaped session
    store.followTracks('s-25', true);
    await flush();

    expect(getStreamTracks).toHaveBeenCalledWith('s-25');
    expect(scheduler.lastFor(2_000)).toBeDefined();
    expect(store.tracks()).toEqual(response);

    store.followTracks('s-25', false);
    store.reset();
  });

  it('switches the tracks session from poll to live, stopping the poll, when LiveFacade opens mid-session', async () => {
    const response = tracksResponse({ streamId: 's-26', lockedTrackId: 6 });
    const getStreamTracks = vi.fn().mockResolvedValue(response);
    const api = stubApi(undefined, getStreamTracks);
    const live = stubLiveFacade('connecting');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('s-26', 'a-26');
    store.followTracks('s-26', true);
    await flush();
    const poll = scheduler.lastFor(2_000);
    expect(poll?.stop).not.toHaveBeenCalled();
    expect(store.tracks()).toEqual(response);

    live.setState('open');
    TestBed.tick(); // flushes the tracks-transport effect

    expect(poll?.stop).toHaveBeenCalledOnce();
    // Seeded from the live signal directly (no separate "seed the other side" step needed like
    // results' own merge — tracks() just re-reads whichever source is now active).
    live.pushTracks('a-26', tracksResponse({ streamId: 's-26', lockedTrackId: 8 }));
    expect(store.tracks()?.lockedTrackId).toBe(8);

    store.followTracks('s-26', false);
    store.reset();
  });

  // --- D1 regression (docs/plans/active/TRACK-FOLLOW-PLAN.md §2.2 D1, wave W4): closing the Vision
  // drawer used to stop CvControlPanel's tracks poll, which used to be the *only* thing that ever
  // wrote CockpitFacade#lockedTrackId — so the "Following #N" overlay lock silently vanished the
  // instant the drawer closed, even though the server-side lock was untouched. The fix moves the
  // facade's own lockedTrackId onto `results()[0]?.tracking?.lockedTrackId` (the per-frame detections
  // feed, tracked independently of any tracks poll) — this test exercises that exact expression
  // directly against this store, with the tracks poll never started at all, standing in for a
  // TestBed-free proof that a closed drawer can no longer zero this value.

  it('D1: the per-frame lockedTrackId (results()[0].tracking.lockedTrackId) reads non-zero with the tracks poll never started', async () => {
    const result: DetectionResult = {
      streamId: 's-lock',
      frameSequence: 1,
      capturedAt: new Date().toISOString(),
      inferenceMillis: 4,
      detections: [],
      tracking: {
        detectorRan: true,
        detectorReason: 'ALWAYS',
        trackerMillis: 2,
        engineId: 'bytetrack',
        lockedTrackId: 7,
        detectionLagMillis: 12,
        reupdateMillis: 0,
        reupdatedTracks: 0,
      },
    };
    const api = stubApi(vi.fn().mockResolvedValue([result]));

    const store = inject(api);
    store.track('s-lock');
    await flush();

    // The tracks poll (`tracks()`) was never started for this session — exactly the "drawer closed,
    // or never opened" case D1 used to break — yet the per-frame lock reads through untouched.
    expect(store.tracks()).toBeNull();
    expect(store.results()[0]?.tracking?.lockedTrackId ?? 0).toBe(7);
    store.reset();
  });
});
