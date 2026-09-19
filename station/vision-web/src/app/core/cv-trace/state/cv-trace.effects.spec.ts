import { TestBed } from '@angular/core/testing';
import { provideMockActions } from '@ngrx/effects/testing';
import { Store, provideState, provideStore } from '@ngrx/store';
import type { Action } from '@ngrx/store';
import { ReplaySubject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { CvTrace, FrameLedger, GateDecision, WorldObject } from '../../api/models';
import { VisionApi } from '../../api/vision-api';
import { LiveSocketActions } from '../../live/state/live.actions';
import { liveFeature } from '../../live/state/live.reducer';
import { PollScheduler } from '../../poll-scheduler';
import { CvTraceApiActions, CvTracePageActions } from './cv-trace.actions';
import { liveFrameAccumulator$, session$ } from './cv-trace.effects';
import { cvTraceFeature } from './cv-trace.reducer';

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

/** Two separate channels — see `telemetry.effects.spec.ts#emit`'s identical doc comment. */
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
  const actions = new ReplaySubject<Action>(1);
  const api = { getCvTrace: vi.fn().mockResolvedValue(trace()), ...apiOverrides };
  TestBed.configureTestingModule({
    providers: [
      provideMockActions(() => actions),
      provideStore(),
      provideState(cvTraceFeature),
      provideState(liveFeature),
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: scheduler },
    ],
  });
  const store = TestBed.inject(Store);
  return { actions, api, scheduler, store };
}

