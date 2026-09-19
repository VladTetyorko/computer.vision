import { createActionGroup, emptyProps, props } from '@ngrx/store';
import type {
  ActiveStream,
  AssetDeletionResponse,
  AssetDetails,
  AssetEdit,
  AssetSummary,
  CvModel,
  CvTracker,
  Device,
  DeviceEdit,
  PatchStreamConfigResponse,
  RegisterDeviceRequest,
  SettableLifecycleState,
  SimulationResponse,
  StartSimulationRequest,
  StartStreamRequest,
  StartStreamResult,
  UpdateStreamConfigRequest,
} from '../../api/models';

/**
 * `FleetStore`'s public surface, one `'Fleet Page'` source (docs/plans/active/NGRX-MIGRATION-PLAN.md
 * §3 rule 1) — every method that used to be a `run()`-wrapped `async` call becomes a `Requested`
 * event here; `fleet-facade.ts` dispatches these and, for every one that must keep returning a
 * value, resolves via `dispatchAndAwait` against the matching `FleetApiActions` pair.
 *
 * **`Booted`** is a noticed fact, not a command — dispatched once from `FleetFacade`'s own
 * constructor (mirroring `AuthFacade`/`ThresholdsFacade`'s identical "facade constructor dispatches
 * its own boot action" idiom) so `fleet.effects.ts#loadRosters$` can fetch the CV model + tracker
 * rosters exactly once, for the app's lifetime — see that effect's own doc comment. The
 * devices/streams poll itself needs no such trigger: `fleet.effects.ts#gate$` starts polling the
 * moment its own `store.select(liveFeature.selectConnectionState)` subscription first emits, which
 * happens as soon as effects are wired up at boot, without needing a page action of its own — see
 * that effect's doc comment for why replicating `FleetStore`'s literal "unconditional refresh(),
 * *then* register the poll" constructor order would double the very first `GET`.
 */
export const FleetPageActions = createActionGroup({
  source: 'Fleet Page',
  events: {
    Booted: emptyProps(),
    /** The Devices page's own explicit "Refresh" button — `FleetStore.refresh()`'s own contract,
     *  `quiet: false` by default there. Every *automatic* refresh (poll tick, live→poll fallback,
     *  post-mutation reconcile) goes straight through `fleet.effects.ts`'s own shared
     *  `refreshDevicesAndStreams$` helper instead of this action — see that file's doc comment. */
    'Refresh Requested': props<{ quiet: boolean }>(),
    'Register Requested': props<{ request: RegisterDeviceRequest }>(),
    'Update Device Requested': props<{ id: string; edit: DeviceEdit }>(),
    'Set Device State Requested': props<{ id: string; state: SettableLifecycleState }>(),
    'Delete Device Requested': props<{ id: string }>(),
    /** Read-only — deliberately never folded into `devices`, see `fleet.model.ts`'s own doc comment
     *  on why the entity collection stays archived-free. */
    'List Devices Including Archived Requested': emptyProps(),
    'Start Requested': props<{ deviceId: string; request: StartStreamRequest }>(),
    'Patch Stream Config Requested': props<{ streamId: string; patch: UpdateStreamConfigRequest }>(),
    'Stop Requested': props<{ streamId: string }>(),
    'Engage Asset Requested': props<{ assetId: string }>(),
    'Disengage Asset Requested': props<{ assetId: string }>(),
    'Simulate Requested': props<{ request: StartSimulationRequest }>(),
    'Stop Simulation Requested': props<{ assetId: string }>(),
    'Update Asset Requested': props<{ id: string; edit: AssetEdit }>(),
    'Set Asset State Requested': props<{ id: string; state: SettableLifecycleState }>(),
    'Delete Asset Requested': props<{ id: string }>(),
    'List Assets Including Archived Requested': emptyProps(),
    'Assign Device Requested': props<{ assetId: string; deviceId: string }>(),
    'Unassign Device Requested': props<{ assetId: string; deviceId: string }>(),
  },
});

