import type { GeoPosition, Mark, MarkEvent, MarkKind } from '../api/models';
import type { IconName } from '../../shared/ui/icon-registry';

export type { MarkEvent } from '../api/models';

/**
 * Pure, Angular-free logic behind `core/marks/marks-store.ts` and the marks UI
 * (`shared/map/live-map/**`, `shared/map/fleet-map/**`, `features/fly/marks-panel.ts`,
 * `features/command/marks-panel.ts`; docs/TACTICAL-MARKS-PLAN.md M5) — the live-delta merge, the
 * colour-by-kind map style, and the bearing/distance readout math — split out so every rule is
 * unit-testable without HTTP/Leaflet/a component, mirroring `core/geofence/geofence-logic.ts`'s own
 * split (`zoneLayerStyle`/`polygonContains` there ↔ `markStyle`/`bearingDistance` here).
 */

// --- Live-delta merge (docs/TACTICAL-MARKS-PLAN.md §5: MarksStore does an initial GET, then merges
// `created`/`updated`/`cleared` deltas from the `marks` live topic on top) --------------------------

/** Upserts `mark` into `marks` by id — replaces an existing entry in place (order preserved), or prepends a genuinely new one. */
export function upsertMark(marks: readonly Mark[], mark: Mark): readonly Mark[] {
  const index = marks.findIndex((candidate) => candidate.id === mark.id);
  if (index === -1) {
    return [mark, ...marks];
  }
  const next = [...marks];
  next[index] = mark;
  return next;
}

/** Drops `id` from `marks` — a no-op (same array reference semantics aside — a fresh array either way) if it isn't present. */
export function removeMark(marks: readonly Mark[], id: string): readonly Mark[] {
  return marks.filter((candidate) => candidate.id !== id);
}

/**
 * Applies one live delta to the current active-marks list: `created`/`updated` upsert by id;
 * `cleared` removes — "clients drop the pin" (docs/TACTICAL-MARKS-PLAN.md §5), matching
 * `MarkService#list()`'s own server-side filter to `ACTIVE` only, so a cleared mark never lingers
 * client-side either.
 */
export function applyMarkEvent(marks: readonly Mark[], event: MarkEvent): readonly Mark[] {
  if (event.action === 'cleared') {
    return removeMark(marks, event.mark.id);
  }
  return upsertMark(marks, event.mark);
}

/** Applies a run of live deltas in arrival order — `core/live/live-store.ts#markEvents` is chronological, and `MarksStore` folds in only the tail it hasn't processed yet. */
export function applyMarkEvents(marks: readonly Mark[], events: readonly MarkEvent[]): readonly Mark[] {
  return events.reduce(applyMarkEvent, marks);
}

/** The map layer's `(markMoved)` output payload — a drag-to-correct gesture on a mark marker, shared by `live-map`/`fleet-map` so neither imports the other's component file for a plain data shape. */
export interface MarkMoved {
  readonly id: string;
  readonly position: GeoPosition;
}

// --- Colour-by-kind (docs/TACTICAL-MARKS-PLAN.md M5: "4 distinct, accessible hues … that don't
// collide with the reserved status hues, same discipline as the hub-tile palette") -----------------
//
// Reserved hues this app's `src/styles.css` tier-2 tokens already own (computed from their hex,
// ±~20° guard band): --color-danger ~0°, --color-warn ~36°, --color-success ~146°,
// --color-info/accent ~219°, --color-live ~342° (`features/hubs/tile-accent.ts` documents the
// identical reservation for its own decorative cool-arc palette). The four hues below sit in the
// three open bands between those guards (56–126°, 166–199°, 239–322°) so a mark can never be
// misread as a system alert/selection/live-now state, exactly like the hub tiles' own accent arc —
// unlike that palette, these carry real per-kind meaning (not merely positional), so each is a
// fixed assignment rather than a cycle-by-index.

const MARK_HUES: Record<MarkKind, number> = {
  // Vivid magenta — the default kind for a cockpit geolocate ("Mark target"), so the most
  // attention-grabbing of the four.
  TARGET: 302,
  // Indigo/violet — genuinely distinct from --color-info's own blue (~219°) at a 40°+ remove.
  FRIENDLY: 259,
  // Cyan/teal — calm, informational; the natural "just a point of interest" register.
  POI: 182,
  // Yellow-green — the one open hue with any "caution" flavor left once true amber/red are off
  // limits (both reserved), sitting exactly between --color-warn and --color-success.
  HAZARD: 91,
};

const SATURATION = 78;
const LIGHTNESS = 60;

/** The categorical colour for `kind` — single source of truth consumed by both the map layer (inline style, not a CSS class) and the marks panel's kind chip. */
export function markColor(kind: MarkKind): string {
  return `hsl(${MARK_HUES[kind]} ${SATURATION}% ${LIGHTNESS}%)`;
}

/** A plain, Leaflet-marker-shaped style object — kept structural (no Leaflet import) so this stays a pure, framework-free module, mirroring `geofence-logic.ts#ZoneLayerStyle`. */
export interface MarkLayerStyle {
  readonly color: string;
  readonly fillColor: string;
  readonly diameterPx: number;
}

