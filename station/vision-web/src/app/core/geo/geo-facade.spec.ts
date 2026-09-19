import { EnvironmentInjector, Injector, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it, vi } from 'vitest';
import type { CorrectionResponse, CorrectionsResponse } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { LiveFacade, type LiveConnectionState } from '../live/live-facade';
import { PollScheduler } from '../poll-scheduler';
import { provideAppState } from '../state/app-state';
import { GeoFacade } from './geo-facade';

/** Lets the fire-and-forget promise chain inside `track()`'s dispatched effect settle before asserting. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function stubApi(liveGeoCorrections: ReturnType<typeof vi.fn> = vi.fn().mockResolvedValue({ corrections: [] })) {
  return { liveGeoCorrections };
}

/** Mirrors `geo-store.spec.ts#stubLiveFacade` exactly — one fake shared by every host in a test, exactly like the real root-singleton `LiveFacade`. */
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

function setUpFacade(
  api: ReturnType<typeof stubApi>,
  options: { live?: ReturnType<typeof stubLiveFacade>; scheduler?: ReturnType<typeof stubScheduler> } = {},
): GeoFacade {
  const live = options.live ?? stubLiveFacade();
  const scheduler = options.scheduler ?? stubScheduler();
  TestBed.configureTestingModule({
    providers: [
      provideAppState(),
      GeoFacade,
      { provide: VisionApi, useValue: api },
      { provide: LiveFacade, useValue: live },
      { provide: PollScheduler, useValue: scheduler },
    ],
  });
  return TestBed.inject(GeoFacade);
}

