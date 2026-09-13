import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import { CvInspectorFacade } from './cv-inspector-facade';
import { CvTraceStore } from '../../core/cv-trace/cv-trace-store';
import { FleetStore } from '../../core/fleet/fleet-store';
import { LiveStore } from '../../core/live/live-store';
import { PollScheduler } from '../../core/poll-scheduler';
import { SystemStatusStore } from '../../core/system-status/system-status-store';
import { VisionApi } from '../../core/api/vision-api';
import type { AssetAttention, CvTrace, FleetSummary, FrameLedger } from '../../core/api/models';

/**
 * Proves wave W5.7's "Live subscription" paragraph on `CvInspectorFacade`'s own class doc: picking
 * a stream resolves its owning asset (`VisionApi#fleetSummary()` + `assetIdForStream`) and starts
 * the LIVE `cv-trace:<assetId>` topic through the REAL `CvTraceStore`, not a mock of it — the only
 * way to prove the whole chain (facade → store → `LiveStore`) actually wires together, mirroring
 * `core/map/map-store.spec.ts`'s identical "real store, stub its own leaf deps" convention one
 * layer up. `FleetStore`/`SystemStatusStore`/`VisionApi`/`LiveStore`/`PollScheduler` are the only
 * leaves stubbed; `CvTraceStore` and `CvInspectorFacade` are both the genuine classes.
 */

/** Lets the fire-and-forget promise chain inside `selectStream()`/`trackWithResolvedAsset()` settle. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function attention(overrides: Partial<AssetAttention> = {}): AssetAttention {
  return {
    assetId: 'asset-1',
    displayName: 'Rover 1',
    categoryId: 'rover',
    categoryName: 'Rover',
    lifecycle: 'ACTIVE',
    streaming: true,
    openEventCount: 0,
    ...overrides,
  };
}

function trace(overrides: Partial<CvTrace> = {}): CvTrace {
  return { streamId: 'stream-1', gate: [], frame: [], world: [], ...overrides };
}

function stubApi(
  fleetSummary: ReturnType<typeof vi.fn> = vi.fn().mockResolvedValue({ categories: [], totalAssets: 0, assets: [] } satisfies FleetSummary),
  getCvTrace: ReturnType<typeof vi.fn> = vi.fn().mockResolvedValue(trace()),
) {
  return { fleetSummary, getCvTrace };
}

/** Mirrors `cv-trace-store.spec.ts#stubLiveStore` — the real `CvTraceStore` calls straight through
 *  to these, so asserting on them proves the subscription itself, not just an intent to make one. */
function stubLiveStore() {
  const perAsset = new Map<string, ReturnType<typeof signal<FrameLedger | undefined>>>();
  const cvTraceFor = vi.fn((assetId: string) => {
    let existing = perAsset.get(assetId);
    if (existing === undefined) {
      existing = signal<FrameLedger | undefined>(undefined);
      perAsset.set(assetId, existing);
    }
    return existing;
  });
  return { cvTraceFor, trackCvTrace: vi.fn(), untrackCvTrace: vi.fn() };
}

function stubScheduler() {
  return { schedule: vi.fn(() => vi.fn()) };
}

function stubFleetStore() {
  return { streams: signal([]) };
}

function stubStatusStore() {
  return { status: signal(undefined), refresh: vi.fn() };
}

function create(api: ReturnType<typeof stubApi>, live: ReturnType<typeof stubLiveStore> = stubLiveStore()) {
  TestBed.configureTestingModule({
    providers: [
      CvInspectorFacade,
      CvTraceStore,
      { provide: VisionApi, useValue: api },
      { provide: LiveStore, useValue: live },
      { provide: PollScheduler, useValue: stubScheduler() },
      { provide: FleetStore, useValue: stubFleetStore() },
      { provide: SystemStatusStore, useValue: stubStatusStore() },
    ],
  });
  return { facade: TestBed.inject(CvInspectorFacade), live };
}

