import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import { CvTraceStore } from './cv-trace-store';
import { VisionApi } from '../api/vision-api';
import { LiveFacade } from '../live/live-facade';
import { PollScheduler } from '../poll-scheduler';
import type { CvTrace, FrameLedger, GateDecision, WorldObject } from '../api/models';

/** Lets the fire-and-forget promise chain inside `track()`/`pollOnce()` settle before asserting. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

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

function stubApi(getCvTrace: ReturnType<typeof vi.fn> = vi.fn().mockResolvedValue(trace())) {
  return { getCvTrace };
}

/** Mirrors `geo-store.spec.ts#stubLiveFacade`, narrowed to the cv-trace topic surface. */
function stubLiveFacade() {
  const perAsset = new Map<string, ReturnType<typeof signal<FrameLedger | undefined>>>();
  const signalFor = (assetId: string) => {
    let existing = perAsset.get(assetId);
    if (existing === undefined) {
      existing = signal<FrameLedger | undefined>(undefined);
      perAsset.set(assetId, existing);
    }
    return existing;
  };
  return {
    cvTraceFor: vi.fn((assetId: string) => signalFor(assetId)),
    trackCvTrace: vi.fn(),
    untrackCvTrace: vi.fn(),
    pushArrival: (assetId: string, frame: FrameLedger) => signalFor(assetId).set(frame),
  };
}

/** Mirrors `geo-store.spec.ts#stubScheduler` — captures registrations so a test can trigger a tick without real timers. */
function stubScheduler() {
  const calls: { periodMs: number; callback: () => void | Promise<void>; stop: ReturnType<typeof vi.fn> }[] = [];
  const schedule = vi.fn((periodMs: number, callback: () => void | Promise<void>) => {
    const stop = vi.fn();
    calls.push({ periodMs, callback, stop });
    return stop;
  });
  const last = () => calls[calls.length - 1];
  return { schedule, calls, last };
}

function inject(
  api: ReturnType<typeof stubApi>,
  options: { live?: ReturnType<typeof stubLiveFacade>; scheduler?: ReturnType<typeof stubScheduler> } = {},
): CvTraceStore {
  const providers: unknown[] = [
    CvTraceStore,
    { provide: VisionApi, useValue: api },
    { provide: LiveFacade, useValue: options.live ?? stubLiveFacade() },
    { provide: PollScheduler, useValue: options.scheduler ?? stubScheduler() },
  ];
  TestBed.configureTestingModule({ providers });
  return TestBed.inject(CvTraceStore);
}

