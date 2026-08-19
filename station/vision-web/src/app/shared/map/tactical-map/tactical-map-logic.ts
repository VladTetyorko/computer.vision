import type {
  AccessLevel,
  Affiliation,
  DetectionEvent,
  DrawKind,
  GeoPosition,
  GeofenceZone,
  LayerKind,
  MarkKind,
  ProjectedTrackResponse,
  TelemetrySample,
  VerificationState,
} from '../../../core/api/models';
import type { FleetMarker } from '../../../core/map/map-logic';
import { zoneKindLabel } from '../../../core/geofence/geofence-logic';
import { readPersistedString, writePersistedString } from '../../../core/panel-state';
import type { IconName } from '../../ui/icon-registry';

/**
 * Pure, Angular-free and Leaflet-free logic behind `shared/map/tactical-map/tactical-map.ts`
 * (docs/plans/done/MAP-REWORK-PLAN.md §5.1) — the one map component that replaced `FleetMap` + `LiveMap`.
 * Everything the map decides *before* it touches a Leaflet object lives here: the display model,
 * affiliation/kind symbology class resolution, layer-visibility filtering (incl. its own
 * `localStorage` shape), legend counts, the drawing-vertex reducers, and the follow-mode marker
 * builder — so every rule is unit-testable without a DOM, HTTP, or a map instance, mirroring
 * `core/map/map-logic.ts`'s own split from `core/map/map-store.ts`.
 *
 * **Both deleted components' shared helpers landed here**: `escapeHtml` (byte-identical in
 * `fleet-map.ts` and `live-map.ts`) and the zone tooltip label they each rebuilt inline.
 */

// --- Display model (docs/plans/done/MAP-REWORK-PLAN.md §2/§5.2) --------------------------------------------
//
// The **enums are the wire enums** — re-exported from `core/api/models.ts`, which this repo's own
// convention makes the single place a wire shape is declared. The three *interfaces* below stay
// here, because they are genuinely narrower than the wire DTOs: this component never reads a mark's
// ownership, timestamps, status or source, and it keys positions nested where the wire keeps them
// flat. `core/map-data/mark-logic.ts#toTacticalMark` / `drawings-logic.ts#toMapDrawing` are the one
// place that projection happens; a `MapLayer` response satisfies {@link LayerView} structurally, so
// the layer list needs no projection at all.
//
// Wave D shipped an interim `adaptMarks` input transform here (old `/api/marks` marks → this model,
// on a synthetic `legacy:marks` layer). Wave E deleted it along with the whole `/api/marks` client:
// hosts pass real v2 data now, and `[marks]`' public type never changed.

export type { AccessLevel, Affiliation, DrawKind, VerificationState } from '../../../core/api/models';

/** What the object *is* — the inner glyph of a symbol. `TacticalMarkKind` is `MarkKind`'s local name (the map's vocabulary is "mark kind", the wire's is `MarkKind`). */
export type TacticalMarkKind = MarkKind;

/** `LayerKind`'s local name — "map layer kind" reads unambiguously beside Leaflet's own tile *layers*. */
export type MapLayerKind = LayerKind;

/** The map's mark display model — `MarkResponse` minus the fields no renderer reads, position reassembled. */
export interface TacticalMark {
  readonly id: string;
  readonly layerId: string;
  readonly position: GeoPosition;
  readonly kind: TacticalMarkKind;
  readonly affiliation: Affiliation;
  readonly label: string;
  readonly note?: string;
  readonly verification: VerificationState;
}

/** The map's drawing display model — `DrawingResponse` minus ownership/timestamps. */
export interface MapDrawing {
  readonly id: string;
  readonly layerId: string;
  readonly kind: DrawKind;
  readonly points: readonly GeoPosition[];
  readonly label?: string;
  /** A UI token *name* (`accent`/`danger`/…), never a hex value — resolved by {@link drawingColor}. */
  readonly colorToken?: string;
}

