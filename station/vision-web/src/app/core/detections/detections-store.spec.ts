import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import { DetectionsStore } from './detections-store';
import { VisionApi } from '../api/vision-api';
import { LiveStore, type LiveConnectionState } from '../live/live-store';
import { PollScheduler } from '../poll-scheduler';
import type { DetectionResult } from '../api/models';
import { CV_STATUS_FRESH_SECONDS } from './detections-logic';

/** Lets the fire-and-forget promise chain inside `track()` settle before asserting. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function stubApi(streamDetections: ReturnType<typeof vi.fn> = vi.fn().mockResolvedValue([])) {
  return { streamDetections };
}

/**
 * A minimal `LiveStore` test double (docs/plans/done/REALTIME-PLAN.md §4, Phase R-c) — mirrors
 * `telemetry-store.spec.ts`'s own stub exactly (see that file's doc comment for the full
 * reasoning); defaults to `'closed'`, matching the real class under jsdom, so every pre-existing
 * test above keeps exercising the poll-only path unmodified.
 */
function stubLiveStore(initialState: LiveConnectionState = 'closed') {
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
  return {
    connectionState: stateSignal.asReadonly(),
    detectionsFor: vi.fn((assetId: string) => signalFor(assetId)),
    trackDetections: vi.fn(),
    untrackDetections: vi.fn(),
    setState: (state: LiveConnectionState) => stateSignal.set(state),
    pushResult: (assetId: string, result: DetectionResult) => signalFor(assetId).set(result),
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
  options: { live?: ReturnType<typeof stubLiveStore>; scheduler?: ReturnType<typeof stubScheduler> } = {},
): DetectionsStore {
  const providers: unknown[] = [DetectionsStore, { provide: VisionApi, useValue: api }];
  if (options.live) {
    providers.push({ provide: LiveStore, useValue: options.live });
  }
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

  // --- LiveStore projection (docs/plans/done/REALTIME-PLAN.md §4, Phase R-c) ----------------------------

  function detectionResult(streamId: string, frameSequence: number): DetectionResult {
    return { streamId, frameSequence, capturedAt: new Date().toISOString(), inferenceMillis: 5, detections: [] };
  }

  it('subscribes live when LiveStore is open and an assetId is given, accumulating latest-frame results', () => {
    const api = stubApi();
    const live = stubLiveStore('open');
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
    const live = stubLiveStore('open');
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

  it('never subscribes live without an assetId, even when LiveStore is open — always polls', async () => {
    const api = stubApi();
    const live = stubLiveStore('open');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('s-10'); // no assetId
    await flush();

    expect(live.trackDetections).not.toHaveBeenCalled();
    expect(scheduler.lastFor(2_000)).toBeDefined();
    store.reset();
  });

  it('falls back to polling while LiveStore is not open, even with an assetId — still subscribes for later', async () => {
    const api = stubApi();
    const live = stubLiveStore('connecting');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('s-11', 'a-11');
    await flush();

    expect(live.trackDetections).toHaveBeenCalledExactlyOnceWith('a-11'); // subscribed regardless of transport
    expect(scheduler.lastFor(2_000)).toBeDefined();
    store.reset();
  });

  it('switches from poll to live, stopping the poll, when LiveStore opens mid-session', async () => {
    const api = stubApi();
    const live = stubLiveStore('connecting');
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

  it('falls back to polling again immediately when LiveStore drops mid-session, keeping the last-visible result', async () => {
    const streamDetections = vi.fn().mockResolvedValue([]);
    const api = stubApi(streamDetections);
    const live = stubLiveStore('open');
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
    const live = stubLiveStore('open');

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
    const live = stubLiveStore('open');

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
    const live = stubLiveStore('open');

    const store = inject(api, { live });
    store.track('s-15', 'a-15');
    store.track('s-16', 'a-16');

    expect(live.untrackDetections).toHaveBeenCalledExactlyOnceWith('a-15');
    expect(live.trackDetections).toHaveBeenCalledWith('a-16');
    store.reset();
  });
});
