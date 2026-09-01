import { FEATURE_KEYS } from '../../core/api/models';
import type { AssetDetails, AssetStatus, Device, FeatureStatus, ReadinessRow, ReadinessVerdict } from '../../core/api/models';
import { featureLabel } from '../../core/readiness/readiness-logic';
import { isSimulated, type PickerGroups } from '../../core/fleet/triage-logic';

/**
 * Pure rendering/sorting/filtering helpers for `/operate/preflight`'s own table
 * (docs/plans/active/OPERATOR-UX-3-PLAN.md finding P1 + §2 P1; extended docs/plans/active/OPERATOR-UX-7-PLAN.md
 * finding P1, §2 P1, wave W1). Feature-local (not `core/readiness/`) per this wave's own file scope —
 * this page is still the only consumer; `core/readiness/readiness-logic.ts`'s
 * `fleetRowAttention`/`sortReadinessRows`/`readinessCounts` stay exactly as they were (unused by this
 * page as of this wave, not deleted — a future second consumer of the old comma-joined rollup or the
 * simpler sort still has them).
 *
 * Ordering a row's checks (canonical `FEATURE_KEYS` sequence first, then any unrecognised keys
 * alphabetically) is intentionally re-derived here rather than importing `readiness-logic.ts`'s own
 * private `orderedFeatureEntries` — that helper isn't exported, and this wave's own file list is
 * `features/preflight/*` only.
 *
 * **OPERATOR-UX-7 P1 — a documented gap between the finding's own wording and the wire it has to
 * work with.** The finding text says this page should "reuse `core/readiness/readiness-logic.ts#hasBeenProbed`"
 * to detect a never-probed row. That function reads `ReadinessReport#profileObservedAt` — a field
 * that exists only on the **per-asset** report (`GET /api/assets/{id}/readiness`), not on the fleet
 * board's own compact `ReadinessRow` (`GET /api/fleet/readiness`, mirrors `ReadinessRowResponse`:
 * `assetId`/`displayName`/`verdict`/`features: Record<featureKey, status>` — no `profileObservedAt`
 * at all, verified against `models.ts`). Fetching the full report per row just to read one boolean
 * would double this page's own network cost for no visible gain (see `isNeverProbedRow`'s own doc
 * comment for the equivalent, wire-shape-native check this file uses instead). Flagged for whoever
 * owns the plan next — either the finding should say "derive it from every feature reading `UNKNOWN`"
 * instead, or the fleet board's own DTO should grow the field.
 */

/** `checks`, ordered: the frozen `FEATURE_KEYS` sequence first, then any unrecognised keys
 * alphabetically — mirrors `readiness-logic.ts#orderedFeatureEntries`'s own ordering rule so the
 * "first" blocker always agrees with the fleet report's canonical feature order, whichever surface
 * renders it. */
function orderedNotReadyKeys(checks: Readonly<Record<string, FeatureStatus>>): readonly string[] {
  const known = FEATURE_KEYS.filter((key) => key in checks && checks[key] !== 'READY');
  const knownSet: readonly string[] = FEATURE_KEYS;
  const unknown = Object.keys(checks)
    .filter((key) => !knownSet.includes(key) && checks[key] !== 'READY')
    .sort();
  return [...known, ...unknown];
}

/** The table's "needs attention" cell (§2 P1): the first non-`READY` check's own label, plus a count
 * of the rest — `first` is `undefined` when every check is `READY` (or `checks` is empty), the same
 * "render a dash, never a fabricated all-clear" honesty `readiness-logic.ts#fleetRowAttention`
 * already follows for its own comma-joined rollup. */
export interface BlockerSummary {
  readonly first: string | undefined;
  readonly more: number;
}

export function blockerSummary(checks: Readonly<Record<string, FeatureStatus>>): BlockerSummary {
  const notReady = orderedNotReadyKeys(checks);
  if (notReady.length === 0) {
    return { first: undefined, more: 0 };
  }
  return { first: featureLabel(notReady[0]), more: notReady.length - 1 };
}

const VERDICT_PRIORITY: Readonly<Record<ReadinessVerdict, number>> = { NO_GO: 0, UNKNOWN: 1, GO: 2 };

/** Number of non-`READY` checks a row carries — `sortWorstFirst`'s own "most failures first" tie-break
 * within the `UNKNOWN` tier. */
