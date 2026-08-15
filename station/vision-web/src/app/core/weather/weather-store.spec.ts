import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { WeatherStore } from './weather-store';

const HERE = { latitude: 10, longitude: 20 };

function jsonResponse(body: unknown, ok = true, status = 200): Response {
  return { ok, status, json: () => Promise.resolve(body) } as Response;
}

function create(): WeatherStore {
  TestBed.configureTestingModule({ providers: [WeatherStore] });
  return TestBed.inject(WeatherStore);
}

/** Flushes the microtask/macrotask queue so `track()`'s fire-and-forget fetch chain settles — real timers throughout (see class doc below for why `Date.now()` is mocked directly instead). */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('WeatherStore', () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  let nowMs: number;

  beforeEach(() => {
    // `Date.now()` is mocked directly rather than via `vi.useFakeTimers()` — this store awaits a
    // real (mocked) `fetch()` promise, and fake timers don't auto-advance/flush that chain without
    // `vi.advanceTimersByTimeAsync` everywhere; controlling the clock this way keeps every test a
    // plain `await`, with real timers doing the actual microtask/macrotask flushing.
    nowMs = 1_000_000;
    vi.spyOn(Date, 'now').mockImplementation(() => nowMs);
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('does nothing with no position at all', () => {
    const store = create();
    store.track(undefined);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(store.reading()).toBeUndefined();
  });

  it('fetches on the very first call and sets the reading', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ current: { wind_speed_10m: 4.2, wind_gusts_10m: 5.1, time: 't0' } }));
    const store = create();

    store.track(HERE);
    await flush();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const url = fetchMock.mock.calls[0][0] as string;
    expect(url).toContain('https://api.open-meteo.com/v1/forecast?');
    expect(store.reading()).toMatchObject({ speedMps: 4.2, gustsMps: 5.1 });
  });

  it('does not refetch within the cache window at the same position', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ current: { wind_speed_10m: 1, wind_gusts_10m: 1 } }));
    const store = create();
    store.track(HERE);
    await flush();
    expect(fetchMock).toHaveBeenCalledTimes(1);

    nowMs += 60_000; // 1 minute — well under the 10-minute cache
    store.track(HERE);
    await flush();

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('refetches once the cache window elapses', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ current: { wind_speed_10m: 1, wind_gusts_10m: 1 } }));
    const store = create();
    store.track(HERE);
    await flush();

    nowMs += 11 * 60 * 1000;
    store.track(HERE);
    await flush();

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('refetches immediately once the position moves far enough, even inside the cache window', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ current: { wind_speed_10m: 1, wind_gusts_10m: 1 } }));
    const store = create();
    store.track(HERE);
    await flush();

    store.track({ latitude: 12, longitude: 22 });
    await flush();

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('clears the reading (hides the chip) on a network failure, never leaving a stale value', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ current: { wind_speed_10m: 1, wind_gusts_10m: 1 } }));
    const store = create();
    store.track(HERE);
    await flush();
    expect(store.reading()).toBeDefined();

    nowMs += 11 * 60 * 1000;
    fetchMock.mockRejectedValueOnce(new Error('offline'));
    store.track(HERE);
    await flush();

    expect(store.reading()).toBeUndefined();
  });

  it('clears the reading on a non-2xx response', async () => {
    fetchMock.mockResolvedValue(jsonResponse({}, false, 503));
    const store = create();

    store.track(HERE);
    await flush();

    expect(store.reading()).toBeUndefined();
  });

  it('clears the reading when the payload does not parse into a wind reading', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ current: {} }));
    const store = create();

    store.track(HERE);
    await flush();

    expect(store.reading()).toBeUndefined();
  });

  it('never starts a second fetch while one is still in flight', async () => {
    let resolveFetch: (value: Response) => void = () => undefined;
    fetchMock.mockReturnValue(new Promise<Response>((resolve) => (resolveFetch = resolve)));
    const store = create();

    store.track(HERE);
    store.track(HERE); // fired again before the first ever resolves
    expect(fetchMock).toHaveBeenCalledTimes(1);

    resolveFetch(jsonResponse({ current: { wind_speed_10m: 1, wind_gusts_10m: 1 } }));
    await flush();
  });

  it('records a failed attempt so it still respects the cache window before retrying', async () => {
    fetchMock.mockRejectedValueOnce(new Error('offline'));
    const store = create();
    store.track(HERE);
    await flush();
    expect(fetchMock).toHaveBeenCalledTimes(1);

    nowMs += 60_000; // still within the cache window
    store.track(HERE);
    await flush();

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
