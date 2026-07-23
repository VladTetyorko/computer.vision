import type {
  AssetDetails,
  AssetSummary,
  RegisterDeviceRequest,
  StartSimulationRequest,
} from '../../core/api/models';

/**
 * Pure logic behind the Devices page's "Simulate a source" wizard (docs/CYCLES-PLAN.md §4):
 * request-building for the `direct`/`rtsp` modes, the `synthetic` mode's register-flow
 * shortcut, and mapping devices to the simulated assets that own them (for the "Simulated" chip
 * and its stop action). Split out so it is unit-testable without HTTP or the router — mirrors
 * `core/telemetry-logic.ts` and `pages/debug/debug-*.ts`.
 *
 * The watch-target resolution (which device a freshly-started asset is watchable through) used
 * to live here too but moved to `core/device-logic.ts#findVideoDevice` when docs/CYCLES-PLAN.md
 * §6's `/map` tab needed the same resolution — see that file's doc comment for why.
 */

/** The category `DefaultSimulationService` creates every simulated asset under. */
export const SIMULATED_CATEGORY = 'simulated';

/** The three zero-hardware entry points the wizard offers. */
export type SimulateMode = 'direct' | 'rtsp' | 'synthetic';

/**
 * Form state for the file-based modes (`direct`/`rtsp`). `synthetic` never reaches this shape —
 * it carries no file path or home position at all, see `buildSyntheticRegisterRequest`.
 */
export interface FileSimulateForm {
  readonly name: string;
  readonly videoPath: string;
  readonly mode: 'direct' | 'rtsp';
  readonly latitude: number | null;
  readonly longitude: number | null;
  readonly autoStart: boolean;
}

/**
 * Builds the `POST /api/simulations` body for the `direct`/`rtsp` modes.
 *
 * Name and position are trimmed and omitted (not sent as `null`) when blank/absent — the same
 * `@JsonInclude(NON_NULL)` convention every request DTO in this app follows — so the backend's
 * own defaults (derive a name from the file, no home position) apply instead of a caller
 * accidentally overriding them with an empty string or `null`.
 */
export function buildSimulationRequest(form: FileSimulateForm): StartSimulationRequest {
  const displayName = form.name.trim();
  return {
    videoPath: form.videoPath.trim(),
    transport: form.mode,
    autoStart: form.autoStart,
    ...(displayName.length > 0 ? { displayName } : {}),
    ...(form.latitude !== null ? { latitude: form.latitude } : {}),
    ...(form.longitude !== null ? { longitude: form.longitude } : {}),
  };
}

const SYNTHETIC_PROTOCOL = 'sim';
const SYNTHETIC_URI = 'sim://demo';
const SYNTHETIC_DEFAULT_NAME = 'sim-demo';

/**
 * The `synthetic` mode registers the same classic `sim`-protocol VIDEO device the Devices
 * page's "Add the simulated source" quick-add has always used — this *is* that path, reused,
 * so every zero-hardware entry point lives in one place (docs/CYCLES-PLAN.md §4). No file, no
 * home position: a blank/whitespace-only name falls back to the quick-add's own default.
 */
export function buildSyntheticRegisterRequest(name: string): RegisterDeviceRequest {
  const trimmed = name.trim();
  return {
    name: trimmed.length > 0 ? trimmed : SYNTHETIC_DEFAULT_NAME,
    protocol: SYNTHETIC_PROTOCOL,
    uri: SYNTHETIC_URI,
  };
}

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
