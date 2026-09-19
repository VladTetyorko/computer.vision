import type { GeoPosition } from '../../api/models';
import type { WindReading } from '../weather-logic';

/**
 * One host's cache bookkeeping — keyed by a synthetic `hostId` minted once per `WeatherFacade`
 * instance (see that file's own doc comment), never by `assetId`: Command's chip centers on the
 * fleet centroid (no asset selected at all, often); Fly's centers on the currently-flown asset.
 * There is no key the two hosts naturally share, so this slice mints its own rather than risk
 * collapsing two hosts' readings into one — the exact regression this wave was warned about, and
 * `weather.reducer.spec.ts`/`weather-facade.spec.ts`'s own "two hosts" cases prove does not happen.
 */
export interface WeatherHostState {
  /** `undefined` means "no chip" — a failed/unparseable fetch clears this rather than leaving a stale number on screen. */
  readonly reading: WindReading | undefined;
  readonly lastAttemptedAtMs: number | undefined;
  readonly lastPosition: GeoPosition | undefined;
  readonly inFlight: boolean;
}

export const initialWeatherHostState: WeatherHostState = {
  reading: undefined,
  lastAttemptedAtMs: undefined,
  lastPosition: undefined,
  inFlight: false,
};

export interface WeatherState {
  readonly byHostId: Readonly<Record<string, WeatherHostState>>;
}

export const initialWeatherState: WeatherState = { byHostId: {} };