/**
 * The map's track display model (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md §5, wave G5) — unlike
 * {@link TacticalMark}/{@link MapDrawing}, no projection is needed: `ProjectedTrackResponse` already
 * carries exactly what this component draws (position, range, error radius, trail), so this is a
 * plain re-export, named for symmetry with its siblings rather than because the shape narrows.
 */
export type TacticalTrack = ProjectedTrackResponse;

/** One visible layer plus the viewer's own access to it — a `MapLayer` response satisfies this as-is. */
export interface LayerView {
  readonly layerId: string;
  readonly name: string;
  readonly kind: MapLayerKind;
  readonly myAccess: AccessLevel;
  readonly markCount?: number;
  readonly drawingCount?: number;
}

/** §5.1's name for the detection-event overlay input — `DetectionEvent` as `selectEventMarkers` already yields it. */
export type EventMarker = DetectionEvent;

/** What a map click means right now (docs/plans/done/MAP-REWORK-PLAN.md §5.1/§5.2). */
export type InteractionMode = 'view' | 'mark' | 'draw-line' | 'draw-polygon' | 'draw-arrow' | 'draw-text';

// --- Symbology (docs/plans/done/MAP-REWORK-PLAN.md §5.1) --------------------------------------------------
//
// Affiliation resolves to a *class*, never to a colour literal: the frame shape and colour both
// live in `tactical-map.css` against this app's own semantic tokens (`--color-info`/`--color-danger`/
// `--color-success`/`--color-warn`), so the symbology follows the light/dark theme like everything
// else. See that file's own comment for why the plan's four hex values are expressed as tokens.

export const AFFILIATIONS: readonly Affiliation[] = ['FRIENDLY', 'HOSTILE', 'NEUTRAL', 'UNKNOWN'];

export const TACTICAL_MARK_KINDS: readonly TacticalMarkKind[] = ['UNIT', 'EQUIPMENT', 'HAZARD', 'POI', 'TARGET'];

const AFFILIATION_CLASSES: Record<Affiliation, string> = {
  FRIENDLY: 'aff-friendly',
  HOSTILE: 'aff-hostile',
  NEUTRAL: 'aff-neutral',
  UNKNOWN: 'aff-unknown',
};

const AFFILIATION_LABELS: Record<Affiliation, string> = {
  FRIENDLY: 'Friendly',
  HOSTILE: 'Hostile',
  NEUTRAL: 'Neutral',
  UNKNOWN: 'Unknown',
};

const MARK_KIND_LABELS: Record<TacticalMarkKind, string> = {
  UNIT: 'Unit',
  EQUIPMENT: 'Equipment',
  HAZARD: 'Hazard',
  POI: 'Point of interest',
  TARGET: 'Target',
};

/** The `<vision-icon>` glyph rendered inside a symbol's frame — the v2 remap of `mark-logic.ts#markKindIcon`. */
const MARK_KIND_ICONS: Record<TacticalMarkKind, IconName> = {
  UNIT: 'pilot',
  EQUIPMENT: 'wrench',
  HAZARD: 'alert',
  POI: 'map-pin',
  TARGET: 'target',
};

export function affiliationClass(affiliation: Affiliation): string {
  return AFFILIATION_CLASSES[affiliation];
}

export function affiliationLabel(affiliation: Affiliation): string {
  return AFFILIATION_LABELS[affiliation];
}

export function markKindLabel(kind: TacticalMarkKind): string {
  return MARK_KIND_LABELS[kind];
}

export function markKindIcon(kind: TacticalMarkKind): IconName {
  return MARK_KIND_ICONS[kind];
}

/**
 * Every class a mark symbol's root div carries, in one string: the shared `mark-symbol` base, the
 * affiliation frame, `unverified` (dashed + dimmed), `cop` (the subtle common-picture ring), and
 * `selected` (the app's one `--color-info` selection ring). Split out here so the class *rules* are
 * testable without rendering a divIcon.
 */
