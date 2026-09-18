import { TestBed } from '@angular/core/testing';
import { provideMockActions } from '@ngrx/effects/testing';
import { Store, provideState, provideStore } from '@ngrx/store';
import type { Action } from '@ngrx/store';
import { ReplaySubject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { AssetDetails, AssetSummary, TelemetrySample } from '../../api/models';
import { VisionApi } from '../../api/vision-api';
import { LiveSocketActions } from '../../live/state/live.actions';
import { liveFeature } from '../../live/state/live.reducer';
import { PollScheduler } from '../../poll-scheduler';
import { TelemetryApiActions, TelemetryPageActions } from './telemetry.actions';
import { session$ } from './telemetry.effects';
import { telemetryFeature } from './telemetry.reducer';

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

/** `provideMockActions` and the real `Store` are separate channels: dispatching feeds the reducer
 *  (what `store.select(transportSelectorFor(...))` reads), while `actions.next` feeds the mocked
 *  `Actions` stream (what this effect's own `ofType`/`takeUntil` listen on) — mirrors
 *  `seat.effects.spec.ts#receive`'s identical two-channel drive. */
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

function assetWithOpenUsage(assetId: string, deviceId: string, usageId: string): AssetDetails {
  return {
    ...summaryFor(assetId),
    devices: [{ id: deviceId } as never],
    recentUsages: [{ usageId, startedAt: '2026-07-24T00:00:00Z', sampleCount: 0 }],
  };
}

function setup(apiOverrides: Partial<VisionApi> = {}, scheduler = stubScheduler()) {
  const actions = new ReplaySubject<Action>(1);
  const api = {
    listAssets: vi.fn().mockResolvedValue([]),
    getAsset: vi.fn().mockResolvedValue(undefined),
    usageTelemetry: vi.fn().mockResolvedValue([]),
    ...apiOverrides,
  };
  TestBed.configureTestingModule({
    providers: [
      provideMockActions(() => actions),
      provideStore(),
      provideState(telemetryFeature),
      provideState(liveFeature),
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: scheduler },
    ],
  });
  const store = TestBed.inject(Store);
  return { actions, api, scheduler, store };
}

