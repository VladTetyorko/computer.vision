import type { GeoPosition, UsageSummary, UsageTimeline } from '../api/models';
import { hasFix } from '../geo/geo-logic';

/**
 * Pure, Angular-free logic behind wave W3 of `docs/plans/active/COMMAND-MAP-FLOW-PLAN.md` §3.4 —
 * "route on click": drawing a selected asset's recent flight(s) as a polyline, distinct from the
 * always-on live breadcrumb (`core/map/map-logic.ts#windowTrail`, capped at `TRAIL_WINDOW=60`). A
 * route is deliberately **not** a `Drawing` — it is never persisted, granted, verified, or
 * promoted, and never touches `/api/map/**`; it is a read-only shaping of the same
 * `GET /api/usages` + `GET /api/usages/{usageId}/timeline` pair `features/replay/**` already reads
 * (never `.../telemetry`, which is earliest-not-latest — D1's own defect, `TelemetryRepositoryPort`
 * fixes it on the backend, this module never depends on the fix).
 */

/** How much of the Telemetry tab's segmented control is showing on the map, per §3.4's frozen contract. */
export type RouteSpan = 'off' | 'last' | 'last3';

/**
 * Mirrors §3.4's frozen `AssetRoute` shape verbatim. `points` is chronological and fix-carrying
 * only (`hasFix` already filtered out any `(0,0)`/missing sample) — never the raw telemetry array,
 * so a caller never has to re-filter before plotting. `truncated` means the usage's own recorded
 * sample count exceeded the request's `maxPoints` cap (the server thinned it, keeping first/last —
 * `UsageTimeline`'s own doc comment) — a fact this module can only know by comparing against the
 * hop-1 `UsageSummary.sampleCount`, which is why {@link buildAssetRoute} takes both.
 */
export interface AssetRoute {
  readonly assetId: string;
  readonly usageId: string;
  readonly startedAt: string;
  readonly endedAt?: string;
  readonly points: readonly GeoPosition[];
  readonly truncated: boolean;
}

/** The two-hop fetch's own request ceiling — `RouteStore`'s `usageTimeline(usageId, { maxPoints })` call. */
export const ROUTE_MAX_POINTS = 500;

/** How many usages hop 1 (`listUsages`) should ask for — `'off'` never fetches at all. */
export function routeUsageLimit(span: RouteSpan): number {
  switch (span) {
    case 'off':
      return 0;
    case 'last':
      return 1;
    case 'last3':
      return 3;
  }
}

/**
 * Shapes one usage's timeline into an `AssetRoute` — the fix-filter + truncation-detection this
 * module exists for. `summary` is the same `UsageSummary` row hop 1 already returned for this
 * usage (its `sampleCount` is the only source of "did the server actually thin this"); `timeline`
 * is hop 2's own response for the identical `usageId`.
 */
export function buildAssetRoute(
  assetId: string,
  summary: Pick<UsageSummary, 'usageId' | 'sampleCount'>,
  timeline: UsageTimeline,
  maxPoints: number = ROUTE_MAX_POINTS,
): AssetRoute {
  const points: readonly GeoPosition[] = timeline.telemetry.filter(hasFix).map((sample) => ({
    latitude: sample.latitude!,
    longitude: sample.longitude!,
    altitudeMeters: sample.altitudeMeters,
  }));
  return {
    assetId,
    usageId: timeline.usage.usageId,
    startedAt: timeline.usage.startedAt,
    endedAt: timeline.usage.endedAt,
    points,
    truncated: summary.sampleCount > maxPoints,
  };
}