function failureCount(row: Pick<ReadinessRow, 'features'>): number {
  return orderedNotReadyKeys(row.features).length;
}

/**
 * Table row order (§2 P1): `NO_GO` first, then `UNKNOWN` — itself ordered by most failing checks
 * first (the asset with the most unresolved work is the one worth seeing first within that tier) —
 * then `GO` last. Ties (same verdict, and within `UNKNOWN` the same failure count) break
 * alphabetically by display name, stable regardless of fetch order — the same tie-break
 * `readiness-logic.ts#sortReadinessRows` uses. Sorting only; nothing here filters or hides a row
 * (OQ3, docs/plans/active/DRONE-ONBOARDING-PLAN.md §10 — advisory-only, unchanged by this wave).
 *
 * Generic over `T extends ReadinessRow` (docs/plans/active/OPERATOR-UX-7-PLAN.md P1, wave W1) so it
 * can sort {@link FleetVehicleRow} — the triage-enriched row {@link buildBoardGroups} sorts within
 * each of its two groups — without widening the result back down to a bare `ReadinessRow` and losing
 * `category`/`status`/`hasTelemetryDevice` at the type level.
 */
export function sortWorstFirst<T extends ReadinessRow>(rows: readonly T[]): readonly T[] {
  return [...rows].sort((a, b) => {
    const byVerdict = VERDICT_PRIORITY[a.verdict] - VERDICT_PRIORITY[b.verdict];
    if (byVerdict !== 0) {
      return byVerdict;
    }
    if (a.verdict === 'UNKNOWN') {
      const byFailures = failureCount(b) - failureCount(a);
      if (byFailures !== 0) {
        return byFailures;
      }
    }
    return a.displayName.localeCompare(b.displayName, undefined, { sensitivity: 'base' });
  });
}

/**
 * The verdict stat cards' own filter (§2 P1) plus the OPERATOR-UX-7 P1 `NOT_PROBED` card
 * ({@link BoardFilter}): `undefined` (no card selected) returns every row unchanged; otherwise only
 * matching rows. `'NOT_PROBED'` matches {@link isNeverProbedRow}; `'UNKNOWN'` now excludes those rows
 * (P1: "`UNKNOWN` counts only probed-but-undecided rows") rather than every `verdict === 'UNKNOWN'`
 * row as before P1. Filtering only, applied after {@link sortWorstFirst} by the facade — this never
 * re-sorts. Generic over `T extends ReadinessRow` for the same reason as {@link sortWorstFirst}.
 */
export function applyVerdictFilter<T extends ReadinessRow>(rows: readonly T[], filter: BoardFilter | undefined): readonly T[] {
  if (filter === undefined) {
    return rows;
  }
  if (filter === 'NOT_PROBED') {
    return rows.filter((row) => isNeverProbedRow(row));
  }
  if (filter === 'UNKNOWN') {
    return rows.filter((row) => row.verdict === 'UNKNOWN' && !isNeverProbedRow(row));
  }
  return rows.filter((row) => row.verdict === filter);
}

/** `vision-empty`'s title for a verdict/NOT_PROBED filter that matched zero rows (§2 P1's own example,
 * for `NO_GO`, reused verbatim; `GO`/`UNKNOWN`/`NOT_PROBED` follow the same shape). Only ever rendered
 * once the board already knows at least one asset exists (the page's pre-existing "no assets at all"
 * empty state stays first and is unaffected), so this is always a *filtered*-empty message, never
 * confused with an actually-empty fleet. */
export function emptyFilterTitle(filter: BoardFilter): string {
  switch (filter) {
    case 'NO_GO':
      return 'No No-go drones — every failing check is listed under Unknown.';
    case 'UNKNOWN':
      return 'No Unknown drones — every drone has a definite Go or No-go verdict.';
    case 'GO':
      return 'No Go drones yet — nothing has cleared every check.';
    case 'NOT_PROBED':
      return 'No vehicles waiting on a first probe — every drone has been checked at least once.';
  }
}

// --- OPERATOR-UX-7 P1 (§2 P1, wave W1) — triage grouping + honest never-probed/no-telemetry copy ---

/** The verdict stat strip's filter, widened past the wire's own {@link ReadinessVerdict} with one
 * client-derived bucket — {@link isNeverProbedRow} rows, which used to be indistinguishable from a
 * genuinely-evaluated `UNKNOWN` row (see {@link applyVerdictFilter}). */
