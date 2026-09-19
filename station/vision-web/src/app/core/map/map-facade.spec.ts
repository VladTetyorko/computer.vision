import { Injector } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Actions } from '@ngrx/effects';
import type { Action } from '@ngrx/store';
import { Store } from '@ngrx/store';
import { describe, expect, it, vi } from 'vitest';
import type { AssetDetails, AssetSummary, Device, TelemetrySample } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { LiveFacade } from '../live/live-facade';
import { LivePageActions, LiveSocketActions } from '../live/state/live.actions';
import { PollScheduler } from '../poll-scheduler';
import { provideAppState } from '../state/app-state';
import { provideMapState } from './state/map.providers';
import { MapFacade } from './map-facade';

/**
 * `MapFacade` end to end — replaces `map-store.spec.ts` case for case
 * (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N6). Unlike `marks-facade.spec.ts`/
 * `drawings-facade.spec.ts`, `MapFacade` *does* inject the real `LiveFacade` directly (for
 * `markers`' telemetry merge — facade-to-facade composition, not effect-level injection; see
 * `map-facade.ts`'s own class doc comment), so constructing it always runs `LiveFacade`'s own
 * constructor too, exactly like the real app. Under jsdom that constructor's own `reconnect()` call
 * always settles to `'closed'` (`LiveGateway.isAvailable()` is `false` with no `EventSource`) — see
 * `create`'s own comment for why that is harmless for every test that wants the default (`'closed'`)
 * transport, and why the "already live at construction" tests must inject `LiveFacade` and let that
 * settle *before* setting `'open'` and only then constructing `MapFacade` (mirrors
 * `core/live/poll-rate.spec.ts`'s identical fix, discovered while building that file this same wave).
 */

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

function stubApi(overrides: Partial<Record<'listAssets' | 'getAsset' | 'usageTelemetry', ReturnType<typeof vi.fn>>> = {}) {
  return {
    listAssets: vi.fn().mockResolvedValue([]),
    getAsset: vi.fn().mockResolvedValue(undefined),
    usageTelemetry: vi.fn().mockResolvedValue([]),
    ...overrides,
  };
}

/** Mirrors `map-store.spec.ts`'s own `stubScheduler` — captures every registration, keyed by cadence,
 *  so a test can assert on/off per-poll (the 5s asset poll, the 1s clock, the 2s telemetry poll)
 *  without real timers, and can manually `.run()` one tick to simulate a fallback-poll fire. */
function stubScheduler() {
  const calls: { periodMs: number; stop: ReturnType<typeof vi.fn>; run: () => void | Promise<void> }[] = [];
  const schedule = vi.fn((periodMs: number, callback: () => void | Promise<void>) => {
    const stop = vi.fn();
    calls.push({ periodMs, stop, run: callback });
    return stop;
  });
  return { schedule, calls, forPeriod: (periodMs: number) => calls.filter((call) => call.periodMs === periodMs) };
}

function configure(api: ReturnType<typeof stubApi>, scheduler: ReturnType<typeof stubScheduler>) {
  TestBed.configureTestingModule({
    providers: [
      provideAppState(), provideMapState(),
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: scheduler },
    ],
  });
}

/**
 * Constructs `MapFacade` in a **child** injector rather than via `TestBed.inject` directly, so a test
 * can `destroy()` it alone — running its `DestroyRef.onDestroy` teardown (`Map Page Released`,
 * untracking every tracker) — the same way a real page's component injector tears down on navigation,
 * without also tearing down the module-level `Store`/`Actions`/`effects` the way
 * `TestBed.resetTestingModule()` does. That distinction is load-bearing here (unlike in
 * `map-store.spec.ts`, whose `FleetMapStore` talked to a stubbed, DI-independent `LiveFacade` test
 * double): `resetTestingModule()` was tried first and found to tear down the `Actions` stream ahead of
 * `MapFacade`'s own destroy hook, silently losing the very dispatch this file's teardown tests assert on.
 */
function createScopedFacade(parent: Injector): { facade: MapFacade; destroy: () => void } {
  const child = Injector.create({ providers: [MapFacade], parent });
  return { facade: child.get(MapFacade), destroy: () => child.destroy() };
}

