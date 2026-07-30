import type { AssetStats, AssetUsage } from '../api/models';
import { relativeTimeLabel } from '../events/events-logic';

/**
 * Pure logic behind the asset manager page's KPI tile row and "Recent flights" utilization chart
 * (docs/ASSET-MANAGER-PAGE-PLAN.md, Wave B items 3–4) — split out so the formatting/aggregation
 * math is unit-testable without HTTP, timers, or the DOM, mirroring `asset-detail-logic.ts`'s own
 * split.
 *
 * No fake numbers, ever (the plan's own guardrail): every value here either renders a real number
 * or degrades to `'—'` — see `formatFlightTime`/`kpiTiles` below for exactly where that happens.
 */

// --- Duration -----------------------------------------------------------------------------------

/**
 * Seconds elapsed for one usage, open or closed — an open usage (`endedAt` absent) counts up to
 * `nowMs`, the same "open flights count toward flight time up to now" rule the backend's
 * `AssetStats` aggregation itself uses (docs/ASSET-MANAGER-PAGE-PLAN.md, Wave A). Shared by
 * `flightBars` below and `AssetDetailPage#usageDuration` (the Usage history table), so both read
 * off one definition of "how long was this flight."
 */
export function usageDurationSeconds(usage: AssetUsage, nowMs: number): number {
  const endMs = usage.endedAt ? Date.parse(usage.endedAt) : nowMs;
  return Math.max(0, (endMs - Date.parse(usage.startedAt)) / 1000);
}

/**
 * `"12h 34m"` / `"34m"` — the KPI row's flight-time formatting (docs/ASSET-MANAGER-PAGE-PLAN.md,
 * Wave B item 3). `null` renders `'—'` — the caller (`kpiTiles`) is the one place that decides
 * *when* a duration is honestly unknown (no flights fetched at all) versus honestly zero (flights
 * fetched, their total just happens to be zero seconds) — this function only ever renders the
 * number it's handed, never guesses. Minutes-only granularity (no seconds): these are lifetime/
 * average aggregates, not a live-ticking single-session duration — `core/stream-info-logic.ts#formatDuration`
 * already owns that shape for the session-in-progress case.
 */
export function formatFlightTime(totalSeconds: number | null): string {
  if (totalSeconds === null) {
    return '—';
  }
  const totalMinutes = Math.round(Math.max(0, totalSeconds) / 60);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  return hours > 0 ? `${hours}h ${String(minutes).padStart(2, '0')}m` : `${minutes}m`;
}

// --- KPI tile row ---------------------------------------------------------------------------------

/**
 * One tile in the KPI row — `dataviz`'s stat-tile contract (label + value, `sub` for a secondary
 * line), trimmed to what this row needs: no delta/sparkline (there's no prior-period comparison
 * here, just the current lifetime aggregate). `live` marks the one tile (Total flight time) that
 * carries the in-progress pip; always `false`/absent on every other tile.
 */
export interface KpiTile {
  readonly label: string;
  readonly value: string;
  readonly sub?: string;
  readonly live?: boolean;
}

/**
 * `AssetStats` → the KPI row's five tiles, in the plan's own "biggest number first" order (Total
 * flight time, Flights, Last flown, Avg flight, Battery). `stats` is `undefined` when the
 * `/stats` fetch itself failed/404'd (`AssetDetailPage#loadStats`'s own catch) — every tile
 * degrades to `'—'` in that case, **including Flights** (a plain `0` there would dishonestly claim
 * "we know this asset has never flown" when the truth is "we don't know", the same distinction
 * `formatFlightTime`'s own doc comment draws for total flight time).
 */
export function kpiTiles(stats: AssetStats | undefined, nowMs: number): readonly KpiTile[] {
  if (!stats) {
    return [
      { label: 'Total flight time', value: '—' },
      { label: 'Flights', value: '—' },
      { label: 'Last flown', value: '—' },
      { label: 'Avg flight', value: '—' },
      { label: 'Battery', value: '—' },
    ];
  }
  return [
    {
      label: 'Total flight time',
      // `flightCount === 0` is "no flights fetched" (honestly unknown/never flown), never a `0m`
      // — see `formatFlightTime`'s own doc comment.
      value: formatFlightTime(stats.flightCount > 0 ? stats.totalFlightSeconds : null),
      sub: stats.flightInProgress ? 'Flying now' : undefined,
      live: stats.flightInProgress,
    },
    { label: 'Flights', value: String(stats.flightCount) },
    {
      label: 'Last flown',
      // Reuses `core/events/events-logic.ts#relativeTimeLabel` verbatim — same "Xh XXm ago" shape
      // already used for the Events card on this same page, no local reimplementation.
      value: stats.lastFlownAt ? relativeTimeLabel(stats.lastFlownAt, nowMs) : '—',
    },
    { label: 'Avg flight', value: formatFlightTime(stats.avgFlightSeconds ?? null) },
    {
      label: 'Battery',
      value: stats.lastKnownBatteryPercent !== undefined ? `${stats.lastKnownBatteryPercent}%` : '—',
    },
  ];
}

// --- "Recent flights" utilization chart ----------------------------------------------------------

/** A bar's minimum visible height, as a percent of the chart's plot height — even a near-zero-duration flight stays hoverable/visible rather than collapsing to nothing. */
const MIN_BAR_HEIGHT_PERCENT = 6;

/**
 * One bar of the "Recent flights" chart — one per `AssetDetails#recentUsages` entry, oldest first
 * (that list itself is newest-first; see `flightBars` below).
 */
export interface FlightBar {
  readonly usageId: string;
  readonly startedAt: string;
  readonly durationSeconds: number;
  /** Still open (`endedAt` absent) — the chart marks this bar with a live pip. */
  readonly open: boolean;
  /** 0–100, floored at `MIN_BAR_HEIGHT_PERCENT` — the component reads this straight into a CSS height, no scale math in the template. */
  readonly heightPercent: number;
}

/**
 * `AssetDetails#recentUsages` (newest-first, capped — `DefaultAssetService#RECENT_USAGES_LIMIT`)
 * → the chart's bars, oldest-first (a left-to-right chronological read, the "trend over time" job
 * — `dataviz`'s own form guidance) with each bar's height pre-scaled against the set's own max
 * duration. Honestly labeled "recent flights" by the caller, distinct from the KPI row's lifetime
 * aggregate — this is only ever the capped list, never a full history.
 */
export function flightBars(recentUsages: readonly AssetUsage[], nowMs: number): readonly FlightBar[] {
  const chronological = [...recentUsages].reverse();
  const durations = chronological.map((usage) => usageDurationSeconds(usage, nowMs));
  const maxDurationSeconds = Math.max(0, ...durations);
  return chronological.map((usage, index) => {
    const durationSeconds = durations[index];
    const heightPercent =
      maxDurationSeconds > 0
        ? Math.max(MIN_BAR_HEIGHT_PERCENT, (durationSeconds / maxDurationSeconds) * 100)
        : MIN_BAR_HEIGHT_PERCENT;
    return {
      usageId: usage.usageId,
      startedAt: usage.startedAt,
      durationSeconds,
      open: usage.endedAt === undefined,
      heightPercent,
    };
  });
}
