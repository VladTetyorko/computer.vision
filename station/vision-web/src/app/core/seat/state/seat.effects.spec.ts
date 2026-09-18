import { TestBed } from '@angular/core/testing';
import { provideMockActions } from '@ngrx/effects/testing';
import { Store, provideState, provideStore } from '@ngrx/store';
import type { Action } from '@ngrx/store';
import { ReplaySubject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { SeatsResponse } from '../../api/models';
import { VisionApi } from '../../api/vision-api';
import { PollScheduler } from '../../poll-scheduler';
import { singleOperatorSeats } from '../seat-logic';
import { SeatApiActions, SeatPageActions } from './seat.actions';
import { poll$, refreshNow$, renew$ } from './seat.effects';
import { seatFeature } from './seat.reducer';

/** Lets a promise chain inside an effect (`readSeats`, `renewMineSeats`) settle before asserting. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

/** Captures every `PollScheduler.schedule` registration so a test can drive ticks and assert
 *  cadence/teardown without real timers — mirrors the old `seat-store.spec.ts`'s own helper. */
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

function seats(partial: Partial<SeatsResponse> = {}): SeatsResponse {
  return { ...singleOperatorSeats('a-1'), ...partial };
}

function setup(apiOverrides: Partial<VisionApi> = {}, scheduler = stubScheduler()) {
  const actions = new ReplaySubject<Action>(1);
  const api = {
    getAssetSeats: vi.fn().mockResolvedValue(singleOperatorSeats('a-1')),
    takeAssetSeat: vi.fn(),
    ...apiOverrides,
  };
  TestBed.configureTestingModule({
    providers: [
      provideMockActions(() => actions),
      provideStore(),
      provideState(seatFeature),
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: scheduler },
    ],
  });
  const store = TestBed.inject(Store);
  return { actions, api, scheduler, store };
}

describe('seat effects — poll$', () => {
  it('reads immediately on Tracked, before any tick', async () => {
    const { actions } = setup();
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => poll$()).subscribe((a) => seen.push(a));
    actions.next(SeatPageActions.tracked({ assetId: 'a-1' }));
    await flush();
    expect(seen).toEqual([SeatApiActions.seatsReceived({ assetId: 'a-1', response: singleOperatorSeats('a-1') })]);
  });

  it('emits Read Failed when the read rejects', async () => {
    const { actions } = setup({ getAssetSeats: vi.fn().mockRejectedValue(new Error('404')) });
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => poll$()).subscribe((a) => seen.push(a));
    actions.next(SeatPageActions.tracked({ assetId: 'a-1' }));
    await flush();
    expect(seen).toEqual([SeatApiActions.readFailed({ assetId: 'a-1' })]);
  });

  it('registers a fixed 3s cadence for the tracked asset', async () => {
    const { actions, scheduler } = setup();
    TestBed.runInInjectionContext(() => poll$()).subscribe();
    actions.next(SeatPageActions.tracked({ assetId: 'a-1' }));
    await flush();
    expect(scheduler.lastFor(3_000)).toBeDefined();
  });

  it('re-reads on the registered tick', async () => {
    const getAssetSeats = vi.fn().mockResolvedValue(singleOperatorSeats('a-1'));
    const { actions, scheduler } = setup({ getAssetSeats });
    TestBed.runInInjectionContext(() => poll$()).subscribe();
    actions.next(SeatPageActions.tracked({ assetId: 'a-1' }));
    await flush();
    expect(getAssetSeats).toHaveBeenCalledOnce();

    await scheduler.lastFor(3_000)?.callback();
    await flush();
    expect(getAssetSeats).toHaveBeenCalledTimes(2);
  });

  it('two different tracked assets poll independently — one never cancels the other (groupBy isolation)', async () => {
    const getAssetSeats = vi.fn((assetId: string) => Promise.resolve(singleOperatorSeats(assetId)));
    const { actions, scheduler } = setup({ getAssetSeats });
    TestBed.runInInjectionContext(() => poll$()).subscribe();

    actions.next(SeatPageActions.tracked({ assetId: 'a-1' }));
    await flush();
    actions.next(SeatPageActions.tracked({ assetId: 'b-1' }));
    await flush();

    // both still have a live, unstopped 3s registration
    const forA = scheduler.calls.filter((c) => c.periodMs === 3_000);
    expect(forA).toHaveLength(2);
    expect(forA.every((c) => !c.stop.mock.calls.length)).toBe(true);
  });

  it('Reset stops that asset’s poll registration', async () => {
    const { actions, scheduler } = setup();
    TestBed.runInInjectionContext(() => poll$()).subscribe();
    actions.next(SeatPageActions.tracked({ assetId: 'a-1' }));
    await flush();
    const poll = scheduler.lastFor(3_000);

    actions.next(SeatPageActions.reset({ assetId: 'a-1' }));
    await flush();

    expect(poll?.stop).toHaveBeenCalled();
  });
});

describe('seat effects — refreshNow$', () => {
  it('forces one immediate read, independent of the poll cadence', async () => {
    const getAssetSeats = vi.fn().mockResolvedValue(seats({ mayForceSeat: true }));
    const { actions } = setup({ getAssetSeats });
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => refreshNow$()).subscribe((a) => seen.push(a));

    actions.next(SeatPageActions.refreshNowRequested({ assetId: 'a-1' }));
    await flush();

    expect(seen).toEqual([SeatApiActions.seatsReceived({ assetId: 'a-1', response: seats({ mayForceSeat: true }) })]);
  });
});

