import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { SeatStore } from './seat-store';
import { VisionApi } from '../api/vision-api';
import { PollScheduler } from '../poll-scheduler';
import { singleOperatorSeats } from './seat-logic';
import type { SeatsResponse } from '../api/models';

/** Lets the fire-and-forget promise chain inside `track()`/renewal settle before asserting. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function stubApi(overrides: {
  getAssetSeats?: ReturnType<typeof vi.fn>;
  takeAssetSeat?: ReturnType<typeof vi.fn>;
} = {}) {
  return {
    getAssetSeats: overrides.getAssetSeats ?? vi.fn().mockResolvedValue(singleOperatorSeats('a-1')),
    takeAssetSeat: overrides.takeAssetSeat ?? vi.fn(),
  };
}

/** Captures every `PollScheduler.schedule` registration so a test can assert cadence/teardown without real timers. */
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

function inject(api: ReturnType<typeof stubApi>, scheduler: ReturnType<typeof stubScheduler>): SeatStore {
  TestBed.configureTestingModule({
    providers: [
      SeatStore,
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: scheduler },
    ],
  });
  return TestBed.inject(SeatStore);
}

function seats(partial: Partial<SeatsResponse> = {}): SeatsResponse {
  return { ...singleOperatorSeats('a-1'), ...partial };
}

