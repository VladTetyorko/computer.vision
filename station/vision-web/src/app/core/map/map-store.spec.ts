import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import { FleetMapStore } from './map-store';
import { VisionApi } from '../api/vision-api';
import { PollScheduler } from '../poll-scheduler';
import { LiveStore } from '../live/live-store';
import type { LiveConnectionState } from '../live/live-fallback-logic';
import type { AssetDetails, AssetSummary, Device, TelemetrySample } from '../api/models';

/** Lets the fire-and-forget promise chains inside `refresh()`/tracker startup settle before asserting. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function summary(partial: Partial<AssetSummary> = {}): AssetSummary {
  return {
    assetId: 'a-1',
    displayName: 'Drone One',
    category: 'drone',
    categoryName: 'Drone',
    owner: 'owner-0',
    status: 'OFFLINE',
    lifecycle: 'ACTIVE',
    attributes: {},
    ...partial,
  };
}

function device(partial: Partial<Device> = {}): Device {
  return {
    id: 'dev-1',
    name: 'device',
    capabilities: ['VIDEO'],
    protocol: 'sim',
    uri: 'sim://demo',
    options: {},
    state: 'ACTIVE',
    ...partial,
  };
}

function details(summaryPart: Partial<AssetSummary>, extra: Partial<AssetDetails> = {}): AssetDetails {
  return { ...summary(summaryPart), devices: [], recentUsages: [], ...extra };
}

function stubApi(
  overrides: Partial<Record<'listAssets' | 'getAsset' | 'usageTelemetry', ReturnType<typeof vi.fn>>> = {},
) {
  return {
    listAssets: vi.fn().mockResolvedValue([]),
    getAsset: vi.fn().mockResolvedValue(undefined),
    usageTelemetry: vi.fn().mockResolvedValue([]),
    ...overrides,
  };
}

/**
 * A minimal `LiveStore` test double (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §5 L7) — real
 * Angular `signal`s so `FleetMapStore`'s own `computed`/`effect`s react to it exactly as they would
 * to the real class, without needing a real `EventSource` (jsdom has none — see `live-store.ts`'s
 * own doc comment). `connectionState` defaults `'closed'` — the same state the *real* `LiveStore`
 * reports under jsdom — so every test that never provides an explicit initial state, or never
 * drives `connectionState`/`fleet` at all, keeps exercising the poll-only path unmodified.
 */
function stubLiveStore(initialState: LiveConnectionState = 'closed') {
  const connectionState = signal<LiveConnectionState>(initialState);
  const fleet = signal<readonly AssetSummary[] | undefined>(undefined);
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
    connectionState,
    fleet,
    telemetryFor: vi.fn((assetId: string) => signalFor(assetId)),
    trackTelemetry: vi.fn(),
    untrackTelemetry: vi.fn(),
    pushSamples: (assetId: string, samples: readonly TelemetrySample[]) => signalFor(assetId).set(samples),
  };
}

/**
 * Captures every `PollScheduler.schedule` registration, keyed by cadence, so a test can assert
 * on/off per-poll (the 5s asset poll vs. the 1s clock, L7d) without real timers — mirrors
 * `telemetry-store.spec.ts`'s own `stubScheduler` helper.
 */
function stubScheduler() {
  const calls: { periodMs: number; stop: ReturnType<typeof vi.fn> }[] = [];
  const schedule = vi.fn((periodMs: number) => {
    const stop = vi.fn();
    calls.push({ periodMs, stop });
    return stop;
  });
  return { schedule, calls, forPeriod: (periodMs: number) => calls.filter((call) => call.periodMs === periodMs) };
}

function create(
  api: ReturnType<typeof stubApi>,
  options: { live?: ReturnType<typeof stubLiveStore>; scheduler?: ReturnType<typeof stubScheduler> } = {},
) {
  const live = options.live ?? stubLiveStore();
  const scheduler = options.scheduler ?? stubScheduler();
  TestBed.configureTestingModule({
    providers: [
      FleetMapStore,
      { provide: VisionApi, useValue: api },
      { provide: LiveStore, useValue: live },
      { provide: PollScheduler, useValue: scheduler },
    ],
  });
  return { store: TestBed.inject(FleetMapStore), live, scheduler };
}

