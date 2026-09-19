import { TestBed } from '@angular/core/testing';
import { Store } from '@ngrx/store';
import { describe, expect, it, vi } from 'vitest';
import type { CvTrace, FrameLedger, GateDecision, WorldObject } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { LiveSocketActions } from '../live/state/live.actions';
import { PollScheduler } from '../poll-scheduler';
import { provideAppState } from '../state/app-state';
import { provideCvTraceState } from './state/cv-trace.providers';
import { CvTraceFacade } from './cv-trace-facade';

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

function setup(apiOverrides: Partial<VisionApi> = {}, scheduler = stubScheduler()) {
  const api = { getCvTrace: vi.fn().mockResolvedValue(trace()), ...apiOverrides };
  TestBed.configureTestingModule({
    providers: [
      provideAppState(), provideCvTraceState(),
      CvTraceFacade,
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: scheduler },
    ],
  });
  return { facade: TestBed.inject(CvTraceFacade), api, scheduler, store: TestBed.inject(Store) };
}

describe('CvTraceFacade', () => {
  it('has empty ledgers before track() is ever called', () => {
    const { facade } = setup();
    expect(facade.gate()).toEqual([]);
    expect(facade.frame()).toEqual([]);
    expect(facade.world()).toEqual([]);
  });

  it('seeds gate/frame/world from the initial GET .../cv/trace', async () => {
    const seeded = trace({ gate: [gateDecision(1)], frame: [frameLedger(1)], world: [worldObject(7)] });
    const { facade, api } = setup({ getCvTrace: vi.fn().mockResolvedValue(seeded) });

    facade.track('stream-1');
    await flush();

    expect(api.getCvTrace).toHaveBeenCalledExactlyOnceWith('stream-1', 50);
    expect(facade.gate()).toEqual(seeded.gate);
    expect(facade.frame()).toEqual(seeded.frame);
    expect(facade.world()).toEqual(seeded.world);
  });

  it('passes a custom `last` through to both the GET and the ring cap', async () => {
    const { facade, api } = setup();

    facade.track('stream-1', undefined, 5);
    await flush();

    expect(api.getCvTrace).toHaveBeenCalledExactlyOnceWith('stream-1', 5);
  });

  it('stays poll-only when no assetId is given', async () => {
    const { facade, api, store } = setup();
    const dispatchSpy = vi.spyOn(store, 'dispatch');

    facade.track('stream-1');
    await flush();

    expect(dispatchSpy).not.toHaveBeenCalledWith(expect.objectContaining({ type: '[Live Page] Cv Trace Tracked' }));
    expect(api.getCvTrace).toHaveBeenCalled();
  });

  it('merges a live arrival into the frame ring between polls, without displacing the poll', async () => {
    const { facade, store } = setup();
    store.dispatch(LiveSocketActions.opened());

    facade.track('stream-1', 'asset-1');
    await flush();

    const frame = frameLedger(1);
    store.dispatch(LiveSocketActions.envelopeReceived({ envelope: { seq: 1, assetId: 'asset-1', type: 'cv-trace', payload: frame } }));

    expect(facade.frame()).toEqual([frame]);
  });

  it("the next poll's frame list authoritatively replaces the ring, never merges with it", async () => {
    const api = { getCvTrace: vi.fn().mockResolvedValue(trace()) };
    const scheduler = stubScheduler();
    const { facade, store } = setup(api, scheduler);
    store.dispatch(LiveSocketActions.opened());

    facade.track('stream-1', 'asset-1');
    await flush();

    store.dispatch(LiveSocketActions.envelopeReceived({ envelope: { seq: 1, assetId: 'asset-1', type: 'cv-trace', payload: frameLedger(99) } }));
    expect(facade.frame().map((f) => f.sequence)).toEqual([99]);

    api.getCvTrace.mockResolvedValueOnce(trace({ frame: [frameLedger(1), frameLedger(2)] }));
    await scheduler.lastFor(3_000)?.callback();
    await flush();

    expect(facade.frame().map((f) => f.sequence)).toEqual([1, 2]);
  });

  it('silently degrades when the poll fails, keeping the last-known ledgers', async () => {
    const seeded = trace({ gate: [gateDecision(1)] });
    const api = { getCvTrace: vi.fn().mockResolvedValueOnce(seeded).mockRejectedValueOnce(new Error('down')) };
    const scheduler = stubScheduler();
    const { facade } = setup(api, scheduler);

    facade.track('stream-1');
    await flush();
    expect(facade.gate()).toEqual(seeded.gate);

    await scheduler.lastFor(3_000)?.callback();
    await flush();
    expect(facade.gate()).toEqual(seeded.gate);
  });

  it('reset() clears every ledger and stops polling', async () => {
    const seeded = trace({ gate: [gateDecision(1)], frame: [frameLedger(1)], world: [worldObject(7)] });
    const { facade, scheduler } = setup({ getCvTrace: vi.fn().mockResolvedValue(seeded) });

    facade.track('stream-1');
    await flush();
    expect(facade.gate().length).toBeGreaterThan(0);
    const poll = scheduler.lastFor(3_000);

    facade.reset();

    expect(facade.gate()).toEqual([]);
    expect(facade.frame()).toEqual([]);
    expect(facade.world()).toEqual([]);
    expect(poll?.stop).toHaveBeenCalledOnce();
  });

  it('re-tracking the same (streamId, assetId, last) is a no-op', async () => {
    const { facade, api } = setup();

    for (let i = 0; i < 3; i++) {
      facade.track('stream-1', 'asset-1');
    }
    await flush();

    expect(api.getCvTrace).toHaveBeenCalledOnce();
  });

  it('re-tracking a different stream releases the old live subscription and starts a fresh session', async () => {
    const { facade, api, store } = setup();
    const dispatchSpy = vi.spyOn(store, 'dispatch');

    facade.track('stream-1', 'asset-1');
    await flush();
    facade.track('stream-2', 'asset-2');
    await flush();

    expect(dispatchSpy).toHaveBeenCalledWith(expect.objectContaining({ type: '[Live Page] Cv Trace Untracked', assetId: 'asset-1' }));
    expect(dispatchSpy).toHaveBeenCalledWith(expect.objectContaining({ type: '[Live Page] Cv Trace Tracked', assetId: 'asset-2' }));
    expect(api.getCvTrace).toHaveBeenLastCalledWith('stream-2', 50);
  });
});