export function markSymbolClasses(
  mark: Pick<TacticalMark, 'affiliation' | 'verification'>,
  options: { readonly selected?: boolean; readonly cop?: boolean } = {},
): string {
  const classes = ['mark-symbol', affiliationClass(mark.affiliation)];
  if (mark.verification === 'UNVERIFIED') {
    classes.push('unverified');
  }
  if (options.cop) {
    classes.push('cop');
  }
  if (options.selected) {
    classes.push('selected');
  }
  return classes.join(' ');
}

/** The ids of every COP layer in `layers` — marks on one get the common-picture ring. */
export function copLayerIds(layers: readonly LayerView[]): ReadonlySet<string> {
  return new Set(layers.filter((layer) => layer.kind === 'COP').map((layer) => layer.layerId));
}

// --- Client-side layer visibility (the eye toggles) --------------------------------------------
//
// Orthogonal to server-side visibility (docs/plans/done/MAP-REWORK-PLAN.md §3 — what the viewer is *allowed*
// to see is resolved before the data ever reaches this component). These toggles only declutter the
// operator's own screen, so they persist per browser, not per account.

export const HIDDEN_LAYERS_KEY = 'vision.map.hiddenLayers';

/** Pseudo-layer ids for the three built-in overlays the panel toggles alongside real layers. */
export const BUILTIN_ASSETS_LAYER = 'builtin:assets';
export const BUILTIN_ZONES_LAYER = 'builtin:zones';
export const BUILTIN_EVENTS_LAYER = 'builtin:events';

/** Tolerant of every shape `localStorage` can actually hold (absent, truncated, a stale non-array) — a corrupt value hides nothing rather than throwing on boot. */
export function parseHiddenLayers(raw: string | null): readonly string[] {
  if (!raw) {
    return [];
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((value): value is string => typeof value === 'string') : [];
  } catch {
    return [];
  }
}

export function readHiddenLayers(): readonly string[] {
  return parseHiddenLayers(readPersistedString(HIDDEN_LAYERS_KEY, null));
}

export function writeHiddenLayers(ids: readonly string[]): void {
  writePersistedString(HIDDEN_LAYERS_KEY, JSON.stringify([...ids]));
}

export function isLayerHidden(hidden: readonly string[], layerId: string): boolean {
  return hidden.includes(layerId);
}

/** Adds/removes `layerId` — the eye button's own reducer. */
export function toggleLayerHidden(hidden: readonly string[], layerId: string): readonly string[] {
  return hidden.includes(layerId) ? hidden.filter((id) => id !== layerId) : [...hidden, layerId];
}

export function visibleMarks(marks: readonly TacticalMark[], hidden: readonly string[]): readonly TacticalMark[] {
  return marks.filter((mark) => !hidden.includes(mark.layerId));
}

export function visibleDrawings(drawings: readonly MapDrawing[], hidden: readonly string[]): readonly MapDrawing[] {
  return drawings.filter((drawing) => !hidden.includes(drawing.layerId));
}

/**
 * Filters projected map tracks (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md §5/D10, wave G5) by the same
 * per-layer eye toggle every other data overlay respects — `layerId` is a track's own target layer
 * (default the COP, §5), so a track shares its layer's toggle with any marks/drawings already on it.
 */
export function visibleTracks(tracks: readonly ProjectedTrackResponse[], hidden: readonly string[]): readonly ProjectedTrackResponse[] {
  return tracks.filter((track) => !hidden.includes(track.layerId));
}

/** One row of the data-layer panel. `kind` is `undefined` for the three built-in overlays. */
export interface LayerRow {
  readonly id: string;
  readonly name: string;
  readonly kind?: MapLayerKind;
  readonly markCount: number;
  readonly drawingCount: number;
  readonly hidden: boolean;
}

