import type { AssetIdentity, MaintenanceRecord, TelemetrySample } from '../../core/api/models';
import type { KpiTile } from '../../core/fleet/asset-stats-logic';
import { hasFix } from '../../core/geo/geo-logic';
import { hoursSinceClose } from '../../core/maintenance/maintenance-logic';
import { humanAge } from '../../core/telemetry/telemetry-logic';
import type { RoleStatus } from '../../core/onboarding/fit-out-logic';

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

/** One row of a telemetry facts grid. `mono` marks a row rendering tabular-nums (a real coordinate/
 *  number), `faint` marks a structural "not yet known" label rather than a numeric reading — the
 *  two are mutually exclusive in practice, but both are plain booleans so a row can carry neither. */
export interface TelemetryFactRow {
  readonly label: string;
  readonly value: string;
  readonly mono?: boolean;
  readonly faint?: boolean;
}

/**
 * The Position row: a real `lat, lon` when the sample carries one, `'No GPS fix yet'` when the
 * sample reports lat/lon but no real fix (exactly `(0, 0)` — docs/plans/active/OPERATOR-UX-4-PLAN.md
 * finding N1, `core/geo/geo-logic.ts#hasFix`), or `'—'` when the sample carries no position field
 * at all (nothing reported yet, a different honest gap). `'No GPS fix yet'` renders in the faint
 * structural register, not `.mono` — it is a label saying what is known, not a number.
 */
function positionFact(sample: TelemetrySample | undefined): TelemetryFactRow {
  if (sample?.latitude === undefined || sample?.longitude === undefined) {
    return { label: 'Position', value: '—', mono: true };
  }
  if (!hasFix({ latitude: sample.latitude, longitude: sample.longitude })) {
    return { label: 'Position', value: 'No GPS fix yet', faint: true };
  }
  return { label: 'Position', value: `${sample.latitude.toFixed(5)}, ${sample.longitude.toFixed(5)}`, mono: true };
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
    positionFact(sample),
    { label: 'Altitude', value: sample?.altitudeMeters !== undefined ? `${sample.altitudeMeters.toFixed(0)} m` : '—' },
    { label: 'Heading', value: sample?.headingDegrees !== undefined ? `${sample.headingDegrees.toFixed(0)}°` : '—' },
    { label: 'Battery', value: sample?.batteryPercent !== undefined ? `${sample.batteryPercent.toFixed(0)}%` : '—' },
  ];
}

/**
 * `<humanAge> ago`, or `'—'` when no sample has arrived yet — never the old bare-suffix bug
 * (`'—s ago'`: the template used to append the literal `s ago` regardless of whether an age was
 * known). One age vocabulary (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N4,
 * `core/telemetry/telemetry-logic.ts#humanAge`) for both the overview's freshest-sample card and the
 * per-device "Full telemetry" drawer rows.
 */
export function sampleAgeLabel(seconds: number | undefined): string {
  return seconds === undefined ? '—' : `${humanAge(seconds)} ago`;
}

/**
 * Redacts a sample's own lat/lon when they carry no real GPS fix (docs/plans/active/OPERATOR-UX-4-PLAN.md
 * finding N1), keeping every other field intact — heading/battery/flightState are still worth
 * surfacing on the map marker popup even while the vehicle has no lock. Feeds the position card's
 * own `<vision-tactical-map>` follow marker (`AssetDetailFacade#mapAssets`): without this, a `(0, 0)`
 * `TelemetryStore.latest()` sample would satisfy `shared/map/tactical-map/tactical-map-logic.ts#followMarker`'s
 * own (unrelated, unpatched here) fix check and recentre the map on Null Island — this instead makes
 * that helper's existing "no fix → fall back to the trail's last point, or nothing" branch fire, the
 * same honest degrade a battery-only sample already gets.
 */
