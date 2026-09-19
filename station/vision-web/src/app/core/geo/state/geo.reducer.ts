import { createFeature, createReducer, on } from '@ngrx/store';
import { GeoApiActions, GeoLiveActions, GeoPageActions } from './geo.actions';
import { initialGeoHostState, initialGeoState } from './geo.model';

export const geoFeature = createFeature({
  name: 'geo',
  reducer: createReducer(
    initialGeoState,
    on(GeoPageActions.trackRequested, (state, { hostId, assetId, initialTransport }) => ({
      byHostId: {
        ...state.byHostId,
        [hostId]: { ...initialGeoHostState, assetId, transport: initialTransport },
      },
    })),
    on(GeoPageActions.resetRequested, (state, { hostId }) => {
      const { [hostId]: _removed, ...rest } = state.byHostId;
      return { byHostId: rest };
    }),
    on(GeoPageActions.hostReleased, (state, { hostId }) => {
      const { [hostId]: _removed, ...rest } = state.byHostId;
      return { byHostId: rest };
    }),
    on(GeoLiveActions.transportResolved, (state, { hostId, transport }) => {
      const host = state.byHostId[hostId];
      if (!host) {
        return state;
      }
      return { byHostId: { ...state.byHostId, [hostId]: { ...host, transport } } };
    }),
    on(GeoApiActions.pollSucceeded, (state, { hostId, correction }) => {
      const host = state.byHostId[hostId];
      if (!host) {
        return state;
      }
      return { byHostId: { ...state.byHostId, [hostId]: { ...host, pollResult: correction } } };
    }),
    on(GeoApiActions.pollDisabled, (state, { hostId }) => {
      const host = state.byHostId[hostId];
      if (!host) {
        return state;
      }
      return { byHostId: { ...state.byHostId, [hostId]: { ...host, disabled: true } } };
    }),
  ),
});
