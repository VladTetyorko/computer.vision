import type { AssetDetails, AssetUsage, Device, GeoPosition, TelemetrySample } from '../api/models';

/**
 * Pure derivations behind `TelemetryStore` (docs/main/CYCLES-PLAN.md §2), split out so the
 * open-usage/trail/staleness logic can be unit-tested without touching HTTP, timers, or
 * Leaflet — mirrors the `features/debug/debug-*.ts` split (pure logic, injectable orchestrates).
 */

/**
 * Re-exported from `core/poll-scheduler.ts`, which is now its canonical home (docs/main/CYCLES-PLAN.md
 * §9, CU-b item 3 — the shared poll scheduler). Kept here too so the existing
 * `telemetry-logic.ts#shouldPoll` import path every caller already used keeps working verbatim.
 */
export { shouldPoll } from '../poll-scheduler';

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

/**
 * Groups one usage's mixed-source telemetry samples by `deviceId` (docs/main/CYCLES-PLAN.md §11,
 * CD-a — every sample carries the telemetry device it came from), preserving each device's own
 * samples in their original (chronological) order — the same order `deriveTrail`/callers already
 * assume for the flat list.
 *
 * Originally `pages/asset-detail/asset-detail-logic.ts#groupTelemetryByDevice`; moved here in
 * docs/plans/done/MVP2-PLAN.md §R (R-b) when the replay cockpit (`features/replay/replay-logic.ts`) needed the
 * identical per-device split for its own telemetry-at-scrub-time panels — this codebase has no
 * precedent for one page importing another page's module (see `core/fleet/device-logic.ts`'s doc
 * comment for the original precedent this follows), so a shared `core/` home was used instead.
 * `asset-detail-logic.ts` re-exports this so its own existing import site keeps working verbatim.
 */
export function groupTelemetryByDevice(
  samples: readonly TelemetrySample[],
): ReadonlyMap<string, readonly TelemetrySample[]> {
  const byDevice = new Map<string, TelemetrySample[]>();
  for (const sample of samples) {
    const existing = byDevice.get(sample.deviceId);
    if (existing) {
      existing.push(sample);
    } else {
      byDevice.set(sample.deviceId, [sample]);
    }
  }
  return byDevice;
}

/**
 * Every `TELEMETRY`-capable device on an asset — the panels a per-device grouped view renders
 * one-per, including a device that has reported no sample yet. Moved here alongside
 * `groupTelemetryByDevice` — see that function's doc comment for why.
 */
export function telemetryDevices(devices: readonly Device[]): readonly Device[] {
  return devices.filter((device) => device.capabilities.includes('TELEMETRY'));
}

// --- Severity tiers (docs/plans/done/MVP3-PLAN.md §C-b) --------------------------------------------------
// Two independent "how worried should the operator be" derivations, both pure so the Fly
// cockpit's OSD chip bar can color-escalate without inventing new backend concepts. `batterySeverity`
// started as a private computed inside `pages/live/telemetry-osd.ts`; lifted here (unchanged
// thresholds/behavior) once the Fly cockpit's own OSD needed the identical classification —
// the same "second consumer needs it, move it to core/" precedent as `findVideoDevice`/
// `groupTelemetryByDevice` above. `telemetry-osd.ts` now imports it instead of computing its own.

/** Battery-bar color thresholds, roughly matching common flight-controller OSDs. */
export const BATTERY_LOW_PERCENT = 45;
export const BATTERY_CRITICAL_PERCENT = 20;

export type BatterySeverity = 'ok' | 'low' | 'critical' | 'unknown';

/** `'unknown'` for a device that hasn't reported a battery reading yet — never a fabricated tier. */
export function batterySeverity(percent: number | undefined): BatterySeverity {
  if (percent === undefined) {
    return 'unknown';
  }
  if (percent <= BATTERY_CRITICAL_PERCENT) {
    return 'critical';
  }
  return percent <= BATTERY_LOW_PERCENT ? 'low' : 'ok';
}

/**
 * A third tier past the existing binary `isStale`, for the Fly cockpit's OSD chip bar
 * (docs/plans/done/MVP3-PLAN.md §C-b: "telemetry age color escalation: fresh/amber >5s/red >10s"). `amber`
 * reuses `STALE_AFTER_SECONDS` — the exact threshold every other "this reading is stale" indicator
 * in this app already uses (`TelemetryStore.stale()`, `TelemetryOsd`'s `.stale`/`.stale-text`
 * classes) — so the OSD's medium tier lines up with what "stale" already means everywhere else;
 * `TELEMETRY_AGE_RED_SECONDS` is the one genuinely new, more urgent threshold this cycle adds.
 */
export const TELEMETRY_AGE_RED_SECONDS = 10;

export type TelemetryAgeSeverity = 'fresh' | 'amber' | 'red';

/** Only meaningful for a known sample age — callers with no sample yet render their own "—" state. */
export function telemetryAgeSeverity(age: number): TelemetryAgeSeverity {
  if (age > TELEMETRY_AGE_RED_SECONDS) {
    return 'red';
  }
  return age > STALE_AFTER_SECONDS ? 'amber' : 'fresh';
}

/**
 * Whether a tracking effect (docs/plans/done/REALTIME-PLAN.md Phase R-a item 2) should re-enter its store's
 * `track()`/`reset()` this run: only when the derived id primitive actually changed from the id it
 * last acted on. A page's own `asset()`/`stream()` signals are fresh objects on every ~5s poll tick
 * even when nothing about the tracked device/stream actually changed (signals compare with
 * `Object.is`), so an effect reading them re-fires on that cadence regardless — without this guard,
 * re-entering `TelemetryStore`/`DetectionsStore`'s `track()` with an *unchanged* id was the
 * diagnosed O(N) amplification bug (`FlyPage`) and, worse, a self-sustaining track/untrack
 * oscillation entirely decoupled from any poll cadence (`AssetDetailPage`, docs/plans/done/REALTIME-PLAN.md §4
 * Phase R-c follow-up): re-entering `track()` re-runs its internal teardown, which reads that
 * store's own `currentAssetIdSignal` — a read that (because it happens synchronously while the
 * *caller's* effect is still the active reactive consumer) gets attributed to the caller's effect,
 * not the store's own internal one; the later write to that same signal (once `track()`'s async
 * lookup resolves) then re-notifies the caller's effect even though nothing it actually reads
 * changed, closing a loop paced only by however fast the store's own lookups resolve. Mirrors
 * `core/map/map-store.ts#reconcileTrackers`'s own reconcile-by-id idiom: compare the id *value*,
 * never the enclosing object's identity.
 *
 * Originally `features/fly/fly-logic.ts#trackingIdChanged`; moved here (docs/plans/done/REALTIME-PLAN.md §4
 * Phase R-c follow-up) when `features/asset-detail/asset-detail.ts` needed the identical guard for
 * its own telemetry/detections tracking effects — this codebase has no precedent for one page
 * importing another page's module (see `core/fleet/device-logic.ts`'s doc comment for the original
 * precedent this follows), so a shared `core/` home was used instead, the same "second consumer
 * needs it, move it to core/" precedent as `groupTelemetryByDevice`/`batterySeverity` above.
 * `fly-logic.ts` re-exports this so its own existing import site keeps working verbatim.
 */
export function trackingIdChanged(nextId: string | undefined, lastActedOnId: string | undefined): boolean {
  return nextId !== lastActedOnId;
}
