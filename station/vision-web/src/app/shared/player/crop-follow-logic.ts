import type { BoundingBox } from '../../core/api/models';

/**
 * F2 — digital crop-follow's pure box→transform math (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.1
 * item 4 / D10, wave W6). A client-side-only re-frame of the *view*: the same video pixels, cropped
 * and CSS-scaled toward whichever box the FOLLOW lock is currently bound to — no camera moves, no
 * extra detail is created (see the toggle's own honest label, `follow-hud.html`'s `title`/`aria-label`
 * — "Digital zoom ×2 — crop, no extra detail"). Split out so the deadband/ease/clamp math is
 * unit-testable without a `<canvas>` or a redraw loop, mirroring every other `*-logic.ts` sibling in
 * this directory.
 *
 * **Coordinate space.** Every box/transform here is the app's existing normalized `[0,1]` frame space
 * (`BoundingBox`, `dto.BoundingBoxResponse`) — never CSS pixels. `player.ts#redrawOverlay` is the one
 * caller that turns a {@link CropFollowTransform} into CSS pixels, by feeding `letterboxRect`'s own
 * output through {@link applyCropFollowToContentRect} to get an *effective* content rect — every
 * per-box CSS-pixel computation downstream of that (the box-rect loop, `drawTrails`, and therefore
 * `drawnBoxes`) already maps a normalized box onto whatever content rect it's handed, so substituting
 * the effective one for the plain one is the entire integration. This is also why D10's other two
 * named sites, `onOverlayMouseMove`/`onOverlayClick`, need no code of their own: both only ever read
 * back `drawnBoxes`, which is already correct the moment the rect it was built from is — no separate
 * screen→video coordinate inversion is needed for the click hit-test to land on the right box at ×2.
 *
 * **Design, one paragraph.** A {@link CropFollowState} carries an eased pan centre
 * (`centerX`/`centerY`) and `scale`, plus a **deadband-gated anchor** the centre eases *toward* — the
 * anchor only re-aims when the locked target's own box centre drifts past
 * {@link CROP_FOLLOW_DEADBAND_RADIUS} away from it, so per-detection jitter well inside that radius
 * never retriggers a re-frame. {@link cropFollowTransform} derives the actual `{scale, offsetX,
 * offsetY}` from the state, clamping the offset to keep the crop window inside `[0,1]` at whatever
 * scale is *currently* true mid-ease — never the eventual target scale — so the crop is never
 * momentarily invalid while easing in or out (see that function's own doc comment). Losing the target
 * outright ({@link stepCropFollow}'s `targetBox === null` — no lock at all, `REQUESTING` before the
 * first confirming frame, or the exact frame the lock falls to `LOST`) snaps the scale back to `1`
 * immediately, bypassing the ease entirely: a lingering zoomed frame with nothing confirming it is
 * exactly the "lie about where the camera is looking" §3.1 item 4 refuses to ship.
 */

/** The one zoom level this wave offers — "×2" is baked into the toggle's own label
 *  (`follow-hud.html`), so the ratio is a fixed constant rather than a slider: a variable ratio would
 *  make that label false the moment it changed. */
export const CROP_FOLLOW_SCALE = 2;

/**
 * Deadband radius, in normalized frame units, around the crop's current anchor — the locked target's
 * box centre must drift past this distance before the anchor re-aims at all. `0.06` is roughly a
 * twelfth of the frame's own width/height: small enough that a walking person's ordinary sway or a
 * detector's per-frame box jitter never retriggers a re-centre, large enough that a real relocation
 * (the target actually moving across the frame) still re-aims promptly rather than waiting for it to
 * approach the crop's own edge.
 */
export const CROP_FOLLOW_DEADBAND_RADIUS = 0.06;

/**
 * Exponential-ease time constant (ms) for both `scale` and the pan centre — a first-order
 * (critically-damped-equivalent) approach to whatever the anchor/target scale currently is, never an
 * overshooting spring. `220`ms settles to within ~2% of the target in roughly one second (≈4.5τ),
 * which reads as a deliberate, camera-operator-like re-frame rather than either an instant snap (jarring
 * at ×2) or a sluggish drift that lags a moving target.
 */
export const CROP_FOLLOW_EASE_TIME_CONSTANT_MS = 220;

/** A CSS-pixel rectangle — structurally identical to `player.ts#letterboxRect`'s own anonymous return
 *  type, so callers pass that straight through with no cast. */