/**
 * The data-layer panel's rows for the *data* layers: every `LayerView` the host passed, plus a
 * synthesized row for any layer id referenced by a mark/drawing the host didn't describe. That
 * fallback used to carry Wave D's `legacy:marks` row and no longer does (real layers arrive from
 * `core/map-data/layers-store.ts` now) — it stays as the honest degrade for the narrow window where
 * a mark arrives over SSE on a layer whose own `created` event hasn't landed yet: the pin is on the
 * map, so it gets a row and an eye toggle, named by its id until the layer list catches up, rather
 * than being silently untoggleable. Counts come from the data actually on the map, falling back to
 * the server's own counts for a layer whose marks are all filtered out.
 */
export function layerRows(
  layers: readonly LayerView[],
  marks: readonly TacticalMark[],
  drawings: readonly MapDrawing[],
  hidden: readonly string[],
): readonly LayerRow[] {
  const markCounts = new Map<string, number>();
  for (const mark of marks) {
    markCounts.set(mark.layerId, (markCounts.get(mark.layerId) ?? 0) + 1);
  }
  const drawingCounts = new Map<string, number>();
  for (const drawing of drawings) {
    drawingCounts.set(drawing.layerId, (drawingCounts.get(drawing.layerId) ?? 0) + 1);
  }

  const rows: LayerRow[] = layers.map((layer) => ({
    id: layer.layerId,
    name: layer.name,
    kind: layer.kind,
    markCount: markCounts.get(layer.layerId) ?? layer.markCount ?? 0,
    drawingCount: drawingCounts.get(layer.layerId) ?? layer.drawingCount ?? 0,
    hidden: hidden.includes(layer.layerId),
  }));

  const described = new Set(layers.map((layer) => layer.layerId));
  const referenced = [...new Set([...markCounts.keys(), ...drawingCounts.keys()])];
  for (const layerId of referenced) {
    if (described.has(layerId)) {
      continue;
    }
    rows.push({
      id: layerId,
      name: layerId,
      markCount: markCounts.get(layerId) ?? 0,
      drawingCount: drawingCounts.get(layerId) ?? 0,
      hidden: hidden.includes(layerId),
    });
  }
  return rows;
}

/** A built-in overlay row (Assets / Zones / Events) — rendered only when that overlay has anything in it. */
export interface BuiltinRow {
  readonly id: string;
  readonly name: string;
  readonly count: number;
  readonly hidden: boolean;
}

export function builtinRows(
  counts: { readonly assets: number; readonly zones: number; readonly events: number },
  hidden: readonly string[],
): readonly BuiltinRow[] {
  return [
    { id: BUILTIN_ASSETS_LAYER, name: 'Assets', count: counts.assets },
    { id: BUILTIN_ZONES_LAYER, name: 'Zones', count: counts.zones },
    { id: BUILTIN_EVENTS_LAYER, name: 'Events', count: counts.events },
  ]
    .filter((row) => row.count > 0)
    .map((row) => ({ ...row, hidden: hidden.includes(row.id) }));
}

// --- Legend counts ------------------------------------------------------------------------------

/** The legend's asset-state row (docs/plans/done/VISUAL-REFRESH-PLAN.md F7's original three counts, plus attention). */
export interface AssetLegendCounts {
  readonly streaming: number;
  readonly offline: number;
  readonly attention: number;
  readonly noPosition: number;
}

/**
 * Counts the plotted assets by state. `noPosition` cannot be derived from `assets` at all — a
 * marker only exists for an asset that *has* a position (`core/map/map-logic.ts#buildMarker`
 * returns `undefined` otherwise) — so it is passed in by the host that knows (Command's own
 * `FleetMapStore.buckets()`), and stays 0 (row hidden) everywhere else. Honest by construction:
 * the map never invents a count for assets it was never given.
 */
