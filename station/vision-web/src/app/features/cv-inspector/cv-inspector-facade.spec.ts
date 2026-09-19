import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Store } from '@ngrx/store';
import { describe, expect, it, vi } from 'vitest';
import { CvInspectorFacade } from './cv-inspector-facade';
import { CvTraceFacade } from '../../core/cv-trace/cv-trace-facade';
import { FleetStore } from '../../core/fleet/fleet-store';
import { PollScheduler } from '../../core/poll-scheduler';
import { provideAppState } from '../../core/state/app-state';
import { provideCvTraceState } from '../../core/cv-trace/state/cv-trace.providers';
import { SystemStatusStore } from '../../core/system-status/system-status-store';
import { VisionApi } from '../../core/api/vision-api';
import type { AssetAttention, FleetSummary } from '../../core/api/models';

/**
 * Proves wave W5.7's "Live subscription" paragraph on `CvInspectorFacade`'s own class doc: picking
 * a stream resolves its owning asset (`VisionApi#fleetSummary()` + `assetIdForStream`) and starts
 * the LIVE `cv-trace:<assetId>` topic through the REAL `CvTraceFacade` — `provideAppState(), provideCvTraceState()` wires
 * the genuine NgRx store/effects (wave N5 replaced the old `CvTraceStore`'s direct `LiveFacade`
 * method calls with dispatched `LivePageActions.cvTraceTracked`/`cvTraceUntracked` actions read
 * straight off the real store below, rather than a stubbed `LiveFacade`) — the only way to prove the
 * whole chain (facade → slice → effects) actually wires together, mirroring `core/cv-trace/
 * cv-trace-facade.spec.ts`'s own convention one layer down. `FleetStore`/`SystemStatusStore`/
 * `VisionApi`/`PollScheduler` are the only leaves stubbed; `CvTraceFacade` and `CvInspectorFacade`
 * are both the genuine classes.
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

function stubApi(
  fleetSummary: ReturnType<typeof vi.fn> = vi.fn().mockResolvedValue({ categories: [], totalAssets: 0, assets: [] } satisfies FleetSummary),
  getCvTrace: ReturnType<typeof vi.fn> = vi.fn().mockResolvedValue({ streamId: 'stream-1', gate: [], frame: [], world: [] }),
) {
  return { fleetSummary, getCvTrace };
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

function create(api: ReturnType<typeof stubApi>) {
  TestBed.configureTestingModule({
    providers: [
      provideAppState(), provideCvTraceState(),
      CvInspectorFacade,
      CvTraceFacade,
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: stubScheduler() },
      { provide: FleetStore, useValue: stubFleetStore() },
      { provide: SystemStatusStore, useValue: stubStatusStore() },
    ],
  });
  return { facade: TestBed.inject(CvInspectorFacade), store: TestBed.inject(Store) };
}

/** A dispatched `[Live Page] Cv Trace Tracked`/`Untracked` action is this wave's own replacement for
 *  the old spec's stubbed `live.trackCvTrace`/`untrackCvTrace` call assertions. */
function cvTraceTrackedAction(assetId: string) {
  return expect.objectContaining({ type: '[Live Page] Cv Trace Tracked', assetId });
}
function cvTraceUntrackedAction(assetId: string) {
  return expect.objectContaining({ type: '[Live Page] Cv Trace Untracked', assetId });
}

