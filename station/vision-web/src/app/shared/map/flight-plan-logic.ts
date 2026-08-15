import type { RouteMode, TelemetryPlanRequest, WaypointRequest } from '../../core/api/models';

/**
 * Pure logic behind `shared/map/flight-plan-dialog.ts` (docs/main/CYCLES-PLAN.md §7, CT-b) — waypoint list
 * editing (add/move/delete/altitude), the demo-triangle seed, the manual `lat,lon[,altM]` text
 * fallback, and serialization to the `TelemetryPlan` wire shape (`StartSimulationRequest.telemetry`,
 * docs/main/CYCLES-PLAN.md §7 CT-a's pinned contract). Split out so the editor's behavior is
 * unit-testable without Leaflet, a component, or the DOM — mirrors `shared/player/detection-overlay-logic.ts`
 * and `shared/player/player-recovery.ts`'s split of pure state/derivation from the component that drives it.
 */

/** One row in the editor's waypoint list — the map-marker/list-row shape, not yet the wire shape. */
export interface EditorWaypoint {
  readonly latitude: number;
  readonly longitude: number;
  /** `null` when the user hasn't set one for this waypoint — omitted from the wire shape. */
  readonly altitudeMeters: number | null;
}

export interface HomePoint {
  readonly latitude: number;
  readonly longitude: number;
}

/** `CT-a`'s `RouteMode` (`vision-application`) — loop (default)/bounce/once, matched case-insensitively server-side. */
export type { RouteMode };

/** The editor's full draft state, serialized by `buildTelemetryRequest`. */
export interface FlightPlanForm {
  readonly waypoints: readonly EditorWaypoint[];
  readonly speedMps: number | null;
  readonly routeMode: RouteMode;
}

/** `SimulatedTelemetrySource`'s own adapter default (docs/main/CYCLES-PLAN.md §7, CT-a) — used as this editor's seed too. */
export const DEFAULT_SPEED_MPS = 12;

/** A reasonable per-waypoint default when the map-click/manual-entry path doesn't specify one. */
export const DEFAULT_ALTITUDE_METERS = 60;

/** `RouteMode`'s server-side default (`SimulatedTelemetrySource`) — this editor's default selection too. */
export const DEFAULT_ROUTE_MODE: RouteMode = 'loop';

/** A generic home point (San Francisco) used only when the wizard has no home lat/lon of its own to seed near. */
export const FALLBACK_HOME_POINT: HomePoint = { latitude: 37.7749, longitude: -122.4194 };

/**
 * Offsets (relative to a home point) reproducing `scripts/demo.sh`'s own 3-waypoint patrol
 * triangle's shape/scale — computed once from that script's fixed Golden Gate Park coordinates
 * relative to their own centroid, then reapplied to *any* home point here, so "the demo triangle
 * is a fine seed near the chosen home point" (docs/main/CYCLES-PLAN.md §7, CT-b) works for whichever
 * home position the user picked, not just San Francisco.
 */
const SEED_OFFSETS: readonly { readonly dLat: number; readonly dLon: number; readonly altitudeMeters: number }[] = [
  { dLat: 0.0004, dLon: -0.0093, altitudeMeters: 60 },
  { dLat: 0.0028, dLon: 0.0023, altitudeMeters: 80 },
  { dLat: -0.0032, dLon: 0.0069, altitudeMeters: 60 },
];

/**
 * A 3-waypoint patrol triangle seeded near `home` (or `FALLBACK_HOME_POINT` when `home` is
 * `null`/absent) — the "sensible defaults prefilled" every fresh dialog opens with, so a user who
 * wants exactly the demo's flight can just hit Save.
 */
export function seedTriangle(home?: HomePoint | null): EditorWaypoint[] {
  const base = home ?? FALLBACK_HOME_POINT;
  return SEED_OFFSETS.map((offset) => ({
    latitude: base.latitude + offset.dLat,
    longitude: base.longitude + offset.dLon,
    altitudeMeters: offset.altitudeMeters,
  }));
}

/** Appends a waypoint at `point` (a map click) with `DEFAULT_ALTITUDE_METERS`. */
export function addWaypoint(
  waypoints: readonly EditorWaypoint[],
  point: { readonly latitude: number; readonly longitude: number },
  altitudeMeters = DEFAULT_ALTITUDE_METERS,
): EditorWaypoint[] {
  return [...waypoints, { latitude: point.latitude, longitude: point.longitude, altitudeMeters }];
}