export function assetLegendCounts(
  assets: readonly FleetMarker[],
  attentionAssetIds: ReadonlySet<string>,
  noPosition: number,
): AssetLegendCounts {
  let streaming = 0;
  let offline = 0;
  let attention = 0;
  for (const asset of assets) {
    if (asset.live) {
      streaming++;
    } else {
      offline++;
    }
    if (attentionAssetIds.has(asset.assetId)) {
      attention++;
    }
  }
  return { streaming, offline, attention, noPosition };
}

/** How many marks carry each affiliation — the count beside each legend swatch. */
export function affiliationCounts(marks: readonly TacticalMark[]): Record<Affiliation, number> {
  const counts: Record<Affiliation, number> = { FRIENDLY: 0, HOSTILE: 0, NEUTRAL: 0, UNKNOWN: 0 };
  for (const mark of marks) {
    counts[mark.affiliation]++;
  }
  return counts;
}

/** How many marks carry each kind — the count beside each legend glyph. */
export function markKindCounts(marks: readonly TacticalMark[]): Record<TacticalMarkKind, number> {
  const counts: Record<TacticalMarkKind, number> = { UNIT: 0, EQUIPMENT: 0, HAZARD: 0, POI: 0, TARGET: 0 };
  for (const mark of marks) {
    counts[mark.kind]++;
  }
  return counts;
}

// --- Drawing drafts (docs/plans/done/MAP-REWORK-PLAN.md §5.1 "drawing-vertex reducers") ---------------------

/** A drawing being built by successive map clicks — also the `(drawingCompleted)` payload. */
export interface DrawingDraft {
  readonly kind: DrawKind;
  readonly points: readonly GeoPosition[];
}

const MODE_KINDS: Partial<Record<InteractionMode, DrawKind>> = {
  'draw-line': 'LINE',
  'draw-polygon': 'POLYGON',
  'draw-arrow': 'ARROW',
  'draw-text': 'TEXT',
};

/** The `DrawKind` a mode draws, or `undefined` for the two non-drawing modes (`view`/`mark`). */
export function draftKindForMode(mode: InteractionMode): DrawKind | undefined {
  return MODE_KINDS[mode];
}

/** Mirrors `Drawing`'s own compact-constructor rules (§2.1): LINE/ARROW ≥ 2, POLYGON ≥ 3, TEXT exactly 1. */
export function minPointsFor(kind: DrawKind): number {
  switch (kind) {
    case 'POLYGON':
      return 3;
    case 'TEXT':
      return 1;
    default:
      return 2;
  }
}

export function appendVertex(draft: DrawingDraft, position: GeoPosition): DrawingDraft {
  return { kind: draft.kind, points: [...draft.points, position] };
}

/**
 * The draft a completion gesture (double-click / Enter / the first click of a TEXT drawing) should
 * actually emit — `undefined` while it still has too few points to be a legal `Drawing`, so an
 * accidental double-click on an empty map emits nothing rather than a degenerate shape.
 */
export function completedDraft(draft: DrawingDraft): DrawingDraft | undefined {
  return draft.points.length >= minPointsFor(draft.kind) ? draft : undefined;
}

/**
 * The compass rotation (deg, clockwise from north) of an arrow's head, from its last segment.
 * Longitude is scaled by `cos(latitude)` so the heading matches what the operator sees on a Web
 * Mercator map rather than raw degrees; `0` for a degenerate one-point draft.
 */
export function arrowRotationDegrees(points: readonly GeoPosition[]): number {
  if (points.length < 2) {
    return 0;
  }
  const from = points[points.length - 2];
  const to = points[points.length - 1];
  const meanLatRad = (((from.latitude + to.latitude) / 2) * Math.PI) / 180;
  const dx = (to.longitude - from.longitude) * Math.cos(meanLatRad);
  const dy = to.latitude - from.latitude;
  return (((Math.atan2(dx, dy) * 180) / Math.PI) + 360) % 360;
}