describe('CvInspectorFacade — W5.7 live subscription', () => {
  it('picking a stream subscribes to cv-trace:<assetId> of the owning asset', async () => {
    const api = stubApi(vi.fn().mockResolvedValue({
      categories: [],
      totalAssets: 1,
      assets: [attention({ assetId: 'asset-7', streamId: 'stream-7' })],
    } satisfies FleetSummary));
    const { facade, store } = create(api);
    const dispatchSpy = vi.spyOn(store, 'dispatch');

    facade.selectStream('stream-7');
    await flush();

    expect(api.fleetSummary).toHaveBeenCalledTimes(1);
    expect(dispatchSpy).toHaveBeenCalledWith(cvTraceTrackedAction('asset-7'));
  });

  it('degrades to poll-only (no subscription) when no asset claims the picked stream', async () => {
    const api = stubApi(vi.fn().mockResolvedValue({ categories: [], totalAssets: 0, assets: [] } satisfies FleetSummary));
    const { facade, store } = create(api);
    const dispatchSpy = vi.spyOn(store, 'dispatch');

    facade.selectStream('stream-unbound');
    await flush();

    expect(dispatchSpy).not.toHaveBeenCalledWith(expect.objectContaining({ type: '[Live Page] Cv Trace Tracked' }));
    expect(api.getCvTrace).toHaveBeenCalled(); // the poll itself still runs — trace demand stays on
  });

  it('degrades to poll-only when the fleet summary fetch itself fails', async () => {
    const api = stubApi(vi.fn().mockRejectedValue(new Error('network down')));
    const { facade, store } = create(api);
    const dispatchSpy = vi.spyOn(store, 'dispatch');

    facade.selectStream('stream-7');
    await flush();

    expect(dispatchSpy).not.toHaveBeenCalledWith(expect.objectContaining({ type: '[Live Page] Cv Trace Tracked' }));
    expect(api.getCvTrace).toHaveBeenCalled();
  });

  it('switching streams releases the old subscription before starting the new one', async () => {
    const api = stubApi(vi.fn()
      .mockResolvedValueOnce({ categories: [], totalAssets: 1, assets: [attention({ assetId: 'asset-1', streamId: 'stream-1' })] } satisfies FleetSummary)
      .mockResolvedValueOnce({ categories: [], totalAssets: 1, assets: [attention({ assetId: 'asset-2', streamId: 'stream-2' })] } satisfies FleetSummary));
    const { facade, store } = create(api);
    const dispatchSpy = vi.spyOn(store, 'dispatch');

    facade.selectStream('stream-1');
    await flush();
    expect(dispatchSpy).toHaveBeenCalledWith(cvTraceTrackedAction('asset-1'));
    expect(dispatchSpy).not.toHaveBeenCalledWith(cvTraceUntrackedAction('asset-1'));

    facade.selectStream('stream-2');
    await flush();
    expect(dispatchSpy).toHaveBeenCalledWith(cvTraceUntrackedAction('asset-1'));
    expect(dispatchSpy).toHaveBeenCalledWith(cvTraceTrackedAction('asset-2'));
  });

  it('a slow, superseded resolution never tracks the stream the engineer already left', async () => {
    let resolveFirst!: (summary: FleetSummary) => void;
    const first = new Promise<FleetSummary>((resolve) => (resolveFirst = resolve));
    const api = stubApi(vi.fn()
      .mockReturnValueOnce(first)
      .mockResolvedValueOnce({ categories: [], totalAssets: 1, assets: [attention({ assetId: 'asset-2', streamId: 'stream-2' })] } satisfies FleetSummary));
    const { facade, store } = create(api);
    const dispatchSpy = vi.spyOn(store, 'dispatch');

    facade.selectStream('stream-1'); // slow — resolves only after stream-2 is already picked
    facade.selectStream('stream-2');
    await flush();
    expect(dispatchSpy).toHaveBeenCalledWith(cvTraceTrackedAction('asset-2'));
    expect(dispatchSpy).not.toHaveBeenCalledWith(cvTraceTrackedAction('asset-1'));

    resolveFirst({ categories: [], totalAssets: 1, assets: [attention({ assetId: 'asset-1', streamId: 'stream-1' })] });
    await flush();

    expect(dispatchSpy).not.toHaveBeenCalledWith(cvTraceTrackedAction('asset-1')); // still never tracked
    expect(dispatchSpy).not.toHaveBeenCalledWith(cvTraceUntrackedAction('asset-2'));
  });

  it('deselecting (the placeholder option) releases the subscription and drops a still-in-flight resolution', async () => {
    let resolveSummary!: (summary: FleetSummary) => void;
    const pending = new Promise<FleetSummary>((resolve) => (resolveSummary = resolve));
    const api = stubApi(vi.fn().mockReturnValueOnce(pending));
    const { facade, store } = create(api);
    const dispatchSpy = vi.spyOn(store, 'dispatch');

    facade.selectStream('stream-1');
    facade.selectStream('');
    resolveSummary({ categories: [], totalAssets: 1, assets: [attention({ assetId: 'asset-1', streamId: 'stream-1' })] });
    await flush();

    expect(dispatchSpy).not.toHaveBeenCalledWith(expect.objectContaining({ type: '[Live Page] Cv Trace Tracked' }));
  });

  it('leaving the page releases the subscription (CvTraceFacade.DestroyRef teardown)', async () => {
    const api = stubApi(vi.fn().mockResolvedValue({
      categories: [],
      totalAssets: 1,
      assets: [attention({ assetId: 'asset-9', streamId: 'stream-9' })],
    } satisfies FleetSummary));
    const { facade, store } = create(api);
    const dispatchSpy = vi.spyOn(store, 'dispatch');

    facade.selectStream('stream-9');
    await flush();
    expect(dispatchSpy).toHaveBeenCalledWith(cvTraceTrackedAction('asset-9'));
    expect(dispatchSpy).not.toHaveBeenCalledWith(cvTraceUntrackedAction('asset-9'));

    TestBed.resetTestingModule(); // destroys CvTraceFacade — runs its DestroyRef.onDestroy teardown

    expect(dispatchSpy).toHaveBeenCalledWith(cvTraceUntrackedAction('asset-9'));
  });
});
