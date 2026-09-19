import { Injectable, inject } from '@angular/core';
import { Actions } from '@ngrx/effects';
import { Store } from '@ngrx/store';
import { VisionApi } from '../api/vision-api';
import type {
  ActiveStream,
  AssetDeletionResponse,
  AssetDetails,
  AssetEdit,
  AssetSummary,
  Device,
  DeviceEdit,
  PatchStreamConfigResponse,
  RegisterDeviceRequest,
  SettableLifecycleState,
  SimulationResponse,
  StartSimulationRequest,
  StartStreamRequest,
  StartStreamResult,
  StreamTracksResponse,
  UpdateStreamConfigRequest,
} from '../api/models';
import { dispatchAndAwait } from '../state/dispatch-bridge';
import { FleetApiActions, FleetPageActions } from './state/fleet.actions';
import { fleetFeature } from './state/fleet.reducer';

/**
 * `FleetStore`'s read/dispatch boundary (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N4b).
 * `providedIn: 'root'`, exactly like the store it replaces — devices/streams are shared app-wide (the
 * Wall, the Devices tab and a Live view must never disagree), and this facade is injected directly
 * by `app.ts` plus `shared/ui/app-sidebar/**`/`notification-bell.ts`/`events-rail.ts`, all reachable
 * before any lazy route loads. See `core/state/app-state.ts`'s own doc comment for why that keeps
 * `fleet` root-registered rather than moved by wave N-split's "page-provided facade" rule.
 *
 * Dispatches its own `FleetPageActions.booted()` once, here, in the constructor — the same
 * "singleton facade kicks off its own one-shot boot work" idiom `AuthFacade`/`ThresholdsFacade`
 * already use — so `fleet.effects.ts#loadRosters$` fetches the CV model + tracker rosters exactly
 * once for the app's lifetime. The devices/streams poll needs no such trigger; see
 * `fleet.effects.ts#gate$`'s own doc comment.
 */
@Injectable({ providedIn: 'root' })
export class FleetFacade {
  private readonly store = inject(Store);
  private readonly actions$ = inject(Actions);
  private readonly api = inject(VisionApi);

  readonly devices = this.store.selectSignal(fleetFeature.selectAllDevices);
  readonly streams = this.store.selectSignal(fleetFeature.selectAllStreams);
  readonly loading = this.store.selectSignal(fleetFeature.selectLoading);
  /** `null` until the first request ever settles, so the header shows no verdict prematurely. */
  readonly reachable = this.store.selectSignal(fleetFeature.selectReachable);
  /** Empty until the one-shot fetch resolves, and permanently empty on failure — see class doc.
   *  `getCvModels()` never errors server-side, so an empty list here means either "still loading" or
   *  "the request itself failed"; every reader degrades to "no extra model facts" rather than
   *  blocking on either (`cv-control-panel-logic.ts#findModel`). */
  readonly models = this.store.selectSignal(fleetFeature.selectModels);
  /** Empty until the one-shot fetch resolves, and permanently empty on failure or against a
   *  pre-tracking server — `cv-control-panel.ts`'s Tracking section degrades to "engine list
   *  unavailable" exactly like `models` does. */
  readonly trackers = this.store.selectSignal(fleetFeature.selectTrackers);
  /** Device ids that currently have a running stream. */
  readonly liveDeviceIds = this.store.selectSignal(fleetFeature.selectLiveDeviceIds);

  constructor() {
    this.store.dispatch(FleetPageActions.booted());
  }

  /** The stream currently running for a device, if any. */
  streamFor(deviceId: string): ActiveStream | undefined {
    return this.streams().find((stream) => stream.deviceId === deviceId);
  }

  device(deviceId: string): Device | undefined {
    return this.devices().find((device) => device.id === deviceId);
  }

  /**
   * One stream's live track book + duty-cycle stats (docs/plans/done/TRACKING-PLAN.md §4.E) —
   * `cv-control-panel.ts`'s own poll for the flow strip + the "Following #N" lock-confirmation chip.
   * **Deliberately calls `VisionApi` directly, not through an action** — same "occasional, uncached,
   * stateless passthrough" exception `core/map/map-facade.ts#resolveWatchDevice` documents: a fast
   * background poll on a fast poll cadence that must *rethrow* on failure so the caller's own
   * `catch` decides what "hidden" means, which an NgRx action's serializable payload cannot carry
   * (the raw `Error`/`HttpErrorResponse` would trip `strictActionSerializability`). Not `run()`-
   * wrapped in the old store either — no toast, ever.
   */
  getStreamTracks(streamId: string): Promise<StreamTracksResponse> {
    return this.api.getStreamTracks(streamId);
  }

