import type { AssetSummary, UsageSummary } from '../../core/api/models';
import { formatDuration } from '../../core/stream-info-logic';

/**
 * Pure, unit-tested logic behind the replay library (`/replay` with no deep-link params —
 * docs/extracts/design/10-replay.md's Wave 4 "real" design, closing F8). Mirrors this codebase's own
 * "extract testable logic into a pure `*-logic.ts`, keep the facade/component thin" precedent
 * (e.g. `core/activity/activity-logic.ts`'s day-bucketing behind `/activity`).
 */

/**
 * A flight's own duration cell — `"Flying now"` for a still-open usage (`durationSeconds`
 * genuinely absent per the frozen wire contract, never `null`/negative), else
 * `stream-info-logic.ts#formatDuration`'s existing `"44m 02s"`/`"1h 03m"` rendering, reused
 * verbatim so this list and the replay cockpit's own duration readouts never disagree on format.
 */
export function formatUsageDuration(durationSeconds: number | undefined): string {
  return durationSeconds === undefined ? 'Flying now' : formatDuration(durationSeconds);
}

/** The page bar's time-range filter — client-side only (the frozen `GET /api/usages` contract has
 *  no `since`/date-range query param), applied over whatever `limit` rows were already fetched. */
export type TimeRangeFilter = 'all' | 'today' | '7d' | '30d';

const TIME_RANGE_WINDOW_MS: Readonly<Record<Exclude<TimeRangeFilter, 'all'>, number>> = {
  today: 24 * 60 * 60 * 1000,
  '7d': 7 * 24 * 60 * 60 * 1000,
  '30d': 30 * 24 * 60 * 60 * 1000,
};

/**
 * `usages` narrowed to those started within `range` of `nowMs` — `'all'` is a no-op (identity),
 * every other tier is a plain `startedAt >= cutoff` filter; a still-open flight's own `startedAt`
 * is all this needs, `endedAt` never enters into it. `nowMs` is a parameter, not a fresh
 * `Date.now()` read in here — this app's own determinism convention for time-filtering helpers
 * (e.g. `core/telemetry/flight-state-logic.ts#derivePreflight`'s doc comment names it explicitly).
 */
export function filterUsagesByTimeRange(
  usages: readonly UsageSummary[],
  range: TimeRangeFilter,
  nowMs: number,
): readonly UsageSummary[] {
  if (range === 'all') {
    return usages;
  }
  const cutoffMs = nowMs - TIME_RANGE_WINDOW_MS[range];
  return usages.filter((usage) => Date.parse(usage.startedAt) >= cutoffMs);
}

/** One `<select>` option for the page bar's asset filter — display name only, no lifecycle/category
 *  noise (the filter is "which drone", not a second asset browser). */
export interface UsageAssetOption {
  readonly assetId: string;
  readonly displayName: string;
}

/**
 * Every asset the library could filter to, alphabetized — built from the full asset list
 * (`VisionApi.listAssets`), independent of which assets actually appear in the current
 * `limit`-capped usage page, so filtering to a quiet asset's own history is always reachable
 * (an asset with only old flights would otherwise never earn a dropdown entry of its own).
 */
export function usageAssetOptions(assets: readonly AssetSummary[]): readonly UsageAssetOption[] {
  return [...assets]
    .map((asset) => ({ assetId: asset.assetId, displayName: asset.displayName }))
    .sort((a, b) => a.displayName.localeCompare(b.displayName));
}