// --- Leaflet vector colours (re-derived from the live theme, never a frozen snapshot) -----------
//
// Leaflet writes path colours as SVG presentation attributes, which do not resolve `var(--token)` —
// so a vector overlay's colour has to be a literal, exactly as `core/geofence/geofence-logic.ts`
// (`resolveZoneColors`) already establishes for this app's other Leaflet layer. Anything rendered as
// *HTML* instead (every divIcon symbol, all the chrome) uses the tokens directly and needs none of
// this.
//
// These used to be permanent, hand-copied hex snapshots of the *dark* theme's tokens
// (`TRAIL_COLOR`/`DRAWING_COLORS`, frozen at import time) — correct only while dark was this app's
// default theme. Once light became the default (docs/plans/done/VISUAL-REFRESH-PLAN.md), every one of these
// literals silently kept painting the drone trail and every "accent"-toned drawing in dark-theme
// blue on a light page — and three of the five drawing-toolbar swatch colours
// (`danger`/`warn`/`success`) never matched *either* theme's ramp at all (an orphaned, unrelated
// palette; the swatches themselves, `drawing-toolbar.css`'s `.tone-*` rules, were always correct,
// reading `var(--color-danger)` etc. directly — only what actually got painted onto the map drifted
// from them). `resolveMapColors` fixes both problems the same way: read every literal from the live
// theme, via `readVar` — a thin seam the caller supplies (typically a `getComputedStyle` read off
// the element that actually paints; see `TacticalMap#refreshMapColors`'s own comment for why that
// beats `:root`, e.g. inside a `.surface-dark` enclave) — so this module itself stays DOM-free and
// unit-testable with a fake reader.

/** Every Leaflet-paint-layer literal this map draws, resolved from the live theme — see the section comment above. */
export interface MapColors {
  readonly trail: string;
  readonly danger: string;
  readonly warn: string;
  readonly success: string;
  readonly neutral: string;
}

/**
 * Last-resort literals — the exact pre-fix, dark-theme-only values, used only where `readVar`
 * genuinely can't resolve a token (e.g. a test with no stylesheet loaded, or a call before the DOM
 * is ready). A resolution failure degrades to today's known behaviour rather than an invalid/empty
 * Leaflet colour string.
 */
export const FALLBACK_MAP_COLORS: MapColors = {
  trail: '#4f8cff',
  danger: '#e5484d',
  warn: '#f5a524',
  success: '#30a46c',
  neutral: '#8b94a7',
};

/**
 * Resolves {@link MapColors} via `readVar` — kept as an injected function rather than a direct
 * `getComputedStyle` call so this module stays pure/DOM-free and testable with a fake reader.
 * `neutral` maps to `--text-faint`, the exact token `drawing-toolbar.css`'s own `.tone-neutral`
 * swatch already uses, so a drawn "Neutral" line matches its own swatch precisely; every other role
 * maps to the semantic token its name already names.
 */
export function resolveMapColors(readVar: (name: string) => string | undefined): MapColors {
  const read = (name: string, fallback: string): string => {
    const value = readVar(name)?.trim();
    return value && value.length > 0 ? value : fallback;
  };
  return {
    trail: read('--color-info', FALLBACK_MAP_COLORS.trail),
    danger: read('--color-danger', FALLBACK_MAP_COLORS.danger),
    warn: read('--color-warn', FALLBACK_MAP_COLORS.warn),
    success: read('--color-success', FALLBACK_MAP_COLORS.success),
    neutral: read('--text-faint', FALLBACK_MAP_COLORS.neutral),
  };
}

/**
 * A `Drawing.colorToken` slug → its stroke colour; unknown/absent tokens fall back to the trail/
 * accent colour. `colors` defaults to {@link FALLBACK_MAP_COLORS} so an existing caller that only
 * cares about *which* token maps to *which* role (`drawings-logic.ts`'s own token-roster test, this
 * file's own spec) keeps compiling and behaving unchanged; `TacticalMap` is the one caller that
 * passes the live-resolved {@link MapColors} it read at paint time (see `resolveMapColors` above).
 */
