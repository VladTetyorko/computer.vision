import { Injectable, signal } from '@angular/core';
import type { GeoPosition } from '../api/models';
import { openMeteoForecastUrl, parseOpenMeteoReading, shouldRefetchWeather, type WindReading } from './weather-logic';

/** Console prefix mirroring `[player]`/`[fleet]`/`[fly]`/`[live]` — a stable per-file tag, no shared logging service. */
const LOG_PREFIX = '[weather]';

/**
 * `WeatherStore` (docs/OPS-CORE-PLAN.md §W) — Open-Meteo wind/precipitation for the go/no-go chip.
 * **Page-provided, not `providedIn: 'root'`** — mirrors `TelemetryStore`/`DetectionsStore`'s own
 * "different hosts care about different things" precedent: Command's chip is centered on the
 * fleet centroid, Fly's on the currently-flown asset's own position, and those two positions can
 * differ (and change) completely independently, so one shared root instance would have the two
 * pages fight over a single `reading` signal for two different places. `CommandPage`/`FlyPage`
 * each list this in their own `providers` and drive it from an `effect()` keyed on their own
 * position source, exactly like `TelemetryStore.track(deviceId)`.
 *
 * **Plain `fetch()`, not `HttpClient`** — Open-Meteo is a third-party, no-key, CORS-open public
 * API, not this app's own backend; `VisionApi`'s "the only place the frontend knows REST URLs" rule
 * is about *this app's* REST surface, and `shared/map/tile-cache/leaflet-loader.ts`'s own tile
 * `fetch()` calls are the standing precedent for reaching a public third-party host directly.
 *
 * **Cache, not a poll** (docs/OPS-CORE-PLAN.md §W: "refresh ≤ every 10 min, cached"): `track()` is
 * called from a host's own `effect()` every time its position signal changes (which, fed by a 5s
 * telemetry/fleet poll, is often — every few seconds) but only ever issues a real request when
 * `shouldRefetchWeather` says the cache is stale or the position moved meaningfully; there is no
 * `PollScheduler` registration here at all, since this store never needs to do anything on its own
 * initiative between calls.
 *
 * **Failure/offline → the chip hides entirely, never a stale fake** (docs/OPS-CORE-PLAN.md §W): a
 * failed fetch clears `reading` to `undefined` rather than leaving the last-known number on screen
 * with no indication it's gone stale — `shared/ui/weather-chip.ts` renders nothing at all once
 * `reading()` is `undefined`. The *attempt* time still advances on failure (see `track`'s own doc
 * comment) so a persistently-offline client doesn't hammer the endpoint on every position tick.
 */
@Injectable()
export class WeatherStore {
  private readonly readingSignal = signal<WindReading | undefined>(undefined);
  /** `undefined` means "no chip" — see class doc's "never a stale fake". */
  readonly reading = this.readingSignal.asReadonly();

  private lastAttemptedAtMs: number | undefined;
  private lastPosition: GeoPosition | undefined;
  private inFlight = false;

  /**
   * Ensures the reading is fresh for `position` — a no-op (not even a promise created) whenever
   * `position` is `undefined` (nothing known to center the forecast on yet), a fetch is already in
   * flight, or the existing reading is still within its cache window for essentially this same
   * place. Safe to call from an `effect()` on every tick of a fast-changing position signal.
   */
  track(position: GeoPosition | undefined): void {
    if (!position || this.inFlight) {
      return;
    }
    const now = Date.now();
    if (!shouldRefetchWeather(position, this.lastAttemptedAtMs, this.lastPosition, now)) {
      return;
    }
    void this.fetchNow(position, now);
  }

  private async fetchNow(position: GeoPosition, attemptedAtMs: number): Promise<void> {
    this.inFlight = true;
    // Recorded *before* the request settles, deliberately — a failure (offline, rate-limited,
    // CORS hiccup) must still count as "attempted", or a persistently-unreachable Open-Meteo would
    // get hit again on every single `track()` call instead of respecting the cache window.
    this.lastAttemptedAtMs = attemptedAtMs;
    this.lastPosition = position;
    try {
      const response = await fetch(openMeteoForecastUrl(position));
      if (!response.ok) {
        throw new Error(`Open-Meteo responded ${response.status}`);
      }
      const payload = await response.json();
      const reading = parseOpenMeteoReading(payload, Date.now());
      this.readingSignal.set(reading);
      if (!reading) {
        console.warn(`${LOG_PREFIX} unparseable forecast payload — hiding the chip`);
      }
    } catch (error) {
      console.warn(`${LOG_PREFIX} fetch failed — hiding the chip`, { error });
      this.readingSignal.set(undefined);
    } finally {
      this.inFlight = false;
    }
  }
}