/** Updates one waypoint's position (a marker drag) by index — altitude is left untouched. */
export function moveWaypoint(
  waypoints: readonly EditorWaypoint[],
  index: number,
  point: { readonly latitude: number; readonly longitude: number },
): EditorWaypoint[] {
  return waypoints.map((waypoint, i) =>
    i === index ? { ...waypoint, latitude: point.latitude, longitude: point.longitude } : waypoint,
  );
}

/** Updates one waypoint's altitude by index — `null` clears it (omitted from the wire shape). */
export function updateWaypointAltitude(
  waypoints: readonly EditorWaypoint[],
  index: number,
  altitudeMeters: number | null,
): EditorWaypoint[] {
  return waypoints.map((waypoint, i) => (i === index ? { ...waypoint, altitudeMeters } : waypoint));
}

/** Removes one waypoint by index — a list "remove" button or a marker's own delete control. */
export function removeWaypoint(waypoints: readonly EditorWaypoint[], index: number): EditorWaypoint[] {
  return waypoints.filter((_, i) => i !== index);
}

/**
 * Parses the manual `lat,lon[,altM]` text fallback (docs/main/CYCLES-PLAN.md §7, CT-b: "a manual
 * `lat,lon` text fallback") — one waypoint per non-blank line, `,`-or-whitespace separated.
 * Lenient like every option parser in this app (docs/main/CYCLES-PLAN.md §7 CT-a's own "bad points →
 * fallback" convention on the backend): a line that doesn't parse to two/three finite numbers
 * within range is silently skipped rather than rejecting the whole paste.
 */
export function parseManualWaypoints(text: string): EditorWaypoint[] {
  const waypoints: EditorWaypoint[] = [];
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (line.length === 0) {
      continue;
    }
    const parts = line.split(/[\s,]+/).filter((part) => part.length > 0);
    if (parts.length < 2) {
      continue;
    }
    const latitude = Number(parts[0]);
    const longitude = Number(parts[1]);
    const altitudeMeters = parts.length >= 3 ? Number(parts[2]) : null;
    if (!isValidLatLon(latitude, longitude) || (altitudeMeters !== null && !Number.isFinite(altitudeMeters))) {
      continue;
    }
    waypoints.push({ latitude, longitude, altitudeMeters });
  }
  return waypoints;
}

function isValidLatLon(latitude: number, longitude: number): boolean {
  return (
    Number.isFinite(latitude) &&
    Number.isFinite(longitude) &&
    latitude >= -90 &&
    latitude <= 90 &&
    longitude >= -180 &&
    longitude <= 180
  );
}

/** The inverse of `parseManualWaypoints` — pre-fills the manual-entry textarea from the current list. */
export function formatManualWaypoints(waypoints: readonly EditorWaypoint[]): string {
  return waypoints
    .map((waypoint) =>
      waypoint.altitudeMeters === null
        ? `${waypoint.latitude},${waypoint.longitude}`
        : `${waypoint.latitude},${waypoint.longitude},${waypoint.altitudeMeters}`,
    )
    .join('\n');
}

/** `TelemetryPlan`/`StartSimulationRequest.TelemetryRequest` requires at least 2 waypoints — matches `canSavePlan`. */
export const MIN_ROUTE_WAYPOINTS = 2;

/** Whether the current draft has enough waypoints to save (the wire shape's own `route` minimum). */
export function canSavePlan(waypoints: readonly EditorWaypoint[]): boolean {
  return waypoints.length >= MIN_ROUTE_WAYPOINTS;
}

/**
 * Serializes a draft into the `TelemetryPlan` wire shape (`StartSimulationRequest.telemetry`,
 * docs/main/CYCLES-PLAN.md §7 CT-a's pinned contract: `{speedMps?, routeMode?, route:[{latitude,
 * longitude, altitudeMeters?}]}`) — `undefined` when there aren't enough waypoints to send
 * (`canSavePlan`), so a caller can pass the result straight through to `StartSimulationRequest`'s
 * own `telemetry?` field without a separate guard. `speedMps`/per-waypoint `altitudeMeters` are
 * omitted (not sent as `null`) when unset, matching this app's `@JsonInclude(NON_NULL)` convention
 * for every other request DTO.
 */
export function buildTelemetryRequest(form: FlightPlanForm): TelemetryPlanRequest | undefined {
  if (!canSavePlan(form.waypoints)) {
    return undefined;
  }
  const route: WaypointRequest[] = form.waypoints.map((waypoint) => ({
    latitude: waypoint.latitude,
    longitude: waypoint.longitude,
    ...(waypoint.altitudeMeters !== null ? { altitudeMeters: waypoint.altitudeMeters } : {}),
  }));
  return {
    routeMode: form.routeMode,
    route,
    ...(form.speedMps !== null ? { speedMps: form.speedMps } : {}),
  };
}
