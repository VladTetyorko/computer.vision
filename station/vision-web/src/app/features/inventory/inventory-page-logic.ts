import type { FleetSummary } from '../../core/api/models';
import { attentionReasons } from '../../core/fleet/attention-logic';

/**
 * Pure, Angular-free logic behind `InventoryPage`'s own "Fleet at a glance" KPI strip
 * (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 rule 3 / §3.3, wave W4) — ported byte-for-byte from
 * the now-deleted `features/reports/reports-logic.ts#reportKpis` (`/manage/reports`, **SPLIT** since
 * docs/plans/done/UI-REDESIGN-PLAN.md Wave 4). `ReportsPage`'s other two sections — the "Assets by
 * category" bar chart and the "Needs attention" punch list — are **not** carried forward here: the
 * Vehicles/Equipment table this KPI strip now sits above already answers both questions per-row
 * (the category filter + column narrows by category; the Inventory-state/Readiness columns are the
 * new, per-asset form of "needs attention" — `AssetAttention`'s own reasons vocabulary, which the
 * table doesn't consume, would otherwise duplicate that column with a second, differently-scoped
 * queue). See `station/vision-web/MODULE.md`'s W4 changelog entry for the full "what wasn't
 * absorbed" note.
 */

export interface InventoryKpis {
  readonly totalAssets: number;
  readonly streaming: number;
  readonly active: number;
  readonly deactivated: number;
  readonly needsAttention: number;
}

const EMPTY_KPIS: InventoryKpis = { totalAssets: 0, streaming: 0, active: 0, deactivated: 0, needsAttention: 0 };

/** `undefined` (not yet loaded) degrades to all-zero — the page's own loading/error state is what
 *  tells the difference between "zero" and "unknown", never this function. */
export function inventoryKpis(
  summary: FleetSummary | undefined,
  pipelineErrorMessagesByStreamId?: ReadonlyMap<string, string>,
): InventoryKpis {
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
