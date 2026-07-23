import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { FleetMapStore } from './map-store';
import { VisionApi } from '../../core/api/vision-api';
import type { AssetDetails, AssetSummary, Device, TelemetrySample } from '../../core/api/models';

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

function create(api: ReturnType<typeof stubApi>): FleetMapStore {
  TestBed.configureTestingModule({ providers: [FleetMapStore, { provide: VisionApi, useValue: api }] });
  return TestBed.inject(FleetMapStore);
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

    const store = create(api);
    await flush();
    await flush();

    expect(store.buckets().streaming.map((a) => a.assetId)).toEqual(['s-1']);
    expect(store.buckets().offline.map((a) => a.assetId)).toEqual(['o-1']);
    expect(store.buckets().noPosition.map((a) => a.assetId)).toEqual(['n-1']);
    expect(store.markers().map((m) => m.assetId).sort()).toEqual(['o-1', 's-1']);
  });

  it('starts a telemetry tracker for a streaming asset with an open usage', async () => {
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

    const store = create(api);
    await flush();
    await flush();
    await flush();

    expect(api.getAsset).toHaveBeenCalledWith('s-2');
    expect(api.usageTelemetry).toHaveBeenCalledWith('u-1', 200);
    const marker = store.markers().find((m) => m.assetId === 's-2');
    expect(marker?.live).toBe(true);
    expect(marker?.position).toEqual({ latitude: 9, longitude: 8, altitudeMeters: undefined });
    expect(marker?.headingDegrees).toBe(45);
  });

  it('never polls telemetry for an asset with no open usage', async () => {
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

    const store = create(api);
    await flush();
    await flush();

    expect(api.usageTelemetry).not.toHaveBeenCalled();
    const marker = store.markers().find((m) => m.assetId === 's-3');
    expect(marker?.live).toBe(true);
    expect(marker?.position).toEqual({ latitude: 1, longitude: 2 }); // falls back to lastKnownPosition
  });

  it('tears down a tracker once its asset is no longer streaming, on the next refresh', async () => {
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

    const store = create(api);
    await flush();
    await flush();
    await flush();
    expect(store.markers().find((m) => m.assetId === 's-4')?.live).toBe(true);

    await store.refresh();
    await flush();

    const marker = store.markers().find((m) => m.assetId === 's-4');
    expect(marker?.live).toBe(false);
    expect(marker?.position).toEqual({ latitude: 1, longitude: 2 });
  });

  it('silently degrades when the asset poll fails, rather than throwing', async () => {
    const api = stubApi({ listAssets: vi.fn().mockRejectedValue(new Error('network down')) });

    const store = create(api);
    await flush();

    expect(store.assets()).toEqual([]);
    expect(store.markers()).toEqual([]);
  });

  it('resolveWatchDevice returns the asset VIDEO device', async () => {
    const video = device({ id: 'dev-v', capabilities: ['VIDEO'] });
    const api = stubApi({ getAsset: vi.fn().mockResolvedValue(details({}, { devices: [video] })) });

    const store = create(api);
    await expect(store.resolveWatchDevice('a-1')).resolves.toBe(video);
  });

  it('resolveWatchDevice resolves to undefined on failure', async () => {
    const api = stubApi({ getAsset: vi.fn().mockRejectedValue(new Error('gone')) });

    const store = create(api);
    await expect(store.resolveWatchDevice('a-1')).resolves.toBeUndefined();
  });
});
