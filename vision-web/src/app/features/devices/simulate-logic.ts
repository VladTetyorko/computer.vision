import type { AssetDetails, AssetSummary } from '../../core/api/models';

/**
 * Pure logic behind the Warehouse page's own display of already-created simulated assets: which
 * assets are simulated, and which devices they own (for the Advanced table's "Simulated" chip and
 * its stop-simulation action). Split out so it is unit-testable without HTTP or the router —
 * mirrors `core/telemetry/telemetry-logic.ts`.
 *
 * Every *request-building* concern this file used to hold (`buildSimulationRequest`/
 * `buildSyntheticRegisterRequest`/`SimulateMode`/`FileSimulateForm`, the `direct`/`rtsp`/`synthetic`
 * modes; `buildTestDroneRequest`/`TestDroneForm`, `testDrone`, before that) moved to
 * `core/fleet/simulation-logic.ts` (docs/UX-REWORK-PLAN.md §U-d — the onboarding wizard's Connect
 * step needs every mode, not just the ones this page still calls directly) — see that module's own
 * doc comment for the full history. This file keeps only what stays Warehouse-only: mapping
 * assets/devices that already exist back to "is this simulated, and by which asset" for display.
 */

/** The category `DefaultSimulationService` creates every simulated asset under. */
export const SIMULATED_CATEGORY = 'simulated';

/** Enough about a device's owning simulated asset to render a chip and a stop action. */
export interface SimulatedDeviceInfo {
  readonly assetId: string;
  readonly displayName: string;
}

/** Narrows `listAssets()` results to the ones the simulation wizard created. */
export function isSimulatedAsset(asset: Pick<AssetSummary, 'category'>): boolean {
  return asset.category === SIMULATED_CATEGORY;
}

/**
 * deviceId → owning simulated asset, derived from already-fetched `AssetDetails`.
 *
 * Callers fetch details only for the (usually few) assets `isSimulatedAsset` selects — not
 * every asset — so this stays cheap without needing an N+1 `getAsset` per device shown in the
 * table.
 */
export function mapSimulatedDevices(
  simulatedAssets: readonly AssetDetails[],
): ReadonlyMap<string, SimulatedDeviceInfo> {
  const map = new Map<string, SimulatedDeviceInfo>();
  for (const asset of simulatedAssets) {
    for (const device of asset.devices) {
      map.set(device.id, { assetId: asset.assetId, displayName: asset.displayName });
    }
  }
  return map;
}
