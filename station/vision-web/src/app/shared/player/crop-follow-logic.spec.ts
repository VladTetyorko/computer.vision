import { describe, expect, it } from 'vitest';
import {
  CROP_FOLLOW_DEADBAND_RADIUS,
  CROP_FOLLOW_SCALE,
  IDENTITY_CROP_FOLLOW_STATE,
  applyCropFollowToContentRect,
  cropFollowTransform,
  stepCropFollow,
  type CropFollowState,
} from './crop-follow-logic';

/** A big-enough `dtMs` that the exponential ease is indistinguishable from fully settled — used
 *  whenever a test wants the *eventual* state rather than a mid-ease snapshot. */
const SETTLE_MS = 50_000;

/** A zero-size box places its centre exactly at `(x, y)` — the simplest way to hand `stepCropFollow`
 *  a target centre without box-size arithmetic cluttering every test. */
function pointBox(x: number, y: number) {
  return { x, y, width: 0, height: 0 };
}

describe('cropFollowTransform', () => {
  it('renders the identity state as scale 1 with zero offset', () => {
    expect(cropFollowTransform(IDENTITY_CROP_FOLLOW_STATE)).toEqual({ scale: 1, offsetX: 0, offsetY: 0 });
  });

  it('collapses the offset to 0 at scale 1 regardless of an off-centre pan value', () => {
    const state: CropFollowState = { scale: 1, centerX: 0.9, centerY: 0.1, anchorX: 0.9, anchorY: 0.1 };
    expect(cropFollowTransform(state)).toEqual({ scale: 1, offsetX: 0, offsetY: 0 });
  });
});

describe('applyCropFollowToContentRect', () => {
  it('returns the content rect unchanged (in value) at the identity transform', () => {
    const content = { x: 0, y: 0, width: 640, height: 360 };
    expect(applyCropFollowToContentRect(content, { scale: 1, offsetX: 0, offsetY: 0 })).toEqual(content);
  });

  it('doubles the size and shifts the origin for a ×2 crop at a non-zero offset', () => {
    const content = { x: 10, y: 20, width: 640, height: 360 };
    const effective = applyCropFollowToContentRect(content, { scale: 2, offsetX: 0.25, offsetY: 0.1 });
    expect(effective).toEqual({
      x: 10 - 0.25 * 2 * 640,
      y: 20 - 0.1 * 2 * 360,
      width: 1280,
      height: 720,
    });
  });
});

describe('stepCropFollow — deadband', () => {
  const settled: CropFollowState = { scale: 2, centerX: 0.5, centerY: 0.5, anchorX: 0.5, anchorY: 0.5 };

  it('holds the anchor steady while the target drifts less than the deadband radius', () => {
    const smallDrift = CROP_FOLLOW_DEADBAND_RADIUS * 0.5;
    const next = stepCropFollow(settled, {
      enabled: true,
      targetBox: pointBox(0.5 + smallDrift, 0.5),
      dtMs: SETTLE_MS, // if the anchor had moved, this would fully settle onto it — it must not
      reducedMotion: false,
    });
    expect(next.anchorX).toBe(0.5);
    expect(next.anchorY).toBe(0.5);
    expect(next.centerX).toBeCloseTo(0.5, 9);
  });

  it('re-aims the anchor once the target drifts past the deadband radius', () => {
    const bigDrift = CROP_FOLLOW_DEADBAND_RADIUS * 3;
    const next = stepCropFollow(settled, {
      enabled: true,
      targetBox: pointBox(0.5 + bigDrift, 0.5),
      dtMs: SETTLE_MS,
      reducedMotion: false,
    });
    expect(next.anchorX).toBeCloseTo(0.5 + bigDrift, 9);
    expect(next.centerX).toBeCloseTo(0.5 + bigDrift, 5);
  });
});

describe('stepCropFollow — clamp at frame edges', () => {
  it('clamps the offset so the crop window never scrolls past the raw video bounds', () => {
    let state = IDENTITY_CROP_FOLLOW_STATE;
    // A target pinned near the frame's own right edge (centre 0.98) — an un-clamped anchor would
    // want offsetX = 0.98 - 0.25 = 0.73, well past the scale-2 ceiling of 1 - 1/2 = 0.5.
    state = stepCropFollow(state, {
      enabled: true,
      targetBox: pointBox(0.98, 0.5),
      dtMs: SETTLE_MS,
      reducedMotion: false,
    });
    const transform = cropFollowTransform(state);
    expect(transform.scale).toBeCloseTo(CROP_FOLLOW_SCALE, 5);
    expect(transform.offsetX).toBeCloseTo(1 - 1 / CROP_FOLLOW_SCALE, 9);
    expect(transform.offsetX).toBeLessThanOrEqual(1 - 1 / CROP_FOLLOW_SCALE + 1e-9);
  });

  it('clamps the offset at the low edge too (never negative)', () => {
    let state = IDENTITY_CROP_FOLLOW_STATE;
    state = stepCropFollow(state, {
      enabled: true,
      targetBox: pointBox(0.02, 0.02),
      dtMs: SETTLE_MS,
      reducedMotion: false,
    });
    const transform = cropFollowTransform(state);
    expect(transform.offsetX).toBeGreaterThanOrEqual(0);
    expect(transform.offsetY).toBeGreaterThanOrEqual(0);
  });
});

