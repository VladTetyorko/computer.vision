import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { TelemetryStore } from './telemetry-store';
import { VisionApi } from './api/vision-api';
import type { AssetDetails, AssetSummary, TelemetrySample } from './api/models';

/** Lets the fire-and-forget promise chain inside `track()` settle before asserting. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
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

function inject(api: ReturnType<typeof stubApi>): TelemetryStore {
  TestBed.configureTestingModule({ providers: [TelemetryStore, { provide: VisionApi, useValue: api }] });
  return TestBed.inject(TelemetryStore);
}

describe('TelemetryStore', () => {
  it('resolves the owning asset, finds its open usage, and polls its telemetry', async () => {
    const asset: AssetDetails = {
      ...summaryFor('a-1'),
      devices: [{ id: 'dev-1' } as never],
      recentUsages: [{ usageId: 'u-1', startedAt: '2026-07-22T00:00:00Z', sampleCount: 1 }],
    };
    const sample: TelemetrySample = { at: '2026-07-22T00:00:01Z', latitude: 10, longitude: 20 };
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

  it('reset() clears samples and stops polling', async () => {
    const asset: AssetDetails = {
      ...summaryFor('a-4'),
      devices: [{ id: 'dev-4' } as never],
      recentUsages: [{ usageId: 'u-4', startedAt: '2026-07-22T00:00:00Z', sampleCount: 1 }],
    };
    const sample: TelemetrySample = { at: '2026-07-22T00:00:01Z', latitude: 1, longitude: 2 };
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
});
