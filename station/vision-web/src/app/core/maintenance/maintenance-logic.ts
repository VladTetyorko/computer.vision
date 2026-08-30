import type { AssetSummary, FleetMaintenanceRecord, MaintenanceKind } from '../api/models';

/**
 * Pure, Angular-free logic behind `/fleet/maintenance` (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3/§4
 * wave W7, one-call rewrite wave W9). `MaintenanceFacade` fetches the fleet-wide `GET /api/maintenance`
 * once (`VisionApi.fleetMaintenance`, docs/plans/active/WAREHOUSE-UX-CONTEXT.md "W8 → W9 handoff") —
 * every function here takes that already-fetched flat {@link FleetMaintenanceRecord} list rather than
 * reaching for `VisionApi` itself, so it stays unit-testable with hand-built arrays, no
 * `HttpClient`/zoneless-signal ceremony. `FleetMaintenanceRecord` already carries its own asset's
 * `assetName`/`categoryId` (the endpoint's whole point — WAREHOUSE-UX-PLAN.md §3.3 D5), so unlike the
 * pre-W9 `AssetMaintenanceRow` this file no longer joins against a separately-fetched asset list at
 * all; `MaintenanceFacade` still loads `assets` for the KPI tiles' `RETIRED` count, the "Ground a
 * vehicle" picker, and the (asset-only) category display name / custodian lookups the table also
 * shows — see that class's own doc comment.
 *
 * A record with an open kind this app has never heard of (a future `MaintenanceKind` value the
 * backend adds before this file does) degrades honestly — it counts toward nothing in
 * {@link maintenanceKpis} rather than being guessed into a bucket, mirroring
 * `readiness-logic.ts#featureLabel`'s "render it, don't invent its meaning" rule for an unknown key.
 */

export interface MaintenanceKpis {
  readonly grounded: number;
  readonly inspectionDue: number;
  readonly inRepair: number;
  readonly retired: number;
}

/**
 * Human labels for `MaintenanceKind` — the open-records table's Kind chip and the "Ground a
 * vehicle" kind picker share this one map so the two never drift (a table row reading "Grounded"
 * and a select option reading "GROUNDING" for the same value would be its own small honesty bug).
 */
export const MAINTENANCE_KIND_LABELS: Record<MaintenanceKind, string> = {
  GROUNDING: 'Grounded',
  INSPECTION_DUE: 'Inspection due',
  REPAIR: 'In repair',
  NOTE: 'Note',
};

/**
 * Severity/priority order — the strongest blocker first. Drives both the open-records table's sort
 * (worst-first, so the manager sees the thing actually keeping a vehicle NO-GO before an
 * informational note) and {@link primaryOpenRecord}'s pick of which kind an asset with several open
 * records is bucketed by on the KPI tiles. Mirrors `DefaultReadinessService`'s own
 * `MaintenanceKind#blocksFlight()` split (`GROUNDING`/`INSPECTION_DUE` block; `REPAIR`/`NOTE` don't) —
 * the two blocking kinds sort first, in the same order the plan's own KPI tiles list them.
 */
const KIND_PRIORITY: Record<MaintenanceKind, number> = {
  GROUNDING: 0,
  INSPECTION_DUE: 1,
  REPAIR: 2,
  NOTE: 3,
};

/** A record is open exactly when `closedAt` is absent — the same rule `MaintenanceRecord#isOpen()` (Java) applies server-side. */
export function isOpenRecord(record: FleetMaintenanceRecord): boolean {
  return record.closedAt === undefined;
}

function byOpenedAtAsc(a: FleetMaintenanceRecord, b: FleetMaintenanceRecord): number {
  return Date.parse(a.openedAt) - Date.parse(b.openedAt);
}

function bySeverityThenAge(a: FleetMaintenanceRecord, b: FleetMaintenanceRecord): number {
  const bySeverity = KIND_PRIORITY[a.kind] - KIND_PRIORITY[b.kind];
  return bySeverity !== 0 ? bySeverity : byOpenedAtAsc(a, b);
}

/**
 * One asset's "primary" open record — the one that actually explains why it's grounded: most
 * severe kind first, then the longest-outstanding (oldest `openedAt`) of that kind. `undefined` for
 * an asset with no open record — either a genuinely clean asset, or one whose maintenance read
 * failed and degraded to an empty list (never fabricated). Callers pass a list already scoped to
 * one asset — see {@link maintenanceKpis}'s own grouping.
 */
export function primaryOpenRecord(records: readonly FleetMaintenanceRecord[]): FleetMaintenanceRecord | undefined {
  const open = records.filter(isOpenRecord);
  if (open.length === 0) {
    return undefined;
  }
  return [...open].sort(bySeverityThenAge)[0];
}

/** Every open record in a fleet-wide list, worst-first (severity, then longest outstanding) — the open-records table's own row order. */
export function openRecords(records: readonly FleetMaintenanceRecord[]): readonly FleetMaintenanceRecord[] {
  return [...records].filter(isOpenRecord).sort(bySeverityThenAge);
}

/** Every closed record in a fleet-wide list, newest-closed-first, capped to `limit` (the page's "Recently closed" section, default last 20). */
export function recentlyClosedRecords(
  records: readonly FleetMaintenanceRecord[],
  limit = 20,
): readonly FleetMaintenanceRecord[] {
  return [...records]
    .filter((record) => !isOpenRecord(record))
    .sort((a, b) => Date.parse(b.closedAt as string) - Date.parse(a.closedAt as string))
    .slice(0, limit);
}

