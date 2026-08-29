import type { AssetStatus, AssetSummary, GeoPosition, TelemetrySample } from '../api/models';
import { hasFix } from '../geo/geo-logic';
import { ageSeconds, deriveTrail } from '../telemetry/telemetry-logic';

/**
 * Pure derivations behind the fleet map (docs/main/CYCLES-PLAN.md §6's `/map` tab), split out so
 * bucketing/trail-windowing/auto-fit/marker-building are unit-testable without HTTP, timers, or
 * Leaflet — mirrors `core/telemetry/telemetry-logic.ts`'s split of pure logic from the injectable
 * (`core/map-store.ts`) that drives it.
 *
 * Moved here from `pages/map/map-logic.ts` in docs/plans/done/MVP3-PLAN.md §C-c when the Command dashboard
 * needed the identical fleet map embed (`shared/map/fleet-map.ts`, `core/map-store.ts`) a second page —
 * this codebase has no precedent for one page importing another page's module (see
 * `core/fleet/device-logic.ts`'s doc comment for the original precedent this follows, most recently
 * repeated by `shared/map/live-map.ts`/`shared/player/detections-strip.ts`'s own moves). That original
 * host, `MapPage`, is deleted now (`/map` redirects to `/command`, see `features/map/map.routes.ts`'s
 * own doc comment) — `features/command/command.ts` (via `shared/map/fleet-map.ts`) is this module's
 * sole importer today; nothing about its own behavior changed by either move.
 */

/** How many recent trail points a live fleet marker keeps (docs/main/CYCLES-PLAN.md §6: "short recent trail"). */
export const TRAIL_WINDOW = 60;

/** Which of the three ways an asset appears on the fleet map, derived from `AssetSummary` alone. */
export type MarkerBucket = 'streaming' | 'offline' | 'noPosition';

/**
 * Buckets a single asset by how it should appear on the map.
 *
 * `AssetSummaryResponse#lastKnownPosition` is recomputed server-side on every request from the
 * freshest usage row and updates on *every telemetry sample*, even while a usage is still open
 * (`DefaultAssetService#toSummary`/`#lastKnownPosition`) — so it is already a live-enough
 * position to bucket a `STREAMING` asset as plottable even before this page's own telemetry poll
 * for that asset has landed.
 *
 * - `streaming`: a live marker; position/heading/battery are refined by telemetry as it arrives,
 *   but `lastKnownPosition` is the immediate fallback so the marker is never a no-op until the
 *   first poll.
 * - `offline`: a dimmed static marker at `lastKnownPosition` — the crew's "where to retrieve it".
 * - `noPosition`: no position has ever been recorded — including the brief window right after a
 *   stream starts, before any positioned sample exists yet (see `DefaultStreamService#start`) —
 *   never invisible, always accounted for in the "no position yet" rail instead.
 *
 * A `lastKnownPosition` that carries no real GPS fix (exactly `(0, 0)` — see `core/geo/geo-logic.ts#hasFix`'s
 * own doc comment, docs/plans/active/OPERATOR-UX-4-PLAN.md finding N1) is treated exactly like no position at
 * all: bucketing it as `streaming`/`offline` would plot a marker on Null Island and recentre the
 * fleet map on open ocean for data that was never acquired.
 */
export function bucketForAsset(asset: Pick<AssetSummary, 'status' | 'lastKnownPosition'>): MarkerBucket {
  if (!hasFix(asset.lastKnownPosition)) {
    return 'noPosition';
  }
  return asset.status === 'STREAMING' ? 'streaming' : 'offline';
}

export interface AssetBuckets {
  readonly streaming: readonly AssetSummary[];
  readonly offline: readonly AssetSummary[];
  readonly noPosition: readonly AssetSummary[];
}

/** Partitions `GET /api/assets`'s result into the three map buckets, preserving relative order. */
export function bucketAssets(assets: readonly AssetSummary[]): AssetBuckets {
  const streaming: AssetSummary[] = [];
  const offline: AssetSummary[] = [];
  const noPosition: AssetSummary[] = [];
  for (const asset of assets) {
    switch (bucketForAsset(asset)) {
      case 'streaming':
        streaming.push(asset);
        break;
      case 'offline':
        offline.push(asset);
        break;
      case 'noPosition':
        noPosition.push(asset);
        break;
    }
  }
  return { streaming, offline, noPosition };
}

