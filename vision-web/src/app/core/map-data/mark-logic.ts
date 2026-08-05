import type {
  Affiliation,
  CreateMarkRequest,
  GeoPosition,
  MapEventPayload,
  MapMark,
  MarkKind,
  PatchMarkRequest,
  VerificationState,
} from '../api/models';
import type { TacticalMark } from '../../shared/map/tactical-map/tactical-map-logic';
import { applyMapEvents, deletedLayerIds, dropByLayer, type MapEntitySpec } from './map-event-logic';

/**
 * Pure, Angular-free logic behind `core/map-data/marks-store.ts` and the marks UI
 * (`shared/map/map-controls/**`, `features/fly/marks-panel.ts`, `features/command/marks-panel.ts`;
 * docs/MAP-REWORK-PLAN.md §5.2). **Moved from `core/marks/mark-logic.ts` and reworked to the v2
 * model** — the old file's colour-by-kind palette (`markColor`/`markStyle`) is gone with it: v2 marks
 * are drawn by `shared/map/tactical-map/tactical-map-logic.ts`'s APP-6-inspired **affiliation**
 * symbology (frame shape + one semantic token), which is the single source of truth for how a mark
 * looks, in the map *and* in every list row. The bearing/distance readout below is the one block
 * that came across byte-for-byte.
 */

// --- Position: the wire shape is flat, the map/readout code wants a GeoPosition ------------------

/**
 * `MarkResponse`'s flat `latitude`/`longitude`/`altitudeMeters` reassembled — the **one** place that
 * conversion happens, so no component ever hand-builds a position object out of a mark again.
 */
export function markPosition(mark: MapMark): GeoPosition {
  return { latitude: mark.latitude, longitude: mark.longitude, altitudeMeters: mark.altitudeMeters };
}

/**
 * A wire mark as `<vision-tactical-map>`'s `[marks]` display model
 * (`tactical-map-logic.ts#TacticalMark`). The map renders far less than the wire carries — no
 * ownership, timestamps, status or source — and keys its position nested, so this is a genuine
 * projection, not a cast. It replaces Wave D's interim `adaptMarks` input transform, which is
 * deleted: hosts now pass real v2 data, and the component's public input type never changed.
 */
export function toTacticalMark(mark: MapMark): TacticalMark {
  return {
    id: mark.markId,
    layerId: mark.layerId,
    position: markPosition(mark),
    kind: mark.kind,
    affiliation: mark.affiliation,
    label: mark.label,
    note: mark.note,
    verification: mark.verification,
  };
}

export function toTacticalMarks(marks: readonly MapMark[]): readonly TacticalMark[] {
  return marks.map(toTacticalMark);
}

/** The map layer's `(markMoved)` payload — a drag-to-correct gesture on a mark symbol. */
export interface MarkMoved {
  readonly id: string;
  readonly position: GeoPosition;
}

// --- SSE fold (docs/MAP-REWORK-PLAN.md §4.3) ------------------------------------------------------

const MARK_SPEC: MapEntitySpec<MapMark> = {
  entity: 'mark',
  idOf: (mark) => mark.markId,
  payloadOf: (event) => event.mark,
};

/**
 * Folds the `mark` half of a run of `map` deltas in: `created`/`updated` upsert by id,
 * `cleared`/`deleted` remove ("clients drop the pin", matching the server's own ACTIVE-only list).
 * A deleted *layer* takes its marks with it too — see `map-event-logic.ts#deletedLayerIds` for why
 * that cascade is applied here as well as trusted from the server.
 */
export function applyMarkEvents(marks: readonly MapMark[], events: readonly MapEventPayload[]): readonly MapMark[] {
  return dropByLayer(applyMapEvents(marks, events, MARK_SPEC), deletedLayerIds(events));
}

// --- The palette: what the next mark will be (docs/MAP-REWORK-PLAN.md §5.2) -----------------------

/**
 * The operator's current mark-creation selection — replaces the old store's bare `pendingKind`.
 * `layerId` is `undefined` until the layer list has loaded (or when the viewer has no contributable
 * layer at all), which `CreateMarkRequest` renders as an omitted field so §3's server-side default
 * applies rather than a guess.
 */
export interface MarkPalette {
  readonly kind: MarkKind;
  readonly affiliation: Affiliation;
  readonly layerId?: string;
}

/**
 * `TARGET` + `HOSTILE` — the same default the backend's own geolocate applies (`kind` absent →
 * `TARGET`), and the overwhelmingly common reason an operator reaches for the palette mid-flight.
 */
export const DEFAULT_MARK_PALETTE: MarkPalette = { kind: 'TARGET', affiliation: 'HOSTILE' };

export function withPaletteKind(palette: MarkPalette, kind: MarkKind): MarkPalette {
  return { ...palette, kind };
}