describe('SeatStore', () => {
  it('reads as the single-operator fallback before the first poll ever resolves', () => {
    const api = stubApi({ getAssetSeats: vi.fn(() => new Promise<SeatsResponse>(() => {})) });
    const store = inject(api, stubScheduler());

    store.track('a-1');

    expect(store.seats()).toEqual(singleOperatorSeats('a-1'));
    expect(store.mayTakeFlight()).toBe(true);
    expect(store.mayTakeCamera()).toBe(true);
    expect(store.mayForceSeat()).toBe(false);
    store.reset();
  });

  it('exposes the served seats once the poll resolves', async () => {
    const response = seats({
      camera: {
        holderUserId: 'u-2',
        holderDisplayName: 'Anna Kovalenko',
        acquiredAt: '2026-09-04T10:12:03Z',
        expiresAt: '2026-09-04T10:12:18Z',
        mine: true,
      },
      mayForceSeat: true,
    });
    const api = stubApi({ getAssetSeats: vi.fn().mockResolvedValue(response) });
    const store = inject(api, stubScheduler());

    store.track('a-1');
    await flush();

    expect(store.camera()).toEqual(response.camera);
    expect(store.flight().mine).toBe(false);
    expect(store.mayForceSeat()).toBe(true);
    store.reset();
  });

  it('degrades to the single-operator fallback on a 404 with no prior good response — feature not deployed or off alike', async () => {
    const api = stubApi({ getAssetSeats: vi.fn().mockRejectedValue(new Error('404')) });
    const store = inject(api, stubScheduler());

    store.track('a-1');
    await flush();

    expect(store.seats()).toEqual(singleOperatorSeats('a-1'));
    store.reset();
  });

  it('keeps the last-known-good seats on a later poll failure, rather than blanking to the fallback', async () => {
    const held = seats({ camera: { ...singleOperatorSeats('a-1').camera, holderUserId: 'u-2', mine: true } });
    const getAssetSeats = vi.fn().mockResolvedValueOnce(held).mockRejectedValueOnce(new Error('network down'));
    const scheduler = stubScheduler();
    const store = inject(stubApi({ getAssetSeats }), scheduler);

    store.track('a-1');
    await flush();
    expect(store.camera().mine).toBe(true);

    const poll = scheduler.lastFor(3_000);
    await poll?.callback();
    await flush();

    expect(store.camera().mine).toBe(true); // unchanged, not blanked
    store.reset();
  });

  it('registers the observation poll at a fixed 3s cadence', () => {
    const scheduler = stubScheduler();
    const store = inject(stubApi(), scheduler);

    store.track('a-1');

    expect(scheduler.lastFor(3_000)).toBeDefined();
    store.reset();
  });

  it('derives the renewal cadence from the served ttlMs — never a hard-coded interval', async () => {
    const response = seats({ ttlMs: 30_000 }); // 30_000 / 3 = 10_000
    const scheduler = stubScheduler();
    const store = inject(stubApi({ getAssetSeats: vi.fn().mockResolvedValue(response) }), scheduler);

    store.track('a-1');
    await flush();

    expect(scheduler.lastFor(10_000)).toBeDefined();
    expect(scheduler.lastFor(5_000)).toBeUndefined(); // not the default cadence — proves it isn't hard-coded
    store.reset();
  });

  it('renews only the seats last known to be mine, leaving an unheld or someone-else-held seat untouched', async () => {
    const response = seats({ camera: { ...singleOperatorSeats('a-1').camera, holderUserId: 'me', mine: true } });
    const scheduler = stubScheduler();
    const takeAssetSeat = vi.fn().mockResolvedValue(response);
    const store = inject(stubApi({ getAssetSeats: vi.fn().mockResolvedValue(response), takeAssetSeat }), scheduler);

    store.track('a-1');
    await flush();
    const renew = scheduler.lastFor(5_000); // default 15_000 / 3
    await renew?.callback();
    await flush();

    expect(takeAssetSeat).toHaveBeenCalledExactlyOnceWith('a-1', 'CAMERA');
    store.reset();
  });

  it('never calls takeAssetSeat when neither seat is mine — a lone reader triggers no heartbeat', async () => {
    const scheduler = stubScheduler();
    const takeAssetSeat = vi.fn();
    const store = inject(stubApi({ takeAssetSeat }), scheduler);

    store.track('a-1');
    await flush();
    const renew = scheduler.lastFor(5_000);
    await renew?.callback();
    await flush();

    expect(takeAssetSeat).not.toHaveBeenCalled();
    store.reset();
  });

  it('treats a 409 on renewal as an authoritative correction — re-reads instead of reconciling the message locally', async () => {
    const mine = seats({ camera: { ...singleOperatorSeats('a-1').camera, holderUserId: 'me', mine: true } });
    const preempted = seats({
      camera: { holderUserId: 'u-9', holderDisplayName: 'Anna Kovalenko', acquiredAt: null, expiresAt: null, mine: false },
    });
    const getAssetSeats = vi.fn().mockResolvedValueOnce(mine).mockResolvedValueOnce(preempted);
    const takeAssetSeat = vi.fn().mockRejectedValue({ status: 409, error: { message: 'held by Anna Kovalenko' } });
    const scheduler = stubScheduler();
    const store = inject(stubApi({ getAssetSeats, takeAssetSeat }), scheduler);

    store.track('a-1');
    await flush();
    expect(store.camera().mine).toBe(true);

    const renew = scheduler.lastFor(5_000);
    await renew?.callback();
    await flush();

    expect(getAssetSeats).toHaveBeenCalledTimes(2);
    expect(store.camera().mine).toBe(false);
    expect(store.camera().holderDisplayName).toBe('Anna Kovalenko');
    store.reset();
  });

  it('re-tracking the same assetId is a no-op — polls exactly once', async () => {
    const getAssetSeats = vi.fn().mockResolvedValue(singleOperatorSeats('a-1'));
    const store = inject(stubApi({ getAssetSeats }), stubScheduler());

    for (let i = 0; i < 5; i++) {
      store.track('a-1');
    }
    await flush();

    expect(getAssetSeats).toHaveBeenCalledOnce();
    store.reset();
  });

  it('reset() stops both the poll and the renewal task', async () => {
    const scheduler = stubScheduler();
    const store = inject(stubApi(), scheduler);

    store.track('a-1');
    await flush();
    const poll = scheduler.lastFor(3_000);
    const renew = scheduler.lastFor(5_000);

    store.reset();

    expect(poll?.stop).toHaveBeenCalled();
    expect(renew?.stop).toHaveBeenCalled();
  });

  it('reset() clears state back to the single-operator fallback for the next track() session', async () => {
    const held = seats({ camera: { ...singleOperatorSeats('a-1').camera, holderUserId: 'me', mine: true } });
    const store = inject(stubApi({ getAssetSeats: vi.fn().mockResolvedValue(held) }), stubScheduler());

    store.track('a-1');
    await flush();
    expect(store.camera().mine).toBe(true);

    store.reset();
    expect(store.seats()).toEqual(singleOperatorSeats(''));
  });

  it('refreshNow() forces an immediate re-read outside the poll cadence', async () => {
    const getAssetSeats = vi
      .fn()
      .mockResolvedValueOnce(singleOperatorSeats('a-1'))
      .mockResolvedValueOnce(seats({ camera: { ...singleOperatorSeats('a-1').camera, holderUserId: 'me', mine: true } }));
    const store = inject(stubApi({ getAssetSeats }), stubScheduler());

    store.track('a-1');
    await flush();
    expect(store.camera().mine).toBe(false);

    store.refreshNow();
    await flush();

    expect(store.camera().mine).toBe(true);
    expect(getAssetSeats).toHaveBeenCalledTimes(2);
    store.reset();
  });
});