/** Keeps only the most recent `max` trail points — a long-running flight shouldn't fill the map with old track. */
export function windowTrail(trail: readonly GeoPosition[], max: number = TRAIL_WINDOW): readonly GeoPosition[] {
  return trail.length <= max ? trail : trail.slice(trail.length - max);
}

/** One streaming asset's live telemetry, distilled for its marker — `map-store.ts`'s per-asset analog of `TelemetryStore`. */
export interface AssetTelemetrySnapshot {
  readonly latest: TelemetrySample | undefined;
  readonly trail: readonly GeoPosition[];
}

/** Builds a snapshot from a raw `usageTelemetry` response — windows the trail, keeps the latest sample regardless of whether it carries a position (a sample with only battery/heading is still worth surfacing). */
export function snapshotFromSamples(samples: readonly TelemetrySample[]): AssetTelemetrySnapshot {
  return {
    latest: samples.length > 0 ? samples[samples.length - 1] : undefined,
    trail: windowTrail(deriveTrail(samples)),
  };
}

/**
 * One asset's fully-derived marker: where to plot it and what its popup shows.
 *
 * `flightMode`/`armed`/`failsafe`/`gpsFixType` (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-d) all come from the
 * same place `headingDegrees`/`batteryPercent` already do — the streaming asset's own latest
 * telemetry sample's `flightState` (see `buildMarker`) — never from `AssetSummary` itself, which
 * carries none of this (unlike `AssetAttention`'s own `flightMode`/`armed`/`failsafe`, a *different*,
 * fleet-summary-level derivation `features/command/command-logic.ts` reads directly off that DTO
 * instead — this marker-level set exists for `shared/map/fleet-map/fleet-map.ts`'s popup and
 * `features/command/asset-panel.ts`'s Status tab, both of which only ever have a `FleetMarker` to
 * read from, not the fleet-summary row). Absent for the `offline` bucket (no live telemetry poller
 * — see `buildMarker`) or before the first sample of a freshly-`streaming` asset arrives.
 *
 * `firmware` (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1, new) — the identical "same place, same
 * absence rule" as the four fields above, added specifically so
 * `features/command/asset-panel.ts`'s "Bring home" button (`shared/ui/return-home-button.ts`) can
 * gate visibility (`core/telemetry/flight-state-logic.ts#canCommandReturnHome`) from data this
 * panel already has, without a second `TelemetryStore` poller — `AssetAttention` carries no
 * firmware field at all (the fleet-summary DTO was never widened for it), so this marker-level
 * field is the only source Command has.
 */
export interface FleetMarker {
  readonly assetId: string;
  readonly displayName: string;
  readonly category: string;
  readonly categoryName: string;
  readonly status: AssetStatus;
  /** `true` for the `streaming` bucket — drives live vs. dimmed marker styling. */
  readonly live: boolean;
  readonly position: GeoPosition;
  readonly headingDegrees?: number;
  readonly batteryPercent?: number;
  readonly trail: readonly GeoPosition[];
  readonly sampleAgeSeconds?: number;
  readonly flightMode?: string;
  readonly armed?: boolean;
  readonly failsafe?: boolean;
  readonly gpsFixType?: number;
  /** docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1 — see this interface's own doc comment above. */
  readonly firmware?: string;
  /**
   * docs/plans/done/FC-INTEGRATIONS-PLAN.md F-e — the same raw `TelemetrySample.extra` map `flightMode`/etc.
   * above are decoded from, passed through verbatim so `features/command/asset-panel.ts`'s Status
   * tab can feed it straight into `core/telemetry/flight-state-logic.ts#deriveDiagnostics` without a
   * second `TelemetryStore` poller — same reuse rationale as every other marker-level field here.
   * Absent entirely for the `offline` bucket, like every other live-telemetry-sourced field.
   */
  readonly extra?: Record<string, number>;
}

/**
 * Combines a bucketed asset with its (possibly not-yet-arrived) live telemetry snapshot into a
 * plottable marker, or `undefined` for the `noPosition` bucket.
 *
 * A `streaming` asset with no telemetry snapshot yet — or whose latest sample carries no lat/lon
 * — falls back to the asset's own `lastKnownPosition` for *where* to plot it, but still surfaces
 * whatever the latest sample does carry (heading, battery, age) independently, since those are
 * reported on their own cadence, not gated on a fresh GPS fix.
 */
