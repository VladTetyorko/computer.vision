import type { BoundingBox } from '../../core/api/models';

/**
 * Pure geometry behind `sample-box-editor.ts` — the editable annotation canvas for a training
 * sample (docs/plans/done/CV-TRAINING-PLAN.md §4, Wave T5). Split out so drag/resize/create math is
 * unit-testable with no `<canvas>`/pointer events, mirroring `player-recovery.ts`/
 * `detection-overlay-logic.ts`'s own precedent in this folder.
 *
 * **A deliberate fork, not a shared import from `player.ts`.** The live player's own
 * `letterboxRect`/`DrawnBox`/`drawBox` are private to that component and read-only (detections are
 * drawn, never dragged) — this module duplicates just the small "object-fit: contain" letterbox math
 * (`letterboxRect` below is byte-for-byte the same formula) and adds the write-side half player.ts
 * has no reason to ever need: px↔normalized round-tripping, hit-testing, and drag/resize. Forking
 * keeps the live player's hot render path untouched while giving the editor its own small, fully
 * tested surface — the task brief's own explicit guidance ("fork into an editable variant rather
 * than complicating the live player").
 */

export interface PxRect {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

/** The image's actual rendered rectangle within its container, accounting for `object-fit: contain`
 *  letterboxing — identical formula to `player.ts#letterboxRect` (see this file's own doc comment). */
export function letterboxRect(
  elementWidth: number,
  elementHeight: number,
  contentWidth: number,
  contentHeight: number,
): PxRect {
  if (elementWidth <= 0 || elementHeight <= 0 || contentWidth <= 0 || contentHeight <= 0) {
    return { x: 0, y: 0, width: 0, height: 0 };
  }
  const elementRatio = elementWidth / elementHeight;
  const contentRatio = contentWidth / contentHeight;
  if (contentRatio > elementRatio) {
    const width = elementWidth;
    const height = width / contentRatio;
    return { x: 0, y: (elementHeight - height) / 2, width, height };
  }
  const height = elementHeight;
  const width = height * contentRatio;
  return { x: (elementWidth - width) / 2, y: 0, width, height };
}

/** A normalized `[0,1]` box → its on-screen pixel rect within `content` (the letterboxed image area). */
export function boxToPx(box: BoundingBox, content: PxRect): PxRect {
  return {
    x: content.x + box.x * content.width,
    y: content.y + box.y * content.height,
    width: box.width * content.width,
    height: box.height * content.height,
  };
}

/** The inverse of {@link boxToPx} — a screen-pixel rect within `content` → a clamped, well-formed normalized box. Degenerates to a zero box if `content` has no area (image not yet loaded/sized). */
export function pxToBox(rect: PxRect, content: PxRect): BoundingBox {
  if (content.width <= 0 || content.height <= 0) {
    return { x: 0, y: 0, width: 0, height: 0 };
  }
  return clampBox({
    x: (rect.x - content.x) / content.width,
    y: (rect.y - content.y) / content.height,
    width: rect.width / content.width,
    height: rect.height / content.height,
  });
}

/** Clamps a (possibly out-of-range) normalized box into `[0,1]`, keeping it inside the frame — used after every drag/resize so a box can never be saved partly or wholly off-frame. */
export function clampBox(box: BoundingBox): BoundingBox {
  const width = Math.max(0, Math.min(1, box.width));
  const height = Math.max(0, Math.min(1, box.height));
  const x = Math.max(0, Math.min(1 - width, box.x));
  const y = Math.max(0, Math.min(1 - height, box.y));
  return { x, y, width, height };
}

/** Turns two arbitrary drag-corner points (in either direction) into a well-formed rect with a non-negative width/height — the drag-to-create gesture doesn't care which corner the pointer started on. */
export function normalizeDragRect(x0: number, y0: number, x1: number, y1: number): PxRect {
  const x = Math.min(x0, x1);
  const y = Math.min(y0, y1);
  return { x, y, width: Math.abs(x1 - x0), height: Math.abs(y1 - y0) };
}

/** The four corner-resize handles this editor supports (edge-midpoint handles are a deliberate scope
 *  cut — a bounding box's four corners cover every practical correction; see this module's own doc
 *  comment on why edge handles were dropped) plus `'move'` for a drag that starts inside the box. */
export type HandleId = 'move' | 'nw' | 'ne' | 'sw' | 'se';

export interface HitTarget<Id> {
  readonly id: Id;
  readonly rect: PxRect;
}

export interface Hit<Id> {
  readonly id: Id;
  readonly handle: HandleId;
}

/**
 * Which box (and which part of it) a pointer landed on, topmost-drawn first (`boxes` is iterated
 * back-to-front so a later, on-top box wins an overlap — the same z-order it's drawn in).
 * `handleSizePx` is deliberately generous (larger than the visual handle) for touch-friendly hit
 * targets (CLAUDE.md's own "large touch targets" rule) — corners are checked before the box interior
 * so a corner never loses to a `'move'` hit just because the two overlap near the edge.
 */
export function hitTest<Id>(
  boxes: readonly HitTarget<Id>[],
  point: { readonly x: number; readonly y: number },
  handleSizePx: number,
): Hit<Id> | null {
  for (let i = boxes.length - 1; i >= 0; i--) {
    const { id, rect } = boxes[i];
    const handle = hitHandle(rect, point, handleSizePx);
    if (handle) {
      return { id, handle };
    }
  }
  return null;
}

function hitHandle(rect: PxRect, point: { readonly x: number; readonly y: number }, handleSizePx: number): HandleId | null {
  const corners: readonly [HandleId, number, number][] = [
    ['nw', rect.x, rect.y],
    ['ne', rect.x + rect.width, rect.y],
    ['sw', rect.x, rect.y + rect.height],
    ['se', rect.x + rect.width, rect.y + rect.height],
  ];
  for (const [id, cx, cy] of corners) {
    if (Math.abs(point.x - cx) <= handleSizePx && Math.abs(point.y - cy) <= handleSizePx) {
      return id;
    }
  }
  const withinX = point.x >= rect.x && point.x <= rect.x + rect.width;
  const withinY = point.y >= rect.y && point.y <= rect.y + rect.height;
  return withinX && withinY ? 'move' : null;
}

/** Translates `rect` by `(dx, dy)`, clamped so it never leaves `bounds` (the letterboxed content area). */
export function moveBox(rect: PxRect, dx: number, dy: number, bounds: PxRect): PxRect {
  const x = clampRange(rect.x + dx, bounds.x, bounds.x + bounds.width - rect.width);
  const y = clampRange(rect.y + dy, bounds.y, bounds.y + bounds.height - rect.height);
  return { x, y, width: rect.width, height: rect.height };
}

/**
 * Resizes `rect` by dragging `handle`, keeping the opposite corner fixed — the standard "drag a
 * corner" resize idiom. Clamped to `minSizePx` (never collapses to a point/negative size) and to
 * `bounds` (never grows past the image edge).
 */
export function resizeBox(rect: PxRect, handle: HandleId, dx: number, dy: number, minSizePx: number, bounds: PxRect): PxRect {
  if (handle === 'move') {
    return rect;
  }
  const left = handle === 'nw' || handle === 'sw';
  const top = handle === 'nw' || handle === 'ne';

  const fixedX = left ? rect.x + rect.width : rect.x;
  const fixedY = top ? rect.y + rect.height : rect.y;
  const draggedX = clampRange((left ? rect.x : rect.x + rect.width) + dx, bounds.x, bounds.x + bounds.width);
  const draggedY = clampRange((top ? rect.y : rect.y + rect.height) + dy, bounds.y, bounds.y + bounds.height);

  const x = Math.min(fixedX, draggedX);
  const y = Math.min(fixedY, draggedY);
  const width = Math.max(minSizePx, Math.abs(draggedX - fixedX));
  const height = Math.max(minSizePx, Math.abs(draggedY - fixedY));
  return { x, y, width, height };
}

function clampRange(value: number, min: number, max: number): number {
  return Math.max(Math.min(min, max), Math.min(value, Math.max(min, max)));
}
