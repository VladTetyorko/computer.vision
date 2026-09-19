import { describe, expect, it } from 'vitest';
import type { WindReading } from '../weather-logic';
import { WeatherApiActions, WeatherPageActions } from './weather.actions';
import { initialWeatherState } from './weather.model';
import { weatherFeature } from './weather.reducer';

const { reducer } = weatherFeature;

const HERE = { latitude: 10, longitude: 20 };
const THERE = { latitude: 30, longitude: 40 };

function reading(overrides: Partial<WindReading> = {}): WindReading {
  return { speedMps: 4.2, gustsMps: 5.1, precipitationMm: 0, observedAt: 't0', fetchedAtMs: 1_000, ...overrides };
}

describe('weatherFeature reducer', () => {
  it('starts with no hosts at all', () => {
    expect(initialWeatherState.byHostId).toEqual({});
  });

  it('trackRequested seeds a fresh host entry as in-flight', () => {
    const state = reducer(
      initialWeatherState,
      WeatherPageActions.trackRequested({ hostId: 'host-a', position: HERE, nowMs: 1_000 }),
    );
    expect(state.byHostId['host-a']).toEqual({
      reading: undefined,
      lastAttemptedAtMs: 1_000,
      lastPosition: HERE,
      inFlight: true,
    });
  });

  it('fetchSucceeded stores the reading and clears inFlight for that host only', () => {
    const tracked = reducer(
      initialWeatherState,
      WeatherPageActions.trackRequested({ hostId: 'host-a', position: HERE, nowMs: 1_000 }),
    );
    const state = reducer(tracked, WeatherApiActions.fetchSucceeded({ hostId: 'host-a', reading: reading() }));
    expect(state.byHostId['host-a'].reading).toEqual(reading());
    expect(state.byHostId['host-a'].inFlight).toBe(false);
  });

  it('fetchFailed clears the reading (hides the chip), never leaving a stale value', () => {
    const tracked = reducer(
      initialWeatherState,
      WeatherPageActions.trackRequested({ hostId: 'host-a', position: HERE, nowMs: 1_000 }),
    );
    const succeeded = reducer(tracked, WeatherApiActions.fetchSucceeded({ hostId: 'host-a', reading: reading() }));
    const state = reducer(succeeded, WeatherApiActions.fetchFailed({ hostId: 'host-a' }));
    expect(state.byHostId['host-a'].reading).toBeUndefined();
    expect(state.byHostId['host-a'].inFlight).toBe(false);
  });

  it('hostReleased removes that host entry entirely', () => {
    const tracked = reducer(
      initialWeatherState,
      WeatherPageActions.trackRequested({ hostId: 'host-a', position: HERE, nowMs: 1_000 }),
    );
    const state = reducer(tracked, WeatherPageActions.hostReleased({ hostId: 'host-a' }));
    expect(state.byHostId['host-a']).toBeUndefined();
  });

  describe('two hosts never clobber each other', () => {
    it('tracking two hosts keeps two entirely independent entries', () => {
      const afterA = reducer(
        initialWeatherState,
        WeatherPageActions.trackRequested({ hostId: 'command', position: HERE, nowMs: 1_000 }),
      );
      const afterB = reducer(
        afterA,
        WeatherPageActions.trackRequested({ hostId: 'fly', position: THERE, nowMs: 2_000 }),
      );

      expect(afterB.byHostId['command']).toEqual({
        reading: undefined,
        lastAttemptedAtMs: 1_000,
        lastPosition: HERE,
        inFlight: true,
      });
      expect(afterB.byHostId['fly']).toEqual({
        reading: undefined,
        lastAttemptedAtMs: 2_000,
        lastPosition: THERE,
        inFlight: true,
      });
    });

    it("fly's fetch resolving never touches command's entry, and vice versa", () => {
      const tracked = [
        WeatherPageActions.trackRequested({ hostId: 'command', position: HERE, nowMs: 1_000 }),
        WeatherPageActions.trackRequested({ hostId: 'fly', position: THERE, nowMs: 1_000 }),
      ].reduce(reducer, initialWeatherState);

      const flyReading = reading({ speedMps: 9 });
      const afterFlySucceeded = reducer(tracked, WeatherApiActions.fetchSucceeded({ hostId: 'fly', reading: flyReading }));

      // command is still exactly where trackRequested left it — untouched by fly's own outcome.
      expect(afterFlySucceeded.byHostId['command']).toEqual({
        reading: undefined,
        lastAttemptedAtMs: 1_000,
        lastPosition: HERE,
        inFlight: true,
      });
      expect(afterFlySucceeded.byHostId['fly'].reading).toEqual(flyReading);

      const afterCommandFailed = reducer(afterFlySucceeded, WeatherApiActions.fetchFailed({ hostId: 'command' }));
      // fly's already-settled reading survives command's own failure untouched.
      expect(afterCommandFailed.byHostId['fly'].reading).toEqual(flyReading);
      expect(afterCommandFailed.byHostId['command'].reading).toBeUndefined();
    });

    it("releasing one host's entry leaves the other's completely intact", () => {
      const tracked = [
        WeatherPageActions.trackRequested({ hostId: 'command', position: HERE, nowMs: 1_000 }),
        WeatherPageActions.trackRequested({ hostId: 'fly', position: THERE, nowMs: 1_000 }),
      ].reduce(reducer, initialWeatherState);

      const state = reducer(tracked, WeatherPageActions.hostReleased({ hostId: 'command' }));

      expect(state.byHostId['command']).toBeUndefined();
      expect(state.byHostId['fly']).toBeDefined();
    });
  });
});
