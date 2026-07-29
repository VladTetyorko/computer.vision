import type { GeoPosition } from '../api/models';

/**
 * Pure, Angular-free logic behind `core/weather/weather-store.ts` and the go/no-go chip
 * (`shared/ui/weather-chip.ts`; docs/OPS-CORE-PLAN.md §W) — the wind severity thresholds, the
 * chip's own label text, the fleet-centroid position helper, the Open-Meteo request URL/response
 * decoding, and the cache-vs-refetch decision — split out so every rule is unit-testable without
 * `fetch`/timers/a component, mirroring every other feature's own `*-logic.ts` split.
 */

// --- Wind severity (docs/OPS-CORE-PLAN.md §W's pinned thresholds) -------------------------------

export type WindSeverity = 'ok' | 'warn' | 'no-go';

/** The asset attribute key an operator sets to override the default limit (docs/OPS-CORE-PLAN.md §W). */
export const WIND_LIMIT_ATTRIBUTE_KEY = 'windLimitMps';

/** The limit used whenever an asset carries no `windLimitMps` attribute. */
export const DEFAULT_WIND_LIMIT_MPS = 10;

/** Below this fraction of the limit is `'ok'`; at/above it (but ≤100%) is `'warn'`; over 100% is `'no-go'`. */
const WARN_RATIO = 0.7;

/**
 * `windSeverity(speedMps, gustsMps, limitMps)` — the worse of steady speed and gusts decides the
 * tier (a calm steady wind with a violent gust is still a no-go): `< 70%` of `limitMps` is `'ok'`,
 * `70–100%` is `'warn'`, `> 100%` is `'no-go'`. Advisory only — this function (and every caller of
 * it) never blocks anything, it only informs (docs/OPS-CORE-PLAN.md §W: "advisory only … informs,
 * doesn't fake authority").
 */
export function windSeverity(speedMps: number, gustsMps: number, limitMps: number = DEFAULT_WIND_LIMIT_MPS): WindSeverity {
  const worst = Math.max(speedMps, gustsMps);
  if (limitMps <= 0) {
    return worst > 0 ? 'no-go' : 'ok';
  }
  const ratio = worst / limitMps;
  if (ratio > 1) {
    return 'no-go';
  }
  return ratio >= WARN_RATIO ? 'warn' : 'ok';
}

export interface WindAdvisory {
  readonly severity: WindSeverity;
  /** e.g. `"GO · 4.2 m/s, gusts 5.1"` / `"CAUTION · 7.4 m/s, gusts 8.9"` / `"NO-GO · 8.2 m/s, gusts 12.1"`. */
  readonly label: string;
}

const SEVERITY_PREFIX: Readonly<Record<WindSeverity, string>> = { ok: 'GO', warn: 'CAUTION', 'no-go': 'NO-GO' };

/** The chip's own verdict + label, one decimal place on each figure, always naming both speed and gusts. */
export function windAdvisory(speedMps: number, gustsMps: number, limitMps: number = DEFAULT_WIND_LIMIT_MPS): WindAdvisory {
  const severity = windSeverity(speedMps, gustsMps, limitMps);
  return {
    severity,
    label: `${SEVERITY_PREFIX[severity]} · ${speedMps.toFixed(1)} m/s, gusts ${gustsMps.toFixed(1)}`,
  };
}

/**
 * Reads `windLimitMps` off an asset's `attributes` map (docs/OPS-CORE-PLAN.md §W) — an absent,
 * non-numeric, or non-positive value falls back to `DEFAULT_WIND_LIMIT_MPS` rather than a crash or
 * a nonsensical zero/negative limit.
 */
export function parseWindLimitMps(attributes: Record<string, string> | undefined): number {
  const raw = attributes?.[WIND_LIMIT_ATTRIBUTE_KEY];
  if (raw === undefined) {
    return DEFAULT_WIND_LIMIT_MPS;
  }
  const parsed = Number(raw);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : DEFAULT_WIND_LIMIT_MPS;
}

// --- Fleet centroid (Command's chip position, when no one asset is selected) --------------------

/**
 * The plain arithmetic mean of `positions` — deliberately not geodesic (equirectangular/great-
 * circle math), same planar-approximation call `domain.model.GeofenceZone#contains` makes: accurate
 * enough at the scale a single fleet operates over, and Open-Meteo's own grid resolution (~1km) is
 * coarser than the error this introduces. `undefined` with no positioned assets at all — nothing to
 * center on, not a fabricated `{0,0}`.
 */
