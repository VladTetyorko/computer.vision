import type { StartSimulationRequest, TelemetryPlanRequest } from '../api/models';

/**
 * Pure request-builder for the "moving test drone, no file" zero-hardware entry point
 * (docs/CYCLES-PLAN.md §9, CU-a/CU-b). Split out of `features/devices/simulate-logic.ts` into
 * `core/fleet/` (vision-web/docs/UI-STRUCTURE-PLAN.md §3, B1) once `features/map/map.ts` needed
 * the same builder its own Devices-page-local `simulate-logic.ts` module used to hold exclusively
 * — this codebase has no precedent for one feature importing another feature's private module
 * (see `core/device-logic.ts`'s doc comment), so the shared piece moved to `core/fleet/` alongside
 * `fleet-store.ts`/`device-logic.ts`/`warehouse-logic.ts`, the rest of this app's fleet-inventory
 * concern. `features/devices/simulate-logic.ts` keeps everything `testDrone` doesn't need
 * (`direct`/`rtsp`/`synthetic` request-building, simulated-asset mapping) — those stay
 * Devices-only.
 */

/**
 * Form state for the `testDrone` mode: a home point (optional — the backend defaults it when
 * absent) and whether to start streaming immediately. No `videoPath` —
 * `StartSimulationRequest.videoPath` is optional precisely so this mode can omit it entirely
 * (CU-a, vision-api). `telemetry` (docs/CYCLES-PLAN.md §7, CT-b) is the flight-plan dialog's
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
 * drone, no file" entry point (docs/CYCLES-PLAN.md §9, CU-a/CU-b): `videoPath` is omitted
 * entirely (not even blank), which is what tells the backend to register a fully synthetic
 * VIDEO+TELEMETRY device instead of a `file`-backed one. Name/position follow the same
 * trim-and-omit-when-blank convention as `features/devices/simulate-logic.ts#buildSimulationRequest`.
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