describe('CvInspectorFacade — W5.7 live subscription', () => {
  it('picking a stream subscribes to cv-trace:<assetId> of the owning asset', async () => {
    const api = stubApi(vi.fn().mockResolvedValue({
      categories: [],
      totalAssets: 1,
      assets: [attention({ assetId: 'asset-7', streamId: 'stream-7' })],
    } satisfies FleetSummary));
    const { facade, live } = create(api);

    facade.selectStream('stream-7');
    await flush();

    expect(api.fleetSummary).toHaveBeenCalledTimes(1);
    expect(live.trackCvTrace).toHaveBeenCalledExactlyOnceWith('asset-7');
  });

  it('degrades to poll-only (no subscription) when no asset claims the picked stream', async () => {
    const api = stubApi(vi.fn().mockResolvedValue({ categories: [], totalAssets: 0, assets: [] } satisfies FleetSummary));
    const { facade, live } = create(api);

    facade.selectStream('stream-unbound');
    await flush();

    expect(live.trackCvTrace).not.toHaveBeenCalled();
    expect(api.getCvTrace).toHaveBeenCalled(); // the poll itself still runs — trace demand stays on
  });

  it('degrades to poll-only when the fleet summary fetch itself fails', async () => {
    const api = stubApi(vi.fn().mockRejectedValue(new Error('network down')));
    const { facade, live } = create(api);

    facade.selectStream('stream-7');
    await flush();

    expect(live.trackCvTrace).not.toHaveBeenCalled();
    expect(api.getCvTrace).toHaveBeenCalled();
  });

  it('switching streams releases the old subscription before starting the new one', async () => {
    const api = stubApi(vi.fn()
      .mockResolvedValueOnce({ categories: [], totalAssets: 1, assets: [attention({ assetId: 'asset-1', streamId: 'stream-1' })] } satisfies FleetSummary)
      .mockResolvedValueOnce({ categories: [], totalAssets: 1, assets: [attention({ assetId: 'asset-2', streamId: 'stream-2' })] } satisfies FleetSummary));
    const { facade, live } = create(api);

    facade.selectStream('stream-1');
    await flush();
    expect(live.trackCvTrace).toHaveBeenNthCalledWith(1, 'asset-1');
    expect(live.untrackCvTrace).not.toHaveBeenCalled();

    facade.selectStream('stream-2');
    await flush();
    expect(live.untrackCvTrace).toHaveBeenCalledExactlyOnceWith('asset-1');
    expect(live.trackCvTrace).toHaveBeenNthCalledWith(2, 'asset-2');
  });

  it('a slow, superseded resolution never tracks the stream the engineer already left', async () => {
    let resolveFirst!: (summary: FleetSummary) => void;
    const first = new Promise<FleetSummary>((resolve) => (resolveFirst = resolve));
    const api = stubApi(vi.fn()
      .mockReturnValueOnce(first)
      .mockResolvedValueOnce({ categories: [], totalAssets: 1, assets: [attention({ assetId: 'asset-2', streamId: 'stream-2' })] } satisfies FleetSummary));
    const { facade, live } = create(api);

    facade.selectStream('stream-1'); // slow — resolves only after stream-2 is already picked
    facade.selectStream('stream-2');
    await flush();
    expect(live.trackCvTrace).toHaveBeenCalledExactlyOnceWith('asset-2');

    resolveFirst({ categories: [], totalAssets: 1, assets: [attention({ assetId: 'asset-1', streamId: 'stream-1' })] });
    await flush();

    expect(live.trackCvTrace).toHaveBeenCalledExactlyOnceWith('asset-2'); // still just the one call
    expect(live.untrackCvTrace).not.toHaveBeenCalledWith('asset-2');
  });

  it('deselecting (the placeholder option) releases the subscription and drops a still-in-flight resolution', async () => {
    let resolveSummary!: (summary: FleetSummary) => void;
    const pending = new Promise<FleetSummary>((resolve) => (resolveSummary = resolve));
    const api = stubApi(vi.fn().mockReturnValueOnce(pending));
    const { facade, live } = create(api);

    facade.selectStream('stream-1');
    facade.selectStream('');
    resolveSummary({ categories: [], totalAssets: 1, assets: [attention({ assetId: 'asset-1', streamId: 'stream-1' })] });
    await flush();

    expect(live.trackCvTrace).not.toHaveBeenCalled();
  });

  it('leaving the page releases the subscription (CvTraceStore.DestroyRef teardown)', async () => {
    const api = stubApi(vi.fn().mockResolvedValue({
      categories: [],
      totalAssets: 1,
      assets: [attention({ assetId: 'asset-9', streamId: 'stream-9' })],
    } satisfies FleetSummary));
    const { facade, live } = create(api);

    facade.selectStream('stream-9');
    await flush();
    expect(live.trackCvTrace).toHaveBeenCalledExactlyOnceWith('asset-9');
    expect(live.untrackCvTrace).not.toHaveBeenCalled();

    TestBed.resetTestingModule(); // destroys CvTraceStore — runs its DestroyRef.onDestroy teardown

    expect(live.untrackCvTrace).toHaveBeenCalledExactlyOnceWith('asset-9');
  });
});