/**
 * Records every action that reaches the `Actions` stream — used in place of a `store.dispatch` spy,
 * since `@ngrx/effects`' internal merge-and-redispatch path does not necessarily call through the
 * exact `dispatch` method reference a `vi.spyOn(store, 'dispatch')` patches (confirmed empirically:
 * a dispatch spy set up before `MapFacade` construction saw the test's own direct `store.dispatch(...)`
 * calls, but never the `LivePageActions.telemetryTracked`/`telemetryUntracked` actions the effects
 * themselves dispatch). `Actions` is fed by the same underlying action stream regardless of which
 * internal path produced the emission, so it is the reliable place to count from.
 */
function captureDispatched(actions$: Actions): Action[] {
  const seen: Action[] = [];
  actions$.subscribe((action) => seen.push(action));
  return seen;
}

/**
 * The common case: `LiveFacade`'s own jsdom-forced `'closed'` settle *is* the desired default
 * transport, so no special ordering is needed — constructing `MapFacade` (which injects `LiveFacade`
 * as a field, ahead of its own constructor body dispatching `Map Page Activated`) triggers both in
 * the right order for free.
 */
function create(api: ReturnType<typeof stubApi>, scheduler = stubScheduler()) {
  configure(api, scheduler);
  const store = TestBed.inject(Store);
  const dispatch = captureDispatched(TestBed.inject(Actions));
  const { facade, destroy } = createScopedFacade(TestBed.inject(Injector));
  return { facade, store, scheduler, dispatch, destroy };
}

/** For "already live at construction" cases: forces `LiveFacade`'s own settle first, then sets the
 *  real desired transport, and only *then* constructs `MapFacade` — see file doc comment. */
async function createLiveOpen(api: ReturnType<typeof stubApi>, scheduler = stubScheduler()) {
  configure(api, scheduler);
  const store = TestBed.inject(Store);
  TestBed.inject(LiveFacade);
  await flush();
  store.dispatch(LiveSocketActions.opened());
  const dispatch = captureDispatched(TestBed.inject(Actions));
  const { facade, destroy } = createScopedFacade(TestBed.inject(Injector));
  return { facade, store, scheduler, dispatch, destroy };
}

function trackedCount(dispatch: readonly Action[], assetId: string): number {
  return dispatch.filter((action) => {
    const a = action as Action & { assetId?: string };
    return a.type === LivePageActions.telemetryTracked.type && a.assetId === assetId;
  }).length;
}

function untrackedCount(dispatch: readonly Action[], assetId: string): number {
  return dispatch.filter((action) => {
    const a = action as Action & { assetId?: string };
    return a.type === LivePageActions.telemetryUntracked.type && a.assetId === assetId;
  }).length;
}