export type BoardFilter = ReadinessVerdict | 'NOT_PROBED';

/**
 * True for a row whose verdict is `UNKNOWN` **and** every evaluated feature reads `UNKNOWN` — the
 * exact wire signature `DefaultReadinessService#evaluateFeature` produces when the asset has no
 * `VehicleProfile` at all ("Never probed.", verified against source). The identical signature is
 * also produced when a profile exists but is incomplete ("The most recent probe was incomplete: …") —
 * `ReadinessRow` carries no `detail` text (only `ReadinessReport`, the per-asset report, does), so
 * the fleet board cannot tell those two apart; see this file's own top-of-file doc comment for why
 * `core/readiness/readiness-logic.ts#hasBeenProbed` (which *can* tell them apart, via
 * `profileObservedAt`) isn't reusable here. Both cases read as "nothing to show yet" either way — an
 * incomplete probe has produced no usable per-feature verdict any more than no probe has — so folding
 * them into one `Not probed yet` copy is an honest approximation, not a fabrication.
 *
 * `verdict !== 'UNKNOWN'` short-circuits false without inspecting `features` at all (`DefaultReadinessService#evaluate`:
 * `verdict` can only be `UNKNOWN` when this "unresolvable" state holds — no other verdict ever pairs
 * with an all-`UNKNOWN` feature set). An empty `features` map (no evaluated checks at all — an
 * unrecognised firmware, or a bare test fixture) reads `false`, not vacuously `true` — the same "zero
 * checks is as inconclusive as zero failures" posture {@link blockerSummary} already takes.
 */
export function isNeverProbedRow(row: Pick<ReadinessRow, 'verdict' | 'features'>): boolean {
  if (row.verdict !== 'UNKNOWN') {
    return false;
  }
  const statuses = Object.values(row.features);
  return statuses.length > 0 && statuses.every((status) => status === 'UNKNOWN');
}

/**
 * Whether `devices` includes at least one `TELEMETRY`-capable device — the fact that decides whether
 * a never-probed row reads `Not probed yet` (it might still be probed once online) or `No telemetry
 * device` (it never will be: `DefaultReadinessService` can only ever find a `VehicleProfile` behind a
 * MAVLink-speaking device). Structurally typed on just `capabilities` so a caller can pass a plain
 * `Device[]` or a narrower fixture.
 */
export function hasTelemetryCapableDevice(devices: readonly Pick<Device, 'capabilities'>[]): boolean {
  return devices.some((device) => device.capabilities.includes('TELEMETRY'));
}

/**
 * One fleet-board row, `ReadinessRow` (verdict/features, from `GET /api/fleet/readiness`) enriched
 * with the triage fields `core/fleet/triage-logic.ts#groupAndSort`'s `TriageCandidate` needs —
 * `category`/`status`/`lastUsedAt` — plus `hasTelemetryDevice`, none of which the fleet board's own
 * compact DTO carries (see this file's top-of-file doc comment). Sourced from `AssetDetails`
 * (`GET /api/assets/{id}`, the same per-asset call `features/assets/assets-facade.ts#refreshAssets`
 * already fans out over every listed asset for its own grid) — see {@link buildFleetVehicleRows}.
 *
 * `hasTelemetryDevice` is `undefined`, never a fabricated `false`, whenever that asset's own
 * `AssetDetails` read hasn't resolved yet or failed (CLAUDE.md "degrade honestly") — a row in that
 * state reads the safer `Not probed yet` rather than the more specific, unverified `No telemetry
 * device` (see {@link vehicleStatusOverride}).
 */
export interface FleetVehicleRow extends ReadinessRow {
  readonly category: string;
  readonly status: AssetStatus;
  readonly lastUsedAt?: string;
  readonly hasTelemetryDevice?: boolean;
}

/**
 * Merges the fleet board's own rows with whatever `AssetDetails` reads the facade managed to fetch
 * (best-effort, keyed by `assetId`; missing entries are either still loading or failed — same
 * "silent per-row degrade" posture `AssetsFacade#refreshAssets` already uses for its own bulk
 * `getAsset` fan-out). A missing/failed detail degrades to `category: ''` (never `'simulated'` —
 * `isSimulated` reads false, so the row lands in **Your vehicles**, never a guessed **Simulated**),
 * `status: 'OFFLINE'` (the conservative default — never claim a verified-streaming state without
 * evidence), `lastUsedAt: undefined`, and `hasTelemetryDevice: undefined`.
 */
