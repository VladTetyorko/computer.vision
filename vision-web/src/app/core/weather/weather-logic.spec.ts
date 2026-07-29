import { describe, expect, it } from 'vitest';
import {
  DEFAULT_WIND_LIMIT_MPS,
  fleetCentroid,
  openMeteoForecastUrl,
  parseOpenMeteoReading,
  parseWindLimitMps,
  shouldRefetchWeather,
  windAdvisory,
  windSeverity,
} from './weather-logic';

describe('windSeverity', () => {
  it('is ok comfortably under 70% of the limit', () => {
    expect(windSeverity(3, 4, 10)).toBe('ok');
    expect(windSeverity(6.9, 6.9, 10)).toBe('ok');
  });

  it('is warn from 70% up to and including 100% of the limit', () => {
    expect(windSeverity(7, 7, 10)).toBe('warn');
    expect(windSeverity(10, 10, 10)).toBe('warn');
  });

  it('is no-go strictly above the limit', () => {
    expect(windSeverity(10.1, 10.1, 10)).toBe('no-go');
  });

  it('takes the worse of speed and gusts', () => {
    expect(windSeverity(2, 12, 10)).toBe('no-go'); // calm steady, violent gust
    expect(windSeverity(12, 2, 10)).toBe('no-go'); // violent steady, calm gust reading (unusual, still honest)
  });

  it('defaults the limit to 10 m/s when not given', () => {
    expect(windSeverity(4, 4)).toBe('ok');
    expect(windSeverity(11, 11)).toBe('no-go');
  });

  it('never divides by a non-positive limit', () => {
    expect(windSeverity(0, 0, 0)).toBe('ok');
    expect(windSeverity(1, 1, 0)).toBe('no-go');
    expect(windSeverity(1, 1, -5)).toBe('no-go');
  });
});

describe('windAdvisory', () => {
  it('renders both figures with one decimal place and the right verdict prefix', () => {
    expect(windAdvisory(4.2, 5.06, 10)).toEqual({ severity: 'ok', label: 'GO · 4.2 m/s, gusts 5.1' });
    expect(windAdvisory(7.4, 8.9, 10)).toEqual({ severity: 'warn', label: 'CAUTION · 7.4 m/s, gusts 8.9' });
    expect(windAdvisory(8.2, 12.1, 10)).toEqual({ severity: 'no-go', label: 'NO-GO · 8.2 m/s, gusts 12.1' });
  });
});

describe('parseWindLimitMps', () => {
  it('defaults to 10 when the attribute is absent', () => {
    expect(parseWindLimitMps(undefined)).toBe(DEFAULT_WIND_LIMIT_MPS);
    expect(parseWindLimitMps({})).toBe(DEFAULT_WIND_LIMIT_MPS);
  });

  it('reads a valid override', () => {
    expect(parseWindLimitMps({ windLimitMps: '14.5' })).toBe(14.5);
  });

  it('falls back to the default for a non-numeric or non-positive value', () => {
    expect(parseWindLimitMps({ windLimitMps: 'fast' })).toBe(DEFAULT_WIND_LIMIT_MPS);
    expect(parseWindLimitMps({ windLimitMps: '0' })).toBe(DEFAULT_WIND_LIMIT_MPS);
    expect(parseWindLimitMps({ windLimitMps: '-3' })).toBe(DEFAULT_WIND_LIMIT_MPS);
  });
});

describe('fleetCentroid', () => {
  it('is undefined with no positions', () => {
    expect(fleetCentroid([])).toBeUndefined();
  });

  it('is the position itself with exactly one', () => {
    expect(fleetCentroid([{ latitude: 10, longitude: 20 }])).toEqual({ latitude: 10, longitude: 20 });
  });

  it('averages several positions', () => {
    expect(
      fleetCentroid([
        { latitude: 0, longitude: 0 },
        { latitude: 10, longitude: 20 },
      ]),
    ).toEqual({ latitude: 5, longitude: 10 });
  });
});

describe('openMeteoForecastUrl', () => {
  it('builds the pinned endpoint with m/s wind units', () => {
    const url = openMeteoForecastUrl({ latitude: 37.7749, longitude: -122.4194 });
    expect(url).toContain('https://api.open-meteo.com/v1/forecast?');
    expect(url).toContain('latitude=37.7749');
    expect(url).toContain('longitude=-122.4194');
    expect(url).toContain('current=wind_speed_10m%2Cwind_gusts_10m%2Cprecipitation');
    expect(url).toContain('wind_speed_unit=ms');
  });
});

describe('parseOpenMeteoReading', () => {
  it('decodes a well-formed payload', () => {
    const reading = parseOpenMeteoReading(
      { current: { time: '2026-07-28T12:00', wind_speed_10m: 4.2, wind_gusts_10m: 6.1, precipitation: 0 } },
      1000,
    );
    expect(reading).toEqual({ speedMps: 4.2, gustsMps: 6.1, precipitationMm: 0, observedAt: '2026-07-28T12:00', fetchedAtMs: 1000 });
  });

  it('defaults observedAt when the payload omits current.time', () => {
    const reading = parseOpenMeteoReading({ current: { wind_speed_10m: 1, wind_gusts_10m: 1 } }, 1000);
    expect(reading?.observedAt).toBe(new Date(1000).toISOString());
  });

  it('is undefined when current is missing entirely', () => {
    expect(parseOpenMeteoReading({}, 1000)).toBeUndefined();
  });

  it('is undefined when the wind fields are missing or non-numeric', () => {
    expect(parseOpenMeteoReading({ current: {} }, 1000)).toBeUndefined();
    expect(parseOpenMeteoReading({ current: { wind_speed_10m: 1 } }, 1000)).toBeUndefined();
  });
});

describe('shouldRefetchWeather', () => {
  const here = { latitude: 10, longitude: 20 };

  it('is true on the very first call (no prior attempt)', () => {
    expect(shouldRefetchWeather(here, undefined, undefined, 100_000)).toBe(true);
  });

  it('is false within the cache window at the same position', () => {
    expect(shouldRefetchWeather(here, 0, here, 5 * 60 * 1000)).toBe(false);
  });

  it('is true once the cache window has elapsed', () => {
    expect(shouldRefetchWeather(here, 0, here, 10 * 60 * 1000)).toBe(true);
  });

  it('is true when the position has moved far enough, even within the cache window', () => {
    const movedFar = { latitude: 10.5, longitude: 20.5 };
    expect(shouldRefetchWeather(movedFar, 0, here, 1_000)).toBe(true);
  });

  it('is false for a tiny position jitter well within the epsilon', () => {
    const jitter = { latitude: 10.001, longitude: 20.001 };
    expect(shouldRefetchWeather(jitter, 0, here, 1_000)).toBe(false);
  });
});
