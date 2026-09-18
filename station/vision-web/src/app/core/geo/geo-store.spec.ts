import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it, vi } from 'vitest';
import { GeoStore } from './geo-store';
import { VisionApi } from '../api/vision-api';
import { LiveFacade, type LiveConnectionState } from '../live/live-facade';
import { PollScheduler } from '../poll-scheduler';
import type { CorrectionResponse, CorrectionsResponse } from '../api/models';

/** Lets the fire-and-forget promise chain inside `track()` settle before asserting. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function stubApi(liveGeoCorrections: ReturnType<typeof vi.fn> = vi.fn().mockResolvedValue({ corrections: [] })) {
  return { liveGeoCorrections };
}

/** Mirrors `detections-store.spec.ts#stubLiveFacade` exactly, narrowed to the geo topic surface. */
function stubLiveFacade(initialState: LiveConnectionState = 'closed') {
  const stateSignal = signal<LiveConnectionState>(initialState);
  const perAsset = new Map<string, ReturnType<typeof signal<CorrectionResponse | undefined>>>();
  const signalFor = (assetId: string) => {
    let existing = perAsset.get(assetId);
    if (existing === undefined) {
      existing = signal<CorrectionResponse | undefined>(undefined);
      perAsset.set(assetId, existing);
    }
    return existing;
  };
  return {
    connectionState: stateSignal.asReadonly(),
    geoFor: vi.fn((assetId: string) => signalFor(assetId)),
    trackGeo: vi.fn(),
    untrackGeo: vi.fn(),
    setState: (state: LiveConnectionState) => stateSignal.set(state),
    pushResult: (assetId: string, result: CorrectionResponse) => signalFor(assetId).set(result),
  };
}

/** Captures every `PollScheduler.schedule` registration so a test can assert on/off without real timers. */
function stubScheduler() {
  const calls: { periodMs: number; stop: ReturnType<typeof vi.fn> }[] = [];
  const schedule = vi.fn((periodMs: number) => {
    const stop = vi.fn();
    calls.push({ periodMs, stop });
    return stop;
  });
  const lastFor = (periodMs: number) => [...calls].reverse().find((call) => call.periodMs === periodMs);
  return { schedule, calls, lastFor };
}

function inject(
  api: ReturnType<typeof stubApi>,
  options: { live?: ReturnType<typeof stubLiveFacade>; scheduler?: ReturnType<typeof stubScheduler> } = {},
): GeoStore {
  const providers: unknown[] = [GeoStore, { provide: VisionApi, useValue: api }];
  providers.push({ provide: LiveFacade, useValue: options.live ?? stubLiveFacade() });
  if (options.scheduler) {
    providers.push({ provide: PollScheduler, useValue: options.scheduler });
  }
  TestBed.configureTestingModule({ providers });
  return TestBed.inject(GeoStore);
}

function correction(assetId: string, partial: Partial<CorrectionResponse> = {}): CorrectionResponse {
  return {
    assetId,
    usageId: 'usage-1',
    frameAt: new Date().toISOString(),
    computedAt: new Date().toISOString(),
    status: 'CONFIRMED',
    source: 'VISUAL_HEAVY',
    divergent: false,
    ...partial,
  };
}