export function withPaletteAffiliation(palette: MarkPalette, affiliation: Affiliation): MarkPalette {
  return { ...palette, affiliation };
}

/** `undefined` clears the pick back to "let the server default it" — the layer `<select>`'s own empty option. */
export function withPaletteLayer(palette: MarkPalette, layerId: string | undefined): MarkPalette {
  return { ...palette, layerId };
}

/** Seeds the palette from an existing mark, so opening its editor starts from what it already is. */
export function paletteFromMark(mark: MapMark): MarkPalette {
  return { kind: mark.kind, affiliation: mark.affiliation, layerId: mark.layerId };
}

/**
 * Re-points a palette at a layer the viewer may actually contribute to. Called whenever the layer
 * list changes: a palette still holding a layer that was deleted, or that the viewer just lost
 * CONTRIBUTE on, must not keep sending a `layerId` the server will 403 — it falls back to the
 * default pick instead. Returns the same object when nothing needs to change, so a store can use it
 * inside an `effect()` without looping.
 */
export function reconcilePaletteLayer(
  palette: MarkPalette,
  contributableLayerIds: readonly string[],
  fallbackLayerId: string | undefined,
): MarkPalette {
  if (palette.layerId !== undefined && contributableLayerIds.includes(palette.layerId)) {
    return palette;
  }
  return palette.layerId === fallbackLayerId ? palette : { ...palette, layerId: fallbackLayerId };
}

/** The position captured by an armed map click, awaiting the label/confirm step in the palette. */
export interface MarkDraft {
  readonly palette: MarkPalette;
  readonly position: GeoPosition;
}

/** The create body for a confirmed draft — the flat wire shape, with every genuinely-absent field omitted rather than sent as `null`. */
export function createMarkRequest(draft: MarkDraft, label: string, note?: string): CreateMarkRequest {
  return {
    layerId: draft.palette.layerId,
    latitude: draft.position.latitude,
    longitude: draft.position.longitude,
    altitudeMeters: draft.position.altitudeMeters,
    kind: draft.palette.kind,
    affiliation: draft.palette.affiliation,
    label,
    note,
  };
}

/** The patch body for editing an existing mark through the same palette + label/note fields. */
export function editMarkRequest(palette: MarkPalette, label: string, note?: string): PatchMarkRequest {
  return { kind: palette.kind, affiliation: palette.affiliation, label, note };
}

// --- Verification (docs/MAP-REWORK-PLAN.md §5.2's verify/promote UI) ------------------------------

const VERIFICATION_LABELS: Record<VerificationState, string> = {
  UNVERIFIED: 'Unverified',
  CONFIRMED: 'Confirmed',
  REJECTED: 'Rejected',
};

export function verificationLabel(state: VerificationState): string {
  return VERIFICATION_LABELS[state];
}

/**
 * The `.chip` modifier for a verification state — `warn` for unverified (provisional, needs a human
 * decision), `ok` for confirmed, `danger` for rejected. Returns `''` rather than a made-up class for
 * any future state, so an unknown value renders as a plain neutral chip instead of vanishing.
 */
export function verificationChipClass(state: VerificationState): string {
  switch (state) {
    case 'UNVERIFIED':
      return 'warn';
    case 'CONFIRMED':
      return 'ok';
    case 'REJECTED':
      return 'danger';
    default:
      return '';
  }
}

export function isUnverified(mark: Pick<MapMark, 'verification'>): boolean {
  return mark.verification === 'UNVERIFIED';
}

/** The marks panels' UNVERIFIED filter chip (§5.2) — a plain client-side *view* filter over an already-scoped list, not a visibility rule. */
export function filterMarks(marks: readonly MapMark[], options: { readonly unverifiedOnly?: boolean } = {}): readonly MapMark[] {
  return options.unverifiedOnly ? marks.filter(isUnverified) : marks;
}

export function countUnverified(marks: readonly MapMark[]): number {
  return marks.reduce((count, mark) => (isUnverified(mark) ? count + 1 : count), 0);
}

// --- Bearing/distance (docs/TACTICAL-MARKS-PLAN.md §3/§1 — mirrors `domain.model.GeoProjection
// #bearingDistance` byte-for-byte, the same "reimplement the backend's pure math client-side for a
// live, no-round-trip readout" idiom `geofence-logic.ts#polygonContains` already established for
// `GeofenceZone#contains`. Moved here verbatim from `core/marks/mark-logic.ts`.) -------------------

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
 * what direction is this mark from the drone" (docs/TACTICAL-MARKS-PLAN.md §3's selected-mark
 * readout). Byte-for-byte port of `GeoProjection.bearingDistance` (vision-domain) — see that
 * method's own golden-value tests, mirrored in this module's own spec.
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

/** `1234` m → `"1.2 km"`; under 1000 m → whole meters (`"420 m"`) — no false precision on a haversine estimate. */
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