describe('CvTraceStore', () => {
  it('seeds gate/frame/world from the initial GET .../cv/trace', async () => {
    const seeded = trace({ gate: [gateDecision(1)], frame: [frameLedger(1)], world: [worldObject(7)] });
    const api = stubApi(vi.fn().mockResolvedValue(seeded));

    const store = inject(api);
    store.track('stream-1');
    await flush();

    expect(api.getCvTrace).toHaveBeenCalledExactlyOnceWith('stream-1', 50);
    expect(store.gate()).toEqual(seeded.gate);
    expect(store.frame()).toEqual(seeded.frame);
    expect(store.world()).toEqual(seeded.world);
    store.reset();
  });

  it('passes a custom `last` through to both the GET and the ring cap', async () => {
    const api = stubApi();
    const store = inject(api);
    store.track('stream-1', undefined, 5);
    await flush();

    expect(api.getCvTrace).toHaveBeenCalledExactlyOnceWith('stream-1', 5);
    store.reset();
  });

  it('subscribes live only when an assetId is given', async () => {
    const api = stubApi();
    const live = stubLiveFacade();

    const withAsset = inject(api, { live });
    withAsset.track('stream-1', 'asset-1');
    expect(live.trackCvTrace).toHaveBeenCalledExactlyOnceWith('asset-1');
    withAsset.reset();
    expect(live.untrackCvTrace).toHaveBeenCalledExactlyOnceWith('asset-1');
  });

  it('stays poll-only when no assetId is given — never calls trackCvTrace', async () => {
    const api = stubApi();
    const live = stubLiveFacade();

    const store = inject(api, { live });
    store.track('stream-1');
    await flush();

    expect(live.trackCvTrace).not.toHaveBeenCalled();
    expect(api.getCvTrace).toHaveBeenCalled(); // still trace demand, via the poll alone
    store.reset();
  });

  it('merges a live arrival into the frame ring between polls', () => {
    const api = stubApi();
    const live = stubLiveFacade();

    const store = inject(api, { live });
    store.track('stream-1', 'asset-1');

    live.pushArrival('asset-1', frameLedger(1));
    TestBed.tick();
    expect(store.frame().map((f) => f.sequence)).toEqual([1]);

    live.pushArrival('asset-1', frameLedger(2));
    TestBed.tick();
    expect(store.frame().map((f) => f.sequence)).toEqual([1, 2]);
    store.reset();
  });

  it("the next poll's frame list authoritatively replaces the ring, never merges with it", async () => {
    const api = stubApi();
    const live = stubLiveFacade();
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('stream-1', 'asset-1');
    await flush();

    live.pushArrival('asset-1', frameLedger(99));
    TestBed.tick();
    expect(store.frame().map((f) => f.sequence)).toEqual([99]);

    api.getCvTrace.mockResolvedValueOnce(trace({ frame: [frameLedger(1), frameLedger(2)] }));
    await scheduler.last().callback();

    expect(store.frame().map((f) => f.sequence)).toEqual([1, 2]);
    store.reset();
  });

  it('silently degrades when the poll fails, keeping the last-known ledgers', async () => {
    const seeded = trace({ gate: [gateDecision(1)] });
    const api = stubApi(vi.fn().mockResolvedValueOnce(seeded).mockRejectedValueOnce(new Error('network down')));
    const scheduler = stubScheduler();

    const store = inject(api, { scheduler });
    store.track('stream-1');
    await flush();
    expect(store.gate()).toEqual(seeded.gate);

    await scheduler.last().callback();
    expect(store.gate()).toEqual(seeded.gate); // unchanged, not blanked
    store.reset();
  });

  it('reset() clears every ledger and stops polling', async () => {
    const seeded = trace({ gate: [gateDecision(1)], frame: [frameLedger(1)], world: [worldObject(7)] });
    const api = stubApi(vi.fn().mockResolvedValue(seeded));
    const scheduler = stubScheduler();

    const store = inject(api, { scheduler });
    store.track('stream-1');
    await flush();
    expect(store.gate().length).toBeGreaterThan(0);

    store.reset();
    expect(store.gate()).toEqual([]);
    expect(store.frame()).toEqual([]);
    expect(store.world()).toEqual([]);
    expect(scheduler.last().stop).toHaveBeenCalledOnce();
  });

  it('re-tracking the same (streamId, assetId, last) is a no-op', async () => {
    const api = stubApi();
    const live = stubLiveFacade();

    const store = inject(api, { live });
    for (let i = 0; i < 3; i++) {
      store.track('stream-1', 'asset-1');
    }
    await flush();

    expect(live.trackCvTrace).toHaveBeenCalledOnce();
    expect(api.getCvTrace).toHaveBeenCalledOnce();
    store.reset();
  });

  it('re-tracking a different stream releases the old live subscription and starts a fresh session', async () => {
    const api = stubApi();
    const live = stubLiveFacade();

    const store = inject(api, { live });
    store.track('stream-1', 'asset-1');
    store.track('stream-2', 'asset-2');
    await flush();

    expect(live.untrackCvTrace).toHaveBeenCalledExactlyOnceWith('asset-1');
    expect(live.trackCvTrace).toHaveBeenCalledWith('asset-2');
    expect(api.getCvTrace).toHaveBeenLastCalledWith('stream-2', 50);
    store.reset();
  });
});