/** Groups a flat fleet-wide record list by `assetId` — the endpoint's own row shape doesn't nest by asset, so grouping happens here for {@link maintenanceKpis}'s per-asset bucketing. */
function groupByAssetId(records: readonly FleetMaintenanceRecord[]): ReadonlyMap<string, readonly FleetMaintenanceRecord[]> {
  const map = new Map<string, FleetMaintenanceRecord[]>();
  for (const record of records) {
    const bucket = map.get(record.assetId);
    if (bucket) {
      bucket.push(record);
    } else {
      map.set(record.assetId, [record]);
    }
  }
  return map;
}

/**
 * The page's four KPI tiles (WAREHOUSE-UX-PLAN.md §4 W7: "Grounded (n) · Inspection due (n) · In
 * repair (n) · Retired (n)"). `Retired` reads `inventoryState` directly — no record lookup needed,
 * it's a stored fact (D1). The other three bucket every `MAINTENANCE`-state asset by its
 * {@link primaryOpenRecord}'s kind; an asset whose only open record is a `NOTE` (never how `GROUND`
 * itself stores one, but not impossible if one was opened via the plain `POST .../maintenance`
 * endpoint on an asset some other record already grounded) counts toward none of the three —
 * degrading honestly rather than inventing a fifth silent bucket. `records` should be the fleet-wide
 * `state=all` (or at least every open record) list — an asset's `MAINTENANCE` state with no matching
 * open record here (a `state=open`-only caller that dropped it, or a genuinely stale read) still
 * counts toward none of the three, same degrade-honestly rule.
 */
export function maintenanceKpis(
  assets: readonly AssetSummary[],
  records: readonly FleetMaintenanceRecord[],
): MaintenanceKpis {
  let grounded = 0;
  let inspectionDue = 0;
  let inRepair = 0;
  let retired = 0;
  const byAsset = groupByAssetId(records);
  for (const asset of assets) {
    if (asset.inventoryState === 'RETIRED') {
      retired += 1;
      continue;
    }
    if (asset.inventoryState !== 'MAINTENANCE') {
      continue;
    }
    switch (primaryOpenRecord(byAsset.get(asset.assetId) ?? [])?.kind) {
      case 'GROUNDING':
        grounded += 1;
        break;
      case 'INSPECTION_DUE':
        inspectionDue += 1;
        break;
      case 'REPAIR':
        inRepair += 1;
        break;
      default:
        break;
    }
  }
  return { grounded, inspectionDue, inRepair, retired };
}

/**
 * Hours elapsed since a record's `closedAt` — the "hours since service" fact WAREHOUSE-UX-PLAN.md
 * §3.3 names for the asset-detail KPI band (`features/asset-detail/asset-detail-logic.ts#sinceServiceTile`,
 * called with a per-asset `MaintenanceRecord`), reused here for the Maintenance page's own "Recently
 * closed" section (called with a fleet-wide {@link FleetMaintenanceRecord}) — typed against the one
 * field both shapes share rather than either concrete type, so this one function serves both callers.
 * `undefined` for a still-open record or an unparseable timestamp (degrades to "—", never `NaN`).
 */
export function hoursSinceClose(record: { readonly closedAt?: string }, nowMs: number): number | undefined {
  if (record.closedAt === undefined) {
    return undefined;
  }
  const closedMs = Date.parse(record.closedAt);
  if (Number.isNaN(closedMs)) {
    return undefined;
  }
  return Math.max(0, (nowMs - closedMs) / 3_600_000);
}

/** Assets a "Ground a vehicle" picker should offer — anything not already `MAINTENANCE` or `RETIRED` (grounding either again is meaningless: one's already grounded, the other is gone for good). */
export function groundableAssets(assets: readonly AssetSummary[]): readonly AssetSummary[] {
  return assets.filter((asset) => asset.inventoryState !== 'MAINTENANCE' && asset.inventoryState !== 'RETIRED');
}

/**
 * Whether `record` is the only open record left on its own asset — the open-records table's own
 * Close/Release button treatment (docs/plans/active/WAREHOUSE-UX-CONTEXT.md W10 finding M1: "primary
 * 'Close' vs secondary 'Release' is ambiguous"). The two actions have genuinely different scope —
 * Close (`POST .../maintenance/{id}/close`) closes just this one record; Release (`POST
 * .../inventory {action:RELEASE}`) returns the *whole asset* to stock regardless of anything else
 * still open on it — and the old fixed primary/secondary pair implied a hierarchy between them that
 * doesn't exist. This flips which one is "the" primary action per row instead: with other open
 * records left after this one, Release would return the asset to service while something else is
 * still flagged — the riskier move — so Close (the narrow, low-consequence one) stays primary; once
 * this is the asset's last open record, closing it and releasing it amount to the same outcome, and
 * Release is the one that actually finishes the job. Never changes what either button *does* — only
 * which one `maintenance.page.html` renders as `.btn` vs `.btn.secondary`.
 */
export function isLastOpenRecordForAsset(
  record: FleetMaintenanceRecord,
  records: readonly FleetMaintenanceRecord[],
): boolean {
  return openRecords(records).filter((candidate) => candidate.assetId === record.assetId).length <= 1;
}
