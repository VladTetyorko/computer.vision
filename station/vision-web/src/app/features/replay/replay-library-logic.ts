import type { AssetSummary, UsageSummary } from '../../core/api/models';
import { formatDuration } from '../../core/stream-info-logic';
import { humanAge } from '../../core/telemetry/telemetry-logic';

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
 *
 * **Superseded as the library's own row/detail status by {@link usageStatus} below**
 * (OPERATOR-UX-5-PLAN.md finding U1, §2 U1) — this still backs a *closed* flight's duration text
 * (both surfaces), but no longer decides "Flying now" on its own: an open usage whose last
 * activity has gone stale must not render this way. Kept exported (unchanged signature/behavior)
 * since `usageStatus` itself calls through to it for the closed case.
 */
export function formatUsageDuration(durationSeconds: number | undefined): string {
  return durationSeconds === undefined ? 'Flying now' : formatDuration(durationSeconds);
}

// --- Honest open-usage status (OPERATOR-UX-5-PLAN.md finding U1, §2 U1) ------------------------
//
// U1's own root cause: `GET /api/usages` can carry a usage with no `endedAt` whose last real
// activity was days ago — a crash, a killed process, or a lost link leaves a usage open forever
// until W1's backend idle-close sweep lands (`contexts/vision-warehouse`'s `UsageSessionService`,
// a parallel, disjoint-file wave this one does not touch). Until then, and even after — the sweep
// itself runs on a timer, so there is always a window between "activity actually stopped" and "the
// backend notices" — the web client must never render every `endedAt === undefined` row as
// confidently "live" the way the old `!usage.endedAt` check did.

/**
 * The best last-activity instant the wire actually gives an *open* usage. `UsageSummary` carries
 * no `lastSampleAt`/`updatedAt` field (grepped `core/api/models.ts`; confirmed server-side too —
 * `vision-warehouse`'s `DefaultUsageService`/`UsageSummary` set `durationSeconds` to `null`
 * whenever `endedAt` is `null`, i.e. it is **never** populated for a still-open usage today). This
 * is therefore `startedAt + durationSeconds` when a future wire adds a live elapsed duration for an
 * open flight, else the plain `startedAt` — an honest proxy, not a true last-sample time. A usage
 * that is genuinely still flying past {@link OPEN_USAGE_STALE_AFTER_SECONDS} has no way to
 * distinguish itself from a same-age ghost using only these two fields; that is a real gap in the
 * frozen `GET /api/usages` contract, not something this function can paper over.
 */
function lastActivityMs(usage: Pick<UsageSummary, 'startedAt' | 'durationSeconds'>): number {
  return Date.parse(usage.startedAt) + (usage.durationSeconds ?? 0) * 1000;
}

/**
 * How long an open usage's own {@link lastActivityMs} can age before the library stops calling it
 * "Flying now". Matches OPERATOR-UX-5-PLAN.md §2's own citation of the backend's
 * `vision.usage.idle-close` default (10 min, root `application.yaml`) — chosen so a genuinely
 * still-flying usage (which the backend's idle-close sweep keeps open only while fresh telemetry/
 * frame activity keeps arriving) reads live, and only a same-shape ghost the sweep hasn't caught
 * yet (or one that predates the backend fix entirely) reads as stale. The frontend has no way to
 * read the real configured value back — no client-config endpoint exists anywhere in this codebase
 * (see `station/vision-web/MODULE.md`'s own Gotchas precedent for `flight-state-logic.ts`'s rover
 * battery threshold) — so this is a reasoned placeholder, not a measured constant.
 */
export const OPEN_USAGE_STALE_AFTER_SECONDS = 10 * 60;

export type UsageStatusKind = 'flying' | 'open-stale' | 'closed' | 'no-samples';

/**
 * The one derivation both the row and the detail-pane preview render from — never let the two
 * surfaces independently re-derive "is this flying" and risk disagreeing (the class of bug
 * OPERATOR-UX-4-PLAN.md's N2 named for the Command rail/panel split). `tone`/`live` are the chip's
 * own visual vocabulary (`tone: 'ok'` + a live dot only for `'flying'`; every other kind is the
 * neutral, dot-less structural register — `frontend-style` §8). `isReplayLink` is `false` only for
 * `'no-samples'`: a usage with zero recorded samples has nothing to scrub, so it must not offer a
 * broken "Open replay ›" affordance.
 */
export interface UsageStatus {
  readonly kind: UsageStatusKind;
  readonly label: string;
  readonly tone: 'ok' | 'neutral';
  readonly live: boolean;
  readonly isReplayLink: boolean;
}

/**
 * `usage`'s honest status as of `nowMs` (a parameter, never a fresh `Date.now()` read in here —
 * this app's own determinism convention for time-based pure logic, see
 * `filterUsagesByTimeRange`'s own doc comment).
 *
 * Order of checks matters: `sampleCount === 0` wins outright, even over an open usage — a flight
 * with literally nothing recorded is not "flying now" just because it never got an `endedAt`
 * (U1's own second finding: "rows with 0 samples listed like flights"). Otherwise an open usage
 * (`endedAt === undefined`) is `'flying'` only while its {@link lastActivityMs} is fresh; past
 * {@link OPEN_USAGE_STALE_AFTER_SECONDS} it is `'open-stale'` — a neutral chip, never the confident
 * green "Flying now" a usage days old was wrongly getting. A closed usage (`endedAt` present) is
 * unchanged from today: `formatUsageDuration`'s own rendering, plain text register (never a chip —
 * matches this table's pre-existing "one chip per row max" convention, `frontend-style` §5).
 */
export function usageStatus(usage: UsageSummary, nowMs: number): UsageStatus {
  if (usage.sampleCount === 0) {
    return { kind: 'no-samples', label: 'No samples', tone: 'neutral', live: false, isReplayLink: false };
  }
  if (usage.endedAt === undefined) {
    const ageSeconds = Math.max(0, (nowMs - lastActivityMs(usage)) / 1000);
    if (ageSeconds > OPEN_USAGE_STALE_AFTER_SECONDS) {
      return { kind: 'open-stale', label: `Open · last sample ${humanAge(ageSeconds)} ago`, tone: 'neutral', live: false, isReplayLink: true };
    }
    return { kind: 'flying', label: 'Flying now', tone: 'ok', live: true, isReplayLink: true };
  }
  return { kind: 'closed', label: formatUsageDuration(usage.durationSeconds), tone: 'neutral', live: false, isReplayLink: true };
}

/**
 * The library's own display order: `usages` (server-delivered newest-first, per `UsageSummary`'s
 * own doc comment) with every `sampleCount === 0` row stably demoted to the end — a flight with no
 * recorded samples has nothing to triage, so it should never crowd out a real flight from the
 * visible top of a long list. `Array.prototype.sort` is stable (guaranteed since ES2019, which this
 * app's build target already assumes), so the newest-first order is otherwise fully preserved
 * within both partitions.
 */
export function sortUsagesForDisplay(usages: readonly UsageSummary[]): readonly UsageSummary[] {
  return [...usages].sort((a, b) => Number(a.sampleCount === 0) - Number(b.sampleCount === 0));
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
