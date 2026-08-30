import type { AssetSummary, MaintenanceKind, MaintenanceRecord } from '../api/models';

/**
 * Pure, Angular-free logic behind `/fleet/maintenance` (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3/§4
 * wave W7). There is no fleet-wide maintenance endpoint (docs/plans/active/WAREHOUSE-UX-CONTEXT.md's "W3
 * → W4/W6/W7 handoff" — flagged there as a W3 follow-up): `MaintenanceFacade` fetches
 * `GET /api/assets/{id}/maintenance` per asset, only for assets whose `inventoryState` is already
 * `MAINTENANCE` (the stored fact — WAREHOUSE-UX-PLAN.md §3.2 D2). Every function here takes that
 * already-fetched `assetId → MaintenanceRecord[]` map rather than reaching for `VisionApi` itself,
 * so it stays unit-testable with hand-built maps, no `HttpClient`/zoneless-signal ceremony.
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

/** One open/closed record paired with the asset it belongs to — the table row shape both `openRecordRows`/`recentlyClosedRecordRows` below build. */
export interface AssetMaintenanceRow {
  readonly asset: AssetSummary;
  readonly record: MaintenanceRecord;
}

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
export function isOpenRecord(record: MaintenanceRecord): boolean {
  return record.closedAt === undefined;
}

function byOpenedAtAsc(a: MaintenanceRecord, b: MaintenanceRecord): number {
  return Date.parse(a.openedAt) - Date.parse(b.openedAt);
}

function bySeverityThenAge(a: MaintenanceRecord, b: MaintenanceRecord): number {
  const bySeverity = KIND_PRIORITY[a.kind] - KIND_PRIORITY[b.kind];
  return bySeverity !== 0 ? bySeverity : byOpenedAtAsc(a, b);
}

/**
 * One asset's "primary" open record — the one that actually explains why it's grounded: most
 * severe kind first, then the longest-outstanding (oldest `openedAt`) of that kind. `undefined` for
 * an asset with no open record — either a genuinely clean asset, or one whose maintenance read
 * failed and degraded to an empty list (never fabricated).
 */
export function primaryOpenRecord(records: readonly MaintenanceRecord[]): MaintenanceRecord | undefined {
  const open = records.filter(isOpenRecord);
  if (open.length === 0) {
    return undefined;
  }
  return [...open].sort(bySeverityThenAge)[0];
}

/**
 * Every open record across a fleet's worth of already-fetched per-asset lists, worst-first
 * (severity, then longest outstanding) — the open-records table's own row order.
 */
export function openRecords(
  recordsByAssetId: ReadonlyMap<string, readonly MaintenanceRecord[]>,
): readonly MaintenanceRecord[] {
  return [...recordsByAssetId.values()]
    .flatMap((records) => records.filter(isOpenRecord))
    .sort(bySeverityThenAge);
}

/** {@link openRecords} joined back to the asset each belongs to — skips a record whose asset isn't in `assets` (shouldn't happen; records are only ever fetched for a known asset id, but never fabricates a row over a missing one). */
export function openRecordRows(
  assets: readonly AssetSummary[],
  recordsByAssetId: ReadonlyMap<string, readonly MaintenanceRecord[]>,
): readonly AssetMaintenanceRow[] {
  const assetsById = new Map(assets.map((asset) => [asset.assetId, asset]));
  const rows: AssetMaintenanceRow[] = [];
  for (const record of openRecords(recordsByAssetId)) {
    const asset = assetsById.get(record.assetId);
    if (asset) {
      rows.push({ asset, record });
    }
  }
  return rows;
}

/** Every closed record across a fleet's worth of already-fetched per-asset lists, newest-closed-first, capped to `limit` (the page's "Recently closed" section, default last 20). */
export function recentlyClosedRecordRows(
  assets: readonly AssetSummary[],
  recordsByAssetId: ReadonlyMap<string, readonly MaintenanceRecord[]>,
  limit = 20,
): readonly AssetMaintenanceRow[] {
  const assetsById = new Map(assets.map((asset) => [asset.assetId, asset]));
  const closed = [...recordsByAssetId.values()]
    .flatMap((records) => records.filter((record) => !isOpenRecord(record)))
    .sort((a, b) => Date.parse(b.closedAt as string) - Date.parse(a.closedAt as string));
  const rows: AssetMaintenanceRow[] = [];
  for (const record of closed) {
    if (rows.length >= limit) {
      break;
    }
    const asset = assetsById.get(record.assetId);
    if (asset) {
      rows.push({ asset, record });
    }
  }
  return rows;
}

/**
 * The page's four KPI tiles (WAREHOUSE-UX-PLAN.md §4 W7: "Grounded (n) · Inspection due (n) · In
 * repair (n) · Retired (n)"). `Retired` reads `inventoryState` directly — no record lookup needed,
 * it's a stored fact (D1). The other three bucket every `MAINTENANCE`-state asset by its
 * {@link primaryOpenRecord}'s kind; an asset whose only open record is a `NOTE` (never how `GROUND`
 * itself stores one, but not impossible if one was opened via the plain `POST .../maintenance`
 * endpoint on an asset some other record already grounded) counts toward none of the three —
 * degrading honestly rather than inventing a fifth silent bucket.
 */
export function maintenanceKpis(
  assets: readonly AssetSummary[],
  recordsByAssetId: ReadonlyMap<string, readonly MaintenanceRecord[]>,
): MaintenanceKpis {
  let grounded = 0;
  let inspectionDue = 0;
  let inRepair = 0;
  let retired = 0;
  for (const asset of assets) {
    if (asset.inventoryState === 'RETIRED') {
      retired += 1;
      continue;
    }
    if (asset.inventoryState !== 'MAINTENANCE') {
      continue;
    }
    switch (primaryOpenRecord(recordsByAssetId.get(asset.assetId) ?? [])?.kind) {
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
 * §3.3 names for the asset-detail KPI band, reused here for the "Recently closed" section.
 * `undefined` for a still-open record or an unparseable timestamp (degrades to "—", never `NaN`).
 */
export function hoursSinceClose(record: MaintenanceRecord, nowMs: number): number | undefined {
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