export function withFixOnlyPosition(sample: TelemetrySample | undefined): TelemetrySample | undefined {
  if (!sample || (sample.latitude === undefined && sample.longitude === undefined)) {
    return sample;
  }
  if (hasFix({ latitude: sample.latitude, longitude: sample.longitude })) {
    return sample;
  }
  return { ...sample, latitude: undefined, longitude: undefined };
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

// --- Identity (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4, wave W4 — replaces the single
// Registration inline-edit with a 4-field Identity fact group backed by `AssetIdentity`) ------------

/**
 * `serialNumber`/`make`/`model`/`registration` form fields → the `PATCH /api/assets/{id}` body's
 * `identity` replacement (`AssetEdit#identity`'s own doc comment: "present replaces the asset's
 * identity wholesale — absent fields mean unknown, not unchanged"). A blank field is omitted rather
 * than sent as `""`, matching `withRegistrationNumber`'s own trim-and-omit convention — clearing a
 * field in the form and saving means "this is now unknown", the honest reading of the backend's own
 * "absent means unknown" contract.
 */
export function buildIdentityEdit(serialNumber: string, make: string, model: string, registration: string): AssetIdentity {
  const clean = (value: string): string | undefined => {
    const trimmed = value.trim();
    return trimmed.length > 0 ? trimmed : undefined;
  };
  return {
    serialNumber: clean(serialNumber),
    make: clean(make),
    model: clean(model),
    registration: clean(registration),
  };
}

/**
 * Moved to `core/fleet/asset-attributes.ts` in wave W4 (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3)
 * once the Inventory page's own detail panel needed the identical `identity.registration`/legacy-
 * attribute fallback — see that module's own doc comment for the full migration-gap writeup.
 * Re-exported here so this page's own pre-existing import site keeps working verbatim.
 */
export { effectiveRegistration } from '../../core/fleet/asset-attributes';

/**
 * The most recently closed maintenance record among an asset's own history — "Since service"
 * (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4's KPI-band addition) reads *when this closed*, not
 * when it opened, so a long-open repair that just wrapped up still reads as "just serviced." Ties
 * broken by nothing further (an asset closing two records at the identical instant is not a case
 * worth resolving deterministically beyond "pick one"). `undefined` for an asset with no closed
 * record at all — a genuinely honest "never serviced," never a fabricated one.
 */
export function mostRecentlyClosedRecord(records: readonly MaintenanceRecord[]): MaintenanceRecord | undefined {
  const closed = records.filter((record) => record.closedAt !== undefined);
  if (closed.length === 0) {
    return undefined;
  }
  return [...closed].sort((a, b) => Date.parse(b.closedAt as string) - Date.parse(a.closedAt as string))[0];
}

/** `<humanAge> ago`, or `'—'` for an asset with no closed maintenance record yet — same register as {@link sampleAgeLabel}. */
export function formatSinceService(hours: number | undefined): string {
  return hours === undefined ? '—' : `${humanAge(hours * 3600)} ago`;
}

/**
 * The KPI band's "Since service" tile (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4) — appended after
 * `core/fleet/asset-stats-logic.ts#kpiTiles`'s own five flight-stat tiles, not folded into that
 * function: it reads maintenance records, a different fetch this page's facade owns independently of
 * `AssetStats`, and mirrors `AssetStats`'s own "never fabricate, always `'—'`" discipline rather than
 * this file's own telemetry-fact discipline.
 */
export function sinceServiceTile(records: readonly MaintenanceRecord[], nowMs: number): KpiTile {
  const record = mostRecentlyClosedRecord(records);
  return { label: 'Since service', value: formatSinceService(record ? hoursSinceClose(record, nowMs) : undefined) };
}

// --- Sense/Sight role status (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md P3) ---------------------

/**
 * Presentational tone for a {@link RoleStatus} value, shared with `features/devices/devices-page-
 * logic.ts#roleStatusDescriptor` — same mapping, deliberately duplicated rather than centralized in
 * `core/onboarding/fit-out-logic.ts`. This codebase's own precedent for that trade-off is
 * `core/fleet/triage-logic.ts`'s doc comment: no feature imports another feature's own `*-logic.ts`,
 * and a two-line mapping duplicated once is cheaper than a new cross-feature import or a `core/` move
 * for a single extra reader — this wave's own file-scope (features/asset-detail/** only) makes editing
 * `core/onboarding/**` an out-of-scope change regardless.
 */
export type RoleStatusToneKind = 'muted' | 'quiet' | 'ok' | 'warn';

export interface RoleStatusDescriptor {
  readonly kind: RoleStatusToneKind;
  readonly label: string;
}

const ROLE_STATUS_DESCRIPTORS: Readonly<Record<RoleStatus, RoleStatusDescriptor>> = {
  'not-fitted': { kind: 'muted', label: '—' },
  'never-seen': { kind: 'quiet', label: 'Never heard' },
  live: { kind: 'ok', label: 'Live' },
  stalled: { kind: 'warn', label: 'Stalled' },
  stopped: { kind: 'quiet', label: 'Stopped' },
  stale: { kind: 'warn', label: 'Stale' },
};

/**
 * `not-fitted` → muted `—` (render no chip at all — the asset was never fitted for this role, not a
 * failure); `never-seen` → honest quiet non-error state (fitted, but nothing has ever reported);
 * `live` → the one green/live affordance; `stalled`/`stale` → warn (something is fitted and was seen,
 * but isn't fresh right now); `stopped` → quiet (fitted, intentionally idle). At most one chip per
 * row either way (§5).
 */
export function roleStatusDescriptor(status: RoleStatus): RoleStatusDescriptor {
  return ROLE_STATUS_DESCRIPTORS[status];
}