describe('seat effects — renew$', () => {
  /** Feeds `assetId`'s response into both the mocked `Actions` stream (what `renew$` listens on)
   *  and the real store (what `renewMineSeats` reads back via `selectSignal`) — the two are
   *  separate channels under `provideMockActions`, so a test must drive both explicitly. */
  function receive(store: Store, actions: ReplaySubject<Action>, assetId: string, response: SeatsResponse) {
    const action = SeatApiActions.seatsReceived({ assetId, response });
    store.dispatch(action);
    actions.next(action);
  }

  it('derives the renewal cadence from the served ttlMs, never a hard-coded interval', async () => {
    const { actions, scheduler, store } = setup();
    TestBed.runInInjectionContext(() => renew$()).subscribe();

    receive(store, actions, 'a-1', seats({ ttlMs: 30_000 })); // 30_000 / 3 = 10_000
    await flush();

    expect(scheduler.lastFor(10_000)).toBeDefined();
    expect(scheduler.lastFor(5_000)).toBeUndefined();
  });

  it('does not re-register when a later tick reports the same cadence (distinctUntilChanged)', async () => {
    const { actions, scheduler, store } = setup();
    TestBed.runInInjectionContext(() => renew$()).subscribe();

    receive(store, actions, 'a-1', seats({ ttlMs: 30_000 }));
    await flush();
    receive(store, actions, 'a-1', seats({ ttlMs: 30_000 }));
    await flush();

    expect(scheduler.calls.filter((c) => c.periodMs === 10_000)).toHaveLength(1);
  });

  it('renews only the seats last known to be mine', async () => {
    const mine = seats({ camera: { ...singleOperatorSeats('a-1').camera, holderUserId: 'me', mine: true } });
    const takeAssetSeat = vi.fn().mockResolvedValue(mine);
    const { actions, scheduler, store } = setup({ takeAssetSeat });
    TestBed.runInInjectionContext(() => renew$()).subscribe();

    receive(store, actions, 'a-1', mine); // default ttlMs (15_000) / 3 = 5_000
    await flush();
    await scheduler.lastFor(5_000)?.callback();
    await flush();

    expect(takeAssetSeat).toHaveBeenCalledExactlyOnceWith('a-1', 'CAMERA');
  });

  it('never renews when nothing is mine — a lone reader triggers no heartbeat', async () => {
    const takeAssetSeat = vi.fn();
    const { actions, scheduler, store } = setup({ takeAssetSeat });
    TestBed.runInInjectionContext(() => renew$()).subscribe();

    receive(store, actions, 'a-1', singleOperatorSeats('a-1'));
    await flush();
    await scheduler.lastFor(5_000)?.callback();
    await flush();

    expect(takeAssetSeat).not.toHaveBeenCalled();
  });

  it('treats a renewal failure as an authoritative correction — re-reads instead of reconciling the rejection locally', async () => {
    const mine = seats({ camera: { ...singleOperatorSeats('a-1').camera, holderUserId: 'me', mine: true } });
    const preempted = seats({
      camera: { holderUserId: 'u-9', holderDisplayName: 'Anna Kovalenko', acquiredAt: null, expiresAt: null, mine: false },
    });
    const getAssetSeats = vi.fn().mockResolvedValue(preempted);
    const takeAssetSeat = vi.fn().mockRejectedValue({ status: 409, error: { message: 'held by Anna Kovalenko' } });
    const { actions, scheduler, store } = setup({ getAssetSeats, takeAssetSeat });
    TestBed.runInInjectionContext(() => renew$()).subscribe();

    receive(store, actions, 'a-1', mine);
    await flush();
    await scheduler.lastFor(5_000)?.callback();
    await flush();

    expect(getAssetSeats).toHaveBeenCalledWith('a-1');
    expect(store.selectSignal(seatFeature.selectByAssetId)()['a-1']).toEqual(preempted);
  });

  it('dispatches Read Failed when both the renewal and its follow-up re-read fail', async () => {
    const mine = seats({ flight: { ...singleOperatorSeats('a-1').flight, holderUserId: 'me', mine: true } });
    const getAssetSeats = vi.fn().mockRejectedValue(new Error('down'));
    const takeAssetSeat = vi.fn().mockRejectedValue(new Error('down'));
    const { actions, scheduler, store } = setup({ getAssetSeats, takeAssetSeat });
    TestBed.runInInjectionContext(() => renew$()).subscribe();
    const dispatchSpy = vi.spyOn(store, 'dispatch');

    receive(store, actions, 'a-1', mine);
    await flush();
    dispatchSpy.mockClear();
    await scheduler.lastFor(5_000)?.callback();
    await flush();

    expect(dispatchSpy).toHaveBeenCalledWith(SeatApiActions.readFailed({ assetId: 'a-1' }));
  });

  it('Reset stops that asset’s renewal registration', async () => {
    const mine = seats({ camera: { ...singleOperatorSeats('a-1').camera, holderUserId: 'me', mine: true } });
    const { actions, scheduler, store } = setup();
    TestBed.runInInjectionContext(() => renew$()).subscribe();

    receive(store, actions, 'a-1', mine);
    await flush();
    const renew = scheduler.lastFor(5_000);

    actions.next(SeatPageActions.reset({ assetId: 'a-1' }));
    await flush();

    expect(renew?.stop).toHaveBeenCalled();
  });
});
