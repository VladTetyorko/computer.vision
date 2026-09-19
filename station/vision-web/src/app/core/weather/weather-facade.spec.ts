import { EnvironmentInjector, Injector } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { provideAppState } from '../state/app-state';
import { WeatherFacade } from './weather-facade';

const HERE = { latitude: 10, longitude: 20 };
const THERE = { latitude: 30, longitude: 40 };

function jsonResponse(body: unknown, ok = true, status = 200): Response {
  return { ok, status, json: () => Promise.resolve(body) } as Response;
}

/** Flushes the microtask/macrotask queue so `track()`'s fire-and-forget dispatch chain settles. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

/** A fresh child injector providing its own `WeatherFacade`, parented to the shared TestBed root —
 *  exactly `CommandPage`'s and `FlyPage`'s own separate `providers: [WeatherFacade]` arrays, each a
 *  distinct component injector sharing the one app-wide `provideAppState()` root. */
function newHost(): WeatherFacade {
  const injector = Injector.create({ providers: [WeatherFacade], parent: TestBed.inject(EnvironmentInjector) });
  return injector.get(WeatherFacade);
}

describe('WeatherFacade', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    TestBed.configureTestingModule({ providers: [provideAppState(), WeatherFacade] });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('has no reading before track() is ever called', () => {
    const facade = TestBed.inject(WeatherFacade);
    expect(facade.reading()).toBeUndefined();
  });

  it('track() with no position is a no-op', () => {
    const facade = TestBed.inject(WeatherFacade);
    facade.track(undefined);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('fetches on the first track() and exposes the reading', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ current: { wind_speed_10m: 4.2, wind_gusts_10m: 5.1, time: 't0' } }));
    const facade = TestBed.inject(WeatherFacade);

    facade.track(HERE);
    await flush();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(facade.reading()).toMatchObject({ speedMps: 4.2, gustsMps: 5.1 });
  });

  it('does not refetch within the cache window at the same position', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ current: { wind_speed_10m: 1, wind_gusts_10m: 1 } }));
    const facade = TestBed.inject(WeatherFacade);

    facade.track(HERE);
    await flush();
    facade.track(HERE);
    await flush();

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('never starts a second fetch while one is still in flight', async () => {
    let resolveFetch: (value: Response) => void = () => undefined;
    fetchMock.mockReturnValue(new Promise<Response>((resolve) => (resolveFetch = resolve)));
    const facade = TestBed.inject(WeatherFacade);

    facade.track(HERE);
    facade.track(HERE); // fired again before the first ever resolves
    expect(fetchMock).toHaveBeenCalledTimes(1);

    resolveFetch(jsonResponse({ current: { wind_speed_10m: 1, wind_gusts_10m: 1 } }));
    await flush();
  });

  it('clears the reading on a fetch failure, never leaving a stale value', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ current: { wind_speed_10m: 1, wind_gusts_10m: 1 } }));
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const facade = TestBed.inject(WeatherFacade);
    facade.track(HERE);
    await flush();
    expect(facade.reading()).toBeDefined();

    fetchMock.mockRejectedValueOnce(new Error('offline'));
    facade.track(THERE); // a different position, past the epsilon, forces an immediate refetch
    await flush();

    expect(facade.reading()).toBeUndefined();
    warn.mockRestore();
  });

  describe('two hosts never clobber each other', () => {
    it('opening a second host (e.g. Fly) never rewrites the first host’s (e.g. Command) reading', async () => {
      fetchMock.mockImplementation((url: string) =>
        Promise.resolve(
          url.includes('10.0000')
            ? jsonResponse({ current: { wind_speed_10m: 4, wind_gusts_10m: 5, time: 'cmd' } })
            : jsonResponse({ current: { wind_speed_10m: 9, wind_gusts_10m: 11, time: 'fly' } }),
        ),
      );

      const command = newHost();
      command.track(HERE);
      await flush();
      expect(command.reading()).toMatchObject({ speedMps: 4, gustsMps: 5 });

      // Opening Fly mints a brand-new WeatherFacade instance — a fresh hostId, a fresh
      // byHostId entry — sharing the exact same NgRx store command already wrote into.
      const fly = newHost();
      expect(fly.reading()).toBeUndefined(); // a fresh host starts with no reading of its own

      fly.track(THERE);
      await flush();

      expect(fly.reading()).toMatchObject({ speedMps: 9, gustsMps: 11 });
      // The critical assertion: command's own reading is completely untouched by fly's fetch.
      expect(command.reading()).toMatchObject({ speedMps: 4, gustsMps: 5 });
    });

    it('a fetch failure on one host clears only that host’s reading, never the other’s', async () => {
      fetchMock.mockResolvedValue(jsonResponse({ current: { wind_speed_10m: 4, wind_gusts_10m: 5 } }));
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
      const command = newHost();
      const fly = newHost();

      command.track(HERE);
      await flush();
      fly.track(THERE);
      await flush();
      expect(command.reading()).toBeDefined();
      expect(fly.reading()).toBeDefined();

      fetchMock.mockRejectedValueOnce(new Error('offline'));
      // fly moves far enough to force a fresh (failing) fetch; command is left alone entirely.
      fly.track({ latitude: 31, longitude: 41 });
      await flush();

      expect(fly.reading()).toBeUndefined();
      expect(command.reading()).toMatchObject({ speedMps: 4, gustsMps: 5 });
      warn.mockRestore();
    });

    it('releasing one host (its facade destroyed) leaves the other host reading exactly as it was', async () => {
      fetchMock.mockResolvedValue(jsonResponse({ current: { wind_speed_10m: 4, wind_gusts_10m: 5 } }));
      const commandInjector = Injector.create({ providers: [WeatherFacade], parent: TestBed.inject(EnvironmentInjector) });
      const command = commandInjector.get(WeatherFacade);
      const fly = newHost();

      command.track(HERE);
      await flush();
      fly.track(THERE);
      await flush();

      commandInjector.destroy(); // simulates leaving /command — WeatherFacade's own DestroyRef fires

      expect(fly.reading()).toMatchObject({ speedMps: 4, gustsMps: 5 });
    });
  });
});