  /**
   * Re-reads devices and streams together — the Devices page's own explicit "Refresh" button.
   * `quiet` suppresses the toast for background polls; every automatic refresh bypasses this method
   * entirely (see `fleet.effects.ts#gate$`'s own doc comment), so in practice only an explicit,
   * user-initiated call ever reaches here. Never rejects, mirroring `FleetStore.refresh`'s own
   * "errors become a toast, not a throw" contract.
   */
  async refresh(options: { quiet?: boolean } = {}): Promise<void> {
    await dispatchAndAwait(
      this.store,
      this.actions$,
      FleetPageActions.refreshRequested({ quiet: options.quiet ?? false }),
      FleetApiActions.refreshSucceeded,
      FleetApiActions.refreshFailed,
      () => undefined,
      () => undefined,
    );
  }

  async register(request: RegisterDeviceRequest): Promise<Device | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      FleetPageActions.registerRequested({ request }),
      FleetApiActions.registerSucceeded,
      FleetApiActions.registerFailed,
      (action) => action.device,
      () => null,
    );
  }

  // --- Warehouse: device lifecycle (docs/main/CYCLES-PLAN.md §8's pinned contract) ----------------

  async updateDevice(id: string, edit: DeviceEdit): Promise<Device | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      FleetPageActions.updateDeviceRequested({ id, edit }),
      FleetApiActions.updateDeviceSucceeded,
      FleetApiActions.updateDeviceFailed,
      (action) => action.device,
      () => null,
    );
  }

  /** `DEACTIVATED` on a `DELETED` device is how the pinned contract spells "restore". */
  async setDeviceState(id: string, state: SettableLifecycleState): Promise<Device | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      FleetPageActions.setDeviceStateRequested({ id, state }),
      FleetApiActions.setDeviceStateSucceeded,
      FleetApiActions.setDeviceStateFailed,
      (action) => action.device,
      () => null,
    );
  }

  /** Soft delete (archive) — the device drops out of the default device listing afterward. */
  async deleteDevice(id: string): Promise<Device | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      FleetPageActions.deleteDeviceRequested({ id }),
      FleetApiActions.deleteDeviceSucceeded,
      FleetApiActions.deleteDeviceFailed,
      (action) => action.device,
      () => null,
    );
  }

  /** Read-only — deliberately does **not** write `devices` (so Wall/Live and the default device
   *  list stay exactly as archived-free as they are today); this only backs the warehouse table's
   *  "show archived" toggle. */
  async listDevicesIncludingArchived(): Promise<Device[] | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      FleetPageActions.listDevicesIncludingArchivedRequested(),
      FleetApiActions.listDevicesIncludingArchivedSucceeded,
      FleetApiActions.listDevicesIncludingArchivedFailed,
      (action) => [...action.devices],
      () => null,
    );
  }

  async start(deviceId: string, request: StartStreamRequest = {}): Promise<StartStreamResult | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      FleetPageActions.startRequested({ deviceId, request }),
      FleetApiActions.startSucceeded,
      FleetApiActions.startFailed,
      (action) => action.result,
      () => null,
    );
  }

  /**
   * Live-patches a *running* stream's detection config (docs/plans/done/CV-CONTROL-PLAN.md §3) —
   * `features/fly/cv-control-panel.ts`'s one write path. `null` on failure, mirroring
   * `updateDevice`/`assignDevice`'s own contract; the caller checks the response's own
   * `modelReArmed` on success.
   */
  async patchStreamConfig(streamId: string, patch: UpdateStreamConfigRequest): Promise<PatchStreamConfigResponse | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      FleetPageActions.patchStreamConfigRequested({ streamId, patch }),
      FleetApiActions.patchStreamConfigSucceeded,
      FleetApiActions.patchStreamConfigFailed,
      (action) => action.response,
      () => null,
    );
  }

  async stop(streamId: string): Promise<boolean> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      FleetPageActions.stopRequested({ streamId }),
      FleetApiActions.stopSucceeded,
      FleetApiActions.stopFailed,
      () => true,
      () => false,
    );
  }

  /**
   * `AssetSessionController#engage` (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §3 P4) —
   * the operator's own "this asset is in use" verb, no video stream needed. The "is it engaged" fact
   * always comes from `CockpitFacade`'s own asset poll, never from this call's own return — see
   * `VisionApi#engageAssetSession`'s doc comment.
   */
  async engageAsset(assetId: string): Promise<boolean> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      FleetPageActions.engageAssetRequested({ assetId }),
      FleetApiActions.engageAssetSucceeded,
      FleetApiActions.engageAssetFailed,
      () => true,
      () => false,
    );
  }

  /** `AssetSessionController#disengage` — the operator's "end session" verb; idempotent, and a
   *  demote-not-close if a video stream is still running (that controller's own javadoc). */
  async disengageAsset(assetId: string): Promise<boolean> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      FleetPageActions.disengageAssetRequested({ assetId }),
      FleetApiActions.disengageAssetSucceeded,
      FleetApiActions.disengageAssetFailed,
      () => true,
      () => false,
    );
  }

  /**
   * Creates a simulated asset (docs/main/CYCLES-PLAN.md §4) and refreshes the device/stream lists so
   * the new device shows up immediately. `null` on failure (already toasted); the caller decides the
   * specific success toast/Watch action since that depends on whether `autoStart` produced a
   * `streamId`.
   */
  async simulate(request: StartSimulationRequest): Promise<SimulationResponse | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      FleetPageActions.simulateRequested({ request }),
      FleetApiActions.simulateSucceeded,
      FleetApiActions.simulateFailed,
      (action) => action.result,
      () => null,
    );
  }

  /** Idempotent — stops a simulated asset's stream (and, for `transport=rtsp`, its feed). */
  async stopSimulation(assetId: string): Promise<boolean> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      FleetPageActions.stopSimulationRequested({ assetId }),
      FleetApiActions.stopSimulationSucceeded,
      FleetApiActions.stopSimulationFailed,
      () => true,
      () => false,
    );
  }

  // --- Warehouse: asset lifecycle (docs/main/CYCLES-PLAN.md §8's pinned contract) -----------------

  async updateAsset(id: string, edit: AssetEdit): Promise<AssetDetails | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      FleetPageActions.updateAssetRequested({ id, edit }),
      FleetApiActions.updateAssetSucceeded,
      FleetApiActions.updateAssetFailed,
      (action) => action.asset,
      () => null,
    );
  }

  /** `DEACTIVATED` on a `DELETED` asset is how the pinned contract spells "restore". */
  async setAssetState(id: string, state: SettableLifecycleState): Promise<AssetDetails | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      FleetPageActions.setAssetStateRequested({ id, state }),
      FleetApiActions.setAssetStateSucceeded,
      FleetApiActions.setAssetStateFailed,
      (action) => action.asset,
      () => null,
    );
  }

  /** Soft delete (archive) — the success toast names what survived. */
  async deleteAsset(id: string): Promise<AssetDeletionResponse | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      FleetPageActions.deleteAssetRequested({ id }),
      FleetApiActions.deleteAssetSucceeded,
      FleetApiActions.deleteAssetFailed,
      (action) => action.result,
      () => null,
    );
  }

  /** Read-only — mirrors `listDevicesIncludingArchived`: backs the warehouse asset section's "show
   *  archived" toggle without touching any signal Wall/Live read from. */
  async listAssetsIncludingArchived(): Promise<AssetSummary[] | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      FleetPageActions.listAssetsIncludingArchivedRequested(),
      FleetApiActions.listAssetsIncludingArchivedSucceeded,
      FleetApiActions.listAssetsIncludingArchivedFailed,
      (action) => [...action.assets],
      () => null,
    );
  }

  /** Assigns an unowned device to an asset. A device-already-owned 409 gets a specific toast. */
  async assignDevice(assetId: string, deviceId: string): Promise<AssetDetails | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      FleetPageActions.assignDeviceRequested({ assetId, deviceId }),
      FleetApiActions.assignDeviceSucceeded,
      FleetApiActions.assignDeviceFailed,
      (action) => action.asset,
      () => null,
    );
  }

  /**
   * Unassigns a device from an asset. The ≥1-device invariant's 409 gets its own explanation —
   * `describeHttpError`'s generic conflict sentence doesn't know *why* (docs/main/CYCLES-PLAN.md §8).
   */
  async unassignDevice(assetId: string, deviceId: string): Promise<AssetDetails | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      FleetPageActions.unassignDeviceRequested({ assetId, deviceId }),
      FleetApiActions.unassignDeviceSucceeded,
      FleetApiActions.unassignDeviceFailed,
      (action) => action.asset,
      () => null,
    );
  }
}