export function buildFleetVehicleRows(
  rows: readonly ReadinessRow[],
  detailsById: ReadonlyMap<string, Pick<AssetDetails, 'category' | 'status' | 'lastUsedAt'> & { devices: readonly Pick<Device, 'capabilities'>[] }>,
): readonly FleetVehicleRow[] {
  return rows.map((row) => {
    const details = detailsById.get(row.assetId);
    return {
      ...row,
      category: details?.category ?? '',
      status: details?.status ?? 'OFFLINE',
      lastUsedAt: details?.lastUsedAt,
      hasTelemetryDevice: details ? hasTelemetryCapableDevice(details.devices) : undefined,
    };
  });
}

/**
 * The verdict cell's override text for a row {@link isNeverProbedRow} is true for — `undefined` for
 * every other row, meaning "render the normal verdict chip" (§2 P1: "structural-label register, no
 * chip" — this app's one-chip-per-row rule, frontend-style §5, is what a never-probed/no-telemetry
 * row now costs *zero* chips rather than a muted `Unknown` one). `No telemetry device` only once
 * positive evidence says so (`hasTelemetryDevice === false`); every other case — including "we don't
 * know yet" (`undefined`) — reads the softer `Not probed yet`, never a guessed negative.
 */
export function vehicleStatusOverride(row: Pick<FleetVehicleRow, 'verdict' | 'features' | 'hasTelemetryDevice'>): string | undefined {
  if (!isNeverProbedRow(row)) {
    return undefined;
  }
  return row.hasTelemetryDevice === false ? 'No telemetry device' : 'Not probed yet';
}

/**
 * The board's own **Your vehicles** / **Simulated** split (§2 P1: "Rows in `core/fleet/triage-logic.ts`
 * order with the same … group headers as `/fly` and `/command`") — reuses that module's own
 * `isSimulated` fact (the one this app already applies everywhere else) for the split, but re-sorts
 * each resulting group with {@link sortWorstFirst} rather than keeping `groupAndSort`'s own bundled
 * "streaming first" ordering: unlike `/fly`'s picker or Command's rail, this page's entire purpose is
 * readiness-severity triage, not "what's flying right now" — a currently-streaming `GO` row sorting
 * ahead of a grounded `NO_GO` row would bury this page's one reason to exist. `PickerGroups` (the
 * shared shape) is reused verbatim; only the ordering *within* it is page-specific.
 */
export function buildBoardGroups(rows: readonly FleetVehicleRow[]): PickerGroups<FleetVehicleRow> {
  const yours: FleetVehicleRow[] = [];
  const simulated: FleetVehicleRow[] = [];
  for (const row of rows) {
    (isSimulated(row) ? simulated : yours).push(row);
  }
  return { yours: sortWorstFirst(yours), simulated: sortWorstFirst(simulated) };
}

/** The summary strip's four counts (§2 P1: "Summary strip gains `NOT PROBED n` so `UNKNOWN` counts
 * only probed-but-undecided rows") — a page-local rollup, not `core/readiness/readiness-logic.ts#readinessCounts`
 * (that function's own three-way split stays exactly as it was for its own callers). Under the
 * current backend (`DefaultReadinessService#evaluate`), `verdict` can only be `UNKNOWN` when
 * {@link isNeverProbedRow} is also true — there is no code path that evaluates a complete profile to
 * `UNKNOWN` — so `unknown` is expected to read `0` on every deployment today; it is kept as its own,
 * separately-computed count (rather than hard-coded to `0`) so it starts reporting real rows the
 * moment the backend ever grows one, with no client change. */
export interface BoardCounts {
  readonly go: number;
  readonly noGo: number;
  readonly unknown: number;
  readonly notProbed: number;
}

export function boardCounts(rows: readonly ReadinessRow[]): BoardCounts {
  let go = 0;
  let noGo = 0;
  let unknown = 0;
  let notProbed = 0;
  for (const row of rows) {
    if (row.verdict === 'GO') {
      go++;
    } else if (row.verdict === 'NO_GO') {
      noGo++;
    } else if (isNeverProbedRow(row)) {
      notProbed++;
    } else {
      unknown++;
    }
  }
  return { go, noGo, unknown, notProbed };
}