describe('stepCropFollow — identity and loss', () => {
  it('produces the identity transform at scale 1 before any target has ever appeared', () => {
    expect(cropFollowTransform(IDENTITY_CROP_FOLLOW_STATE)).toEqual({ scale: 1, offsetX: 0, offsetY: 0 });
  });

  it('snaps straight back to scale 1 the instant the target is lost, never an eased lingering zoom', () => {
    const zoomed: CropFollowState = { scale: 2, centerX: 0.7, centerY: 0.3, anchorX: 0.7, anchorY: 0.3 };
    const next = stepCropFollow(zoomed, { enabled: true, targetBox: null, dtMs: 16, reducedMotion: false });
    expect(cropFollowTransform(next)).toEqual({ scale: 1, offsetX: 0, offsetY: 0 });
  });

  it('never eases toward a target scale while disabled — settles back to identity', () => {
    const zoomed: CropFollowState = { scale: 2, centerX: 0.6, centerY: 0.4, anchorX: 0.6, anchorY: 0.4 };
    const next = stepCropFollow(zoomed, {
      enabled: false,
      targetBox: pointBox(0.6, 0.4),
      dtMs: SETTLE_MS,
      reducedMotion: false,
    });
    expect(cropFollowTransform(next)).toEqual({ scale: 1, offsetX: 0, offsetY: 0 });
  });
});

describe('stepCropFollow — prefers-reduced-motion', () => {
  it('jumps straight to the target scale/centre in one step instead of easing fractionally', () => {
    const next = stepCropFollow(IDENTITY_CROP_FOLLOW_STATE, {
      enabled: true,
      targetBox: pointBox(0.6, 0.6),
      dtMs: 1, // tiny — an eased step would barely move at all
      reducedMotion: true,
    });
    expect(next.scale).toBe(CROP_FOLLOW_SCALE);
    expect(next.centerX).toBeCloseTo(0.6, 9);
    expect(next.centerY).toBeCloseTo(0.6, 9);
  });
});

/** Mirrors `player.ts#drawDetections`'s own box-rect math verbatim (`content.x + box.x*content.width`
 *  etc.) and `onOverlayMouseMove`/`onOverlayClick`'s own hit-test inequality — the acceptance test for
 *  wave W6 (TRACK-FOLLOW-PLAN.md D10): "click-to-follow still selects the correct box at ×2". */
function boxToScreenRect(
  content: { x: number; y: number; width: number; height: number },
  box: { x: number; y: number; width: number; height: number },
) {
  return {
    x: content.x + box.x * content.width,
    y: content.y + box.y * content.height,
    width: box.width * content.width,
    height: box.height * content.height,
  };
}

function hitTest(rect: { x: number; y: number; width: number; height: number }, point: { x: number; y: number }) {
  return point.x >= rect.x && point.x <= rect.x + rect.width && point.y >= rect.y && point.y <= rect.y + rect.height;
}

describe('hit-test round trip at ×2 (D10 acceptance)', () => {
  it('a click inside the zoomed box hits it, and does not also hit a box the crop pushed off-canvas', () => {
    const content = { x: 0, y: 0, width: 640, height: 360 };
    // A settled ×2 crop pinned to the bottom-right quadrant (offset clamped to its ceiling, 0.5).
    const transform = { scale: 2, offsetX: 0.5, offsetY: 0.5 };
    const effective = applyCropFollowToContentRect(content, transform);

    // Inside the visible crop window ([0.5,1] x [0.5,1] of the raw frame).
    const followedBox = { x: 0.9, y: 0.9, width: 0.05, height: 0.05 };
    // Outside it (top-left quadrant) — at ×1 this would sit near the canvas centre and could be
    // mistaken for a hit; a correct ×2 transform pushes it off-canvas (negative rect coordinates).
    const otherBox = { x: 0.1, y: 0.1, width: 0.05, height: 0.05 };

    const followedRect = boxToScreenRect(effective, followedBox);
    const otherRect = boxToScreenRect(effective, otherBox);

    const clickPoint = { x: followedRect.x + followedRect.width / 2, y: followedRect.y + followedRect.height / 2 };

    expect(hitTest(followedRect, clickPoint)).toBe(true);
    expect(hitTest(otherRect, clickPoint)).toBe(false);
    // The pushed-off-canvas box's rect must actually be outside the CSS canvas box — proof the
    // transform, not just this test's own arithmetic, is what separates the two.
    expect(otherRect.x + otherRect.width).toBeLessThan(0);
  });
});