describe('cv-trace effects — session$', () => {
  it('polls immediately and on the configured cadence, seeding gate/frame/world', async () => {
    const seeded = trace({ gate: [gateDecision(1)], frame: [frameLedger(1)], world: [worldObject(7)] });
    const { actions, api, scheduler, store } = setup({ getCvTrace: vi.fn().mockResolvedValue(seeded) });
    TestBed.runInInjectionContext(() => session$()).subscribe((a) => store.dispatch(a));

    emit(store, actions, CvTracePageActions.tracked({ streamId: 's-1', assetId: undefined, last: 50 }));
    await flush();

    expect(api.getCvTrace).toHaveBeenCalledExactlyOnceWith('s-1', 50);
    expect(store.selectSignal(cvTraceFeature.selectByStreamId)()['s-1']?.gate).toEqual(seeded.gate);
    expect(scheduler.lastFor(3_000)).toBeDefined();
  });

  it('passes a custom `last` through to both the GET and the ring cap', async () => {
    const { actions, api, store } = setup();
    TestBed.runInInjectionContext(() => session$()).subscribe((a) => store.dispatch(a));

    emit(store, actions, CvTracePageActions.tracked({ streamId: 's-1', assetId: undefined, last: 5 }));
    await flush();

    expect(api.getCvTrace).toHaveBeenCalledExactlyOnceWith('s-1', 5);
  });

  it('subscribes live only when an assetId is given', async () => {
    const { actions, store } = setup();
    const dispatchSpy = vi.spyOn(store, 'dispatch');
    TestBed.runInInjectionContext(() => session$()).subscribe((a) => store.dispatch(a));

    emit(store, actions, CvTracePageActions.tracked({ streamId: 's-1', assetId: 'a-1', last: 50 }));
    await flush();

    expect(dispatchSpy).toHaveBeenCalledWith(expect.objectContaining({ type: '[Live Page] Cv Trace Tracked', assetId: 'a-1' }));
  });

  it('stays poll-only when no assetId is given — never dispatches a live track', async () => {
    const { actions, api, store } = setup();
    const dispatchSpy = vi.spyOn(store, 'dispatch');
    TestBed.runInInjectionContext(() => session$()).subscribe((a) => store.dispatch(a));

    emit(store, actions, CvTracePageActions.tracked({ streamId: 's-1', assetId: undefined, last: 50 }));
    await flush();

    expect(dispatchSpy).not.toHaveBeenCalledWith(expect.objectContaining({ type: '[Live Page] Cv Trace Tracked' }));
    expect(api.getCvTrace).toHaveBeenCalled(); // still trace demand, via the poll alone
  });

  it('the poll keeps running even while an assetId is given — poll and live are never exclusive', async () => {
    const { actions, api, store } = setup();
    TestBed.runInInjectionContext(() => session$()).subscribe((a) => store.dispatch(a));

    emit(store, actions, CvTracePageActions.tracked({ streamId: 's-1', assetId: 'a-1', last: 50 }));
    await flush();

    expect(api.getCvTrace).toHaveBeenCalledExactlyOnceWith('s-1', 50);
  });

  it('a poll failure dispatches Poll Failed, keeping the last-known ledgers', async () => {
    const { actions, store } = setup({ getCvTrace: vi.fn().mockRejectedValue(new Error('down')) });
    const seen: Action[] = [];
    TestBed.runInInjectionContext(() => session$()).subscribe((a) => {
      seen.push(a);
      store.dispatch(a);
    });

    emit(store, actions, CvTracePageActions.tracked({ streamId: 's-1', assetId: undefined, last: 50 }));
    await flush();

    expect(seen.some((a) => a.type === CvTraceApiActions.pollFailed.type)).toBe(true);
  });

  it('Reset for the tracked stream stops its poll and releases its live subscription', async () => {
    const { actions, scheduler, store } = setup();
    const dispatchSpy = vi.spyOn(store, 'dispatch');
    TestBed.runInInjectionContext(() => session$()).subscribe((a) => store.dispatch(a));

    emit(store, actions, CvTracePageActions.tracked({ streamId: 's-14', assetId: 'a-14', last: 50 }));
    await flush();
    const poll = scheduler.lastFor(3_000);

    emit(store, actions, CvTracePageActions.reset({ streamId: 's-14' }));
    await flush();

    expect(poll?.stop).toHaveBeenCalledOnce();
    expect(dispatchSpy).toHaveBeenCalledWith(expect.objectContaining({ type: '[Live Page] Cv Trace Untracked', assetId: 'a-14' }));
  });

  it('two streams poll independently — one never cancels the other (groupBy isolation)', async () => {
    const { actions, api, scheduler, store } = setup();
    TestBed.runInInjectionContext(() => session$()).subscribe((a) => store.dispatch(a));

    emit(store, actions, CvTracePageActions.tracked({ streamId: 's-a', assetId: undefined, last: 50 }));
    await flush();
    emit(store, actions, CvTracePageActions.tracked({ streamId: 's-b', assetId: undefined, last: 50 }));
    await flush();

    expect(api.getCvTrace).toHaveBeenCalledWith('s-a', 50);
    expect(api.getCvTrace).toHaveBeenCalledWith('s-b', 50);
    const polls = scheduler.calls.filter((c) => c.periodMs === 3_000);
    expect(polls).toHaveLength(2);
    expect(polls.every((c) => !c.stop.mock.calls.length)).toBe(true);
  });
});

describe('cv-trace effects — liveFrameAccumulator$', () => {
  it('merges a live cv-trace envelope into the tracking stream\'s frame ring', async () => {
    const { actions, store } = setup();
    store.dispatch(CvTracePageActions.tracked({ streamId: 's-1', assetId: 'a-1', last: 50 }));
    TestBed.runInInjectionContext(() => liveFrameAccumulator$()).subscribe((a) => store.dispatch(a));

    const frame = frameLedger(1);
    actions.next(LiveSocketActions.envelopeReceived({ envelope: { seq: 1, assetId: 'a-1', type: 'cv-trace', payload: frame } }));
    await flush();

    expect(store.selectSignal(cvTraceFeature.selectByStreamId)()['s-1']?.frame).toEqual([frame]);
  });

  it('ignores an envelope for an asset no tracked stream matches', async () => {
    const { actions, store } = setup();
    store.dispatch(CvTracePageActions.tracked({ streamId: 's-1', assetId: 'a-1', last: 50 }));
    TestBed.runInInjectionContext(() => liveFrameAccumulator$()).subscribe((a) => store.dispatch(a));

    actions.next(LiveSocketActions.envelopeReceived({ envelope: { seq: 1, assetId: 'a-other', type: 'cv-trace', payload: frameLedger(1) } }));
    await flush();

    expect(store.selectSignal(cvTraceFeature.selectByStreamId)()['s-1']?.frame).toEqual([]);
  });
});
