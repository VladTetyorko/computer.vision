import type { AssetAttention, CategoryCounts, FleetSummary } from '../../core/api/models';
import { attentionReasons, type AttentionReason } from '../../core/fleet/attention-logic';

/**
 * Pure, Angular-free logic behind `ReportsPage` (`/manage/reports`, docs/plans/done/UI-REDESIGN-PLAN.md Wave 4
 * — **SPLIT**: the read-only stats dashboard below (KPI tiles, a per-category breakdown, an
 * attention list) is functional, reusing `FleetController`'s summary (`GET /api/fleet/summary`,
 * already `VisionApi.fleetSummary`); exportable/generated reports are not built — no export endpoint
 * exists — stated plainly on the page, never a fake "Export" button).
 *
 * `attentionReasons` is reused from `core/fleet/attention-logic.ts` (moved out of
 * `features/command/command-logic.ts` this same wave so this page could reuse it without importing
 * across feature folders — see that module's own doc comment) with `gpsFixType`/`geofenceBreaches`
 * never supplied: this dashboard has no live map marker or geofence feed to draw either from, so
 * those two reason kinds simply never fire here — an honest "not evaluated", identical to how
 * Command itself treats an asset with no marker. `pipelineErrorMessagesByStreamId`
 * (docs/plans/done/SYSTEM-STATUS-PLAN.md §3.4) *is* threaded through, optionally — `ReportsFacade` has a
 * `LiveStore` to read (unlike a map marker or geofence feed, `LiveEvent`s cost this page nothing
 * extra to read), so `pipeline-error` is the one live-derived reason this static dashboard can still
 * show honestly.
 */

export interface ReportKpis {
  readonly totalAssets: number;
  readonly streaming: number;
  readonly active: number;
  readonly deactivated: number;
  readonly needsAttention: number;
}

const EMPTY_KPIS: ReportKpis = { totalAssets: 0, streaming: 0, active: 0, deactivated: 0, needsAttention: 0 };

/** `undefined` (not yet loaded) degrades to all-zero — the page's own loading/error state is what
 *  tells the difference between "zero" and "unknown", never this function. */
export function reportKpis(
  summary: FleetSummary | undefined,
  pipelineErrorMessagesByStreamId?: ReadonlyMap<string, string>,
): ReportKpis {
  if (!summary) {
    return EMPTY_KPIS;
  }
  const streaming = summary.assets.filter((asset) => asset.streaming).length;
  const active = summary.categories.reduce((sum, category) => sum + category.active, 0);
  const deactivated = summary.categories.reduce((sum, category) => sum + category.deactivated, 0);
  const needsAttention = summary.assets.filter(
    (asset) =>
      attentionReasons(
        asset,
        undefined,
        undefined,
        asset.streamId ? pipelineErrorMessagesByStreamId?.get(asset.streamId) : undefined,
      ).length > 0,
  ).length;
  return { totalAssets: summary.totalAssets, streaming, active, deactivated, needsAttention };
}

/** One category's own bar in the "assets by category" breakdown. */
export interface CategoryBar {
  readonly categoryId: string;
  readonly name: string;
  readonly total: number;
  /** 0–100, scaled against the largest category's own total; floored so a non-zero bar stays visible. */
  readonly percentOfMax: number;
}

/** Floor so a small-but-nonzero category still renders a visible sliver, mirroring
 *  `core/fleet/asset-stats-logic.ts#MIN_BAR_HEIGHT_PERCENT`'s identical "hoverable, not invisible" rule. */
const MIN_BAR_PERCENT = 4;

/** Largest total first, name as the tie-break — a magnitude comparison reads top-to-bottom. */
export function categoryBars(categories: readonly CategoryCounts[]): readonly CategoryBar[] {
  const max = Math.max(1, ...categories.map((category) => category.total));
  return [...categories]
    .sort((a, b) => b.total - a.total || a.categoryName.localeCompare(b.categoryName))
    .map((category) => ({
      categoryId: category.categoryId,
      name: category.categoryName,
      total: category.total,
      percentOfMax: category.total === 0 ? 0 : Math.max(MIN_BAR_PERCENT, Math.round((category.total / max) * 100)),
    }));
}

/** One flagged asset's own row in the attention table. */
export interface AttentionRow {
  readonly asset: AssetAttention;
  /** Most severe first — `attentionReasons`' own order. Never empty (see {@link attentionRows}). */
  readonly reasons: readonly AttentionReason[];
}

/**
 * Every asset with ≥1 triggered reason, most-reasons-first, name as the tie-break — a "what needs
 * looking at" punch list, not the full fleet (unlike Command's entity rail, which deliberately shows
 * every asset; this dashboard's attention section is specifically the flagged subset).
 */
export function attentionRows(
  assets: readonly AssetAttention[],
  pipelineErrorMessagesByStreamId?: ReadonlyMap<string, string>,
): readonly AttentionRow[] {
  return assets
    .map(
      (asset): AttentionRow => ({
        asset,
        reasons: attentionReasons(
          asset,
          undefined,
          undefined,
          asset.streamId ? pipelineErrorMessagesByStreamId?.get(asset.streamId) : undefined,
        ),
      }),
    )
    .filter((row) => row.reasons.length > 0)
    .sort(
      (a, b) =>
        b.reasons.length - a.reasons.length ||
        a.asset.displayName.localeCompare(b.asset.displayName, undefined, { sensitivity: 'base' }),
    );
}
