import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import type { SeatsResponse } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { PollScheduler } from '../poll-scheduler';
import { provideAppState } from '../state/app-state';
import { provideSeatState } from './state/seat.providers';
import { singleOperatorSeats } from './seat-logic';
import { SeatFacade } from './seat-facade';

/** Lets a facade-triggered promise chain (the effect's HTTP read) settle before asserting. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

/** Captures every `PollScheduler.schedule` registration — a real scheduler would use live
 *  `setInterval`/`Date.now`, which this end-to-end spec avoids exactly like the old
 *  `seat-store.spec.ts` avoided real timers. */
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
  const api = {
    getAssetSeats: vi.fn().mockResolvedValue(singleOperatorSeats('a-1')),
    takeAssetSeat: vi.fn(),
    ...apiOverrides,
  };
  TestBed.configureTestingModule({
    providers: [
      provideAppState(), provideSeatState(),
      SeatFacade,
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: scheduler },
    ],
  });
  return { facade: TestBed.inject(SeatFacade), api, scheduler };
}

/**
 * The seat slice end to end — facade → action → effect (real HTTP call through a stub `VisionApi`,
 * real polling through a stub `PollScheduler`) → reducer → facade computed signals. Replaces
 * `SeatStore`'s own spec case for case (docs/plans/done/NGRX-MIGRATION-PLAN.md N2); the deeply
 * RxJS-timing-specific cases (cadence derivation, `groupBy` per-asset isolation, `distinctUntilChanged`
 * dedup) moved to `state/seat.effects.spec.ts`, since they exercise the effects directly.
 */
describe('SeatFacade', () => {
  it('reads as the single-operator fallback before the first poll ever resolves', () => {
    const { facade } = setup({ getAssetSeats: vi.fn(() => new Promise<SeatsResponse>(() => {})) });

    facade.track('a-1');

    expect(facade.seats()).toEqual(singleOperatorSeats('a-1'));
    expect(facade.mayTakeFlight()).toBe(true);
    expect(facade.mayTakeCamera()).toBe(true);
    expect(facade.mayForceSeat()).toBe(false);
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
    const { facade } = setup({ getAssetSeats: vi.fn().mockResolvedValue(response) });

    facade.track('a-1');
    await flush();

    expect(facade.camera()).toEqual(response.camera);
    expect(facade.flight().mine).toBe(false);
    expect(facade.mayForceSeat()).toBe(true);
  });

  it('degrades to the single-operator fallback on a read failure with no prior good response', async () => {
    const { facade } = setup({ getAssetSeats: vi.fn().mockRejectedValue(new Error('404')) });

    facade.track('a-1');
    await flush();

    expect(facade.seats()).toEqual(singleOperatorSeats('a-1'));
  });

  it('keeps the last-known-good seats on a later poll failure, rather than blanking to the fallback', async () => {
    const held = seats({ camera: { ...singleOperatorSeats('a-1').camera, holderUserId: 'u-2', mine: true } });
    const getAssetSeats = vi.fn().mockResolvedValueOnce(held).mockRejectedValueOnce(new Error('network down'));
    const { facade, scheduler } = setup({ getAssetSeats });

    facade.track('a-1');
    await flush();
    expect(facade.camera().mine).toBe(true);

    await scheduler.lastFor(3_000)?.callback();
    await flush();

    expect(facade.camera().mine).toBe(true); // unchanged, not blanked
  });

  it('re-tracking the same assetId is a no-op — polls exactly once', async () => {
    const getAssetSeats = vi.fn().mockResolvedValue(singleOperatorSeats('a-1'));
    const { facade } = setup({ getAssetSeats });

    for (let i = 0; i < 5; i++) {
      facade.track('a-1');
    }
    await flush();

    expect(getAssetSeats).toHaveBeenCalledOnce();
  });

  it('reset() stops both the poll and the renewal registration', async () => {
    const mine = seats({ camera: { ...singleOperatorSeats('a-1').camera, holderUserId: 'me', mine: true } });
    const { facade, scheduler } = setup({ getAssetSeats: vi.fn().mockResolvedValue(mine) });

    facade.track('a-1');
    await flush();
    const poll = scheduler.lastFor(3_000);
    const renew = scheduler.lastFor(5_000); // default ttlMs (15_000) / 3

    facade.reset();

    expect(poll?.stop).toHaveBeenCalled();
    expect(renew?.stop).toHaveBeenCalled();
  });

  it('reset() clears state back to the single-operator fallback for the next track() session', async () => {
    const held = seats({ camera: { ...singleOperatorSeats('a-1').camera, holderUserId: 'me', mine: true } });
    const { facade } = setup({ getAssetSeats: vi.fn().mockResolvedValue(held) });

    facade.track('a-1');
    await flush();
    expect(facade.camera().mine).toBe(true);

    facade.reset();
    expect(facade.seats()).toEqual(singleOperatorSeats(''));
  });

  it('refreshNow() forces an immediate re-read outside the poll cadence', async () => {
    const getAssetSeats = vi
      .fn()
      .mockResolvedValueOnce(singleOperatorSeats('a-1'))
      .mockResolvedValueOnce(seats({ camera: { ...singleOperatorSeats('a-1').camera, holderUserId: 'me', mine: true } }));
    const { facade } = setup({ getAssetSeats });

    facade.track('a-1');
    await flush();
    expect(facade.camera().mine).toBe(false);

    facade.refreshNow();
    await flush();

    expect(facade.camera().mine).toBe(true);
    expect(getAssetSeats).toHaveBeenCalledTimes(2);
  });
});
