import { createFeature, createReducer, createSelector, on } from '@ngrx/store';
import { type AssetScopedTransport, resolveAssetScopedTransport } from '../../live/live-fallback-logic';
import { liveFeature } from '../../live/state/live.reducer';
import { TelemetryApiActions, TelemetryPageActions } from './telemetry.actions';
import { initialTelemetryState } from './telemetry.model';

export const telemetryFeature = createFeature({
  name: 'telemetry',
  reducer: createReducer(
    initialTelemetryState,
    // Mirrors `TelemetryStore#startTracking`'s own immediate clear — a brand-new key starts empty; a
    // *re*-tracked key drops its previous session's samples rather than showing stale data until the
    // new backfill/poll resolves. `assetId` is set synchronously here (it is a `track()` parameter,
    // never itself resolved asynchronously — only the usage id is) so a same-tick selector read
    // already sees it, even though nothing subscribes to the transport selector until after the
    // async backfill below settles (`telemetry.effects.ts#session$`'s own doc comment).
    on(TelemetryPageActions.tracked, (state, { deviceId, assetId }) => ({
      ...state,
      byDeviceId: { ...state.byDeviceId, [deviceId]: { assetId, backfill: [], pollSamples: [] } },
    })),
    on(TelemetryPageActions.reset, (state, { deviceId }) => {
      const { [deviceId]: _removed, ...rest } = state.byDeviceId;
      return { ...state, byDeviceId: rest };
    }),
    // Deliberately a no-op — the entry `tracked` already created stays exactly as it is (no open
    // usage means nothing to poll or subscribe to; see `TelemetryFacade`'s class doc on silent-degrade).
    on(TelemetryApiActions.usageNotFound, (state) => state),
    on(TelemetryApiActions.backfillReceived, (state, { deviceId, samples }) => {
      const entry = state.byDeviceId[deviceId];
      if (entry === undefined) {
        return state; // superseded by a reset() before this async lookup settled
      }
      return {
        ...state,
        byDeviceId: { ...state.byDeviceId, [deviceId]: { ...entry, backfill: samples, pollSamples: samples } },
      };
    }),
    on(TelemetryApiActions.pollReceived, (state, { deviceId, samples }) => {
      const entry = state.byDeviceId[deviceId];
      if (entry === undefined) {
        return state;
      }
      return { ...state, byDeviceId: { ...state.byDeviceId, [deviceId]: { ...entry, pollSamples: samples } } };
    }),
    // Silent-degrade: a missed poll just leaves `pollSamples` at its last-known value.
    on(TelemetryApiActions.pollFailed, (state) => state),
  ),
});

/**
 * The poll-vs-live decision for `deviceId`, derived purely from this slice's own stored `assetId`
 * plus the `live` slice's connection state — the concrete answer to NGRX-MIGRATION-PLAN.md §3's
 * convention 9 ("cross-slice reads go through selectors, never by injecting another slice's facade
 * into an effect"). Lives here, outside `telemetryFeature`'s own `extraSelectors`, because
 * `createFeature` only ever sees its own feature's state — combining two slices' selectors needs a
 * plain `createSelector` call. `telemetry.effects.ts`'s poll-gate is this selector's only
 * subscriber; `TelemetryFacade` derives the same decision for display purposes directly from
 * `LiveFacade` + `resolveAssetScopedTransport` (a facade reading another facade is not the hazard
 * this convention guards against — only an eagerly-injected cross-slice facade inside a
 * `createEffect` factory is, see NGRX-MIGRATION-PLAN.md §9).
 */
export function transportSelectorFor(deviceId: string) {
  return createSelector(
    telemetryFeature.selectByDeviceId,
    liveFeature.selectConnectionState,
    (byDeviceId, connectionState): AssetScopedTransport =>
      resolveAssetScopedTransport(connectionState, byDeviceId[deviceId]?.assetId),
  );
}
