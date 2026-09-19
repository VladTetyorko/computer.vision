import { TestBed } from '@angular/core/testing';
import { provideMockActions } from '@ngrx/effects/testing';
import type { Action } from '@ngrx/store';
import { ReplaySubject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { WeatherApiActions, WeatherPageActions } from './weather.actions';
import { track$ } from './weather.effects';

const HERE = { latitude: 10, longitude: 20 };
const THERE = { latitude: 30, longitude: 40 };

function jsonResponse(body: unknown, ok = true, status = 200): Response {
  return { ok, status, json: () => Promise.resolve(body) } as Response;
}

/** Flushes the microtask/macrotask queue so the effect's own fire-and-forget fetch chain settles. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function setup() {
  const actions = new ReplaySubject<Action>(1);
  TestBed.configureTestingModule({ providers: [provideMockActions(() => actions)] });
  return { actions };
}

describe('weather effects — track$', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('fetches and reports Fetch Succeeded with the parsed reading', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ current: { wind_speed_10m: 4.2, wind_gusts_10m: 5.1, time: 't0' } }));
    const { actions } = setup();
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => track$()).subscribe((a) => seen.push(a));

    actions.next(WeatherPageActions.trackRequested({ hostId: 'command', position: HERE, nowMs: 1_000 }));
    await flush();

    expect(seen).toHaveLength(1);
    expect((seen[0] as ReturnType<typeof WeatherApiActions.fetchSucceeded>).hostId).toBe('command');
    expect((seen[0] as ReturnType<typeof WeatherApiActions.fetchSucceeded>).reading).toMatchObject({
      speedMps: 4.2,
      gustsMps: 5.1,
    });
  });

  it('reports Fetch Failed on a network error, never throwing out of the effect', async () => {
    fetchMock.mockRejectedValue(new Error('offline'));
    const { actions } = setup();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => track$()).subscribe((a) => seen.push(a));

    actions.next(WeatherPageActions.trackRequested({ hostId: 'command', position: HERE, nowMs: 1_000 }));
    await flush();

    expect(seen).toEqual([WeatherApiActions.fetchFailed({ hostId: 'command' })]);
    warn.mockRestore();
  });

  it('reports Fetch Succeeded with an undefined reading when the payload does not parse', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ current: {} }));
    const { actions } = setup();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => track$()).subscribe((a) => seen.push(a));

    actions.next(WeatherPageActions.trackRequested({ hostId: 'command', position: HERE, nowMs: 1_000 }));
    await flush();

    expect(seen).toEqual([WeatherApiActions.fetchSucceeded({ hostId: 'command', reading: undefined })]);
    warn.mockRestore();
  });

  it(
    "two hosts never clobber each other: command's still-pending fetch is not cancelled by fly's " +
      'dispatch, and each resolves with its own hostId — the exact regression a plain top-level ' +
      'switchMap (instead of groupBy+mergeMap) would cause',
    async () => {
      let resolveCommandFetch: (value: Response) => void = () => undefined;
      fetchMock.mockImplementation((url: string) => {
        if (url.includes('10.0000')) {
          // command's position (HERE) — deliberately never resolves until the test says so.
          return new Promise<Response>((resolve) => (resolveCommandFetch = resolve));
        }
        // fly's position (THERE) — resolves immediately.
        return Promise.resolve(jsonResponse({ current: { wind_speed_10m: 9, wind_gusts_10m: 11, time: 'fly-t0' } }));
      });
      const { actions } = setup();
      const seen: unknown[] = [];
      TestBed.runInInjectionContext(() => track$()).subscribe((a) => seen.push(a));

      // command's request starts first and is still in flight when fly's own dispatch arrives.
      actions.next(WeatherPageActions.trackRequested({ hostId: 'command', position: HERE, nowMs: 1_000 }));
      await flush();
      actions.next(WeatherPageActions.trackRequested({ hostId: 'fly', position: THERE, nowMs: 1_000 }));
      await flush();

      // fly's own fetch already resolved — command's has not been cancelled by it.
      expect(seen).toEqual([WeatherApiActions.fetchSucceeded({ hostId: 'fly', reading: expect.objectContaining({ speedMps: 9 }) })]);

      // Now let command's fetch resolve too — a plain top-level switchMap would have unsubscribed
      // this before it ever got the chance, so `fetchSucceeded` for 'command' would never arrive.
      resolveCommandFetch(jsonResponse({ current: { wind_speed_10m: 4, wind_gusts_10m: 5, time: 'cmd-t0' } }));
      await flush();

      expect(seen).toHaveLength(2);
      expect((seen[1] as ReturnType<typeof WeatherApiActions.fetchSucceeded>).hostId).toBe('command');
      expect((seen[1] as ReturnType<typeof WeatherApiActions.fetchSucceeded>).reading).toMatchObject({ speedMps: 4 });
    },
  );

  it('a hostId still fetches correctly after an earlier Host Released for that same id (e.g. a fast page revisit)', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ current: { wind_speed_10m: 1, wind_gusts_10m: 1 } }));
    const { actions } = setup();
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => track$()).subscribe((a) => seen.push(a));

    actions.next(WeatherPageActions.trackRequested({ hostId: 'command', position: HERE, nowMs: 1_000 }));
    await flush();
    actions.next(WeatherPageActions.hostReleased({ hostId: 'command' }));
    await flush();
    actions.next(WeatherPageActions.trackRequested({ hostId: 'command', position: THERE, nowMs: 2_000 }));
    await flush();

    expect(seen).toEqual([
      WeatherApiActions.fetchSucceeded({ hostId: 'command', reading: expect.objectContaining({ speedMps: 1 }) }),
      WeatherApiActions.fetchSucceeded({ hostId: 'command', reading: expect.objectContaining({ speedMps: 1 }) }),
    ]);
  });
});
