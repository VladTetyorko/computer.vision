import { createFeature, createReducer, on } from '@ngrx/store';
import { SeatApiActions, SeatPageActions } from './seat.actions';
import { initialSeatState } from './seat.model';

export const seatFeature = createFeature({
  name: 'seat',
  reducer: createReducer(
    initialSeatState,
    // Mirrors `SeatStore#track`'s own immediate `this.seatsSignal.set(undefined)` — a brand-new key
    // is already `undefined`; a *re*-tracked key drops its previous session's last-known-good value
    // rather than showing possibly-very-stale data until the new poll resolves.
    on(SeatPageActions.tracked, (state, { assetId }) => ({
      ...state,
      byAssetId: { ...state.byAssetId, [assetId]: undefined },
    })),
    on(SeatPageActions.reset, (state, { assetId }) => {
      const { [assetId]: _removed, ...rest } = state.byAssetId;
      return { ...state, byAssetId: rest };
    }),
    on(SeatApiActions.seatsReceived, (state, { assetId, response }) => ({
      ...state,
      byAssetId: { ...state.byAssetId, [assetId]: response },
    })),
    // `Read Failed` is deliberately a no-op here — the whole point is leaving the last-known-good
    // entry (or the absent-entry fallback) exactly as it was; see the class doc this slice replaces.
    on(SeatApiActions.readFailed, (state) => state),
  ),
});
