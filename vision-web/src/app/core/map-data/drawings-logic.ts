import type {
  CreateDrawingRequest,
  DrawKind,
  MapDrawingResponse,
  MapEventPayload,
  PatchDrawingRequest,
  PositionDto,
} from '../api/models';
import type { DrawingDraft, InteractionMode, MapDrawing } from '../../shared/map/tactical-map/tactical-map-logic';
import { minPointsFor } from '../../shared/map/tactical-map/tactical-map-logic';
import { applyMapEvents, deletedLayerIds, dropByLayer, type MapEntitySpec } from './map-event-logic';

/**
 * Pure, Angular-free logic behind `core/map-data/drawings-store.ts` and
 * `shared/map/map-controls/drawing-toolbar.ts` (docs/MAP-REWORK-PLAN.md §5.2) — the SSE fold, the
 * wire↔display projection, the colour-token catalogue the toolbar offers, and the interaction-mode
 * resolution the hosts feed back into `<vision-tactical-map>`'s `[interactionMode]`.
 *
 * Geometry *validity* is not re-implemented here: `tactical-map-logic.ts#minPointsFor` already owns
 * the LINE/ARROW ≥2, POLYGON ≥3, TEXT ==1 rule (mirroring `Drawing`'s own compact constructor) and
 * `completedDraft` already refuses a degenerate shape before it ever reaches a store — this module
 * imports that one rule rather than restating it.
 */

// --- Wire ↔ display ------------------------------------------------------------------------------

/**
 * A `DrawingResponse` as `<vision-tactical-map>`'s `[drawings]` display model. Same shape of
 * projection as marks': the map keys the drawing by `id` (not `drawingId`) and never reads
 * ownership/timestamps.
 */
export function toMapDrawing(drawing: MapDrawingResponse): MapDrawing {
  return {
    id: drawing.drawingId,
    layerId: drawing.layerId,
    kind: drawing.kind,
    points: drawing.points,
    label: drawing.label,
    colorToken: drawing.colorToken,
  };
}

export function toMapDrawings(drawings: readonly MapDrawingResponse[]): readonly MapDrawing[] {
  return drawings.map(toMapDrawing);
}

// --- SSE fold ------------------------------------------------------------------------------------

const DRAWING_SPEC: MapEntitySpec<MapDrawingResponse> = {
  entity: 'drawing',
  idOf: (drawing) => drawing.drawingId,
  payloadOf: (event) => event.drawing,
};

/** Folds the `drawing` half of a run of `map` deltas in, including a deleted layer's cascade (see `map-event-logic.ts#deletedLayerIds`). */
export function applyDrawingEvents(
  drawings: readonly MapDrawingResponse[],
  events: readonly MapEventPayload[],
): readonly MapDrawingResponse[] {
  return dropByLayer(applyMapEvents(drawings, events, DRAWING_SPEC), deletedLayerIds(events));
}

// --- Kinds + the drawing toolbar's own vocabulary ------------------------------------------------

/** Every kind, in the order the toolbar renders them — simplest gesture first. */
export const DRAW_KINDS: readonly DrawKind[] = ['LINE', 'POLYGON', 'ARROW', 'TEXT'];

const DRAW_KIND_LABELS: Record<DrawKind, string> = {
  LINE: 'Line',
  POLYGON: 'Area',
  ARROW: 'Arrow',
  TEXT: 'Label',
};

/** `POLYGON` reads as **Area** and `TEXT` as **Label** on purpose — what the operator is drawing, not the geometry primitive (frontend-style §10). */
export function drawKindLabel(kind: DrawKind): string {
  return DRAW_KIND_LABELS[kind];
}

/** How many clicks each kind still needs before the completion gesture does anything — the toolbar's live hint. */
export function remainingPoints(kind: DrawKind, placed: number): number {
  return Math.max(0, minPointsFor(kind) - placed);
}

// --- Colour tokens (never hex — see `tactical-map-logic.ts#drawingColor`) -------------------------

/**
 * The tokens the toolbar's colour picker offers, each a **UI token name** the map resolves to a
 * literal stroke colour (Leaflet writes SVG presentation attributes, which cannot resolve
 * `var(--token)`). Kept to the app's own semantic set — one accent plus the three status hues plus
 * neutral — so a drawing can never introduce a colour the design system doesn't already own.
 * `accent` is first because it is `drawingColor`'s own fallback for an absent/unknown token.
 */
export const DRAWING_COLOR_TOKENS: readonly { readonly token: string; readonly label: string }[] = [
  { token: 'accent', label: 'Accent' },
  { token: 'danger', label: 'Danger' },
  { token: 'warn', label: 'Caution' },
  { token: 'success', label: 'Safe' },
  { token: 'neutral', label: 'Neutral' },
];

// --- Interaction mode ----------------------------------------------------------------------------

/** The `[interactionMode]` value for a drawing kind — `null` (nothing being drawn) is `'view'`. */
export function interactionModeForDrawKind(kind: DrawKind | null): InteractionMode {
  switch (kind) {
    case 'LINE':
      return 'draw-line';
    case 'POLYGON':
      return 'draw-polygon';
    case 'ARROW':
      return 'draw-arrow';
    case 'TEXT':
      return 'draw-text';
    default:
      return 'view';
  }
}

/**
 * The single `[interactionMode]` a host feeds the map, resolved from the two independent arming
 * states that can produce one (`MarksStore`'s armed palette and `DrawingsStore`'s draw kind).
 * **Mark placement wins** on the (UI-prevented, but possible) tie: arming a mark is a single
 * deliberate click-to-place, whereas a draw mode is a sticky multi-click session — silently
 * swallowing the placement click would be the more surprising of the two failures. The controls
 * themselves disarm each other so the tie should never actually be reachable.
 */
export function resolveInteractionMode(markArmed: boolean, drawKind: DrawKind | null): InteractionMode {
  return markArmed ? 'mark' : interactionModeForDrawKind(drawKind);
}

// --- Requests ------------------------------------------------------------------------------------

/** The create body for a completed draft (`<vision-tactical-map>`'s `(drawingCompleted)` payload). */
export function createDrawingRequest(
  draft: DrawingDraft,
  options: { readonly layerId?: string; readonly label?: string; readonly colorToken?: string } = {},
): CreateDrawingRequest {
  return {
    layerId: options.layerId,
    kind: draft.kind,
    label: options.label,
    colorToken: options.colorToken,
    // `DrawingDraft.points` is `GeoPosition[]`, structurally identical to `PositionDto` — no cast.
    points: draft.points,
  };
}

/** The patch body for an edit — only the fields the caller actually names travel. */
export function patchDrawingRequest(edit: {
  readonly points?: readonly PositionDto[];
  readonly label?: string;
  readonly colorToken?: string;
}): PatchDrawingRequest {
  return edit;
}