describe('telemetry effects — session$', () => {
  it('resolves the owning asset via listAssets when no assetId is given, then backfills and polls', async () => {
    const sample: TelemetrySample = { deviceId: 'dev-1', at: '2026-07-22T00:00:01Z', latitude: 10, longitude: 20 };
    const { actions, api, scheduler, store } = setup({
      listAssets: vi.fn().mockResolvedValue([summaryFor('a-1')]),
      getAsset: vi.fn().mockResolvedValue(assetWithOpenUsage('a-1', 'dev-1', 'u-1')),
      usageTelemetry: vi.fn().mockResolvedValue([sample]),
    });
    TestBed.runInInjectionContext(() => session$()).subscribe((a) => store.dispatch(a));

    emit(store, actions, TelemetryPageActions.tracked({ deviceId: 'dev-1', assetId: undefined }));
    await flush();
    await flush();

    expect(api.getAsset).toHaveBeenCalledWith('a-1');
    expect(api.usageTelemetry).toHaveBeenCalledWith('u-1', 200);
    expect(store.selectSignal(telemetryFeature.selectByDeviceId)()['dev-1']?.pollSamples).toEqual([sample]);
    // The ticking timer is still registered right away — only the immediate *fetch* is skipped on the
    // very first poll-vs-live decision (backfill already populated pollSamples for this same tick).
    expect(scheduler.lastFor(2_000)).toBeDefined();
    expect(api.usageTelemetry).toHaveBeenCalledOnce(); // backfill only — no redundant immediate re-poll
  });

  it('dispatches Usage Not Found and never polls when the asset has no open usage', async () => {
    const { actions, api, store } = setup({
      getAsset: vi.fn().mockResolvedValue({ ...summaryFor('a-2'), devices: [], recentUsages: [] }),
    });
    const seen: Action[] = [];
    TestBed.runInInjectionContext(() => session$()).subscribe((a) => {
      seen.push(a);
      store.dispatch(a);
    });

    emit(store, actions, TelemetryPageActions.tracked({ deviceId: 'dev-2', assetId: 'a-2' }));
    await flush();

    expect(api.usageTelemetry).not.toHaveBeenCalled();
    expect(seen).toEqual([TelemetryApiActions.usageNotFound({ deviceId: 'dev-2' })]);
  });

  it('two devices poll independently — one never cancels the other (groupBy isolation)', async () => {
    const { actions, api, scheduler, store } = setup({
      getAsset: vi.fn((assetId: string) =>
        Promise.resolve(assetWithOpenUsage(assetId, `dev-for-${assetId}`, `u-${assetId}`)),
      ),
    });
    TestBed.runInInjectionContext(() => session$()).subscribe((a) => store.dispatch(a));

    emit(store, actions, TelemetryPageActions.tracked({ deviceId: 'dev-a', assetId: 'a-a' }));
    await flush();
    emit(store, actions, TelemetryPageActions.tracked({ deviceId: 'dev-b', assetId: 'a-b' }));
    await flush();

    expect(api.getAsset).toHaveBeenCalledTimes(2);
    // Each device registers its own ticking timer — neither is stopped by the other's Tracked action,
    // which a top-level (non-grouped) switchMap would have done.
    const polls = scheduler.calls.filter((c) => c.periodMs === 2_000);
    expect(polls).toHaveLength(2);
    expect(polls.every((c) => !c.stop.mock.calls.length)).toBe(true);
  });

  it('subscribes live and never registers a poll timer when the connection is already open', async () => {
    const backfill: TelemetrySample = { deviceId: 'dev-9', at: '2026-07-24T00:00:00Z', latitude: 1, longitude: 1 };
    const { actions, scheduler, store } = setup({
      getAsset: vi.fn().mockResolvedValue(assetWithOpenUsage('a-9', 'dev-9', 'u-9')),
      usageTelemetry: vi.fn().mockResolvedValue([backfill]),
    });
    const dispatchSpy = vi.spyOn(store, 'dispatch');
    TestBed.runInInjectionContext(() => session$()).subscribe((a) => store.dispatch(a));

    store.dispatch(LiveSocketActions.opened());
    emit(store, actions, TelemetryPageActions.tracked({ deviceId: 'dev-9', assetId: 'a-9' }));
    await flush();
    await flush();

    expect(dispatchSpy).toHaveBeenCalledWith(expect.objectContaining({ type: '[Live Page] Telemetry Tracked', assetId: 'a-9' }));
    expect(scheduler.lastFor(2_000)).toBeUndefined();
  });

  it('falls back to polling immediately when the connection drops mid-session', async () => {
    const usageTelemetry = vi.fn().mockResolvedValue([]);
    const { actions, scheduler, store } = setup({
      getAsset: vi.fn().mockResolvedValue(assetWithOpenUsage('a-13', 'dev-13', 'u-13')),
      usageTelemetry,
    });
    store.dispatch(LiveSocketActions.opened());
    TestBed.runInInjectionContext(() => session$()).subscribe((a) => store.dispatch(a));

    emit(store, actions, TelemetryPageActions.tracked({ deviceId: 'dev-13', assetId: 'a-13' }));
    await flush();
    await flush();
    expect(scheduler.lastFor(2_000)).toBeUndefined();
    const callsWhileLive = usageTelemetry.mock.calls.length;

    store.dispatch(LiveSocketActions.closed());
    await flush();

    expect(scheduler.lastFor(2_000)).toBeDefined();
    expect(usageTelemetry.mock.calls.length).toBeGreaterThan(callsWhileLive); // fetched fresh immediately, not stale
  });

  it('Reset for the tracked device stops its poll and releases its live subscription', async () => {
    const { actions, scheduler, store } = setup({
      getAsset: vi.fn().mockResolvedValue(assetWithOpenUsage('a-14', 'dev-14', 'u-14')),
    });
    const dispatchSpy = vi.spyOn(store, 'dispatch');
    TestBed.runInInjectionContext(() => session$()).subscribe((a) => store.dispatch(a));

    emit(store, actions, TelemetryPageActions.tracked({ deviceId: 'dev-14', assetId: 'a-14' }));
    await flush();
    await flush();
    const poll = scheduler.lastFor(2_000);

    emit(store, actions, TelemetryPageActions.reset({ deviceId: 'dev-14' }));
    await flush();

    expect(poll?.stop).toHaveBeenCalledOnce();
    expect(dispatchSpy).toHaveBeenCalledWith(expect.objectContaining({ type: '[Live Page] Telemetry Untracked', assetId: 'a-14' }));
  });

  it('a stale in-flight lookup from a superseded track() never overwrites the newer device', async () => {
    let resolveFirst: (() => void) | undefined;
    const getAsset = vi.fn((assetId: string) => {
      if (assetId === 'a-old') {
        return new Promise<AssetDetails>((resolve) => {
          resolveFirst = () => resolve(assetWithOpenUsage('a-old', 'dev-old', 'u-old'));
        });
      }
      return Promise.resolve(assetWithOpenUsage('a-new', 'dev-new', 'u-new'));
    });
    const { actions, store } = setup({ getAsset });
    TestBed.runInInjectionContext(() => session$()).subscribe((a) => store.dispatch(a));

    emit(store, actions, TelemetryPageActions.tracked({ deviceId: 'dev-old', assetId: 'a-old' }));
    await flush();
    emit(store, actions, TelemetryPageActions.reset({ deviceId: 'dev-old' }));
    emit(store, actions, TelemetryPageActions.tracked({ deviceId: 'dev-new', assetId: 'a-new' }));
    await flush();
    await flush();

    resolveFirst?.();
    await flush();
    await flush();

    expect(store.selectSignal(telemetryFeature.selectByDeviceId)()['dev-old']).toBeUndefined();
    expect(store.selectSignal(telemetryFeature.selectByDeviceId)()['dev-new']?.assetId).toBe('a-new');
  });
});
