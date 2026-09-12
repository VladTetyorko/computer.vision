import type { DetectionEvent, FleetSummary } from '../api/models';

/**
 * How long a summary refetch waits after the previous one before an invalidation may trigger it
 * (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §5 L8a). `GET /api/fleet/summary` is not derivable
 * from any broadcast topic — seven of `AssetAttention`'s thirteen fields have no topic at all (§5
 * L8's own table) — so the endpoint stays; only its *trigger* changes, from a fixed 5s timer to
 * "something structural actually happened, at most this often".
 */
export const SUMMARY_INVALIDATION_DEBOUNCE_MS = 2_000;

/**
 * The slowest the summary may go stale with nothing invalidating it. The telemetry-derived fields
 * (`batteryPercent`, `telemetryAgeMs`, `flightMode`, `armed`, `failsafe`) ride no topic, so no live
 * arrival can announce that they changed — without this floor they would only refresh when some
 * *other* fact happened to move.
 *
 * **This is the one place L8a is worse than the 5s timer it replaces**, and deliberately so: battery
 * freshness goes 5s → 10s, while structural freshness (a stream starting, a device appearing)
 * improves from "up to 5s" to "~2s". That trade is the wave's whole point and is called out in the
 * plan rather than discovered later.
 */
export const SUMMARY_FLOOR_INTERVAL_MS = 10_000;

/**
 * How long an invalidation that arrives `nowMs` must wait, given the last fetch started at
 * `lastFetchAtMs` — `0` meaning "go now". Pure so the debounce arithmetic is testable without
 * standing up a facade and its dozen injected collaborators.
 *
 * `lastFetchAtMs <= 0` means nothing has been fetched yet, which cannot be debounced against.
 */
export function invalidationDelayMs(
  lastFetchAtMs: number,
  nowMs: number,
  debounceMs: number = SUMMARY_INVALIDATION_DEBOUNCE_MS,
): number {
  if (lastFetchAtMs <= 0) {
    return 0;
  }
  return Math.max(0, Math.min(debounceMs, debounceMs - (nowMs - lastFetchAtMs)));
}

/** The asset ids the summary currently lists — the set a detection event must name to be worth a refetch. */
export function listedAssetIds(summary: FleetSummary | undefined): ReadonlySet<string> {
  return new Set((summary?.assets ?? []).map((asset) => asset.assetId));
}

/**
 * Whether any of `events` is worth a summary refetch: it must name an asset the summary already
 * lists. A detection event for an asset outside this viewer's summary changes nothing the summary
 * would render, and an event with no `assetId` at all (a stream not yet bound to an asset — see
 * {@link DetectionEvent}'s own optional field) names nothing to refresh.
 */
export function anyNamesListedAsset(
  events: readonly DetectionEvent[],
  listed: ReadonlySet<string>,
): boolean {
  return events.some((event) => event.assetId !== undefined && listed.has(event.assetId));
}
