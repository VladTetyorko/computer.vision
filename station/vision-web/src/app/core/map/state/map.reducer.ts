import { createFeature, createReducer, createSelector, on } from '@ngrx/store';
import { MapApiActions, MapPageActions } from './map.actions';
import { initialMapState, type MapState, type MapTrackerState } from './map.model';

/**
 * A fresh mount resets `assets`/`trackers` to initial rather than just bumping the count — mirrors
 * `RouteFacade`'s own "a fresh mount resets stale global slice state" precedent (`route-facade.ts`'s
 * doc comment). `FleetMapStore` was a genuinely fresh instance on every mount (`assetsSignal =
 * signal([])`), and this slice's data is never meant to be shared visually across concurrent
 * consumers — `CommandPage` is the only host, one at a time (the old class's own doc: "two
 * independent instances, never simultaneous in this single-route-at-a-time SPA").
 */
function activate(state: MapState): MapState {
  return { ...initialMapState, activeConsumers: state.activeConsumers + 1 };
}

function release(state: MapState): MapState {
  return { ...state, activeConsumers: Math.max(0, state.activeConsumers - 1) };
}

/** Only ADDS a hollow entry per newly-started id — `stopped` ids are deleted later, one at a time,
 *  by the per-asset `Tracker Removed` the lifecycle effect dispatches only after the real unsubscribe
 *  side effect (`live.effects`'s untrack) completes (see `map.effects.ts#trackerLifecycle$`'s own doc
 *  comment for why deletion must not happen here). */
function reconcileTrackers(state: MapState, started: readonly string[]): MapState {
  if (started.length === 0) {
    return state;
  }
  const trackers: Record<string, MapTrackerState> = { ...state.trackers };
  for (const assetId of started) {
    trackers[assetId] = { backfill: [], usageId: undefined };
  }
  return { ...state, trackers };
}

function removeTracker(state: MapState, assetId: string): MapState {
  if (state.trackers[assetId] === undefined) {
    return state;
  }
  const { [assetId]: _removed, ...rest } = state.trackers;
  return { ...state, trackers: rest };
}

export const mapFeature = createFeature({
  name: 'map',
  reducer: createReducer(
    initialMapState,
    on(MapPageActions.activated, activate),
    on(MapPageActions.released, release),

    on(MapApiActions.assetsLoaded, (state, { assets }) => ({ ...state, assets })),
    // Modeled, never a swallowed catch (NGRX-MIGRATION-PLAN.md §3 rule 7) — but there is nothing to
    // change: `FleetMapStore.refresh()`'s own catch left every signal at its last-known value.
    on(MapApiActions.assetsLoadFailed, (state) => state),

    on(MapPageActions.trackersReconciled, (state, { started }) => reconcileTrackers(state, started)),
    on(MapPageActions.trackerRemoved, (state, { assetId }) => removeTracker(state, assetId)),

    // A stale/superseded action for an already-removed tracker is a safe no-op — defense-in-depth,
    // even though `trackerLifecycle$`'s own `groupBy`/`switchMap` cancellation should already prevent
    // one from arriving after the matching `Tracker Removed`.
    on(MapApiActions.trackerBackfillLoaded, (state, { assetId, samples, usageId }) =>
      state.trackers[assetId] === undefined
        ? state
        : { ...state, trackers: { ...state.trackers, [assetId]: { backfill: samples, usageId } } },
    ),
    on(MapApiActions.trackerPollLoaded, (state, { assetId, samples }) =>
      state.trackers[assetId] === undefined
        ? state
        : { ...state, trackers: { ...state.trackers, [assetId]: { ...state.trackers[assetId], backfill: samples } } },
    ),
  ),
  extraSelectors: ({ selectTrackers }) => ({
    selectTrackerIds: createSelector(selectTrackers, (trackers) => Object.keys(trackers)),
  }),
});
