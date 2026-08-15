import { describe, expect, it } from 'vitest';
import {
  boxToPx,
  clampBox,
  hitTest,
  letterboxRect,
  moveBox,
  normalizeDragRect,
  pxToBox,
  resizeBox,
  type PxRect,
} from './sample-box-editor-logic';

describe('letterboxRect', () => {
  it('returns a zero rect for a zero-area element or content', () => {
    expect(letterboxRect(0, 100, 100, 100)).toEqual({ x: 0, y: 0, width: 0, height: 0 });
    expect(letterboxRect(100, 100, 0, 100)).toEqual({ x: 0, y: 0, width: 0, height: 0 });
  });

  it('fills the element exactly when aspect ratios match', () => {
    expect(letterboxRect(200, 100, 1920, 960)).toEqual({ x: 0, y: 0, width: 200, height: 100 });
  });

  it('letterboxes top/bottom when content is wider than the element', () => {
    // content 2:1, element 1:1 -> width fills, height shrinks, centered vertically.
    const rect = letterboxRect(100, 100, 200, 100);
    expect(rect.width).toBe(100);
    expect(rect.height).toBe(50);
    expect(rect.x).toBe(0);
    expect(rect.y).toBe(25);
  });

  it('letterboxes left/right when content is taller than the element', () => {
    // content 1:2, element 1:1 -> height fills, width shrinks, centered horizontally.
    const rect = letterboxRect(100, 100, 100, 200);
    expect(rect.height).toBe(100);
    expect(rect.width).toBe(50);
    expect(rect.y).toBe(0);
    expect(rect.x).toBe(25);
  });
});

describe('boxToPx / pxToBox round-trip', () => {
  const content: PxRect = { x: 10, y: 20, width: 200, height: 100 };

  it('round-trips a normalized box through px and back', () => {
    const box = { x: 0.25, y: 0.5, width: 0.2, height: 0.1 };
    const px = boxToPx(box, content);
    expect(px).toEqual({ x: 10 + 0.25 * 200, y: 20 + 0.5 * 100, width: 0.2 * 200, height: 0.1 * 100 });
    expect(pxToBox(px, content)).toEqual(box);
  });

  it('pxToBox degenerates to a zero box when content has no area', () => {
    expect(pxToBox({ x: 0, y: 0, width: 10, height: 10 }, { x: 0, y: 0, width: 0, height: 0 })).toEqual({
      x: 0,
      y: 0,
      width: 0,
      height: 0,
    });
  });
});

describe('clampBox', () => {
  it('leaves an in-range box unchanged', () => {
    const box = { x: 0.1, y: 0.2, width: 0.3, height: 0.4 };
    expect(clampBox(box)).toEqual(box);
  });

  it('clamps width/height to [0,1] and repositions x/y so the box stays fully inside the frame', () => {
    expect(clampBox({ x: 0.9, y: 0.9, width: 0.5, height: 0.5 })).toEqual({ x: 0.5, y: 0.5, width: 0.5, height: 0.5 });
  });

  it('clamps a negative origin to 0', () => {
    expect(clampBox({ x: -0.2, y: -0.1, width: 0.3, height: 0.3 })).toEqual({ x: 0, y: 0, width: 0.3, height: 0.3 });
  });

  it('clamps an over-large box to fill the frame', () => {
    expect(clampBox({ x: 0, y: 0, width: 1.5, height: 2 })).toEqual({ x: 0, y: 0, width: 1, height: 1 });
  });
});

describe('normalizeDragRect', () => {
  it('is order-independent — dragging from any corner yields the same rect', () => {
    const expected = { x: 10, y: 20, width: 30, height: 40 };
    expect(normalizeDragRect(10, 20, 40, 60)).toEqual(expected); // top-left -> bottom-right
    expect(normalizeDragRect(40, 60, 10, 20)).toEqual(expected); // bottom-right -> top-left
    expect(normalizeDragRect(10, 60, 40, 20)).toEqual(expected); // bottom-left -> top-right
    expect(normalizeDragRect(40, 20, 10, 60)).toEqual(expected); // top-right -> bottom-left
  });
});

