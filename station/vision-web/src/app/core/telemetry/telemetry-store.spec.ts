import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import { TelemetryStore } from './telemetry-store';
import { VisionApi } from '../api/vision-api';
import { LiveFacade, type LiveConnectionState } from '../live/live-facade';
import { PollScheduler } from '../poll-scheduler';
import type { AssetDetails, AssetSummary, TelemetrySample } from '../api/models';

/** Lets the fire-and-forget promise chain inside `track()` settle before asserting. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

/**
 * A minimal `LiveFacade` test double (docs/plans/done/REALTIME-PLAN.md §4, Phase R-c) — real Angular `signal`s
 * so `TelemetryStore`'s own `computed`/`effect` react to it exactly as they would to the real
 * class, without needing a real `EventSource` (jsdom has none — see `live-facade.ts`'s own doc
 * comment). Defaults to `'closed'` — the same state the *real* `LiveFacade` reports under jsdom —
 * so every pre-existing test above, which never provides this stub at all, keeps exercising the
 * poll-only path unmodified.
 */
function stubLiveFacade(initialState: LiveConnectionState = 'closed') {
  const stateSignal = signal<LiveConnectionState>(initialState);
  const perAsset = new Map<string, ReturnType<typeof signal<readonly TelemetrySample[]>>>();
  const signalFor = (assetId: string) => {
    let existing = perAsset.get(assetId);
    if (existing === undefined) {
      existing = signal<readonly TelemetrySample[]>([]);
      perAsset.set(assetId, existing);
    }
    return existing;
  };
  return {
    connectionState: stateSignal.asReadonly(),
    telemetryFor: vi.fn((assetId: string) => signalFor(assetId)),
    trackTelemetry: vi.fn(),
    untrackTelemetry: vi.fn(),
    setState: (state: LiveConnectionState) => stateSignal.set(state),
    pushSamples: (assetId: string, samples: readonly TelemetrySample[]) => signalFor(assetId).set(samples),
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
  /** The most recent registration for `periodMs` (there may be more than one over a test's lifetime). */
  const lastFor = (periodMs: number) => [...calls].reverse().find((call) => call.periodMs === periodMs);
  return { schedule, calls, lastFor };
}

function summaryFor(assetId: string): AssetSummary {
  return {
    assetId,
    displayName: assetId,
    category: 'drone',
    categoryName: 'Drone',
    owner: 'owner-0',
    status: 'STREAMING',
    lifecycle: 'ACTIVE',
    attributes: {},
  };
}

/**
 * `VisionApi`'s three asset-facing methods, stubbed. `TelemetryStore` uses nothing else, so a
 * plain object stands in for the whole HTTP client without spinning up `HttpTestingController`
 * (that seam is already exercised by `vision-api.spec.ts`).
 */
function stubApi(overrides: Partial<Record<'listAssets' | 'getAsset' | 'usageTelemetry', ReturnType<typeof vi.fn>>> = {}) {
  return {
    listAssets: vi.fn().mockResolvedValue([]),
    getAsset: vi.fn().mockResolvedValue(undefined),
    usageTelemetry: vi.fn().mockResolvedValue([]),
    ...overrides,
  };
}

function inject(
  api: ReturnType<typeof stubApi>,
  options: { live?: ReturnType<typeof stubLiveFacade>; scheduler?: ReturnType<typeof stubScheduler> } = {},
): TelemetryStore {
  const providers: unknown[] = [TelemetryStore, { provide: VisionApi, useValue: api }];
  providers.push({ provide: LiveFacade, useValue: options.live ?? stubLiveFacade() });
  if (options.scheduler) {
    providers.push({ provide: PollScheduler, useValue: options.scheduler });
  }
  TestBed.configureTestingModule({ providers });
  return TestBed.inject(TelemetryStore);
}

describe('TelemetryStore', () => {
  it('resolves the owning asset, finds its open usage, and polls its telemetry', async () => {
    const asset: AssetDetails = {
      ...summaryFor('a-1'),
      devices: [{ id: 'dev-1' } as never],
      recentUsages: [{ usageId: 'u-1', startedAt: '2026-07-22T00:00:00Z', sampleCount: 1 }],
    };
    const sample: TelemetrySample = { deviceId: 'dev-1', at: '2026-07-22T00:00:01Z', latitude: 10, longitude: 20 };
    const api = stubApi({
      listAssets: vi.fn().mockResolvedValue([summaryFor('a-1')]),
      getAsset: vi.fn().mockResolvedValue(asset),
      usageTelemetry: vi.fn().mockResolvedValue([sample]),
    });

    const store = inject(api);
    store.track('dev-1');
    await flush();
    await flush();

    expect(api.getAsset).toHaveBeenCalledWith('a-1');
    expect(api.usageTelemetry).toHaveBeenCalledWith('u-1', 200);
    expect(store.hasTelemetry()).toBe(true);
    expect(store.latest()).toEqual(sample);
    expect(store.trail()).toEqual([{ latitude: 10, longitude: 20, altitudeMeters: undefined }]);

    store.reset();
  });

  it('never polls telemetry for a device whose asset has no open usage', async () => {
    const asset: AssetDetails = {
      ...summaryFor('a-2'),
      devices: [{ id: 'dev-2' } as never],
      recentUsages: [
        { usageId: 'u-2', startedAt: '2026-07-22T00:00:00Z', endedAt: '2026-07-22T00:10:00Z', sampleCount: 3 },
      ],
    };
    const api = stubApi({
      listAssets: vi.fn().mockResolvedValue([summaryFor('a-2')]),
      getAsset: vi.fn().mockResolvedValue(asset),
    });

    const store = inject(api);
    store.track('dev-2');
    await flush();

    expect(api.usageTelemetry).not.toHaveBeenCalled();
    expect(store.hasTelemetry()).toBe(false);
  });

  it('silently degrades when the asset lookup fails, rather than throwing', async () => {
    const api = stubApi({ listAssets: vi.fn().mockRejectedValue(new Error('network down')) });

    const store = inject(api);
    store.track('dev-3');
    await flush();

    expect(store.hasTelemetry()).toBe(false);
    expect(store.samples()).toEqual([]);
  });

  // --- assetId O(1) path (docs/plans/done/REALTIME-PLAN.md Phase R-a item 3) -----------------------------

  it('given an assetId, resolves the open usage with one getAsset() call — no listAssets() at all', async () => {
    const asset: AssetDetails = {
      ...summaryFor('a-5'),
      devices: [{ id: 'dev-5' } as never],
      recentUsages: [{ usageId: 'u-5', startedAt: '2026-07-22T00:00:00Z', sampleCount: 1 }],
    };
    const sample: TelemetrySample = { deviceId: 'dev-5', at: '2026-07-22T00:00:01Z', latitude: 5, longitude: 6 };
    const api = stubApi({
      getAsset: vi.fn().mockResolvedValue(asset),
      usageTelemetry: vi.fn().mockResolvedValue([sample]),
    });

    const store = inject(api);
    store.track('dev-5', 'a-5');
    await flush();
    await flush();

    expect(api.getAsset).toHaveBeenCalledExactlyOnceWith('a-5');
    expect(api.listAssets).not.toHaveBeenCalled();
    expect(api.usageTelemetry).toHaveBeenCalledWith('u-5', 200);
    expect(store.hasTelemetry()).toBe(true);
    expect(store.latest()).toEqual(sample);

    store.reset();
  });

  it('given an assetId whose open usage is missing, polls nothing — still no listAssets() fallback', async () => {
    const asset: AssetDetails = {
      ...summaryFor('a-6'),
      devices: [{ id: 'dev-6' } as never],
      recentUsages: [
        { usageId: 'u-6', startedAt: '2026-07-22T00:00:00Z', endedAt: '2026-07-22T00:10:00Z', sampleCount: 2 },
      ],
    };
    const api = stubApi({ getAsset: vi.fn().mockResolvedValue(asset) });

    const store = inject(api);
    store.track('dev-6', 'a-6');
    await flush();

    expect(api.listAssets).not.toHaveBeenCalled();
    expect(api.usageTelemetry).not.toHaveBeenCalled();
    expect(store.hasTelemetry()).toBe(false);
  });

  it('re-entering track() with the same (deviceId, assetId) is a no-op — no second getAsset() call at all', async () => {
    // Defense in depth (docs/plans/done/REALTIME-PLAN.md §4 Phase R-c follow-up): a caller-side guard
    // (`trackingIdChanged`, `AssetDetailPage`/`FlyPage`) is the primary fix for the re-entry churn
    // bug, but this store's own `track()` no-ops on an unchanged `(deviceId, assetId)` pair too, so
    // an unguarded call site (or a caller-side guard bug) can't reopen the same tight loop.
    const asset: AssetDetails = {
      ...summaryFor('a-7'),
      devices: [{ id: 'dev-7' } as never],
      recentUsages: [{ usageId: 'u-7', startedAt: '2026-07-22T00:00:00Z', sampleCount: 1 }],
    };
    const api = stubApi({
      getAsset: vi.fn().mockResolvedValue(asset),
      usageTelemetry: vi.fn().mockResolvedValue([]),
    });

    const store = inject(api);
    store.track('dev-7', 'a-7');
    await flush();
    await flush();
    store.track('dev-7', 'a-7'); // e.g. an effect re-notified with the same id — see fly.ts's own guard
    await flush();
    await flush();

    expect(api.getAsset).toHaveBeenCalledOnce(); // the second track() call never re-entered startTracking()
    expect(api.listAssets).not.toHaveBeenCalled();

    store.reset();
  });

  it('N successive track() calls with the same id — even with no caller-side guard at all — subscribe live exactly once and never untrack', async () => {
    // Simulates the exact churn class this test suite exists to guard against: an effect reading a
    // fresh `AssetDetails` object every poll tick, re-entering `track()` with unchanged primitives
    // each time (docs/plans/done/REALTIME-PLAN.md §4 Phase R-c follow-up — the `AssetDetailPage` bug, not just
    // R-a's slower-paced original). Without the `lastTrackKey` guard, each of these calls would tear
    // down and rebuild the live subscription — the `untrack`/`track` log-spam loop this fix closes.
    const asset: AssetDetails = {
      ...summaryFor('a-17'),
      devices: [{ id: 'dev-17' } as never],
      recentUsages: [{ usageId: 'u-17', startedAt: '2026-07-24T00:00:00Z', sampleCount: 0 }],
    };
    const api = stubApi({
      getAsset: vi.fn().mockResolvedValue(asset),
      usageTelemetry: vi.fn().mockResolvedValue([]),
    });
    const live = stubLiveFacade('open');

    const store = inject(api, { live });
    for (let i = 0; i < 5; i++) {
      // A fresh object every call — mirrors a poll-refreshed `AssetDetails` — but the same ids.
      store.track('dev-17', 'a-17');
    }
    await flush();
    await flush();

    expect(live.trackTelemetry).toHaveBeenCalledExactlyOnceWith('a-17');
    expect(live.untrackTelemetry).not.toHaveBeenCalled();
    expect(api.getAsset).toHaveBeenCalledOnce(); // only the first call actually started a lookup

    store.reset();
    expect(live.untrackTelemetry).toHaveBeenCalledExactlyOnceWith('a-17'); // reset() still releases it
  });

  it('silently degrades when getAsset(assetId) fails, rather than falling back to listAssets()', async () => {
    const api = stubApi({ getAsset: vi.fn().mockRejectedValue(new Error('network down')) });

    const store = inject(api);
    store.track('dev-8', 'a-8');
    await flush();

    expect(api.listAssets).not.toHaveBeenCalled();
    expect(store.hasTelemetry()).toBe(false);
  });

  it('reset() clears samples and stops polling', async () => {
    const asset: AssetDetails = {
      ...summaryFor('a-4'),
      devices: [{ id: 'dev-4' } as never],
      recentUsages: [{ usageId: 'u-4', startedAt: '2026-07-22T00:00:00Z', sampleCount: 1 }],
    };
    const sample: TelemetrySample = { deviceId: 'dev-4', at: '2026-07-22T00:00:01Z', latitude: 1, longitude: 2 };
    const api = stubApi({
      listAssets: vi.fn().mockResolvedValue([summaryFor('a-4')]),
      getAsset: vi.fn().mockResolvedValue(asset),
      usageTelemetry: vi.fn().mockResolvedValue([sample]),
    });

    const store = inject(api);
    store.track('dev-4');
    await flush();
    await flush();
    expect(store.hasTelemetry()).toBe(true);

    store.reset();
    expect(store.hasTelemetry()).toBe(false);
    expect(store.samples()).toEqual([]);
  });

  // --- LiveFacade projection (docs/plans/done/REALTIME-PLAN.md §4, Phase R-c) ----------------------------

  function assetWithOpenUsage(assetId: string, deviceId: string, usageId: string): AssetDetails {
    return {
      ...summaryFor(assetId),
      devices: [{ id: deviceId } as never],
      recentUsages: [{ usageId, startedAt: '2026-07-24T00:00:00Z', sampleCount: 0 }],
    };
  }

  it('subscribes live when LiveFacade is open and an assetId is given — one backfill GET, no repeat poll', async () => {
    const backfill: TelemetrySample = { deviceId: 'dev-9', at: '2026-07-24T00:00:00Z', latitude: 1, longitude: 1 };
    const api = stubApi({
      getAsset: vi.fn().mockResolvedValue(assetWithOpenUsage('a-9', 'dev-9', 'u-9')),
      usageTelemetry: vi.fn().mockResolvedValue([backfill]),
    });
    const live = stubLiveFacade('open');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('dev-9', 'a-9');
    await flush();
    await flush();

    expect(live.trackTelemetry).toHaveBeenCalledExactlyOnceWith('a-9');
    expect(api.usageTelemetry).toHaveBeenCalledOnce(); // backfill only
    expect(scheduler.lastFor(2_000)).toBeUndefined(); // no poll registered while live

    const pushed: TelemetrySample = { deviceId: 'dev-9', at: '2026-07-24T00:00:01Z', latitude: 2, longitude: 2 };
    live.pushSamples('a-9', [pushed]);

    expect(store.samples()).toEqual([backfill, pushed]); // merged: one-time backfill + live delta
    store.reset();
  });

  it('never subscribes live without an assetId, even when LiveFacade is open — always polls', async () => {
    const api = stubApi({
      listAssets: vi.fn().mockResolvedValue([summaryFor('a-10')]),
      getAsset: vi.fn().mockResolvedValue(assetWithOpenUsage('a-10', 'dev-10', 'u-10')),
    });
    const live = stubLiveFacade('open');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('dev-10'); // no assetId
    await flush();
    await flush();

    expect(live.trackTelemetry).not.toHaveBeenCalled();
    expect(scheduler.lastFor(2_000)).toBeDefined(); // still polling
    store.reset();
  });

  it('falls back to polling while LiveFacade is not open, even with an assetId — still subscribes for later', async () => {
    const api = stubApi({
      getAsset: vi.fn().mockResolvedValue(assetWithOpenUsage('a-11', 'dev-11', 'u-11')),
      usageTelemetry: vi.fn().mockResolvedValue([]),
    });
    const live = stubLiveFacade('connecting');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('dev-11', 'a-11');
    await flush();
    await flush();

    expect(live.trackTelemetry).toHaveBeenCalledExactlyOnceWith('a-11'); // subscribed regardless of transport
    expect(scheduler.lastFor(2_000)).toBeDefined(); // but reads via polling until LiveFacade opens
    store.reset();
  });

  it('switches from poll to live, stopping the poll, when LiveFacade opens mid-session', async () => {
    const api = stubApi({
      getAsset: vi.fn().mockResolvedValue(assetWithOpenUsage('a-12', 'dev-12', 'u-12')),
      usageTelemetry: vi.fn().mockResolvedValue([]),
    });
    const live = stubLiveFacade('connecting');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('dev-12', 'a-12');
    await flush();
    await flush();
    const poll = scheduler.lastFor(2_000);
    expect(poll?.stop).not.toHaveBeenCalled();

    live.setState('open');
    TestBed.tick(); // flushes the transport-switch effect

    expect(poll?.stop).toHaveBeenCalledOnce(); // the poll is stopped, not left running alongside live
    store.reset();
  });

  it('falls back to polling again, fetching fresh data immediately, when LiveFacade drops mid-session', async () => {
    const usageTelemetry = vi.fn().mockResolvedValue([]);
    const api = stubApi({
      getAsset: vi.fn().mockResolvedValue(assetWithOpenUsage('a-13', 'dev-13', 'u-13')),
      usageTelemetry,
    });
    const live = stubLiveFacade('open');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('dev-13', 'a-13');
    await flush();
    await flush();
    expect(scheduler.lastFor(2_000)).toBeUndefined(); // live from the start, no poll registered yet
    const callsWhileLive = usageTelemetry.mock.calls.length;

    live.setState('connecting');
    TestBed.tick();
    await flush();

    expect(scheduler.lastFor(2_000)).toBeDefined(); // now polling
    expect(usageTelemetry.mock.calls.length).toBeGreaterThan(callsWhileLive); // fetched fresh, not stale
    store.reset();
  });

  it('reset() releases the live subscription', async () => {
    const api = stubApi({
      getAsset: vi.fn().mockResolvedValue(assetWithOpenUsage('a-14', 'dev-14', 'u-14')),
      usageTelemetry: vi.fn().mockResolvedValue([]),
    });
    const live = stubLiveFacade('open');

    const store = inject(api, { live });
    store.track('dev-14', 'a-14');
    await flush();
    await flush();
    expect(live.trackTelemetry).toHaveBeenCalledExactlyOnceWith('a-14');

    store.reset();
    expect(live.untrackTelemetry).toHaveBeenCalledExactlyOnceWith('a-14');
  });

  it('re-tracking a different assetId releases the old live subscription and subscribes to the new one', async () => {
    const api = stubApi({
      getAsset: vi
        .fn()
        .mockResolvedValueOnce(assetWithOpenUsage('a-15', 'dev-15', 'u-15'))
        .mockResolvedValueOnce(assetWithOpenUsage('a-16', 'dev-16', 'u-16')),
      usageTelemetry: vi.fn().mockResolvedValue([]),
    });
    const live = stubLiveFacade('open');

    const store = inject(api, { live });
    store.track('dev-15', 'a-15');
    await flush();
    await flush();
    store.track('dev-16', 'a-16');
    await flush();
    await flush();

    expect(live.untrackTelemetry).toHaveBeenCalledExactlyOnceWith('a-15');
    expect(live.trackTelemetry).toHaveBeenCalledWith('a-16');
    store.reset();
  });
});