export function drawingColor(colorToken: string | undefined, colors: MapColors = FALLBACK_MAP_COLORS): string {
  switch (colorToken) {
    case 'danger':
      return colors.danger;
    case 'warn':
      return colors.warn;
    case 'success':
      return colors.success;
    case 'neutral':
      return colors.neutral;
    default:
      return colors.trail; // 'accent' | 'info' | unknown/absent — trail is the deliberate fallback
  }
}

// --- Shared helpers lifted from the two deleted components --------------------------------------

const ESCAPE_MAP: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
};

/**
 * Popup/tooltip content is raw HTML handed to Leaflet, not an Angular template — user-controlled
 * text (an asset name, a zone name, a mark label) must be escaped by hand. Was duplicated
 * byte-for-byte in `fleet-map.ts` and `live-map.ts`; this is the one copy now.
 */
export function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (char) => ESCAPE_MAP[char]);
}

/** The permanent centre label of a zone polygon — `"KEEP-OUT: North field (disabled)"`. */
export function zoneTooltipLabel(zone: Pick<GeofenceZone, 'kind' | 'name' | 'enabled'>): string {
  return `${zoneKindLabel(zone.kind)}: ${zone.name}${zone.enabled ? '' : ' (disabled)'}`;
}

// --- Follow mode --------------------------------------------------------------------------------

/** What a follow-mode host knows about the one asset the map is following. */
export interface FollowMarkerSpec {
  readonly assetId: string;
  readonly displayName: string;
  readonly categoryName?: string;
  /** `TelemetryStore.trail()` — the current usage's positioned samples, oldest first. */
  readonly trail: readonly GeoPosition[];
  /** `TelemetryStore.latest()` — may carry no fix at all (a battery-only sample). */
  readonly latest: TelemetrySample | undefined;
}

/**
 * The single `FleetMarker` a follow-mode host passes as `[assets]` (docs/plans/done/MAP-REWORK-PLAN.md §5.1:
 * "0..n; 1 in follow mode"), built from the telemetry the old `LiveMap` used to read out of an
 * injected `TelemetryStore` itself. `undefined` — i.e. an empty `[assets]`, nothing plotted — when
 * there is no position at all, matching `LiveMap`'s own empty-trail branch.
 *
 * Position falls back to the last trail point when the freshest sample carries no fix, which is
 * exactly what `LiveMap` showed in that case (it simply left the marker where it last was rather
 * than moving or hiding it).
 */
export function followMarker(spec: FollowMarkerSpec): FleetMarker | undefined {
  const latest = spec.latest;
  const hasFix = latest?.latitude !== undefined && latest.longitude !== undefined;
  const position: GeoPosition | undefined = hasFix
    ? { latitude: latest.latitude as number, longitude: latest.longitude as number, altitudeMeters: latest.altitudeMeters }
    : spec.trail.length > 0
      ? spec.trail[spec.trail.length - 1]
      : undefined;
  if (!position) {
    return undefined;
  }
  return {
    assetId: spec.assetId,
    displayName: spec.displayName,
    category: '',
    categoryName: spec.categoryName ?? '',
    status: 'STREAMING',
    live: true,
    position,
    headingDegrees: latest?.headingDegrees,
    batteryPercent: latest?.batteryPercent,
    trail: spec.trail,
    flightMode: latest?.flightState?.mode,
    armed: latest?.flightState?.armed,
    failsafe: latest?.flightState?.failsafe,
  };
}

/** `[assets]`-shaped convenience wrapper around {@link followMarker} — an empty array when nothing is plottable. */
export function followMarkers(spec: FollowMarkerSpec): readonly FleetMarker[] {
  const marker = followMarker(spec);
  return marker ? [marker] : [];
}