describe('GeoFacade', () => {
  it('polls the fleet-wide live-corrections list and filters it to the tracked asset', async () => {
    const mine = correction('a-1', { latitude: 50.1, longitude: 30.1 });
    const other = correction('a-2');
    const response: CorrectionsResponse = { corrections: [mine, other] };
    const api = stubApi(vi.fn().mockResolvedValue(response));

    const facade = setUpFacade(api);
    facade.track('a-1');
    await flush();

    expect(api.liveGeoCorrections).toHaveBeenCalled();
    expect(facade.latest()).toEqual(mine);
  });

  it('reads undefined (not a fabricated value) when the tracked asset has no correction yet', async () => {
    const api = stubApi(vi.fn().mockResolvedValue({ corrections: [correction('a-other')] }));

    const facade = setUpFacade(api);
    facade.track('a-none');
    await flush();

    expect(facade.latest()).toBeUndefined();
  });

  it('silently degrades when the poll fails, rather than throwing', async () => {
    const api = stubApi(vi.fn().mockRejectedValue(new Error('network down')));

    const facade = setUpFacade(api);
    facade.track('a-err');
    await flush();

    expect(facade.latest()).toBeUndefined();
  });

  it('reset() clears the latest correction and stops polling', async () => {
    const mine = correction('a-3');
    const api = stubApi(vi.fn().mockResolvedValue({ corrections: [mine] }));

    const facade = setUpFacade(api);
    facade.track('a-3');
    await flush();
    expect(facade.latest()).toEqual(mine);

    facade.reset();
    expect(facade.latest()).toBeUndefined();
  });

  it('subscribes live when LiveFacade is open, reading straight from geoFor(assetId) — latest-wins, no accumulation', () => {
    const api = stubApi();
    const live = stubLiveFacade('open');
    const scheduler = stubScheduler();

    const facade = setUpFacade(api, { live, scheduler });
    facade.track('a-9');

    expect(live.trackGeo).toHaveBeenCalledExactlyOnceWith('a-9');
    expect(scheduler.lastFor(2_000)).toBeUndefined(); // no poll registered while live

    const first = correction('a-9', { latitude: 1, longitude: 1 });
    live.pushResult('a-9', first);
    TestBed.tick();
    expect(facade.latest()).toEqual(first);

    const second = correction('a-9', { latitude: 2, longitude: 2 });
    live.pushResult('a-9', second);
    TestBed.tick();
    expect(facade.latest()).toEqual(second); // replaced, not accumulated
  });

  it('falls back to polling while LiveFacade is not open, still subscribing for later', async () => {
    const api = stubApi();
    const live = stubLiveFacade('connecting');
    const scheduler = stubScheduler();

    const facade = setUpFacade(api, { live, scheduler });
    facade.track('a-11');
    await flush();

    expect(live.trackGeo).toHaveBeenCalledExactlyOnceWith('a-11');
    expect(scheduler.lastFor(2_000)).toBeDefined();
  });

  it('switches from poll to live, stopping the poll, when LiveFacade opens mid-session', async () => {
    const api = stubApi();
    const live = stubLiveFacade('connecting');
    const scheduler = stubScheduler();

    const facade = setUpFacade(api, { live, scheduler });
    facade.track('a-12');
    await flush();
    const poll = scheduler.lastFor(2_000);
    expect(poll?.stop).not.toHaveBeenCalled();

    live.setState('open');
    TestBed.tick();
    await flush();

    expect(poll?.stop).toHaveBeenCalledOnce();
  });

  it('falls back to polling again when LiveFacade drops mid-session, keeping the last-visible correction', async () => {
    const api = stubApi(vi.fn().mockResolvedValue({ corrections: [] }));
    const live = stubLiveFacade('open');
    const scheduler = stubScheduler();

    const facade = setUpFacade(api, { live, scheduler });
    facade.track('a-13');
    const liveResult = correction('a-13');
    live.pushResult('a-13', liveResult);
    TestBed.tick();
    expect(facade.latest()).toEqual(liveResult);

    live.setState('connecting');
    TestBed.tick();

    expect(scheduler.lastFor(2_000)).toBeDefined();
    expect(facade.latest()).toEqual(liveResult); // seeded from live — no blank beat
  });

  it('reset() releases the live subscription', () => {
    const api = stubApi();
    const live = stubLiveFacade('open');

    const facade = setUpFacade(api, { live });
    facade.track('a-14');
    expect(live.trackGeo).toHaveBeenCalledExactlyOnceWith('a-14');

    facade.reset();
    expect(live.untrackGeo).toHaveBeenCalledExactlyOnceWith('a-14');
  });

  it('re-tracking the same assetId is a no-op — subscribes live exactly once', () => {
    const api = stubApi();
    const live = stubLiveFacade('open');

    const facade = setUpFacade(api, { live });
    for (let i = 0; i < 5; i++) {
      facade.track('a-17');
    }

    expect(live.trackGeo).toHaveBeenCalledExactlyOnceWith('a-17');
    expect(live.untrackGeo).not.toHaveBeenCalled();
  });

  it('re-tracking a different assetId releases the old live subscription and subscribes to the new one', () => {
    const api = stubApi();
    const live = stubLiveFacade('open');

    const facade = setUpFacade(api, { live });
    facade.track('a-15');
    facade.track('a-16');

    expect(live.untrackGeo).toHaveBeenCalledExactlyOnceWith('a-15');
    expect(live.trackGeo).toHaveBeenCalledWith('a-16');
  });

  it('sets disabled() once a poll observes the D9 409 — distinct from the ordinary "no fix yet" undefined', async () => {
    const disabledError = new HttpErrorResponse({
      status: 409,
      error: { error: 'CONFLICT', message: 'visual geolocation is disabled (vision.geo.visual.enabled)' },
    });
    const api = stubApi(vi.fn().mockRejectedValue(disabledError));

    const facade = setUpFacade(api);
    expect(facade.disabled()).toBe(false);
    facade.track('a-20');
    await flush();

    expect(facade.disabled()).toBe(true);
    expect(facade.latest()).toBeUndefined();
  });

  it('leaves disabled() false for an unrelated poll failure', async () => {
    const api = stubApi(vi.fn().mockRejectedValue(new Error('network down')));

    const facade = setUpFacade(api);
    facade.track('a-21');
    await flush();

    expect(facade.disabled()).toBe(false);
  });

  it('reset() clears disabled() back to false for the next track() session', async () => {
    const disabledError = new HttpErrorResponse({
      status: 409,
      error: { error: 'CONFLICT', message: 'visual geolocation is disabled (vision.geo.visual.enabled)' },
    });
    const api = stubApi(vi.fn().mockRejectedValue(disabledError));

    const facade = setUpFacade(api);
    facade.track('a-22');
    await flush();
    expect(facade.disabled()).toBe(true);

    facade.reset();
    expect(facade.disabled()).toBe(false);
  });

  describe('two hosts never clobber each other', () => {
    it('opening a second host never rewrites the first host’s latest correction', async () => {
      const commandCorrection = correction('a-1');
      const flyCorrection = correction('a-2');
      const api = stubApi(vi.fn().mockResolvedValue({ corrections: [commandCorrection, flyCorrection] }));
      const live = stubLiveFacade('closed');
      const scheduler = stubScheduler();
      TestBed.configureTestingModule({
        providers: [
          provideAppState(),
          { provide: VisionApi, useValue: api },
          { provide: LiveFacade, useValue: live },
          { provide: PollScheduler, useValue: scheduler },
        ],
      });
      const commandInjector = Injector.create({ providers: [GeoFacade], parent: TestBed.inject(EnvironmentInjector) });
      const flyInjector = Injector.create({ providers: [GeoFacade], parent: TestBed.inject(EnvironmentInjector) });
      const command = commandInjector.get(GeoFacade);
      const fly = flyInjector.get(GeoFacade);

      command.track('a-1');
      await flush();
      expect(command.latest()).toEqual(commandCorrection);

      fly.track('a-2');
      await flush();

      expect(fly.latest()).toEqual(flyCorrection);
      // The critical assertion: command's own reading is completely untouched by fly's own poll.
      expect(command.latest()).toEqual(commandCorrection);
    });

    it('releasing one host (its facade destroyed) leaves the other host tracking exactly as it was', async () => {
      const api = stubApi(vi.fn().mockResolvedValue({ corrections: [correction('a-1'), correction('a-2')] }));
      const live = stubLiveFacade('closed');
      TestBed.configureTestingModule({
        providers: [
          provideAppState(),
          { provide: VisionApi, useValue: api },
          { provide: LiveFacade, useValue: live },
        ],
      });
      const commandInjector = Injector.create({ providers: [GeoFacade], parent: TestBed.inject(EnvironmentInjector) });
      const flyInjector = Injector.create({ providers: [GeoFacade], parent: TestBed.inject(EnvironmentInjector) });
      const command = commandInjector.get(GeoFacade);
      const fly = flyInjector.get(GeoFacade);

      command.track('a-1');
      fly.track('a-2');
      await flush();
      expect(fly.latest()).toEqual(correction('a-2'));

      commandInjector.destroy(); // simulates leaving /command — GeoFacade's own DestroyRef fires

      expect(live.untrackGeo).toHaveBeenCalledWith('a-1');
      expect(fly.latest()).toEqual(correction('a-2'));
    });
  });
});