describe('hitTest', () => {
  const boxA = { id: 'a', rect: { x: 0, y: 0, width: 100, height: 100 } };
  const boxB = { id: 'b', rect: { x: 50, y: 50, width: 100, height: 100 } };

  it('returns null when the point hits nothing', () => {
    expect(hitTest([boxA], { x: 500, y: 500 }, 8)).toBeNull();
  });

  it('hits "move" inside a box, away from its corners', () => {
    expect(hitTest([boxA], { x: 50, y: 50 }, 8)).toEqual({ id: 'a', handle: 'move' });
  });

  it('hits a corner handle within the handle radius', () => {
    expect(hitTest([boxA], { x: 2, y: 2 }, 8)).toEqual({ id: 'a', handle: 'nw' });
    expect(hitTest([boxA], { x: 98, y: 98 }, 8)).toEqual({ id: 'a', handle: 'se' });
  });

  it('prefers the topmost (last) box on overlap', () => {
    // (60,60) is inside both boxA and boxB's overlap region — b is drawn on top (last in the array).
    expect(hitTest([boxA, boxB], { x: 60, y: 60 }, 8)).toEqual({ id: 'b', handle: 'move' });
  });
});

describe('moveBox', () => {
  const bounds: PxRect = { x: 0, y: 0, width: 200, height: 100 };

  it('translates by (dx, dy)', () => {
    expect(moveBox({ x: 10, y: 10, width: 20, height: 20 }, 5, -5, bounds)).toEqual({
      x: 15,
      y: 5,
      width: 20,
      height: 20,
    });
  });

  it('clamps so the box never leaves bounds', () => {
    expect(moveBox({ x: 10, y: 10, width: 20, height: 20 }, -100, -100, bounds)).toEqual({
      x: 0,
      y: 0,
      width: 20,
      height: 20,
    });
    expect(moveBox({ x: 10, y: 10, width: 20, height: 20 }, 1000, 1000, bounds)).toEqual({
      x: 180,
      y: 80,
      width: 20,
      height: 20,
    });
  });
});

describe('resizeBox', () => {
  const bounds: PxRect = { x: 0, y: 0, width: 200, height: 100 };
  const rect: PxRect = { x: 20, y: 20, width: 40, height: 40 };

  it('is a no-op for handle "move"', () => {
    expect(resizeBox(rect, 'move', 10, 10, 4, bounds)).toEqual(rect);
  });

  it('dragging "se" keeps the "nw" corner fixed and grows toward the drag', () => {
    const resized = resizeBox(rect, 'se', 10, 5, 4, bounds);
    expect(resized).toEqual({ x: 20, y: 20, width: 50, height: 45 });
  });

  it('dragging "nw" keeps the "se" corner fixed', () => {
    const resized = resizeBox(rect, 'nw', -10, -5, 4, bounds);
    expect(resized).toEqual({ x: 10, y: 15, width: 50, height: 45 });
  });

  it('dragging past the opposite (fixed) corner flips the rect to span it, never negative', () => {
    // se dragged far up-left, past the fixed nw corner at (20,20) -> the box flips to span
    // [0,20]x[0,20] (fixed corner to new pointer position), not a negative-size rect.
    const resized = resizeBox(rect, 'se', -1000, -1000, 4, bounds);
    expect(resized).toEqual({ x: 0, y: 0, width: 20, height: 20 });
  });

  it('never shrinks below minSizePx when the drag lands just short of the fixed corner', () => {
    // se's corner starts at (60,60); dragging it to (22,22) — 2px shy of the fixed nw corner
    // (20,20) — would produce a 2x2 box without the floor.
    const resized = resizeBox(rect, 'se', -38, -38, 4, bounds);
    expect(resized.width).toBe(4);
    expect(resized.height).toBe(4);
  });

  it('never grows past bounds', () => {
    const resized = resizeBox(rect, 'se', 10_000, 10_000, 4, bounds);
    expect(resized.x + resized.width).toBeLessThanOrEqual(bounds.width);
    expect(resized.y + resized.height).toBeLessThanOrEqual(bounds.height);
  });
});
