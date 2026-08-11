import type { TelemetrySample } from '../../core/api/models';

/**
 * Pure logic behind the asset detail page (docs/main/CYCLES-PLAN.md §11, CD-b items 2–3): picking the
 * freshest sample across every source for the map marker. Split out so it is unit-testable
 * without HTTP, timers, or Leaflet — mirrors `core/telemetry/telemetry-logic.ts`.
 *
 * `groupTelemetryByDevice`/`telemetryDevices` used to live here too; moved to
 * `core/telemetry/telemetry-logic.ts` in docs/plans/done/MVP2-PLAN.md §R (R-b) when the replay cockpit needed the
 * identical per-device grouping — see that module's own doc comment. Re-exported below so this
 * page's own existing import site (`asset-detail.ts`) keeps working verbatim.
 */
export { groupTelemetryByDevice, telemetryDevices } from '../../core/telemetry/telemetry-logic';

/**
 * The single freshest sample across every source device (docs/main/CYCLES-PLAN.md §11 item 3: "the map
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

// --- Telemetry facts formatting (docs/plans/done/UI-REDESIGN-PLAN.md Wave 3) -------------------------------
// Shared between the overview's freshest-telemetry summary card and the "Full telemetry" drill-in
// drawer's per-device panels — before this wave each caller inlined its own `toFixed`/`'—'`
// formatting directly in the template; the overview's new summary card needed the identical shape
// a second time, so this is the "second consumer → pull it out" moment (mirrors
// `groupTelemetryByDevice`'s own history above).

/** One row of a telemetry facts grid. `mono` marks the one row (Position) that also carried the
 *  `.mono` class in the original inline markup — tabular-nums coordinates, not the free-form units. */
export interface TelemetryFactRow {
  readonly label: string;
  readonly value: string;
  readonly mono?: boolean;
}

/**
 * Position/altitude/heading/battery, each real or `'—'` — never a fabricated reading.
 * Deliberately excludes sample age: callers show that separately, since "how stale" depends on
 * which clock the caller is comparing against (the per-device drawer's own device clock vs. the
 * overview summary's asset-wide freshest clock) — folding it in here would force one caller's
 * clock onto the other.
 */
export function telemetryFactRows(sample: TelemetrySample | undefined): readonly TelemetryFactRow[] {
  return [
    {
      label: 'Position',
      value:
        sample?.latitude !== undefined && sample?.longitude !== undefined
          ? `${sample.latitude.toFixed(5)}, ${sample.longitude.toFixed(5)}`
          : '—',
      mono: true,
    },
    { label: 'Altitude', value: sample?.altitudeMeters !== undefined ? `${sample.altitudeMeters.toFixed(0)} m` : '—' },
    { label: 'Heading', value: sample?.headingDegrees !== undefined ? `${sample.headingDegrees.toFixed(0)}°` : '—' },
    { label: 'Battery', value: sample?.batteryPercent !== undefined ? `${sample.batteryPercent.toFixed(0)}%` : '—' },
  ];
}

// --- Attributes editor (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3 — advanced-mode key/value editor) ----

/** One row of the advanced-mode attributes editor. */
export interface AttributeRow {
  readonly key: string;
  readonly value: string;
}

/** `asset.attributes` → editable rows, in insertion order — the editor's own seed on open. */
export function attributesToRows(attributes: Record<string, string>): readonly AttributeRow[] {
  return Object.entries(attributes).map(([key, value]) => ({ key, value }));
}

/**
 * Rows → `PATCH /api/assets/{id}`'s full replacement `attributes` map (docs/main/CYCLES-PLAN.md §8's
 * pinned contract; `application.AssetEdit`'s own Javadoc: "replacement attributes, or null to keep
 * the current map" — this is why the editor always submits every row, not just changed ones).
 * Blank-key rows are dropped (an editor row added then left empty on "Save" shouldn't produce a
 * `""` attribute key); keys are trimmed; a later duplicate key wins over an earlier one, matching
 * how a plain object literal with repeated keys behaves.
 */
export function attributeRowsToRecord(rows: readonly AttributeRow[]): Record<string, string> {
  const result: Record<string, string> = {};
  for (const row of rows) {
    const key = row.key.trim();
    if (key.length > 0) {
      result[key] = row.value;
    }
  }
  return result;
}
