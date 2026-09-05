import type { AssetDetails } from '../../core/api/models';

/**
 * Pure logic behind the Warehouse page's own display of already-registered simulated *devices*
 * (for the Advanced table's "Simulated" chip and its stop-simulation action). Split out so it is
 * unit-testable without HTTP or the router — mirrors `core/telemetry/telemetry-logic.ts`.
 *
 * **docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md D6/U1**: this used to narrow to *assets* under the
 * `simulated` category (`isSimulatedAsset`, deleted this wave) — the exact coupling defect
 * `DeviceOrigin` was introduced to replace (`models.ts#DeviceOrigin`'s own doc comment). A vehicle
 * can be *half* real — a real autopilot plus a synthetic camera, both attached to one ordinary-
 * category asset (`core/onboarding/fit-out-logic.ts#fitOutRowToDeviceSpec`'s per-row `simulate`
 * branch stamps `origin: 'SIMULATED'` on exactly the device it creates, leaving a sibling `find` row's
 * device `LIVE`) — a category flag on the whole asset cannot express that; `device.origin` can, and
 * is now the only signal this file reads. `isSimulatedAsset`/`SIMULATED_CATEGORY` are deleted, not
 * kept around: nothing outside this file's own (now-rewritten) spec called them, and the `simulated`
 * category itself stays only as a cosmetic label a user may still pick in Identify (deleting the
 * category outright is a separate data-migration follow-up, SOURCE-ONBOARDING-2-PLAN.md §5.3 N3).
 *
 * Every *request-building* concern this file used to hold (`buildSimulationRequest`/
 * `buildSyntheticRegisterRequest`/`SimulateMode`/`FileSimulateForm`, the `direct`/`rtsp`/`synthetic`
 * modes; `buildTestDroneRequest`/`TestDroneForm`, `testDrone`, before that) moved to
 * `core/fleet/simulation-logic.ts` (docs/plans/done/UX-REWORK-PLAN.md §U-d — the onboarding wizard's Connect
 * step needs every mode, not just the ones this page still calls directly) — see that module's own
 * doc comment for the full history. This file keeps only what stays Warehouse-only: mapping
 * devices that already exist back to "is this simulated, and by which asset" for display.
 */

/** Enough about a device's owning asset to render a chip and a stop action. */
export interface SimulatedDeviceInfo {
  readonly assetId: string;
  readonly displayName: string;
}

/**
 * deviceId → owning asset, for every device whose own `origin` is `SIMULATED` — read straight off
 * `Device#origin` (`core/api/models.ts`), never inferred from the owning asset's category. Callers
 * pass every asset they've loaded (not a pre-filtered subset): there is no cheap category filter any
 * more, since an otherwise-real asset can still own one simulated device.
 *
 * The "Stop simulation" action this feeds still calls `DELETE /api/simulations/{assetId}`
 * (`VisionApi#stopSimulation`) — asset-scoped on the wire, unchanged by this wave — so the map's
 * value is still keyed by owning asset, exactly as it was under the old category-based filter.
 */
export function mapSimulatedDevices(
  assets: readonly AssetDetails[],
): ReadonlyMap<string, SimulatedDeviceInfo> {
  const map = new Map<string, SimulatedDeviceInfo>();
  for (const asset of assets) {
    for (const device of asset.devices) {
      if (device.origin === 'SIMULATED') {
        map.set(device.id, { assetId: asset.assetId, displayName: asset.displayName });
      }
    }
  }
  return map;
}