describe('MapFacade', () => {
  it('polls assets on construction and derives buckets/markers', async () => {
    const streaming = summary({ assetId: 's-1', status: 'STREAMING', lastKnownPosition: { latitude: 1, longitude: 2 } });
    const offline = summary({ assetId: 'o-1', status: 'OFFLINE', lastKnownPosition: { latitude: 3, longitude: 4 } });
    const noPosition = summary({ assetId: 'n-1', status: 'OFFLINE' });
    const api = stubApi({
      listAssets: vi.fn().mockResolvedValue([streaming, offline, noPosition]),
      getAsset: vi.fn().mockResolvedValue(details({ status: 'STREAMING' })),
    });

    const { facade } = create(api);
    await flush();
    await flush();

    expect(facade.buckets().streaming.map((a) => a.assetId)).toEqual(['s-1']);
    expect(facade.buckets().offline.map((a) => a.assetId)).toEqual(['o-1']);
    expect(facade.buckets().noPosition.map((a) => a.assetId)).toEqual(['n-1']);
    expect(facade.markers().map((m) => m.assetId).sort()).toEqual(['o-1', 's-1']);
  });

  it('starts a telemetry tracker (subscribe + one backfill) for a streaming asset with an open usage', async () => {
    const streaming = summary({ assetId: 's-2', status: 'STREAMING', lastKnownPosition: { latitude: 1, longitude: 2 } });
    const sample: TelemetrySample = { deviceId: 'dev-0', at: '2026-07-22T00:00:00Z', latitude: 9, longitude: 8, headingDegrees: 45 };
    const api = stubApi({
      listAssets: vi.fn().mockResolvedValue([streaming]),
      getAsset: vi.fn().mockResolvedValue(details({ status: 'STREAMING' }, { recentUsages: [{ usageId: 'u-1', startedAt: 't0', sampleCount: 1 }] })),
      usageTelemetry: vi.fn().mockResolvedValue([sample]),
    });

    const { facade, dispatch } = create(api);
    await flush();
    await flush();
    await flush();

    expect(api.getAsset).toHaveBeenCalledWith('s-2');
    expect(trackedCount(dispatch, 's-2')).toBe(1);
    expect(api.usageTelemetry).toHaveBeenCalledWith('u-1', 200);
    const marker = facade.markers().find((m) => m.assetId === 's-2');
    expect(marker?.live).toBe(true);
    expect(marker?.position).toEqual({ latitude: 9, longitude: 8, altitudeMeters: undefined });
    expect(marker?.headingDegrees).toBe(45);
  });

  it('subscribes a streaming asset with no open usage, but never fetches a backfill for it', async () => {
    const streaming = summary({ assetId: 's-3', status: 'STREAMING', lastKnownPosition: { latitude: 1, longitude: 2 } });
    const api = stubApi({
      listAssets: vi.fn().mockResolvedValue([streaming]),
      getAsset: vi.fn().mockResolvedValue(
        details({ status: 'STREAMING' }, { recentUsages: [{ usageId: 'u-2', startedAt: 't0', endedAt: 't1', sampleCount: 3 }] }),
      ),
    });

    const { facade, dispatch } = create(api);
    await flush();
    await flush();

    expect(trackedCount(dispatch, 's-3')).toBe(1); // subscribed off the streaming bucket alone
    expect(api.usageTelemetry).not.toHaveBeenCalled(); // no open usage to backfill from
    const marker = facade.markers().find((m) => m.assetId === 's-3');
    expect(marker?.live).toBe(true);
    expect(marker?.position).toEqual({ latitude: 1, longitude: 2 }); // falls back to lastKnownPosition
  });

  it('tears down and untracks a tracker once its asset is no longer streaming, on the next refresh', async () => {
    const streaming = summary({ assetId: 's-4', status: 'STREAMING', lastKnownPosition: { latitude: 1, longitude: 2 } });
    const nowOffline = { ...streaming, status: 'OFFLINE' as const };
    const sample: TelemetrySample = { deviceId: 'dev-0', at: '2026-07-22T00:00:00Z', latitude: 9, longitude: 8 };
    const listAssets = vi.fn().mockResolvedValueOnce([streaming]).mockResolvedValue([nowOffline]);
    const api = stubApi({
      listAssets,
      getAsset: vi.fn().mockResolvedValue(details({ status: 'STREAMING' }, { recentUsages: [{ usageId: 'u-4', startedAt: 't0', sampleCount: 1 }] })),
      usageTelemetry: vi.fn().mockResolvedValue([sample]),
    });

    const { facade, dispatch } = create(api);
    await flush();
    await flush();
    await flush();
    expect(facade.markers().find((m) => m.assetId === 's-4')?.live).toBe(true);
    expect(trackedCount(dispatch, 's-4')).toBe(1);
    expect(untrackedCount(dispatch, 's-4')).toBe(0);

    await facade.refresh(); // now offline
    await flush();

    const marker = facade.markers().find((m) => m.assetId === 's-4');
    expect(marker?.live).toBe(false);
    expect(marker?.position).toEqual({ latitude: 1, longitude: 2 });
    expect(untrackedCount(dispatch, 's-4')).toBe(1);
  });

  it('silently degrades when the asset poll fails, rather than throwing', async () => {
    const api = stubApi({ listAssets: vi.fn().mockRejectedValue(new Error('network down')) });

    const { facade } = create(api);
    await flush();

    expect(facade.assets()).toEqual([]);
    expect(facade.markers()).toEqual([]);
  });

  it('resolveWatchDevice returns the asset VIDEO device', async () => {
    const video = device({ id: 'dev-v', capabilities: ['VIDEO'] });
    const api = stubApi({ getAsset: vi.fn().mockResolvedValue(details({}, { devices: [video] })) });

    const { facade } = create(api);
    await expect(facade.resolveWatchDevice('a-1')).resolves.toBe(video);
  });

  it('resolveWatchDevice resolves to undefined on failure', async () => {
    const api = stubApi({ getAsset: vi.fn().mockRejectedValue(new Error('gone')) });

    const { facade } = create(api);
    await expect(facade.resolveWatchDevice('a-1')).resolves.toBeUndefined();
  });

  describe('live gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1, wave L7a)', () => {
    /**
     * The plan's own frozen acceptance criterion (§5): a poll that stops must still reconcile on
     * reconnect. With the facade active and live open, driving `connectionState` through
     * `open → closed → open` must issue **exactly one** REST refresh on (re-)entering `open`, and
     * **zero** REST requests for as long as `open` persists.
     */
    it('reconciles exactly once on reconnect, and stays silent for as long as live holds', async () => {
      const api = stubApi({ listAssets: vi.fn().mockResolvedValue([]) });
      const { store } = create(api);
      await flush();

      // Reach a known "live open" baseline first — this transition's own reconcile fetch is not
      // what's under test.
      store.dispatch(LiveSocketActions.opened());
      await flush();

      store.dispatch(LiveSocketActions.closed());
      await flush();
      // Falling back to polling refreshes immediately too (D1's own `false | true(live) -> refresh
      // once, then start poll` row) — a separate, legitimate call, also not the segment under test.
      api.listAssets.mockClear();

      store.dispatch(LiveSocketActions.opened());
      await flush();

      expect(api.listAssets).toHaveBeenCalledTimes(1); // exactly one refresh, entering 'open'

      // Zero further REST requests while 'open' persists — a same-mode re-write must be a no-op.
      store.dispatch(LiveSocketActions.opened());
      await flush();
      expect(api.listAssets).toHaveBeenCalledTimes(1);
    });

    it('never schedules the 5s asset poll while live is already open at construction', async () => {
      const api = stubApi({ listAssets: vi.fn().mockResolvedValue([]) });
      const scheduler = stubScheduler();
      const { store } = await createLiveOpen(api, scheduler);
      await flush();

      expect(api.listAssets).toHaveBeenCalledTimes(1); // the one-time reconcile — see class doc
      expect(scheduler.forPeriod(5_000)).toHaveLength(0); // never scheduled — live was already open
      expect(scheduler.forPeriod(1_000)).toHaveLength(1); // the local clock still runs regardless (L7d)

      store.dispatch(LiveSocketActions.opened()); // same-value re-write, must stay a no-op
      await flush();
      expect(scheduler.forPeriod(5_000)).toHaveLength(0);
      expect(api.listAssets).toHaveBeenCalledTimes(1);
    });

    it('reads assets from the live fleet snapshot while live is open, with no REST call, and reconciles trackers from it', async () => {
      const api = stubApi();
      const { facade, store, dispatch } = create(api);
      await flush();

      store.dispatch(LiveSocketActions.opened());
      await flush();
      api.listAssets.mockClear();

      const streaming = summary({ assetId: 's-8', status: 'STREAMING', lastKnownPosition: { latitude: 5, longitude: 6 } });
      store.dispatch(LiveSocketActions.envelopeReceived({ envelope: { seq: 1, type: 'fleet', payload: [streaming] } }));
      await flush();

      expect(facade.assets()).toEqual([streaming]);
      expect(api.listAssets).not.toHaveBeenCalled(); // the fleet snapshot arrived over SSE, no REST needed
      expect(trackedCount(dispatch, 's-8')).toBe(1); // reconciled off the live snapshot too
    });

    it('ignores a fleet snapshot while nobody is viewing the map (activeConsumers === 0)', async () => {
      const api = stubApi();
      const { facade, store, destroy } = create(api);
      await flush();
      store.dispatch(LiveSocketActions.opened());
      await flush();

      destroy(); // tears down just the facade — `Map Page Released` drops activeConsumers to 0

      const streaming = summary({ assetId: 's-ignored', status: 'STREAMING', lastKnownPosition: { latitude: 5, longitude: 6 } });
      store.dispatch(LiveSocketActions.envelopeReceived({ envelope: { seq: 2, type: 'fleet', payload: [streaming] } }));
      await flush();

      expect(facade.assets()).not.toEqual([streaming]); // the last-read signal is stale/torn-down, never silently updated
    });
  });

  describe('telemetry tracking (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §5 L7b/L7c)', () => {
    it('balances trackTelemetry/untrackTelemetry across a streaming -> stopped -> streaming transition', async () => {
      const streaming = summary({ assetId: 's-5', status: 'STREAMING', lastKnownPosition: { latitude: 1, longitude: 2 } });
      const offline = { ...streaming, status: 'OFFLINE' as const };
      const api = stubApi({
        listAssets: vi.fn().mockResolvedValueOnce([streaming]).mockResolvedValueOnce([offline]).mockResolvedValueOnce([streaming]),
        getAsset: vi.fn().mockResolvedValue(details({ status: 'STREAMING' }, { recentUsages: [{ usageId: 'u-5', startedAt: 't0', sampleCount: 1 }] })),
      });

      const { facade, dispatch } = create(api);
      await flush();
      await flush();
      expect(trackedCount(dispatch, 's-5')).toBe(1);
      expect(untrackedCount(dispatch, 's-5')).toBe(0);

      await facade.refresh(); // now offline
      await flush();
      expect(untrackedCount(dispatch, 's-5')).toBe(1);
      expect(trackedCount(dispatch, 's-5')).toBe(1); // unchanged

      await facade.refresh(); // streaming again
      await flush();
      await flush();
      expect(trackedCount(dispatch, 's-5')).toBe(2);
      expect(untrackedCount(dispatch, 's-5')).toBe(1); // unchanged
    });

    it('untracks every remaining tracked asset on facade teardown', async () => {
      const streaming = summary({ assetId: 's-6', status: 'STREAMING', lastKnownPosition: { latitude: 1, longitude: 2 } });
      const api = stubApi({
        listAssets: vi.fn().mockResolvedValue([streaming]),
        getAsset: vi.fn().mockResolvedValue(details({ status: 'STREAMING' }, { recentUsages: [{ usageId: 'u-6', startedAt: 't0', sampleCount: 1 }] })),
      });
      const { dispatch, destroy } = create(api);
      await flush();
      await flush();
      expect(trackedCount(dispatch, 's-6')).toBe(1);
      expect(untrackedCount(dispatch, 's-6')).toBe(0);

      destroy(); // runs the facade's own `DestroyRef.onDestroy` teardown, `Store`/`Actions` untouched

      expect(untrackedCount(dispatch, 's-6')).toBe(1);
    });

    it('fetches the telemetry backfill exactly once per subscribe, not once per refresh', async () => {
      const streaming = summary({ assetId: 's-7', status: 'STREAMING', lastKnownPosition: { latitude: 1, longitude: 2 } });
      const api = stubApi({
        listAssets: vi.fn().mockResolvedValue([streaming]), // same streaming asset, every refresh
        getAsset: vi.fn().mockResolvedValue(details({ status: 'STREAMING' }, { recentUsages: [{ usageId: 'u-7', startedAt: 't0', sampleCount: 1 }] })),
      });
      const { facade } = create(api);
      await flush();
      await flush();
      expect(api.usageTelemetry).toHaveBeenCalledTimes(1);

      await facade.refresh();
      await flush();
      await facade.refresh();
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
        getAsset: vi.fn().mockResolvedValue(details({ status: 'STREAMING' }, { recentUsages: [{ usageId: 'u-9', startedAt: 't0', sampleCount: 1 }] })),
        usageTelemetry: vi.fn().mockResolvedValue([backfillSample]),
      });

      const { facade, store } = create(api);
      // A live sample lands *before* the backfill fetch has resolved — `telemetryTracked` is
      // dispatched synchronously in `runTracker$`, ahead of the `getAsset`/`usageTelemetry` awaits.
      store.dispatch(LiveSocketActions.envelopeReceived({ envelope: { seq: 1, assetId: 's-9', type: 'telemetry', payload: [liveSample] } }));
      await flush();
      await flush();
      await flush();

      const marker = facade.markers().find((m) => m.assetId === 's-9');
      expect(marker?.trail).toEqual([
        { latitude: 9, longitude: 8 },
        { latitude: 10, longitude: 11 },
      ]);
      expect(marker?.position).toEqual({ latitude: 10, longitude: 11, altitudeMeters: undefined }); // latest sample wins
    });
  });

  /**
   * The per-streaming-asset telemetry fallback (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3
   * D1). Wave L7b first deleted this poll outright instead of gating it — the plan's own L7b row
   * said "retire", where its L7a neighbour said "gate per D1; the REST call stays as the not-open
   * fallback" — and the §7 acceptance measurement (`core/live/poll-rate.spec.ts`) caught what that
   * cost: with SSE down, a marker froze at whatever position its one-time backfill captured while
   * still reporting `live: true`.
   */
  describe('telemetry fallback poll (D1, live axis)', () => {
    const openUsage = { usageId: 'u-1', startedAt: 't0', sampleCount: 1 };

    function streamingApi(samples: readonly TelemetrySample[]) {
      return stubApi({
        listAssets: vi.fn().mockResolvedValue([summary({ assetId: 's-1', status: 'STREAMING', lastKnownPosition: { latitude: 1, longitude: 2 } })]),
        getAsset: vi.fn().mockResolvedValue(details({ status: 'STREAMING' }, { recentUsages: [openUsage] })),
        usageTelemetry: vi.fn().mockResolvedValue(samples),
      });
    }

    it('registers a 2s telemetry poll per tracked asset while live is unavailable', async () => {
      const scheduler = stubScheduler();
      create(streamingApi([]), scheduler);
      await flush();
      await flush();
      await flush();

      expect(scheduler.forPeriod(2_000)).toHaveLength(1);
    });

    it('registers no telemetry poll at all while live is open', async () => {
      const scheduler = stubScheduler();
      await createLiveOpen(streamingApi([]), scheduler);
      await flush();
      await flush();
      await flush();

      expect(scheduler.forPeriod(2_000)).toHaveLength(0);
    });

    it('stops the telemetry poll when live comes back, and starts one when live drops', async () => {
      const scheduler = stubScheduler();
      const { store } = create(streamingApi([]), scheduler);
      await flush();
      await flush();
      await flush();
      const [degraded] = scheduler.forPeriod(2_000);
      expect(degraded).toBeDefined();

      store.dispatch(LiveSocketActions.opened());
      await flush();
      expect(degraded.stop).toHaveBeenCalled();
      expect(scheduler.forPeriod(2_000)).toHaveLength(1); // still just the one, now stopped

      store.dispatch(LiveSocketActions.closed());
      await flush();
      expect(scheduler.forPeriod(2_000)).toHaveLength(2); // a fresh registration for the new outage
    });

    it('keeps the marker current off the fallback poll instead of freezing at the backfill', async () => {
      const backfill: TelemetrySample = { deviceId: 'dev-0', at: '2026-07-22T00:00:00Z', latitude: 9, longitude: 8 };
      const api = streamingApi([backfill]);
      const scheduler = stubScheduler();
      const { facade } = create(api, scheduler);
      await flush();
      await flush();
      await flush();
      expect(facade.markers().find((m) => m.assetId === 's-1')?.position).toEqual({
        latitude: 9,
        longitude: 8,
        altitudeMeters: undefined,
      });

      // The aircraft moves; the next fallback tick must be what tells the map about it.
      const moved: TelemetrySample = { deviceId: 'dev-0', at: '2026-07-22T00:00:02Z', latitude: 11, longitude: 12 };
      api.usageTelemetry.mockResolvedValue([backfill, moved]);
      await scheduler.forPeriod(2_000)[0].run();
      await flush();

      const marker = facade.markers().find((m) => m.assetId === 's-1');
      expect(marker?.position).toEqual({ latitude: 11, longitude: 12, altitudeMeters: undefined });
      expect(marker?.trail).toEqual([
        { latitude: 9, longitude: 8 },
        { latitude: 11, longitude: 12 },
      ]);
    });

    it('stops the fallback poll when the asset stops streaming', async () => {
      const api = streamingApi([]);
      const scheduler = stubScheduler();
      const { facade } = create(api, scheduler);
      await flush();
      await flush();
      await flush();
      const [poll] = scheduler.forPeriod(2_000);

      api.listAssets.mockResolvedValue([summary({ assetId: 's-1', status: 'OFFLINE', lastKnownPosition: { latitude: 1, longitude: 2 } })]);
      await facade.refresh();
      await flush();

      expect(poll.stop).toHaveBeenCalled();
    });
  });
});
