import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideMockActions } from '@ngrx/effects/testing';
import type { Action } from '@ngrx/store';
import { ReplaySubject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { CorrectionResponse, CorrectionsResponse } from '../../api/models';
import { VisionApi } from '../../api/vision-api';
import { PollScheduler } from '../../poll-scheduler';
import { GeoApiActions, GeoLiveActions, GeoPageActions } from './geo.actions';
import { track$ } from './geo.effects';

/** Lets the fire-and-forget promise chain inside the effect's own fetch settle before asserting. */
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

function correction(assetId: string, partial: Partial<CorrectionResponse> = {}): CorrectionResponse {
  return {
    assetId,
    usageId: 'usage-1',
    frameAt: '2024-01-01T00:00:00Z',
    computedAt: '2024-01-01T00:00:00Z',
    status: 'CONFIRMED',
    source: 'VISUAL_HEAVY',
    divergent: false,
    ...partial,
  };
}

function setup(liveGeoCorrections: ReturnType<typeof vi.fn> = vi.fn().mockResolvedValue({ corrections: [] })) {
  const actions = new ReplaySubject<Action>(1);
  const scheduler = stubScheduler();
  TestBed.configureTestingModule({
    providers: [
      provideMockActions(() => actions),
      { provide: VisionApi, useValue: { liveGeoCorrections } },
      { provide: PollScheduler, useValue: scheduler },
    ],
  });
  return { actions, scheduler, api: { liveGeoCorrections } };
}

describe('geo effects — track$', () => {
  it('polls immediately when the initial transport is poll, filtering to the tracked asset', async () => {
    const mine = correction('a-1');
    const response: CorrectionsResponse = { corrections: [mine, correction('a-2')] };
    const { actions } = setup(vi.fn().mockResolvedValue(response));
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => track$()).subscribe((a) => seen.push(a));

    actions.next(GeoPageActions.trackRequested({ hostId: 'command', assetId: 'a-1', initialTransport: 'poll' }));
    await flush();

    expect(seen).toEqual([GeoApiActions.pollSucceeded({ hostId: 'command', correction: mine })]);
  });

  it('registers a 2s poll cadence when starting in poll transport', async () => {
    const { actions, scheduler } = setup();
    TestBed.runInInjectionContext(() => track$()).subscribe();

    actions.next(GeoPageActions.trackRequested({ hostId: 'command', assetId: 'a-1', initialTransport: 'poll' }));
    await flush();

    expect(scheduler.lastFor(2_000)).toBeDefined();
  });

  it('never polls at all when the initial transport is already live', async () => {
    const { actions, scheduler, api } = setup();
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => track$()).subscribe((a) => seen.push(a));

    actions.next(GeoPageActions.trackRequested({ hostId: 'command', assetId: 'a-1', initialTransport: 'live' }));
    await flush();

    expect(scheduler.lastFor(2_000)).toBeUndefined();
    expect(api.liveGeoCorrections).not.toHaveBeenCalled();
    expect(seen).toEqual([]);
  });

  it('stops the poll timer when a Transport Resolved flips to live mid-session', async () => {
    const { actions, scheduler } = setup();
    TestBed.runInInjectionContext(() => track$()).subscribe();

    actions.next(GeoPageActions.trackRequested({ hostId: 'command', assetId: 'a-1', initialTransport: 'poll' }));
    await flush();
    const poll = scheduler.lastFor(2_000);
    expect(poll?.stop).not.toHaveBeenCalled();

    actions.next(GeoLiveActions.transportResolved({ hostId: 'command', transport: 'live' }));
    await flush();

    expect(poll?.stop).toHaveBeenCalledOnce();
  });

  it('resumes polling — with an immediate re-read — when Transport Resolved flips back to poll', async () => {
    const { actions, scheduler, api } = setup();
    TestBed.runInInjectionContext(() => track$()).subscribe();

    actions.next(GeoPageActions.trackRequested({ hostId: 'command', assetId: 'a-1', initialTransport: 'live' }));
    await flush();
    expect(api.liveGeoCorrections).not.toHaveBeenCalled();

    actions.next(GeoLiveActions.transportResolved({ hostId: 'command', transport: 'poll' }));
    await flush();

    expect(api.liveGeoCorrections).toHaveBeenCalledTimes(1);
    expect(scheduler.lastFor(2_000)).toBeDefined();
  });

  it('reports Poll Disabled on the D9 409, and nothing at all on an ordinary failure', async () => {
    const disabledError = new HttpErrorResponse({
      status: 409,
      error: { error: 'CONFLICT', message: 'visual geolocation is disabled (vision.geo.visual.enabled)' },
    });
    const { actions } = setup(vi.fn().mockRejectedValue(disabledError));
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => track$()).subscribe((a) => seen.push(a));

    actions.next(GeoPageActions.trackRequested({ hostId: 'command', assetId: 'a-1', initialTransport: 'poll' }));
    await flush();

    expect(seen).toEqual([GeoApiActions.pollDisabled({ hostId: 'command' })]);
  });

  it('silently degrades on an ordinary poll failure — no action dispatched at all', async () => {
    const { actions } = setup(vi.fn().mockRejectedValue(new Error('network down')));
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => track$()).subscribe((a) => seen.push(a));

    actions.next(GeoPageActions.trackRequested({ hostId: 'command', assetId: 'a-1', initialTransport: 'poll' }));
    await flush();

    expect(seen).toEqual([]);
  });

  it('Reset Requested stops the poll for that host without ending the group — a later Track Requested for the same host still fetches', async () => {
    const { actions, scheduler, api } = setup();
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => track$()).subscribe((a) => seen.push(a));

    actions.next(GeoPageActions.trackRequested({ hostId: 'command', assetId: 'a-1', initialTransport: 'poll' }));
    await flush();
    const firstPoll = scheduler.lastFor(2_000);

    actions.next(GeoPageActions.resetRequested({ hostId: 'command' }));
    await flush();
    expect(firstPoll?.stop).toHaveBeenCalledOnce();

    actions.next(GeoPageActions.trackRequested({ hostId: 'command', assetId: 'a-2', initialTransport: 'poll' }));
    await flush();

    expect(api.liveGeoCorrections).toHaveBeenCalledTimes(2);
    expect(seen.at(-1)).toEqual(GeoApiActions.pollSucceeded({ hostId: 'command', correction: undefined }));
  });

  it(
    "two hosts never clobber each other: command's poll ticking never affects fly's, and each " +
      'resolves independently',
    async () => {
      const commandCorrection = correction('a-1');
      const flyCorrection = correction('a-2');
      const { actions } = setup(
        vi.fn((): Promise<CorrectionsResponse> => Promise.resolve({ corrections: [commandCorrection, flyCorrection] })),
      );
      const seen: unknown[] = [];
      TestBed.runInInjectionContext(() => track$()).subscribe((a) => seen.push(a));

      actions.next(GeoPageActions.trackRequested({ hostId: 'command', assetId: 'a-1', initialTransport: 'poll' }));
      await flush();
      actions.next(GeoPageActions.trackRequested({ hostId: 'fly', assetId: 'a-2', initialTransport: 'poll' }));
      await flush();

      expect(seen).toEqual([
        GeoApiActions.pollSucceeded({ hostId: 'command', correction: commandCorrection }),
        GeoApiActions.pollSucceeded({ hostId: 'fly', correction: flyCorrection }),
      ]);

      // command flipping to live never touches fly's own still-polling session.
      actions.next(GeoLiveActions.transportResolved({ hostId: 'command', transport: 'live' }));
      actions.next(GeoLiveActions.transportResolved({ hostId: 'fly', transport: 'live' }));
      await flush();
      expect(seen).toHaveLength(2); // no further ticks once both are live
    },
  );
});