describe('GeoStore', () => {
  it('polls the fleet-wide live-corrections list and filters it to the tracked asset', async () => {
    const mine = correction('a-1', { latitude: 50.1, longitude: 30.1 });
    const other = correction('a-2');
    const response: CorrectionsResponse = { corrections: [mine, other] };
    const api = stubApi(vi.fn().mockResolvedValue(response));

    const store = inject(api);
    store.track('a-1');
    await flush();

    expect(api.liveGeoCorrections).toHaveBeenCalled();
    expect(store.latest()).toEqual(mine);
    store.reset();
  });

  it('reads undefined (not a fabricated value) when the tracked asset has no correction yet', async () => {
    const api = stubApi(vi.fn().mockResolvedValue({ corrections: [correction('a-other')] }));

    const store = inject(api);
    store.track('a-none');
    await flush();

    expect(store.latest()).toBeUndefined();
    store.reset();
  });

  it('silently degrades when the poll fails, rather than throwing', async () => {
    const api = stubApi(vi.fn().mockRejectedValue(new Error('network down')));

    const store = inject(api);
    store.track('a-err');
    await flush();

    expect(store.latest()).toBeUndefined();
    store.reset();
  });

  it('reset() clears the latest correction and stops polling', async () => {
    const mine = correction('a-3');
    const api = stubApi(vi.fn().mockResolvedValue({ corrections: [mine] }));

    const store = inject(api);
    store.track('a-3');
    await flush();
    expect(store.latest()).toEqual(mine);

    store.reset();
    expect(store.latest()).toBeUndefined();
  });

  // --- LiveFacade projection (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.4) -----------------------

  it('subscribes live when LiveFacade is open, reading straight from geoFor(assetId) — latest-wins, no accumulation', () => {
    const api = stubApi();
    const live = stubLiveFacade('open');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('a-9');

    expect(live.trackGeo).toHaveBeenCalledExactlyOnceWith('a-9');
    expect(scheduler.lastFor(2_000)).toBeUndefined(); // no poll registered while live

    const first = correction('a-9', { latitude: 1, longitude: 1 });
    live.pushResult('a-9', first);
    TestBed.tick();
    expect(store.latest()).toEqual(first);

    const second = correction('a-9', { latitude: 2, longitude: 2 });
    live.pushResult('a-9', second);
    TestBed.tick();
    expect(store.latest()).toEqual(second); // replaced, not accumulated
    store.reset();
  });

  it('falls back to polling while LiveFacade is not open, still subscribing for later', async () => {
    const api = stubApi();
    const live = stubLiveFacade('connecting');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('a-11');
    await flush();

    expect(live.trackGeo).toHaveBeenCalledExactlyOnceWith('a-11');
    expect(scheduler.lastFor(2_000)).toBeDefined();
    store.reset();
  });

  it('switches from poll to live, stopping the poll, when LiveFacade opens mid-session', async () => {
    const api = stubApi();
    const live = stubLiveFacade('connecting');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('a-12');
    await flush();
    const poll = scheduler.lastFor(2_000);
    expect(poll?.stop).not.toHaveBeenCalled();

    live.setState('open');
    TestBed.tick();

    expect(poll?.stop).toHaveBeenCalledOnce();
    store.reset();
  });

  it('falls back to polling again when LiveFacade drops mid-session, keeping the last-visible correction', async () => {
    const api = stubApi(vi.fn().mockResolvedValue({ corrections: [] }));
    const live = stubLiveFacade('open');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('a-13');
    const liveResult = correction('a-13');
    live.pushResult('a-13', liveResult);
    TestBed.tick();
    expect(store.latest()).toEqual(liveResult);

    live.setState('connecting');
    TestBed.tick();

    expect(scheduler.lastFor(2_000)).toBeDefined();
    expect(store.latest()).toEqual(liveResult); // seeded from live — no blank beat
    store.reset();
  });

  it('reset() releases the live subscription', () => {
    const api = stubApi();
    const live = stubLiveFacade('open');

    const store = inject(api, { live });
    store.track('a-14');
    expect(live.trackGeo).toHaveBeenCalledExactlyOnceWith('a-14');

    store.reset();
    expect(live.untrackGeo).toHaveBeenCalledExactlyOnceWith('a-14');
  });

  it('re-tracking the same assetId is a no-op — subscribes live exactly once', () => {
    const api = stubApi();
    const live = stubLiveFacade('open');

    const store = inject(api, { live });
    for (let i = 0; i < 5; i++) {
      store.track('a-17');
    }

    expect(live.trackGeo).toHaveBeenCalledExactlyOnceWith('a-17');
    expect(live.untrackGeo).not.toHaveBeenCalled();
    store.reset();
  });

  it('re-tracking a different assetId releases the old live subscription and subscribes to the new one', () => {
    const api = stubApi();
    const live = stubLiveFacade('open');

    const store = inject(api, { live });
    store.track('a-15');
    store.track('a-16');

    expect(live.untrackGeo).toHaveBeenCalledExactlyOnceWith('a-15');
    expect(live.trackGeo).toHaveBeenCalledWith('a-16');
    store.reset();
  });

  // --- D9 flag-off (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.8 "Off state: every geo surface is
  // absent, not empty") ---------------------------------------------------------------------------

  it('sets disabled() once a poll observes the D9 409 — distinct from the ordinary "no fix yet" undefined', async () => {
    const disabledError = new HttpErrorResponse({
      status: 409,
      error: { error: 'CONFLICT', message: 'visual geolocation is disabled (vision.geo.visual.enabled)' },
    });
    const api = stubApi(vi.fn().mockRejectedValue(disabledError));

    const store = inject(api);
    expect(store.disabled()).toBe(false);
    store.track('a-20');
    await flush();

    expect(store.disabled()).toBe(true);
    expect(store.latest()).toBeUndefined();
    store.reset();
  });

  it('leaves disabled() false for an unrelated poll failure', async () => {
    const api = stubApi(vi.fn().mockRejectedValue(new Error('network down')));

    const store = inject(api);
    store.track('a-21');
    await flush();

    expect(store.disabled()).toBe(false);
    store.reset();
  });

  it('reset() clears disabled() back to false for the next track() session', async () => {
    const disabledError = new HttpErrorResponse({
      status: 409,
      error: { error: 'CONFLICT', message: 'visual geolocation is disabled (vision.geo.visual.enabled)' },
    });
    const api = stubApi(vi.fn().mockRejectedValue(disabledError));

    const store = inject(api);
    store.track('a-22');
    await flush();
    expect(store.disabled()).toBe(true);

    store.reset();
    expect(store.disabled()).toBe(false);
  });
});