export function buildMarker(
  asset: AssetSummary,
  telemetry: AssetTelemetrySnapshot | undefined,
  nowMs: number,
): FleetMarker | undefined {
  const bucket = bucketForAsset(asset);
  if (bucket === 'noPosition') {
    return undefined;
  }

  const base = {
    assetId: asset.assetId,
    displayName: asset.displayName,
    category: asset.category,
    categoryName: asset.categoryName,
    status: asset.status,
    live: bucket === 'streaming',
  };

  if (bucket === 'offline') {
    return { ...base, position: asset.lastKnownPosition as GeoPosition, trail: [] };
  }

  const latest = telemetry?.latest;
  const latestHasFix = hasFix(latest);
  const position: GeoPosition = latestHasFix
    ? { latitude: latest!.latitude!, longitude: latest!.longitude!, altitudeMeters: latest!.altitudeMeters }
    : (asset.lastKnownPosition as GeoPosition);

  return {
    ...base,
    position,
    headingDegrees: latest?.headingDegrees,
    batteryPercent: latest?.batteryPercent,
    trail: telemetry?.trail ?? [],
    sampleAgeSeconds: ageSeconds(latest?.at, nowMs),
    flightMode: latest?.flightState?.mode,
    armed: latest?.flightState?.armed,
    failsafe: latest?.flightState?.failsafe,
    gpsFixType: latest?.flightState?.gpsFixType,
    firmware: latest?.flightState?.firmware,
    extra: latest?.extra,
  };
}

/** `buildMarker` over every asset, in order, skipping the `noPosition` ones. */
export function buildMarkers(
  assets: readonly AssetSummary[],
  telemetryByAsset: ReadonlyMap<string, AssetTelemetrySnapshot>,
  nowMs: number,
): readonly FleetMarker[] {
  const markers: FleetMarker[] = [];
  for (const asset of assets) {
    const marker = buildMarker(asset, telemetryByAsset.get(asset.assetId), nowMs);
    if (marker) {
      markers.push(marker);
    }
  }
  return markers;
}

/**
 * A stable fingerprint of which assets are plotted and where.
 *
 * `FleetMapStore.markers()` recomputes every second (it embeds `sampleAgeSeconds`, ticking on the
 * same 1s clock as `TelemetryStore`'s own age readout), producing a new array reference each
 * time even when no position actually moved. `FleetMap` compares this fingerprint rather than the
 * marker array itself to decide whether an auto-fit refit is actually warranted, so a live "n
 * seconds ago" label ticking over doesn't re-run `fitBounds()` every second for no visual reason.
 */
export function fingerprintMarkers(markers: readonly FleetMarker[]): string {
  return markers
    .map((marker) => `${marker.assetId}:${marker.position.latitude.toFixed(6)},${marker.position.longitude.toFixed(6)}`)
    .join('|');
}

/**
 * What can flip auto-fit on the fleet map: a manual pan/zoom disables it, the recenter control
 * re-enables it, and focusing one asset disables it.
 *
 * **`assetFocused`** is deliberately its own event rather than reusing `userInteraction`, even
 * though both resolve to `false`. Selecting an asset centres the map on it; auto-fit's own effect
 * re-fits to *every* marker whenever the marker set changes, which a telemetry poll does every
 * couple of seconds — so leaving auto-fit on would yank the camera back off the chosen asset almost
 * immediately. Naming the case says why the camera stopped following the fleet, and keeps the
 * "operator dragged the map" and "operator picked an asset" transitions separable if they ever need
 * to differ.
 */
export type AutoFitEvent = 'userInteraction' | 'recenterClicked' | 'assetFocused';

/**
 * The auto-fit reducer (docs/main/CYCLES-PLAN.md §6): "auto-fit bounds on load and when the marker set
 * changes, but any manual pan/zoom disables auto-fit until the user re-enables it via a recenter
 * control." Idempotent either way — interacting while already off, or recentering while already
 * on, is a no-op — so the map component can call this from every move/zoom event without first
 * checking whether it actually changes anything.
 */
export function nextAutoFitEnabled(current: boolean, event: AutoFitEvent): boolean {
  switch (event) {
    case 'userInteraction':
      return false;
    case 'recenterClicked':
      return true;
    case 'assetFocused':
      return false;
    default:
      return current;
  }
}
