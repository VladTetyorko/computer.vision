import type { AssetDetails, AssetUsage, GeoPosition, TelemetrySample } from './api/models';

/**
 * Pure derivations behind `TelemetryStore` (docs/CYCLES-PLAN.md §2), split out so the
 * open-usage/trail/staleness logic can be unit-tested without touching HTTP, timers, or
 * Leaflet — mirrors the `pages/debug/debug-*.ts` split (pure logic, injectable orchestrates).
 */

/**
 * The asset (if any) whose devices include `deviceId`.
 *
 * A device belongs to at most one asset, so the first match wins.
 */
export function findOwningAsset(
  assets: readonly AssetDetails[],
  deviceId: string,
): AssetDetails | undefined {
  return assets.find((asset) => asset.devices.some((device) => device.id === deviceId));
}

/**
 * The asset's currently-open usage, i.e. the `recentUsages` entry with no `endedAt` — an asset
 * has at most one open usage at a time.
 */
export function selectOpenUsage(usages: readonly AssetUsage[]): AssetUsage | undefined {
  return usages.find((usage) => usage.endedAt === undefined);
}

/**
 * Chronological lat/lon points for the map's breadcrumb trail.
 *
 * Samples without a position (a device that only reports battery, say, on that tick) are
 * skipped rather than breaking the polyline with a gap at `(0, 0)`.
 */
export function deriveTrail(samples: readonly TelemetrySample[]): readonly GeoPosition[] {
  const trail: GeoPosition[] = [];
  for (const sample of samples) {
    if (sample.latitude === undefined || sample.longitude === undefined) {
      continue;
    }
    trail.push({
      latitude: sample.latitude,
      longitude: sample.longitude,
      altitudeMeters: sample.altitudeMeters,
    });
  }
  return trail;
}

/** Seconds elapsed since `sampleAt`, or `undefined` when there is no sample yet. */
export function ageSeconds(sampleAt: string | undefined, nowMs: number): number | undefined {
  if (sampleAt === undefined) {
    return undefined;
  }
  return Math.max(0, (nowMs - Date.parse(sampleAt)) / 1000);
}

/** Past this age, a sample is stale enough to be a safety concern for the operator. */
export const STALE_AFTER_SECONDS = 5;

/** Whether a sample age counts as stale — the OSD highlights this state, never hides it. */
export function isStale(age: number | undefined): boolean {
  return age !== undefined && age > STALE_AFTER_SECONDS;
}

/** Background polling pauses while the tab is hidden — the same idiom as `FleetStore`. */
export function shouldPoll(documentHidden: boolean): boolean {
  return !documentHidden;
}
