import { createFeature, createReducer, on } from '@ngrx/store';
import { WeatherApiActions, WeatherPageActions } from './weather.actions';
import { initialWeatherHostState, initialWeatherState } from './weather.model';

export const weatherFeature = createFeature({
  name: 'weather',
  reducer: createReducer(
    initialWeatherState,
    // Recorded *before* the request settles, deliberately — see `WeatherFacade#track`'s own doc
    // comment: a failure must still count as "attempted", or a persistently-unreachable Open-Meteo
    // would get hit again on every position tick instead of respecting the cache window.
    on(WeatherPageActions.trackRequested, (state, { hostId, position, nowMs }) => ({
      ...state,
      byHostId: {
        ...state.byHostId,
        [hostId]: {
          ...(state.byHostId[hostId] ?? initialWeatherHostState),
          inFlight: true,
          lastAttemptedAtMs: nowMs,
          lastPosition: position,
        },
      },
    })),
    on(WeatherApiActions.fetchSucceeded, (state, { hostId, reading }) => ({
      ...state,
      byHostId: {
        ...state.byHostId,
        [hostId]: { ...(state.byHostId[hostId] ?? initialWeatherHostState), reading, inFlight: false },
      },
    })),
    // Clears the reading (hides the chip) rather than leaving a stale value on screen — see
    // `weather.model.ts`'s own doc comment.
    on(WeatherApiActions.fetchFailed, (state, { hostId }) => ({
      ...state,
      byHostId: {
        ...state.byHostId,
        [hostId]: { ...(state.byHostId[hostId] ?? initialWeatherHostState), reading: undefined, inFlight: false },
      },
    })),
    on(WeatherPageActions.hostReleased, (state, { hostId }) => {
      const { [hostId]: _removed, ...rest } = state.byHostId;
      return { ...state, byHostId: rest };
    }),
  ),
});