export function fleetCentroid(positions: readonly GeoPosition[]): GeoPosition | undefined {
  if (positions.length === 0) {
    return undefined;
  }
  const sum = positions.reduce(
    (acc, position) => ({ latitude: acc.latitude + position.latitude, longitude: acc.longitude + position.longitude }),
    { latitude: 0, longitude: 0 },
  );
  return { latitude: sum.latitude / positions.length, longitude: sum.longitude / positions.length };
}

// --- Open-Meteo request/response (docs/OPS-CORE-PLAN.md §W: no key, CORS-open) -------------------

/** Builds the exact Open-Meteo `forecast` URL for `position` — no API key, `wind_speed_unit=ms` so readings are already m/s. */
export function openMeteoForecastUrl(position: GeoPosition): string {
  const params = new URLSearchParams({
    latitude: position.latitude.toFixed(4),
    longitude: position.longitude.toFixed(4),
    current: 'wind_speed_10m,wind_gusts_10m,precipitation',
    wind_speed_unit: 'ms',
  });
  return `https://api.open-meteo.com/v1/forecast?${params.toString()}`;
}

/** The subset of Open-Meteo's own response shape this app reads — every other field is ignored, not typed. */
export interface OpenMeteoForecastPayload {
  readonly current?: {
    readonly time?: string;
    readonly wind_speed_10m?: number;
    readonly wind_gusts_10m?: number;
    readonly precipitation?: number;
  };
}

export interface WindReading {
  readonly speedMps: number;
  readonly gustsMps: number;
  readonly precipitationMm: number;
  /** Open-Meteo's own `current.time` (local to the forecast point, no timezone offset) — shown in the chip's tooltip verbatim, not reinterpreted. */
  readonly observedAt: string;
  /** When this app actually fetched the reading — drives the chip's own "updated Ns ago" tooltip text, independent of `observedAt`. */
  readonly fetchedAtMs: number;
}

/**
 * Decodes a raw Open-Meteo response into a `WindReading`, or `undefined` when the two fields this
 * app actually needs (`wind_speed_10m`/`wind_gusts_10m`) aren't both present/numeric — a malformed
 * or unexpectedly-shaped response degrades to "no reading" (the chip stays hidden), never a
 * fabricated `0`.
 */
export function parseOpenMeteoReading(payload: OpenMeteoForecastPayload, fetchedAtMs: number): WindReading | undefined {
  const current = payload.current;
  if (!current || typeof current.wind_speed_10m !== 'number' || typeof current.wind_gusts_10m !== 'number') {
    return undefined;
  }
  return {
    speedMps: current.wind_speed_10m,
    gustsMps: current.wind_gusts_10m,
    precipitationMm: current.precipitation ?? 0,
    observedAt: current.time ?? new Date(fetchedAtMs).toISOString(),
    fetchedAtMs,
  };
}

// --- Cache decision (docs/OPS-CORE-PLAN.md §W: "refresh ≤ every 10 min, cached") -----------------

/** The plan's own pinned minimum refresh interval. */
export const WEATHER_CACHE_MS = 10 * 60 * 1000;

/** Positions within this many degrees (roughly a few km at temperate latitudes) count as "the same place" for cache purposes. */
const POSITION_EPSILON_DEGREES = 0.05;

/**
 * Whether `WeatherStore` should issue a fresh request for `position` rather than reuse its last
 * fetch: always on the very first call (no prior fetch to reuse), once `cacheMs` has elapsed since
 * the last attempt (success *or* failure — see `WeatherStore#track`'s own doc comment for why a
 * failure still counts as "attempted, wait before retrying"), or once the tracked position has
 * moved far enough that the last reading no longer describes "here".
 */
export function shouldRefetchWeather(
  position: GeoPosition,
  lastAttemptedAtMs: number | undefined,
  lastPosition: GeoPosition | undefined,
  nowMs: number,
  cacheMs: number = WEATHER_CACHE_MS,
): boolean {
  if (lastAttemptedAtMs === undefined || lastPosition === undefined) {
    return true;
  }
  if (nowMs - lastAttemptedAtMs >= cacheMs) {
    return true;
  }
  return (
    Math.abs(position.latitude - lastPosition.latitude) > POSITION_EPSILON_DEGREES ||
    Math.abs(position.longitude - lastPosition.longitude) > POSITION_EPSILON_DEGREES
  );
}