describe('FleetMapStore', () => {
  it('polls assets on construction and derives buckets/markers', async () => {
    const streaming = summary({
      assetId: 's-1',
      status: 'STREAMING',
      lastKnownPosition: { latitude: 1, longitude: 2 },
    });
    const offline = summary({
      assetId: 'o-1',
      status: 'OFFLINE',
      lastKnownPosition: { latitude: 3, longitude: 4 },
    });
    const noPosition = summary({ assetId: 'n-1', status: 'OFFLINE' });
    const api = stubApi({
      listAssets: vi.fn().mockResolvedValue([streaming, offline, noPosition]),
      getAsset: vi.fn().mockResolvedValue(details({ status: 'STREAMING' })),
    });

    const { store } = create(api);
    await flush();
    await flush();

    expect(store.buckets().streaming.map((a) => a.assetId)).toEqual(['s-1']);
    expect(store.buckets().offline.map((a) => a.assetId)).toEqual(['o-1']);
    expect(store.buckets().noPosition.map((a) => a.assetId)).toEqual(['n-1']);
    expect(store.markers().map((m) => m.assetId).sort()).toEqual(['o-1', 's-1']);
  });

  it('starts a telemetry tracker (subscribe + one backfill) for a streaming asset with an open usage', async () => {
    const streaming = summary({
      assetId: 's-2',
      status: 'STREAMING',
      lastKnownPosition: { latitude: 1, longitude: 2 },
    });
    const sample: TelemetrySample = { deviceId: 'dev-0', at: '2026-07-22T00:00:00Z', latitude: 9, longitude: 8, headingDegrees: 45 };
    const api = stubApi({
      listAssets: vi.fn().mockResolvedValue([streaming]),
      getAsset: vi.fn().mockResolvedValue(
        details({ status: 'STREAMING' }, { recentUsages: [{ usageId: 'u-1', startedAt: 't0', sampleCount: 1 }] }),
      ),
      usageTelemetry: vi.fn().mockResolvedValue([sample]),
    });

    const { store, live } = create(api);
    await flush();
    await flush();
    await flush();

    expect(api.getAsset).toHaveBeenCalledWith('s-2');
    expect(live.trackTelemetry).toHaveBeenCalledWith('s-2');
    expect(api.usageTelemetry).toHaveBeenCalledWith('u-1', 200);
    const marker = store.markers().find((m) => m.assetId === 's-2');
    expect(marker?.live).toBe(true);
    expect(marker?.position).toEqual({ latitude: 9, longitude: 8, altitudeMeters: undefined });
    expect(marker?.headingDegrees).toBe(45);
  });

  it('subscribes a streaming asset with no open usage, but never fetches a backfill for it', async () => {
    const streaming = summary({
      assetId: 's-3',
      status: 'STREAMING',
      lastKnownPosition: { latitude: 1, longitude: 2 },
    });
    const api = stubApi({
      listAssets: vi.fn().mockResolvedValue([streaming]),
      getAsset: vi.fn().mockResolvedValue(
        details({ status: 'STREAMING' }, {
          recentUsages: [{ usageId: 'u-2', startedAt: 't0', endedAt: 't1', sampleCount: 3 }],
        }),
      ),
    });

    const { store, live } = create(api);
    await flush();
    await flush();

    expect(live.trackTelemetry).toHaveBeenCalledWith('s-3'); // subscribed off the streaming bucket alone
    expect(api.usageTelemetry).not.toHaveBeenCalled(); // no open usage to backfill from
    const marker = store.markers().find((m) => m.assetId === 's-3');
    expect(marker?.live).toBe(true);
    expect(marker?.position).toEqual({ latitude: 1, longitude: 2 }); // falls back to lastKnownPosition
  });

  it('tears down and untracks a tracker once its asset is no longer streaming, on the next refresh', async () => {
    const streaming = summary({
      assetId: 's-4',
      status: 'STREAMING',
      lastKnownPosition: { latitude: 1, longitude: 2 },
    });
    const nowOffline = { ...streaming, status: 'OFFLINE' as const };
    const sample: TelemetrySample = { deviceId: 'dev-0', at: '2026-07-22T00:00:00Z', latitude: 9, longitude: 8 };
    const listAssets = vi.fn().mockResolvedValueOnce([streaming]).mockResolvedValue([nowOffline]);
    const api = stubApi({
      listAssets,
      getAsset: vi.fn().mockResolvedValue(
        details({ status: 'STREAMING' }, { recentUsages: [{ usageId: 'u-4', startedAt: 't0', sampleCount: 1 }] }),
      ),
      usageTelemetry: vi.fn().mockResolvedValue([sample]),
    });

    const { store, live } = create(api);
    await flush();
    await flush();
    await flush();
    expect(store.markers().find((m) => m.assetId === 's-4')?.live).toBe(true);
    expect(live.trackTelemetry).toHaveBeenCalledTimes(1);
    expect(live.untrackTelemetry).not.toHaveBeenCalled();

    await store.refresh();
    await flush();

    const marker = store.markers().find((m) => m.assetId === 's-4');
    expect(marker?.live).toBe(false);
    expect(marker?.position).toEqual({ latitude: 1, longitude: 2 });
    expect(live.untrackTelemetry).toHaveBeenCalledTimes(1);
    expect(live.untrackTelemetry).toHaveBeenCalledWith('s-4');
  });

  it('silently degrades when the asset poll fails, rather than throwing', async () => {
    const api = stubApi({ listAssets: vi.fn().mockRejectedValue(new Error('network down')) });

    const { store } = create(api);
    await flush();

    expect(store.assets()).toEqual([]);
    expect(store.markers()).toEqual([]);
  });

  it('resolveWatchDevice returns the asset VIDEO device', async () => {
    const video = device({ id: 'dev-v', capabilities: ['VIDEO'] });
    const api = stubApi({ getAsset: vi.fn().mockResolvedValue(details({}, { devices: [video] })) });

    const { store } = create(api);
    await expect(store.resolveWatchDevice('a-1')).resolves.toBe(video);
  });

  it('resolveWatchDevice resolves to undefined on failure', async () => {
    const api = stubApi({ getAsset: vi.fn().mockRejectedValue(new Error('gone')) });

    const { store } = create(api);
    await expect(store.resolveWatchDevice('a-1')).resolves.toBeUndefined();
  });

  describe('live gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1, wave L7a)', () => {
    /**
     * The plan's own frozen acceptance criterion (§5): a poll that stops must still reconcile on
     * reconnect. With the store active and live open, driving `connectionState` through
     * `open → closed → open` must issue **exactly one** REST refresh on (re-)entering `open`, and
     * **zero** REST requests for as long as `open` persists.
     */
    it('reconciles exactly once on reconnect, and stays silent for as long as live holds', async () => {
      const api = stubApi({ listAssets: vi.fn().mockResolvedValue([]) });
      const { live } = create(api);
      await flush();
      TestBed.tick();

      // Reach a known "live open" baseline first — this transition's own reconcile fetch is not
      // what's under test.
      live.connectionState.set('open');
      TestBed.tick();
      await flush();

      live.connectionState.set('closed');
      TestBed.tick();
      await flush();
      // Falling back to polling refreshes immediately too (D1's own `false | true(live) -> refresh
      // once, then start poll` row) — a separate, legitimate call, also not the segment under test.
      api.listAssets.mockClear();

      live.connectionState.set('open');
      TestBed.tick();
      await flush();

      expect(api.listAssets).toHaveBeenCalledTimes(1); // exactly one refresh, entering 'open'

      // Zero further REST requests while 'open' persists — an equal-value re-write (signals don't
      // re-notify on an equal write) must be a no-op.
      live.connectionState.set('open');
      TestBed.tick();
      await flush();
      expect(api.listAssets).toHaveBeenCalledTimes(1);
    });

    it('never schedules the 5s asset poll while live is already open at construction', async () => {
      const api = stubApi({ listAssets: vi.fn().mockResolvedValue([]) });
      const scheduler = stubScheduler();
      const { live } = create(api, { live: stubLiveStore('open'), scheduler });
      await flush();

      expect(api.listAssets).toHaveBeenCalledTimes(1); // the one-time reconcile — see class doc
      expect(scheduler.forPeriod(5_000)).toHaveLength(0); // never scheduled — live was already open
      expect(scheduler.forPeriod(1_000)).toHaveLength(1); // the local clock still runs regardless (L7d)

      live.connectionState.set('open'); // equal-value re-write, must stay a no-op
      TestBed.tick();
      await flush();
      expect(scheduler.forPeriod(5_000)).toHaveLength(0);
      expect(api.listAssets).toHaveBeenCalledTimes(1);
    });

    it('reads assets from the live fleet snapshot while live is open, with no REST call, and reconciles trackers from it', async () => {
      const api = stubApi();
      const { store, live } = create(api);
      await flush();
      TestBed.tick();

      live.connectionState.set('open');
      TestBed.tick();
      await flush();
      api.listAssets.mockClear();

      const streaming = summary({ assetId: 's-8', status: 'STREAMING', lastKnownPosition: { latitude: 5, longitude: 6 } });
      live.fleet.set([streaming]);
      TestBed.tick();
      await flush();

      expect(store.assets()).toEqual([streaming]);
      expect(api.listAssets).not.toHaveBeenCalled(); // the fleet snapshot arrived over SSE, no REST needed
      expect(live.trackTelemetry).toHaveBeenCalledWith('s-8'); // reconciled off the live snapshot too
    });
  });

  describe('telemetry tracking (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §5 L7b/L7c)', () => {
    it('balances trackTelemetry/untrackTelemetry across a streaming -> stopped -> streaming transition', async () => {
      const streaming = summary({ assetId: 's-5', status: 'STREAMING', lastKnownPosition: { latitude: 1, longitude: 2 } });
      const offline = { ...streaming, status: 'OFFLINE' as const };
      const api = stubApi({
        listAssets: vi
          .fn()
          .mockResolvedValueOnce([streaming])
          .mockResolvedValueOnce([offline])
          .mockResolvedValueOnce([streaming]),
        getAsset: vi.fn().mockResolvedValue(
          details({ status: 'STREAMING' }, { recentUsages: [{ usageId: 'u-5', startedAt: 't0', sampleCount: 1 }] }),
        ),
      });

      const { store, live } = create(api);
      await flush();
      await flush();
      expect(live.trackTelemetry).toHaveBeenCalledTimes(1);
      expect(live.trackTelemetry).toHaveBeenNthCalledWith(1, 's-5');
      expect(live.untrackTelemetry).not.toHaveBeenCalled();

      await store.refresh(); // now offline
      await flush();
      expect(live.untrackTelemetry).toHaveBeenCalledTimes(1);
      expect(live.untrackTelemetry).toHaveBeenNthCalledWith(1, 's-5');
      expect(live.trackTelemetry).toHaveBeenCalledTimes(1); // unchanged

      await store.refresh(); // streaming again
      await flush();
      await flush();
      expect(live.trackTelemetry).toHaveBeenCalledTimes(2);
      expect(live.untrackTelemetry).toHaveBeenCalledTimes(1); // unchanged
    });

    it('untracks every remaining tracked asset on store teardown', async () => {
      const streaming = summary({ assetId: 's-6', status: 'STREAMING', lastKnownPosition: { latitude: 1, longitude: 2 } });
      const api = stubApi({
        listAssets: vi.fn().mockResolvedValue([streaming]),
        getAsset: vi.fn().mockResolvedValue(
          details({ status: 'STREAMING' }, { recentUsages: [{ usageId: 'u-6', startedAt: 't0', sampleCount: 1 }] }),
        ),
      });
      const { live } = create(api);
      await flush();
      await flush();
      expect(live.trackTelemetry).toHaveBeenCalledWith('s-6');
      expect(live.untrackTelemetry).not.toHaveBeenCalled();

      TestBed.resetTestingModule(); // destroys the store — runs its `DestroyRef.onDestroy` teardown

      expect(live.untrackTelemetry).toHaveBeenCalledTimes(1);
      expect(live.untrackTelemetry).toHaveBeenCalledWith('s-6');
    });

    it('fetches the telemetry backfill exactly once per subscribe, not once per refresh', async () => {
      const streaming = summary({ assetId: 's-7', status: 'STREAMING', lastKnownPosition: { latitude: 1, longitude: 2 } });
      const api = stubApi({
        listAssets: vi.fn().mockResolvedValue([streaming]), // same streaming asset, every refresh
        getAsset: vi.fn().mockResolvedValue(
          details({ status: 'STREAMING' }, { recentUsages: [{ usageId: 'u-7', startedAt: 't0', sampleCount: 1 }] }),
        ),
      });
      const { store } = create(api);
      await flush();
      await flush();
      expect(api.usageTelemetry).toHaveBeenCalledTimes(1);

      await store.refresh();
      await flush();
      await store.refresh();
      await flush();

      // Unlike the old 2s repeating poll, staying continuously streaming across further refreshes
      // must not re-fetch — the tracker was never torn down, so it never resubscribes/re-backfills.
      expect(api.usageTelemetry).toHaveBeenCalledTimes(1);
    });

    it('merges the backfill and live samples for the same asset without loss or duplication (L7c ordering hazard)', async () => {
      const streaming = summary({ assetId: 's-9', status: 'STREAMING', lastKnownPosition: { latitude: 1, longitude: 2 } });
      const backfillSample: TelemetrySample = { deviceId: 'dev-0', at: '2026-07-22T00:00:00Z', latitude: 9, longitude: 8 };
      const liveSample: TelemetrySample = { deviceId: 'dev-0', at: '2026-07-22T00:00:02Z', latitude: 10, longitude: 11 };
      const api = stubApi({
        listAssets: vi.fn().mockResolvedValue([streaming]),
        getAsset: vi.fn().mockResolvedValue(
          details({ status: 'STREAMING' }, { recentUsages: [{ usageId: 'u-9', startedAt: 't0', sampleCount: 1 }] }),
        ),
        usageTelemetry: vi.fn().mockResolvedValue([backfillSample]),
      });

      const { store, live } = create(api);
      // A live sample lands *before* the backfill fetch has resolved — trackTelemetry is called
      // synchronously in `startTracker`, ahead of the `getAsset`/`usageTelemetry` awaits.
      live.pushSamples('s-9', [liveSample]);
      await flush();
      await flush();
      await flush();

      const marker = store.markers().find((m) => m.assetId === 's-9');
      expect(marker?.trail).toEqual([
        { latitude: 9, longitude: 8 },
        { latitude: 10, longitude: 11 },
      ]);
      expect(marker?.position).toEqual({ latitude: 10, longitude: 11, altitudeMeters: undefined }); // latest sample wins
    });
  });
});