/** The kind→map-marker style mapping. `selected` renders a larger dot (docs/TACTICAL-MARKS-PLAN.md M5's own map-overlay ask: the selected mark should read distinctly for the bearing/distance readout it drives). */
export function markStyle(kind: MarkKind, selected: boolean = false): MarkLayerStyle {
  const color = markColor(kind);
  return { color, fillColor: color, diameterPx: selected ? 20 : 14 };
}

const MARK_KIND_LABELS: Record<MarkKind, string> = {
  TARGET: 'Target',
  HAZARD: 'Hazard',
  POI: 'Point of interest',
  FRIENDLY: 'Friendly',
};

/** `TARGET` → `"Target"`, etc. — the display form used in tooltips, list rows, and the kind picker. */
export function markKindLabel(kind: MarkKind): string {
  return MARK_KIND_LABELS[kind];
}

const MARK_KIND_ICONS: Record<MarkKind, IconName> = {
  TARGET: 'target',
  HAZARD: 'alert',
  POI: 'map-pin',
  FRIENDLY: 'flag',
};

/** `TARGET` → `'target'`, etc. — the `<vision-icon>` name for `kind`. */
export function markKindIcon(kind: MarkKind): IconName {
  return MARK_KIND_ICONS[kind];
}

/** Every kind, in the fixed order every kind-picker control renders them (`MARK_KIND_LABELS`'s own iteration order — TARGET first, matching the geolocate default). */
export const MARK_KINDS: readonly MarkKind[] = ['TARGET', 'HAZARD', 'POI', 'FRIENDLY'];

// --- Bearing/distance (docs/TACTICAL-MARKS-PLAN.md §3/§1 — mirrors `domain.model.GeoProjection
// #bearingDistance` byte-for-byte, the same "reimplement the backend's pure math client-side for a
// live, no-round-trip readout" idiom `geofence-logic.ts#polygonContains` already established for
// `GeofenceZone#contains`) -----------------------------------------------------------------------

/** Mean Earth radius in meters (IUGG) — matches `GeoProjection.EARTH_RADIUS_METERS` exactly. */
export const EARTH_RADIUS_METERS = 6_371_000;

export interface BearingDistance {
  /** Initial bearing, degrees, range [0,360), clockwise from true north. */
  readonly bearingDegrees: number;
  /** Great-circle distance in meters; never negative. */
  readonly distanceMeters: number;
}

function toRadians(degrees: number): number {
  return (degrees * Math.PI) / 180;
}

function toDegrees(radians: number): number {
  return (radians * 180) / Math.PI;
}

function normalizeDegrees(degrees: number): number {
  return ((degrees % 360) + 360) % 360;
}

/**
 * Great-circle initial bearing + haversine distance from `from` to `to` — e.g. "how far and in
 * what direction is this mark from the drone / from home" (docs/TACTICAL-MARKS-PLAN.md §3's
 * selected-mark readout). Byte-for-byte port of `GeoProjection.bearingDistance` (vision-domain) —
 * see that method's own golden-value tests, mirrored in this module's own spec.
 */
export function bearingDistance(from: GeoPosition, to: GeoPosition): BearingDistance {
  const lat1 = toRadians(from.latitude);
  const lat2 = toRadians(to.latitude);
  const deltaLat = toRadians(to.latitude - from.latitude);
  const deltaLon = toRadians(to.longitude - from.longitude);

  const sinHalfLat = Math.sin(deltaLat / 2);
  const sinHalfLon = Math.sin(deltaLon / 2);
  const a = sinHalfLat * sinHalfLat + Math.cos(lat1) * Math.cos(lat2) * sinHalfLon * sinHalfLon;
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  const distanceMeters = EARTH_RADIUS_METERS * c;

  const bearingY = Math.sin(deltaLon) * Math.cos(lat2);
  const bearingX = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLon);
  const bearingDegrees = normalizeDegrees(toDegrees(Math.atan2(bearingY, bearingX)));

  return { bearingDegrees, distanceMeters };
}

const COMPASS_POINTS = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'] as const;

/** `0°` → `"N"`, `90°` → `"E"`, … — the nearest of the 8 principal compass points, for a human-scannable readout alongside the raw degrees. */
export function compassPoint(bearingDegrees: number): string {
  const index = Math.round(normalizeDegrees(bearingDegrees) / 45) % 8;
  return COMPASS_POINTS[index];
}

/** `1234` m → `"1.2 km"`; under 1000 m → whole meters (`"420 m"`) — matches this app's other distance-formatting call sites' precision register (no false precision on a haversine estimate). */
export function formatDistanceMeters(distanceMeters: number): string {
  if (distanceMeters >= 1000) {
    return `${(distanceMeters / 1000).toFixed(1)} km`;
  }
  return `${Math.round(distanceMeters)} m`;
}

/** The selected-mark readout's one-line summary, e.g. `"142° SE · 1.2 km"`. */
export function bearingDistanceLabel(bd: BearingDistance): string {
  return `${Math.round(bd.bearingDegrees)}° ${compassPoint(bd.bearingDegrees)} · ${formatDistanceMeters(bd.distanceMeters)}`;
}
