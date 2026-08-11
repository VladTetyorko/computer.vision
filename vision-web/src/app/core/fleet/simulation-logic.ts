import type {
  RegisterDeviceRequest,
  StartSimulationRequest,
  TelemetryPlanRequest,
} from '../api/models';

/**
 * Pure request-builders for every zero-hardware entry point `POST /api/simulations`/
 * `POST /api/devices` back (docs/main/CYCLES-PLAN.md §4, §9; docs/plans/done/UX-REWORK-PLAN.md §U-d).
 *
 * `buildTestDroneRequest`/`TestDroneForm` (the "moving test drone, no file" mode) split out of
 * `features/devices/simulate-logic.ts` into `core/fleet/` (vision-web/docs/plans/done/UI-STRUCTURE-PLAN.md §3,
 * B1) once `features/map/map.ts` needed the same builder its own Devices-page-local
 * `simulate-logic.ts` module used to hold exclusively. `buildSimulationRequest`/
 * `buildSyntheticRegisterRequest`/`SimulateMode`/`FileSimulateForm` (the `direct`/`rtsp`/`synthetic`
 * modes) joined them here for the identical reason (docs/plans/done/UX-REWORK-PLAN.md §U-d): the onboarding
 * wizard's Connect step needs every mode, not just `testDrone`, and `features/devices/devices.ts`
 * (Warehouse)'s own one-click "Add the simulated source" empty-state button still needs
 * `buildSyntheticRegisterRequest` — this codebase has no precedent for one feature importing
 * another feature's private module (see `core/fleet/device-logic.ts`'s doc comment), so every mode's
 * request-builder now lives at this one shared home alongside `fleet-store.ts`/`device-logic.ts`/
 * `warehouse-logic.ts`/`category-logic.ts`, the rest of this app's fleet-inventory concern.
 * `features/devices/simulate-logic.ts` keeps only what stays Warehouse-only: mapping already-created
 * simulated assets back to the devices they own, for that page's own "Simulated" chip/stop action.
 */

/**
 * The four zero-hardware entry points the onboarding wizard's Connect step (Simulate method)
 * offers. `testDrone` (docs/main/CYCLES-PLAN.md §9, CU-b item 7) is CU-a's fully synthetic simulation —
 * no video file, a VIDEO+TELEMETRY device moving along a circular home-point track — distinct from
 * `synthetic`, which registers a plain VIDEO-only `sim`-protocol device with no telemetry at all
 * (the pre-existing quick-add). `direct`/`rtsp` play a real file through the pipeline.
 */
export type SimulateMode = 'direct' | 'rtsp' | 'synthetic' | 'testDrone';

/**
 * Form state for the file-based modes (`direct`/`rtsp`). `synthetic` never reaches this shape —
 * it carries no file path or home position at all, see `buildSyntheticRegisterRequest`.
 *
 * `telemetry` (docs/main/CYCLES-PLAN.md §7, CT-b) is the already-serialized wire shape from
 * `shared/map/flight-plan-logic.ts#buildTelemetryRequest` — the flight-plan dialog builds it, this
 * module only threads it through to the request, exactly like it already does for
 * `latitude`/`longitude`.
 */
export interface FileSimulateForm {
  readonly name: string;
  readonly videoPath: string;
  readonly mode: 'direct' | 'rtsp';
  readonly latitude: number | null;
  readonly longitude: number | null;
  readonly autoStart: boolean;
  readonly telemetry?: TelemetryPlanRequest;
}

/**
 * Builds the `POST /api/simulations` body for the `direct`/`rtsp` modes.
 *
 * Name and position are trimmed and omitted (not sent as `null`) when blank/absent — the same
 * `@JsonInclude(NON_NULL)` convention every request DTO in this app follows — so the backend's
 * own defaults (derive a name from the file, no home position) apply instead of a caller
 * accidentally overriding them with an empty string or `null`. `latitude`/`longitude` are still
 * sent even when `telemetry` carries a route (the backend ignores them in that case per
 * `StartSimulationRequest`'s own Javadoc) — this function doesn't need to know which one wins.
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
    ...(form.telemetry ? { telemetry: form.telemetry } : {}),
  };
}

const SYNTHETIC_PROTOCOL = 'sim';
const SYNTHETIC_URI = 'sim://demo';
const SYNTHETIC_DEFAULT_NAME = 'sim-demo';

/**
 * The `synthetic` mode registers the same classic `sim`-protocol VIDEO device the Warehouse page's
 * "Add the simulated source" quick-add has always used — this *is* that path, reused, so every
 * zero-hardware entry point builds its request from one place (docs/main/CYCLES-PLAN.md §4). No file, no
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

/**
 * Form state for the `testDrone` mode: a home point (optional — the backend defaults it when
 * absent) and whether to start streaming immediately. No `videoPath` —
 * `StartSimulationRequest.videoPath` is optional precisely so this mode can omit it entirely
 * (CU-a, vision-api). `telemetry` (docs/main/CYCLES-PLAN.md §7, CT-b) is the flight-plan dialog's
 * serialized route, same convention as `FileSimulateForm#telemetry` in `simulate-logic.ts`.
 */
export interface TestDroneForm {
  readonly name: string;
  readonly latitude: number | null;
  readonly longitude: number | null;
  readonly autoStart: boolean;
  readonly telemetry?: TelemetryPlanRequest;
}

/**
 * Builds the `POST /api/simulations` body for the `testDrone` mode — the one-click "moving test
 * drone, no file" entry point (docs/main/CYCLES-PLAN.md §9, CU-a/CU-b): `videoPath` is omitted
 * entirely (not even blank), which is what tells the backend to register a fully synthetic
 * VIDEO+TELEMETRY device instead of a `file`-backed one. Name/position follow the same
 * trim-and-omit-when-blank convention as {@link buildSimulationRequest} above.
 */
export function buildTestDroneRequest(form: TestDroneForm): StartSimulationRequest {
  const displayName = form.name.trim();
  return {
    autoStart: form.autoStart,
    ...(displayName.length > 0 ? { displayName } : {}),
    ...(form.latitude !== null ? { latitude: form.latitude } : {}),
    ...(form.longitude !== null ? { longitude: form.longitude } : {}),
    ...(form.telemetry ? { telemetry: form.telemetry } : {}),
  };
}
