import { TestBed } from '@angular/core/testing';
import { Store } from '@ngrx/store';
import { describe, expect, it, vi } from 'vitest';
import type { AssetDetails, AssetSummary, TelemetrySample } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { LiveSocketActions } from '../live/state/live.actions';
import { PollScheduler } from '../poll-scheduler';
import { provideAppState } from '../state/app-state';
import { TelemetryFacade } from './telemetry-facade';

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
  const api = {
    listAssets: vi.fn().mockResolvedValue([]),
    getAsset: vi.fn().mockResolvedValue(undefined),
    usageTelemetry: vi.fn().mockResolvedValue([]),
    ...apiOverrides,
  };
  TestBed.configureTestingModule({
    providers: [
      provideAppState(),
      TelemetryFacade,
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: scheduler },
    ],
  });
  return { facade: TestBed.inject(TelemetryFacade), api, scheduler, store: TestBed.inject(Store) };
}

describe('TelemetryFacade', () => {
  it('has no telemetry before track() is ever called', () => {
    const { facade } = setup();
    expect(facade.hasTelemetry()).toBe(false);
    expect(facade.samples()).toEqual([]);
  });

  it('resolves the owning asset, finds its open usage, and polls its telemetry', async () => {
    const sample: TelemetrySample = { deviceId: 'dev-1', at: '2026-07-22T00:00:01Z', latitude: 10, longitude: 20 };
    const { facade, api } = setup({
      listAssets: vi.fn().mockResolvedValue([summaryFor('a-1')]),
      getAsset: vi.fn().mockResolvedValue(assetWithOpenUsage('a-1', 'dev-1', 'u-1')),
      usageTelemetry: vi.fn().mockResolvedValue([sample]),
    });

    facade.track('dev-1');
    await flush();
    await flush();

    expect(api.getAsset).toHaveBeenCalledWith('a-1');
    expect(facade.hasTelemetry()).toBe(true);
    expect(facade.latest()).toEqual(sample);
    expect(facade.trail()).toEqual([{ latitude: 10, longitude: 20, altitudeMeters: undefined }]);
  });

  it('given an assetId, resolves via one getAsset() call — no listAssets() at all', async () => {
    const { facade, api } = setup({ getAsset: vi.fn().mockResolvedValue(assetWithOpenUsage('a-5', 'dev-5', 'u-5')) });

    facade.track('dev-5', 'a-5');
    await flush();
    await flush();

    expect(api.getAsset).toHaveBeenCalledExactlyOnceWith('a-5');
    expect(api.listAssets).not.toHaveBeenCalled();
  });

  it('never polls a device whose asset has no open usage', async () => {
    const { facade, api } = setup({
      getAsset: vi.fn().mockResolvedValue({ ...summaryFor('a-2'), devices: [], recentUsages: [] }),
    });

    facade.track('dev-2', 'a-2');
    await flush();

    expect(api.usageTelemetry).not.toHaveBeenCalled();
    expect(facade.hasTelemetry()).toBe(false);
  });

  it('silently degrades when the asset lookup fails, rather than throwing', async () => {
    const { facade } = setup({ listAssets: vi.fn().mockRejectedValue(new Error('network down')) });

    facade.track('dev-3');
    await flush();

    expect(facade.hasTelemetry()).toBe(false);
    expect(facade.samples()).toEqual([]);
  });

  it('re-entering track() with the same (deviceId, assetId) is a no-op', async () => {
    const { facade, api } = setup({ getAsset: vi.fn().mockResolvedValue(assetWithOpenUsage('a-7', 'dev-7', 'u-7')) });

    for (let i = 0; i < 5; i++) {
      facade.track('dev-7', 'a-7');
    }
    await flush();
    await flush();

    expect(api.getAsset).toHaveBeenCalledOnce();
  });

  it('reset() clears samples and stops polling', async () => {
    const sample: TelemetrySample = { deviceId: 'dev-4', at: '2026-07-22T00:00:01Z', latitude: 1, longitude: 2 };
    const { facade, scheduler } = setup({
      getAsset: vi.fn().mockResolvedValue(assetWithOpenUsage('a-4', 'dev-4', 'u-4')),
      usageTelemetry: vi.fn().mockResolvedValue([sample]),
    });

    facade.track('dev-4', 'a-4');
    await flush();
    await flush();
    expect(facade.hasTelemetry()).toBe(true);
    const poll = scheduler.lastFor(2_000);

    facade.reset();

    expect(facade.hasTelemetry()).toBe(false);
    expect(facade.samples()).toEqual([]);
    expect(poll?.stop).toHaveBeenCalledOnce();
  });

  it('merges the one-time backfill with live arrivals once the connection is open', async () => {
    const backfill: TelemetrySample = { deviceId: 'dev-9', at: '2026-07-24T00:00:00Z', latitude: 1, longitude: 1 };
    const { facade, store } = setup({
      getAsset: vi.fn().mockResolvedValue(assetWithOpenUsage('a-9', 'dev-9', 'u-9')),
      usageTelemetry: vi.fn().mockResolvedValue([backfill]),
    });
    store.dispatch(LiveSocketActions.opened());

    facade.track('dev-9', 'a-9');
    await flush();
    await flush();

    const pushed: TelemetrySample = { deviceId: 'dev-9', at: '2026-07-24T00:00:01Z', latitude: 2, longitude: 2 };
    store.dispatch(LiveSocketActions.envelopeReceived({ envelope: { seq: 1, assetId: 'a-9', type: 'telemetry', payload: [pushed] } }));

    expect(facade.samples()).toEqual([backfill, pushed]);
  });

  it('re-tracking a different assetId releases the old live subscription and subscribes the new one', async () => {
    const getAsset = vi
      .fn()
      .mockResolvedValueOnce(assetWithOpenUsage('a-15', 'dev-15', 'u-15'))
      .mockResolvedValueOnce(assetWithOpenUsage('a-16', 'dev-16', 'u-16'));
    const { facade, store } = setup({ getAsset });
    store.dispatch(LiveSocketActions.opened());
    const dispatchSpy = vi.spyOn(store, 'dispatch');

    facade.track('dev-15', 'a-15');
    await flush();
    await flush();
    facade.track('dev-16', 'a-16');
    await flush();
    await flush();

    expect(dispatchSpy).toHaveBeenCalledWith(expect.objectContaining({ type: '[Live Page] Telemetry Untracked', assetId: 'a-15' }));
    expect(dispatchSpy).toHaveBeenCalledWith(expect.objectContaining({ type: '[Live Page] Telemetry Tracked', assetId: 'a-16' }));
  });
});