/**
 * `FleetStore`'s own `run()`/`applyTransport`/one-shot-loader outcomes. Every `*Failed` here carries
 * an already-`describeHttpError`-rendered `error` string (never the raw `unknown` — that would trip
 * `strictActionSerializability` on an `Error`/`HttpErrorResponse` instance) — see
 * `fleet.effects.ts`'s own per-mutation `catchError` for where each is produced.
 */
export const FleetApiActions = createActionGroup({
  source: 'Fleet API',
  events: {
    /** Folds atomically — both lists from the same `Promise.all`, exactly like the old
     *  `refresh()`'s own two `set()` calls done back to back. */
    'Refresh Succeeded': props<{ devices: readonly Device[]; streams: readonly ActiveStream[] }>(),
    /** `quiet` is echoed back from whichever request produced this — `fleet.effects.ts#notifyFailure$`
     *  reads it to decide whether this specific failure toasts, matching `refresh({quiet})`'s own contract. */
    'Refresh Failed': props<{ quiet: boolean; error: string }>(),
    'Models Loaded': props<{ models: readonly CvModel[] }>(),
    /** No handler in `fleet.reducer.ts` — `models` silently stays `[]`, replacing `loadModels`'s old
     *  `console.warn` with an action the reducer (or a future consumer) can actually select on,
     *  per NGRX-MIGRATION-PLAN.md §3 rule 7. Never toasted — background roster enrichment, not a
     *  user-initiated action. */
    'Models Load Failed': emptyProps(),
    'Trackers Loaded': props<{ trackers: readonly CvTracker[] }>(),
    /** Same silent-degrade shape as {@link FleetApiActions.modelsLoadFailed} above. */
    'Trackers Load Failed': emptyProps(),

    'Register Succeeded': props<{ device: Device }>(),
    'Register Failed': props<{ error: string }>(),
    'Update Device Succeeded': props<{ device: Device }>(),
    'Update Device Failed': props<{ error: string }>(),
    'Set Device State Succeeded': props<{ device: Device; state: SettableLifecycleState }>(),
    'Set Device State Failed': props<{ error: string }>(),
    'Delete Device Succeeded': props<{ device: Device }>(),
    'Delete Device Failed': props<{ error: string }>(),
    'List Devices Including Archived Succeeded': props<{ devices: readonly Device[] }>(),
    'List Devices Including Archived Failed': props<{ error: string }>(),
    'Start Succeeded': props<{ result: StartStreamResult }>(),
    'Start Failed': props<{ error: string }>(),
    'Patch Stream Config Succeeded': props<{ response: PatchStreamConfigResponse }>(),
    'Patch Stream Config Failed': props<{ error: string }>(),
    'Stop Succeeded': emptyProps(),
    'Stop Failed': props<{ error: string }>(),
    'Engage Asset Succeeded': emptyProps(),
    'Engage Asset Failed': props<{ error: string }>(),
    'Disengage Asset Succeeded': emptyProps(),
    'Disengage Asset Failed': props<{ error: string }>(),
    'Simulate Succeeded': props<{ result: SimulationResponse }>(),
    'Simulate Failed': props<{ error: string }>(),
    'Stop Simulation Succeeded': emptyProps(),
    'Stop Simulation Failed': props<{ error: string }>(),
    'Update Asset Succeeded': props<{ asset: AssetDetails }>(),
    'Update Asset Failed': props<{ error: string }>(),
    'Set Asset State Succeeded': props<{ asset: AssetDetails; state: SettableLifecycleState }>(),
    'Set Asset State Failed': props<{ error: string }>(),
    'Delete Asset Succeeded': props<{ result: AssetDeletionResponse }>(),
    'Delete Asset Failed': props<{ error: string }>(),
    'List Assets Including Archived Succeeded': props<{ assets: readonly AssetSummary[] }>(),
    'List Assets Including Archived Failed': props<{ error: string }>(),
    'Assign Device Succeeded': props<{ asset: AssetDetails }>(),
    'Assign Device Failed': props<{ error: string }>(),
    'Unassign Device Succeeded': props<{ asset: AssetDetails }>(),
    'Unassign Device Failed': props<{ error: string }>(),
  },
});
