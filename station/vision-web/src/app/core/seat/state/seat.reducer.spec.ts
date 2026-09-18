import { describe, expect, it } from 'vitest';
import { singleOperatorSeats } from '../seat-logic';
import { SeatApiActions, SeatPageActions } from './seat.actions';
import { initialSeatState } from './seat.model';
import { seatFeature } from './seat.reducer';

const { reducer } = seatFeature;

describe('seat reducer', () => {
  it('starts with no tracked assets', () => {
    expect(initialSeatState).toEqual({ byAssetId: {} });
  });

  it('Tracked seeds the asset as undefined (no stale value shown while the first poll is in flight)', () => {
    const state = reducer(initialSeatState, SeatPageActions.tracked({ assetId: 'a-1' }));
    expect(state.byAssetId).toEqual({ 'a-1': undefined });
  });

  it('re-Tracking drops a previous session’s last-known-good value', () => {
    const seeded = reducer(initialSeatState, SeatApiActions.seatsReceived({ assetId: 'a-1', response: singleOperatorSeats('a-1') }));
    const state = reducer(seeded, SeatPageActions.tracked({ assetId: 'a-1' }));
    expect(state.byAssetId).toEqual({ 'a-1': undefined });
  });

  it('Seats Received stores the response under its own assetId, leaving other assets untouched', () => {
    const withA = reducer(initialSeatState, SeatApiActions.seatsReceived({ assetId: 'a-1', response: singleOperatorSeats('a-1') }));
    const withBoth = reducer(withA, SeatApiActions.seatsReceived({ assetId: 'b-1', response: singleOperatorSeats('b-1') }));
    expect(withBoth.byAssetId['a-1']).toEqual(singleOperatorSeats('a-1'));
    expect(withBoth.byAssetId['b-1']).toEqual(singleOperatorSeats('b-1'));
  });

  it('Read Failed is a no-op — the last-known-good entry (or its absence) is left exactly as it was', () => {
    const seeded = reducer(initialSeatState, SeatApiActions.seatsReceived({ assetId: 'a-1', response: singleOperatorSeats('a-1') }));
    const state = reducer(seeded, SeatApiActions.readFailed({ assetId: 'a-1' }));
    expect(state).toBe(seeded);
  });

  it('Reset removes only its own assetId', () => {
    const withA = reducer(initialSeatState, SeatPageActions.tracked({ assetId: 'a-1' }));
    const withBoth = reducer(withA, SeatPageActions.tracked({ assetId: 'b-1' }));
    const state = reducer(withBoth, SeatPageActions.reset({ assetId: 'a-1' }));
    expect(state.byAssetId).toEqual({ 'b-1': undefined });
  });
});
