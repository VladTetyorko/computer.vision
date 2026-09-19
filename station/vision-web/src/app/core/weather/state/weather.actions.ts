import { createActionGroup, props } from '@ngrx/store';
import type { GeoPosition } from '../../api/models';
import type { WindReading } from '../weather-logic';

/** Every command a `WeatherFacade` host issues — each carrying its own `hostId` explicitly, so the
 *  one app-wide-registered effect can key its work off the action alone (never off which facade
 *  instance happened to dispatch it), exactly like `SeatPageActions`. */
export const WeatherPageActions = createActionGroup({
  source: 'Weather Page',
  events: {
    'Track Requested': props<{ hostId: string; position: GeoPosition; nowMs: number }>(),
    /** Dispatched from `WeatherFacade`'s own `DestroyRef.onDestroy` — releases this host's
     *  `byHostId` entry so a page re-visited many times over one SPA session never leaks one. */
    'Host Released': props<{ hostId: string }>(),
  },
});

export const WeatherApiActions = createActionGroup({
  source: 'Weather API',
  events: {
    /** `reading: undefined` covers Open-Meteo answering 2xx with an unparseable payload — still a
     *  "succeeded" fetch (not retried early), same as the old store's own `fetchNow`. */
    'Fetch Succeeded': props<{ hostId: string; reading: WindReading | undefined }>(),
    'Fetch Failed': props<{ hostId: string }>(),
  },
});