export interface ContentRect {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

/** The crop-follow transform actually applied to a content rect this redraw — `scale` plus the
 *  top-left corner (normalized `[0,1]` frame units) of the visible crop window. `scale === 1` is the
 *  identity case (`offsetX`/`offsetY` are always `0` then — see {@link cropFollowTransform}). */
export interface CropFollowTransform {
  readonly scale: number;
  readonly offsetX: number;
  readonly offsetY: number;
}

export const IDENTITY_TRANSFORM: CropFollowTransform = { scale: 1, offsetX: 0, offsetY: 0 };

/**
 * The eased, stateful pan/zoom — carried across redraws by the caller (`Player`'s own private field)
 * and threaded through {@link stepCropFollow} once per redraw. `anchorX`/`anchorY` are the
 * deadband-gated re-aim target; `centerX`/`centerY` are what actually eases toward it, so a re-aim
 * (anchor jumps instantly) still produces a smooth on-screen pan rather than a cut.
 */
export interface CropFollowState {
  readonly scale: number;
  readonly centerX: number;
  readonly centerY: number;
  readonly anchorX: number;
  readonly anchorY: number;
}

/** Before any target has ever been seen, or once one has been released — dead centre, scale `1`. */
export const IDENTITY_CROP_FOLLOW_STATE: CropFollowState = {
  scale: 1,
  centerX: 0.5,
  centerY: 0.5,
  anchorX: 0.5,
  anchorY: 0.5,
};

export interface CropFollowStepInput {
  /** The per-viewer setting (`SettingsFacade.cropFollowEnabled`), passed straight through. */
  readonly enabled: boolean;
  /**
   * The locked track's own box this redraw, in normalized `[0,1]` frame coordinates — `null` when
   * there is nothing to aim at right now (no lock, `REQUESTING` before the first confirming frame, or
   * `LOST`). `null` forces the transform back to identity immediately regardless of {@link enabled} —
   * see this module's own doc comment for why that is not merely "disabled" behavior.
   */
  readonly targetBox: BoundingBox | null;
  /** Milliseconds since the previous step — `0` (no ease this tick) for the very first call. */
  readonly dtMs: number;
  /** `prefers-reduced-motion: reduce` — every ease collapses to an immediate snap (frontend-style §9). */
  readonly reducedMotion: boolean;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function easeAlpha(dtMs: number): number {
  if (dtMs <= 0) {
    return 0;
  }
  return 1 - Math.exp(-dtMs / CROP_FOLLOW_EASE_TIME_CONSTANT_MS);
}

/**
 * Advances the eased pan/zoom by one redraw tick. Pure and total — the same function whether called
 * from a live 60fps `requestVideoFrameCallback` loop or a test with a hand-picked `dtMs`.
 */
export function stepCropFollow(state: CropFollowState, input: CropFollowStepInput): CropFollowState {
  if (input.targetBox === null) {
    // Nothing honest to hold the zoom on this tick — release it outright rather than lingering on a
    // stale frame's crop while the ease slowly catches up (see this module's own doc comment). The
    // anchor/centre are kept, not reset to frame-centre: `cropFollowTransform` already forces the
    // offset to `0` at scale `1` regardless of their value (the visible window covers the whole
    // frame), so keeping them costs nothing and lets a target that reappears a frame or two later
    // (a brief detection gap, not a genuine loss) resume the ease from where it left off instead of
    // re-centring from scratch.
    return { ...state, scale: 1 };
  }

  const targetCenterX = input.targetBox.x + input.targetBox.width / 2;
  const targetCenterY = input.targetBox.y + input.targetBox.height / 2;
  const drifted =
    Math.hypot(targetCenterX - state.anchorX, targetCenterY - state.anchorY) > CROP_FOLLOW_DEADBAND_RADIUS;
  const anchorX = drifted ? targetCenterX : state.anchorX;
  const anchorY = drifted ? targetCenterY : state.anchorY;
  const targetScale = input.enabled ? CROP_FOLLOW_SCALE : 1;

  if (input.reducedMotion) {
    return { scale: targetScale, centerX: anchorX, centerY: anchorY, anchorX, anchorY };
  }

  const alpha = easeAlpha(input.dtMs);
  return {
    scale: state.scale + (targetScale - state.scale) * alpha,
    centerX: state.centerX + (anchorX - state.centerX) * alpha,
    centerY: state.centerY + (anchorY - state.centerY) * alpha,
    anchorX,
    anchorY,
  };
}

/**
 * Derives the actual `{scale, offsetX, offsetY}` from a {@link CropFollowState}. The offset clamp
 * uses `state.scale` — the *current*, possibly still-easing value — never the eventual target scale:
 * clamping against a scale the transform hasn't reached yet could momentarily place the crop window
 * partly outside `[0,1]` mid-ease. At `scale === 1` the visible window is the whole frame
 * (`1 - 1/scale === 0`), so the clamp collapses the offset to exactly `0` regardless of
 * `centerX`/`centerY` — the identity case falls out of the same formula, no special-casing needed.
 */
export function cropFollowTransform(state: CropFollowState): CropFollowTransform {
  const visible = 1 / state.scale;
  const half = visible / 2;
  return {
    scale: state.scale,
    offsetX: clamp(state.centerX - half, 0, 1 - visible),
    offsetY: clamp(state.centerY - half, 0, 1 - visible),
  };
}

/**
 * Maps a plain content rect (`player.ts#letterboxRect`'s own output — the letterboxed video's CSS-pixel
 * box) through a {@link CropFollowTransform} into the *effective* content rect every per-box CSS-pixel
 * computation should use instead (D10). At the identity transform (`scale === 1`) this returns `content`
 * unchanged in value (a new object, same numbers) — verified by
 * `crop-follow-logic.spec.ts`'s own "identity" case.
 */
export function applyCropFollowToContentRect(content: ContentRect, transform: CropFollowTransform): ContentRect {
  return {
    x: content.x - transform.offsetX * transform.scale * content.width,
    y: content.y - transform.offsetY * transform.scale * content.height,
    width: content.width * transform.scale,
    height: content.height * transform.scale,
  };
}
