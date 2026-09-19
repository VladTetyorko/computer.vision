import { createFeature, createReducer, createSelector, on } from '@ngrx/store';
import { LiveSocketActions } from '../../live/state/live.actions';
import { FleetApiActions, FleetPageActions } from './fleet.actions';
import { devicesAdapter, initialFleetState, streamsAdapter, type FleetState } from './fleet.model';

const { selectAll: selectAllDevicesEntities } = devicesAdapter.getSelectors();
const { selectAll: selectAllStreamsEntities } = streamsAdapter.getSelectors();

export const fleetFeature = createFeature({
  name: 'fleet',
  reducer: createReducer(
    initialFleetState,

    // --- The explicit "Refresh" button only — see `fleet.model.ts#FleetState.loading`'s own doc
    // comment for why the automatic poll/live/post-mutation paths never touch `loading` at all.
    on(FleetPageActions.refreshRequested, (state): FleetState => ({ ...state, loading: true })),

    /**
     * The one place either transport's device/stream snapshot is folded — `FleetStore
     * .applyDevicesSnapshot`'s own contract, reused for the poll's `Promise.all` result too (see
     * `fleet.effects.ts#refreshDevicesAndStreams$`). `reachable: true` unconditionally: a snapshot
     * arriving at all — poll or live — is itself proof the backend is reachable.
     */
    on(FleetApiActions.refreshSucceeded, (state, { devices, streams }): FleetState => ({
      ...state,
      devices: devicesAdapter.setAll([...devices], state.devices),
      streams: streamsAdapter.setAll([...streams], state.streams),
      reachable: true,
      loading: false,
    })),
    // A failed refresh degrades to stale-but-present data (CLAUDE.md's "never show a blocked page"
    // rule) — only `reachable`/`loading` change, `devices`/`streams` are left exactly as they were.
    on(FleetApiActions.refreshFailed, (state): FleetState => ({ ...state, reachable: false, loading: false })),

    on(FleetApiActions.modelsLoaded, (state, { models }): FleetState => ({ ...state, models })),
    // `modelsLoadFailed` needs no handler — silent degrade, `models` stays `[]` (see fleet.actions.ts).
    on(FleetApiActions.trackersLoaded, (state, { trackers }): FleetState => ({ ...state, trackers })),
    // `trackersLoadFailed` needs no handler — same silent degrade.

    /**
     * The always-on `devices` SSE topic (`DevicesSnapshot`, mirroring `FleetStore`'s own live
     * `effect()`) — both lists set atomically from the *same* envelope, independent of whether the
     * poll is currently running, so a snapshot arriving before `fleet.effects.ts#gate$` has actually
     * stopped polling (or one arriving mid-poll) is never dropped.
     */
    on(LiveSocketActions.envelopeReceived, (state, { envelope }): FleetState =>
      envelope.type === 'devices'
        ? {
            ...state,
            devices: devicesAdapter.setAll([...envelope.payload.devices], state.devices),
            streams: streamsAdapter.setAll([...envelope.payload.streams], state.streams),
            reachable: true,
          }
        : state,
    ),

    // Every mutation below always re-derives `devices`/`streams` through its own trailing
    // `refreshSucceeded`/`refreshFailed` (see `fleet.effects.ts`'s per-mutation effect) rather than
    // folding its own returned entity in place — `FleetStore`'s own contract for every one of these
    // (a full re-fetch, never a targeted patch), preserved because some mutations change more than
    // the one entity returned (e.g. deleting an asset also archives its devices). So none of the
    // `*Succeeded` actions below need a reducer handler at all — they exist only so
    // `dispatchAndAwait` has a value to resolve the facade's `Promise` with.
  ),
  extraSelectors: ({ selectDevices, selectStreams }) => {
    const selectAllDevices = createSelector(selectDevices, selectAllDevicesEntities);
    const selectAllStreams = createSelector(selectStreams, selectAllStreamsEntities);
    const selectLiveDeviceIds = createSelector(selectAllStreams, (streams) => new Set(streams.map((stream) => stream.deviceId)));
    return { selectAllDevices, selectAllStreams, selectLiveDeviceIds };
  },
});
