import type { TelemetrySample } from '../../core/api/models';

/**
 * Pure logic behind the asset detail page (docs/CYCLES-PLAN.md §11, CD-b items 2–3): picking the
 * freshest sample across every source for the map marker. Split out so it is unit-testable
 * without HTTP, timers, or Leaflet — mirrors `core/telemetry/telemetry-logic.ts`.
 *
 * `groupTelemetryByDevice`/`telemetryDevices` used to live here too; moved to
 * `core/telemetry/telemetry-logic.ts` in docs/MVP2-PLAN.md §R (R-b) when the replay cockpit needed the
 * identical per-device grouping — see that module's own doc comment. Re-exported below so this
 * page's own existing import site (`asset-detail.ts`) keeps working verbatim.
 */
export { groupTelemetryByDevice, telemetryDevices } from '../../core/telemetry/telemetry-logic';

/**
 * The single freshest sample across every source device (docs/CYCLES-PLAN.md §11 item 3: "the map
 * marker uses the freshest source"). Each device's own samples are assumed chronological, so only
 * each group's own last element needs comparing — this is `O(devices)`, not `O(samples)`.
 */
export function freshestSample(
  byDevice: ReadonlyMap<string, readonly TelemetrySample[]>,
): TelemetrySample | undefined {
  let freshest: TelemetrySample | undefined;
  let freshestAtMs = -Infinity;
  for (const samples of byDevice.values()) {
    const latest = samples[samples.length - 1];
    if (!latest) {
      continue;
    }
    const atMs = Date.parse(latest.at);
    if (atMs > freshestAtMs) {
      freshestAtMs = atMs;
      freshest = latest;
    }
  }
  return freshest;
}
