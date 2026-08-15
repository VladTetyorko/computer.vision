"""Synthetic ground-truth sequences for the tracking evaluation harness.

`docs/plans/active/TRACKING-V2-PLAN.md` §4 (wave C0). This repo has no
camera -- hardware tier H1 is unbought (`docs/main/TWO-TARGETS-PLAN.md`) --
so ground truth has to be known BY CONSTRUCTION rather than hand-labelled:
every object's box on every frame is computed from a closed-form trajectory,
never estimated or hand-annotated.

Five named scenarios, each isolating one failure mode
`docs/conclusions/TRACKING-REVIEW.md` §3 names:

    linear     the easy case -- 2-3 well-separated objects, constant
               velocity, no ambiguity. The number every later wave must not
               regress.
    occlusion  one object passes fully behind an opaque bar for a known
               frame window and re-emerges (REVIEW finding B3/C1).
    crossing   two similar-sized objects' paths intersect -- the classic
               IoU-matcher id-swap (REVIEW finding B1).
    pan        the CAMERA moves (ego-motion); objects also move. Nothing in
               this build compensates for it yet (REVIEW finding A1/A2).
    dropout    the object is always visible; `replay.py`'s synthetic
               detector is configured to intermittently fail to REPORT it,
               isolating detection recall (REVIEW §4.6) from association.

Every generator takes a `seed` and is otherwise pure: same seed, same
frames, same boxes, forever.

**Lazy `numpy`, by design.** Only `_render_frame` touches `numpy`, and only
inside its own function body -- importing this module, or building a
`Sequence`'s ground truth without ever rendering, costs nothing. This
mirrors `cv_service/tracking/registry.py`'s own lazy-import discipline for
pixel code, and it is what lets `metrics.py` depend on `GroundTruthObject`
without dragging the `cv` extra into a pure-stdlib metrics computation.
Rendering itself is a handful of `numpy` slice assignments -- no `cv2`
needed at all, since a filled/bordered/striped rectangle is exactly what
slicing already does.
"""

from __future__ import annotations

from dataclasses import dataclass
from random import Random
from typing import TYPE_CHECKING, Callable, Optional
from typing import Sequence as TypingSequence

from cv_service.tracking.engines.base import Box

if TYPE_CHECKING:  # pragma: no cover - typing only, see module docstring
    import numpy as np

# -- canvas -------------------------------------------------------------

FRAME_WIDTH = 320
FRAME_HEIGHT = 240
# Matches `PipelineConfig.defaults().inferenceFps` (ASSOCIATE) -- the
# harness's `now_millis` cadence math should exercise the scheduler at the
# rate cv-service actually receives frames at in production.
DEFAULT_FPS = 10.0
BACKGROUND_COLOR = (60, 60, 60)  # BGR, dark neutral gray

DEFAULT_SEED = 20260811  # today's date (see BASELINE.md) -- arbitrary but fixed

# -- object appearance ----------------------------------------------------

OBJECT_WIDTH_FRACTION = 0.14
OBJECT_HEIGHT_FRACTION = 0.20
OBJECT_LABEL = "object"

# BGR colours (matching the frame's own channel order, since these arrays
# are consumed by `engines/lk.py`/`ncc.py`'s `cv2.cvtColor(..., BGR2GRAY)`),
# picked for maximum pairwise separation.
_RED = (30, 30, 210)
_GREEN = (40, 170, 40)
_BLUE = (200, 90, 20)
_YELLOW = (20, 200, 200)
_OBJECT_COLORS: tuple[tuple[int, int, int], ...] = (_RED, _GREEN, _BLUE, _YELLOW)

# Ground-truth PLACEMENT jitter (distinct from `replay.py`'s DETECTOR noise,
# which perturbs what the synthetic detector reports, not the truth itself).
# Small and seeded, so `seed` has a genuine, checkable effect on every
# scenario rather than being an unused parameter.
POSITION_JITTER = 0.004

# World texture for the ego-motion scenarios: enough marks, spread over enough
# world, that `goodFeaturesToTrack` finds a stable set in any single view.
_TEXTURE_COLOR = (150, 150, 150)
_TEXTURE_MARK_PIXELS = 3
_BAR_MARGIN = 0.02
_WORLD_TEXTURE_POINTS: tuple[tuple[float, float], ...] = tuple(
    (round(0.02 + 0.043 * index, 4), round(0.06 + 0.113 * ((index * 7) % 8), 4))
    for index in range(40)
)

# Solid fill + alternating stripes + a bright border: a flat-colour box has
# no corners at all, and `engines/lk.py`'s `cv2.goodFeaturesToTrack` needs
# real corners to hold a target, not just an edge.
_TEXTURE_STRIPE_COUNT = 3
_TEXTURE_COLOR_DELTA = 55


# -- data model -----------------------------------------------------------


@dataclass(frozen=True)
class GroundTruthObject:
    """One object's true state on one frame -- ground truth BY CONSTRUCTION.

    `visible` is False while the object exists but cannot be SEEN: behind an
    occluder, or panned out of frame. A detector, real or synthetic, can
    never report a box for an invisible object -- `replay.py`'s
    `SyntheticDetector` enforces exactly that and never invents noise on top
    of it. `dropout`'s failure mode is different and deliberately NOT
    expressed here: its objects are always `visible=True`, and the miss
    comes from the detector configuration instead (see the module
    docstring).
    """

    gt_id: int
    label: str
    box: Box
    visible: bool = True


@dataclass(frozen=True)
class SyntheticFrame:
    """One rendered frame plus the ground truth that produced it."""

    index: int
    image: "np.ndarray"
    ground_truth: tuple[GroundTruthObject, ...]


@dataclass(frozen=True)
class Sequence:
    """One deterministic synthetic clip: frames + exact per-frame ground truth.

    `primary_gt_id` is which object FOLLOW mode should lock onto -- FOLLOW is
    single-target by charter (`docs/plans/done/TRACKING-PLAN.md` §3.1), so a
    scenario with more than one object still names the one whose fate the
    scenario is about (e.g. the occluded object, not the bar).
    """

    name: str
    fps: float
    width: int
    height: int
    frames: tuple[SyntheticFrame, ...]
    primary_gt_id: int = 1

    @property
    def gt_ids(self) -> frozenset[int]:
        """Every object id that ever exists in this clip, visible or not."""
        return frozenset(obj.gt_id for frame in self.frames for obj in frame.ground_truth)


# -- rendering --------------------------------------------------------------


def _pixel_rect(box: Box, width: int, height: int) -> tuple[int, int, int, int]:
    """Normalized box -> clamped pixel rect, same clamping convention as
    `cv_service/tracking/engines/lk.py`'s `_pixel_bounds`."""
    x0 = max(0, min(width, int(round(box.x * width))))
    y0 = max(0, min(height, int(round(box.y * height))))
    x1 = max(0, min(width, int(round((box.x + box.width) * width))))
    y1 = max(0, min(height, int(round((box.y + box.height) * height))))
    return x0, y0, x1, y1


def _draw_object(image: "np.ndarray", box: Box, color: tuple[int, int, int]) -> None:
    """Filled rect + alternating stripes + a bright border, via plain numpy
    slicing -- no `cv2` needed for solid shapes, so this stays as cheap as
    the `numpy` array the caller already allocated."""
    x0, y0, x1, y1 = _pixel_rect(box, image.shape[1], image.shape[0])
    if x1 <= x0 or y1 <= y0:
        return
    image[y0:y1, x0:x1] = color
    stripe_width = max(1, (x1 - x0) // (_TEXTURE_STRIPE_COUNT * 2))
    dark = tuple(max(0, channel - _TEXTURE_COLOR_DELTA) for channel in color)
    for band_index, x in enumerate(range(x0, x1, stripe_width)):
        if band_index % 2 == 0:
            continue
        image[y0:y1, x : min(x + stripe_width, x1)] = dark
    border = tuple(min(255, channel + _TEXTURE_COLOR_DELTA) for channel in color)
    image[y0, x0:x1] = border
    image[y1 - 1, x0:x1] = border
    image[y0:y1, x0] = border
    image[y0:y1, x1 - 1] = border


def _draw_bar(image: "np.ndarray", left_fraction: float, right_fraction: float, color: tuple[int, int, int]) -> None:
    """A full-height opaque vertical bar -- the `occlusion` scenario's
    occluder. Plain slicing, same reasoning as `_draw_object`."""
    width = image.shape[1]
    x0 = max(0, min(width, int(round(left_fraction * width))))
    x1 = max(0, min(width, int(round(right_fraction * width))))
    if x1 > x0:
        image[:, x0:x1] = color


def _draw_world_texture(image: "np.ndarray", camera_left: float) -> None:
    """A deterministic scatter of world-fixed marks, drawn offset by the camera.

    Every other scenario renders onto a FLAT background, which is fine for
    them -- they test association, and association reads boxes. It is not
    fine for ego-motion: a global-motion estimator works by tracking the
    BACKGROUND, and a uniform field has nothing to track, so `flow` would
    return IDENTITY on a flat scene and a compensation test would pass or
    fail for the wrong reason. These marks are what a real scene supplies
    for free and a synthetic one has to be given deliberately.

    World-fixed, so their apparent motion IS the camera's -- which is
    exactly the signal being estimated.
    """
    height, width = image.shape[:2]
    for world_x, world_y in _WORLD_TEXTURE_POINTS:
        x = int(round((world_x - camera_left) * width))
        y = int(round(world_y * height))
        if not (0 <= x < width - _TEXTURE_MARK_PIXELS and 0 <= y < height - _TEXTURE_MARK_PIXELS):
            continue
        image[y : y + _TEXTURE_MARK_PIXELS, x : x + _TEXTURE_MARK_PIXELS] = _TEXTURE_COLOR


def _render_frame(
    index: int,
    objects: TypingSequence[GroundTruthObject],
    colors: dict[int, tuple[int, int, int]],
    *,
    underlay: Optional[Callable[["np.ndarray"], None]] = None,
) -> SyntheticFrame:
    """Build one frame's pixels. THE only place `numpy` is imported in this
    module (see the module docstring)."""
    import numpy as np

    image = np.full((FRAME_HEIGHT, FRAME_WIDTH, 3), BACKGROUND_COLOR, dtype=np.uint8)
    if underlay is not None:
        underlay(image)
    for obj in objects:
        if obj.visible:
            _draw_object(image, obj.box, colors[obj.gt_id])
    return SyntheticFrame(index=index, image=image, ground_truth=tuple(objects))


def _lane_box(x: float, lane_center: float, jitter: float) -> Box:
    return Box(x, lane_center - OBJECT_HEIGHT_FRACTION / 2.0 + jitter, OBJECT_WIDTH_FRACTION, OBJECT_HEIGHT_FRACTION)


# -- scenario: linear -------------------------------------------------------

LINEAR_FRAME_COUNT = 60
LINEAR_OBJECT_COUNT = 3
LINEAR_LANE_MARGIN = 0.18
LINEAR_TRAVEL_START = 0.08
LINEAR_TRAVEL_END = 0.80


def linear(seed: int = DEFAULT_SEED) -> Sequence:
    """2-3 objects crossing the frame at constant velocity, well separated
    in both lane (y) and phase -- the case every later wave must not
    regress. Always fully visible; no occlusion, no crossing, no ego-motion."""
    rng = Random(seed)
    colors = {gt_id: _OBJECT_COLORS[(gt_id - 1) % len(_OBJECT_COLORS)] for gt_id in range(1, LINEAR_OBJECT_COUNT + 1)}
    span = 1.0 - 2.0 * LINEAR_LANE_MARGIN
    lanes = [
        LINEAR_LANE_MARGIN + slot * span / max(1, LINEAR_OBJECT_COUNT - 1) for slot in range(LINEAR_OBJECT_COUNT)
    ]
    directions = [1.0 if slot % 2 == 0 else -1.0 for slot in range(LINEAR_OBJECT_COUNT)]

    frames = []
    for index in range(LINEAR_FRAME_COUNT):
        objects = []
        t = index / (LINEAR_FRAME_COUNT - 1)
        for slot in range(LINEAR_OBJECT_COUNT):
            direction = directions[slot]
            x = (
                LINEAR_TRAVEL_START + (LINEAR_TRAVEL_END - LINEAR_TRAVEL_START) * t
                if direction > 0
                else LINEAR_TRAVEL_END - (LINEAR_TRAVEL_END - LINEAR_TRAVEL_START) * t
            )
            jitter = rng.uniform(-POSITION_JITTER, POSITION_JITTER)
            box = _lane_box(x, lanes[slot], jitter)
            objects.append(GroundTruthObject(gt_id=slot + 1, label=OBJECT_LABEL, box=box, visible=box.valid))
        frames.append(_render_frame(index, objects, colors))
    return Sequence(name="linear", fps=DEFAULT_FPS, width=FRAME_WIDTH, height=FRAME_HEIGHT, frames=tuple(frames))


# -- scenario: occlusion -----------------------------------------------------

OCCLUSION_FRAME_COUNT = 110
# Deliberately LONGER than `CV_TRACK_MAX_AGE`'s default (30, `DEFAULT_TRACK_MAX_AGE_FRAMES`
# in `cv_service/config.py`) -- a gap shorter than that never pushes a track
# past LOST at all (ByteTrack's own `track_buffer` and `TrackBook`'s miss
# counter both coast right through it, which is correct existing behaviour,
# not the case this scenario exists to isolate). Only a gap that outlasts
# `max_age_frames` exercises REVIEW finding B3 ("a LOST track is
# unrecoverable by construction") -- which is exactly what "recovery rate"
# is supposed to catch before wave C4 builds `ObjectMemory`.
OCCLUSION_GAP_FRAMES = 40
OCCLUSION_BAR_COLOR = (15, 15, 15)
OCCLUSION_BAR_MARGIN_FRACTION = 0.15  # extra bar width beyond the exact swept footprint
OCCLUSION_LANE = 0.5
OCCLUSION_TRAVEL_START = 0.05
OCCLUSION_TRAVEL_END = 0.85


def occlusion(seed: int = DEFAULT_SEED) -> Sequence:
    """One object crosses the frame at constant velocity and passes fully
    behind a static opaque bar for exactly `OCCLUSION_GAP_FRAMES` frames,
    known analytically from the object's own velocity -- not measured by
    overlap after the fact (REVIEW finding B3/C1: this is the "same box
    number through a pole" case)."""
    rng = Random(seed)
    color = _OBJECT_COLORS[0]
    velocity = (OCCLUSION_TRAVEL_END - OCCLUSION_TRAVEL_START) / (OCCLUSION_FRAME_COUNT - 1)
    gap_start = (OCCLUSION_FRAME_COUNT - OCCLUSION_GAP_FRAMES) // 2
    gap_end = gap_start + OCCLUSION_GAP_FRAMES  # exclusive

    margin = OBJECT_WIDTH_FRACTION * OCCLUSION_BAR_MARGIN_FRACTION
    bar_left = OCCLUSION_TRAVEL_START + velocity * gap_start - margin
    bar_right = OCCLUSION_TRAVEL_START + velocity * (gap_end - 1) + OBJECT_WIDTH_FRACTION + margin

    def underlay(image: "np.ndarray") -> None:
        _draw_bar(image, bar_left, bar_right, OCCLUSION_BAR_COLOR)

    frames = []
    for index in range(OCCLUSION_FRAME_COUNT):
        x = OCCLUSION_TRAVEL_START + velocity * index
        jitter = rng.uniform(-POSITION_JITTER, POSITION_JITTER)
        box = _lane_box(x, OCCLUSION_LANE, jitter)
        occluded = gap_start <= index < gap_end
        objects = (GroundTruthObject(gt_id=1, label=OBJECT_LABEL, box=box, visible=not occluded),)
        frames.append(_render_frame(index, objects, {1: color}, underlay=underlay))
    return Sequence(name="occlusion", fps=DEFAULT_FPS, width=FRAME_WIDTH, height=FRAME_HEIGHT, frames=tuple(frames))


# -- scenario: crossing ------------------------------------------------------

CROSSING_FRAME_COUNT = 50
CROSSING_LANE = 0.5
CROSSING_TRAVEL_MARGIN = 0.08


def crossing(seed: int = DEFAULT_SEED) -> Sequence:
    """Two similar-sized objects on the SAME lane, moving toward and then
    past each other -- their boxes genuinely overlap near the midpoint, the
    classic IoU-matcher id-swap ambiguity (REVIEW finding B1: no appearance
    model exists anywhere, so geometry alone cannot disambiguate them)."""
    rng = Random(seed)
    colors = {1: _OBJECT_COLORS[0], 2: _OBJECT_COLORS[1]}
    left_start = CROSSING_TRAVEL_MARGIN
    left_end = 1.0 - OBJECT_WIDTH_FRACTION - CROSSING_TRAVEL_MARGIN

    frames = []
    for index in range(CROSSING_FRAME_COUNT):
        t = index / (CROSSING_FRAME_COUNT - 1)
        x1 = left_start + (left_end - left_start) * t
        x2 = left_end - (left_end - left_start) * t
        jitter1 = rng.uniform(-POSITION_JITTER, POSITION_JITTER)
        jitter2 = rng.uniform(-POSITION_JITTER, POSITION_JITTER)
        objects = (
            GroundTruthObject(gt_id=1, label=OBJECT_LABEL, box=_lane_box(x1, CROSSING_LANE, jitter1), visible=True),
            GroundTruthObject(gt_id=2, label=OBJECT_LABEL, box=_lane_box(x2, CROSSING_LANE, jitter2), visible=True),
        )
        frames.append(_render_frame(index, objects, colors))
    return Sequence(name="crossing", fps=DEFAULT_FPS, width=FRAME_WIDTH, height=FRAME_HEIGHT, frames=tuple(frames))


# -- scenario: pan ------------------------------------------------------------


@dataclass(frozen=True)
class _PanObjectSpec:
    gt_id: int
    start_world_x: float
    own_velocity: float  # world-x units (frame-widths) per frame, own motion only
    lane: float


PAN_FRAME_COUNT = 70
# Camera translation, world-x units (frame-widths) per frame. Deliberately
# large relative to `OBJECT_WIDTH_FRACTION` (0.14): consecutive frames can
# have near-zero IoU purely from ego-motion, which is REVIEW finding A1's
# claim made concrete -- "a target smaller than [the per-frame shift] has
# zero IoU with its own previous box".
PAN_CAMERA_VELOCITY = 0.035
_PAN_OBJECTS: tuple[_PanObjectSpec, ...] = (
    # Three world-STATIC landmarks the pan sweeps across in turn (pure
    # ego-motion, no object motion of their own)...
    _PanObjectSpec(gt_id=1, start_world_x=0.35, own_velocity=0.0, lane=0.5),
    _PanObjectSpec(gt_id=2, start_world_x=1.20, own_velocity=0.0, lane=0.5),
    _PanObjectSpec(gt_id=3, start_world_x=2.10, own_velocity=0.0, lane=0.5),
    # ...plus one object that ALSO moves (against the pan), the "objects
    # also move" half of the scenario -- its apparent per-frame shift is the
    # sum of both motions, the worst case a gimballed drone tracking a
    # crossing vehicle actually sees.
    _PanObjectSpec(gt_id=4, start_world_x=2.55, own_velocity=-0.02, lane=0.3),
)


def pan(seed: int = DEFAULT_SEED) -> Sequence:
    """The whole scene translates because the CAMERA moved; one of the four
    objects also moves under its own power. Nothing in this build
    compensates for ego-motion yet (REVIEW finding A2), so this is the
    scenario expected to be badly broken at this baseline."""
    rng = Random(seed)
    colors = {spec.gt_id: _OBJECT_COLORS[(spec.gt_id - 1) % len(_OBJECT_COLORS)] for spec in _PAN_OBJECTS}

    frames = []
    for index in range(PAN_FRAME_COUNT):
        camera_left = PAN_CAMERA_VELOCITY * index
        objects = []
        for spec in _PAN_OBJECTS:
            world_x = spec.start_world_x + spec.own_velocity * index
            x = world_x - camera_left
            jitter = rng.uniform(-POSITION_JITTER, POSITION_JITTER)
            box = _lane_box(x, spec.lane, jitter)
            objects.append(GroundTruthObject(gt_id=spec.gt_id, label=OBJECT_LABEL, box=box, visible=box.valid))

        def underlay(image: "np.ndarray", camera_left=camera_left) -> None:
            # World-fixed texture, added after this scenario was found unable
            # to test its own claim: it renders the scene translating because
            # the CAMERA moved, but on a flat field a global-motion estimator
            # has nothing to track, so `flow` returned IDENTITY and the row
            # read as "ego-motion compensation does not help" when in truth
            # the scenario never presented any ego-motion evidence to
            # estimate from.
            _draw_world_texture(image, camera_left)

        frames.append(_render_frame(index, objects, colors, underlay=underlay))
    return Sequence(
        name="pan", fps=DEFAULT_FPS, width=FRAME_WIDTH, height=FRAME_HEIGHT, frames=tuple(frames), primary_gt_id=1
    )


# -- scenario: dropout ---------------------------------------------------------

DROPOUT_FRAME_COUNT = 50
DROPOUT_LANE = 0.5
DROPOUT_TRAVEL_START = 0.05
DROPOUT_TRAVEL_END = 0.80


def dropout(seed: int = DEFAULT_SEED) -> Sequence:
    """One object, always physically visible, crossing at constant velocity
    -- geometrically identical to a single `linear` lane. The failure mode
    this scenario isolates lives entirely in `replay.py`'s
    `DetectorNoiseConfig.dropout_probability`, never in this ground truth
    (see the module docstring's `dropout` bullet and `GroundTruthObject`)."""
    rng = Random(seed)
    color = _OBJECT_COLORS[0]
    velocity = (DROPOUT_TRAVEL_END - DROPOUT_TRAVEL_START) / (DROPOUT_FRAME_COUNT - 1)

    frames = []
    for index in range(DROPOUT_FRAME_COUNT):
        x = DROPOUT_TRAVEL_START + velocity * index
        jitter = rng.uniform(-POSITION_JITTER, POSITION_JITTER)
        box = _lane_box(x, DROPOUT_LANE, jitter)
        objects = (GroundTruthObject(gt_id=1, label=OBJECT_LABEL, box=box, visible=True),)
        frames.append(_render_frame(index, objects, {1: color}))
    return Sequence(name="dropout", fps=DEFAULT_FPS, width=FRAME_WIDTH, height=FRAME_HEIGHT, frames=tuple(frames))


# -- scenario: pan_step -----------------------------------------------------
#
# The scenario that isolates what ego-motion compensation actually buys. Three
# things had to be true at once, and each rules out a simpler scenario:
#
#  * a CONSTANT pan does not discriminate -- constant-velocity prediction
#    absorbs it, because the track learns the apparent velocity while it can
#    still see the object. So the camera is STILL while the target is visible
#    and starts panning exactly when the target disappears: the velocity the
#    track learned is now stale.
#  * an occlusion alone does not discriminate -- a single-object tracker
#    follows PIXELS, and with the target hidden LK happily tracks whatever
#    texture is in its window, which on a world-fixed background moves exactly
#    like a world-fixed target. So the occluder is a UNIFORM bar: LK loses its
#    corners and stalls, leaving the session coasting on prediction alone.
#  * a lone target does not discriminate either -- re-acquisition falls back to
#    the nearest box, and with one detection on screen that is trivially the
#    right one. So there is a DISTRACTOR, world-placed so that it sits exactly
#    where an UNCOMPENSATED prediction points when the target reappears.
#
# The result is a fork with a visibly different outcome: uncompensated, the
# re-anchor takes the distractor and the operator's id follows the wrong
# object; compensated, it takes the target. REVIEW findings A1/A2.

PAN_STEP_FRAME_COUNT = 90
PAN_STEP_STATIC_FRAMES = 30          # camera still; the track learns velocity 0
PAN_STEP_GAP_FRAMES = 30             # both objects hidden, camera panning
PAN_STEP_CAMERA_VELOCITY = 0.005     # frame-widths per frame, once it starts
PAN_STEP_TARGET_WORLD_X = 0.62
PAN_STEP_LANE = 0.55
# The distractor sits exactly one pan-displacement to the right of the target,
# so when both reappear it occupies the image position the target ITSELF
# occupied before the pan -- which is precisely where a prediction that does
# not know the camera moved still points.
PAN_STEP_DISTRACTOR_OFFSET = PAN_STEP_CAMERA_VELOCITY * PAN_STEP_GAP_FRAMES


def pan_step(seed: int = DEFAULT_SEED) -> Sequence:
    """A world-static target and a world-static distractor, both hidden behind
    a uniform bar exactly as the camera starts to pan -- so the learned
    velocity is stale, the visual tracker has nothing of the target to hold,
    and the distractor is waiting at the stale prediction's position when the
    bar clears."""
    rng = Random(seed)
    colors = {1: _OBJECT_COLORS[0], 2: _OBJECT_COLORS[1]}
    gap_start = PAN_STEP_STATIC_FRAMES
    gap_end = gap_start + PAN_STEP_GAP_FRAMES  # exclusive
    distractor_world_x = PAN_STEP_TARGET_WORLD_X + PAN_STEP_DISTRACTOR_OFFSET

    def camera_left_at(index: int) -> float:
        return PAN_STEP_CAMERA_VELOCITY * max(0, index - gap_start)

    # The bar only has to deny LK any texture belonging to either object while
    # they are hidden, so it spans both objects' whole image excursion across
    # the gap. Visibility itself is carried by the ground truth, not inferred
    # from the pixels.
    hidden_positions = [
        world - camera_left_at(index)
        for index in range(gap_start, gap_end)
        for world in (PAN_STEP_TARGET_WORLD_X, distractor_world_x)
    ]
    bar_left = min(hidden_positions) - _BAR_MARGIN
    bar_right = max(hidden_positions) + OBJECT_WIDTH_FRACTION + _BAR_MARGIN

    frames = []
    for index in range(PAN_STEP_FRAME_COUNT):
        camera_left = camera_left_at(index)
        hidden = gap_start <= index < gap_end

        def underlay(image: "np.ndarray", camera_left=camera_left, hidden=hidden) -> None:
            _draw_world_texture(image, camera_left)
            if hidden:
                _draw_bar(image, bar_left, bar_right, OCCLUSION_BAR_COLOR)

        objects = tuple(
            GroundTruthObject(
                gt_id=gt_id,
                label=OBJECT_LABEL,
                box=_lane_box(
                    world - camera_left,
                    PAN_STEP_LANE,
                    rng.uniform(-POSITION_JITTER, POSITION_JITTER),
                ),
                visible=not hidden,
            )
            for gt_id, world in ((1, PAN_STEP_TARGET_WORLD_X), (2, distractor_world_x))
        )
        frames.append(_render_frame(index, objects, colors, underlay=underlay))
    return Sequence(
        name="pan_step",
        fps=DEFAULT_FPS,
        width=FRAME_WIDTH,
        height=FRAME_HEIGHT,
        frames=tuple(frames),
        primary_gt_id=1,
    )


# -- scenario: long_occlusion -----------------------------------------------
#
# The scenario that tests MEMORY rather than retention. `occlusion`'s gap is
# short enough that the track never leaves `TrackBook` -- it ages, it coasts,
# and the same entry is still sitting there when the object returns, so the id
# survives without anything having had to remember it. That is a real and
# useful behaviour, but it is not re-acquisition, and a scenario that cannot
# tell the two apart cannot show whether an object memory works.
#
# So the gap here is deliberately longer than the book's own retention (the
# LOST window times its retention multiplier) and shorter than the dormant
# gallery's TTL: the track is genuinely gone by the time the object returns,
# and the only thing that can hand back its id is something that remembered
# it after it was deleted.

LONG_OCCLUSION_FRAME_COUNT = 150
LONG_OCCLUSION_GAP_START = 40
LONG_OCCLUSION_GAP_FRAMES = 90
LONG_OCCLUSION_LANE = 0.5
LONG_OCCLUSION_TRAVEL_START = 0.10
LONG_OCCLUSION_SPEED = 0.004


def long_occlusion(seed: int = DEFAULT_SEED) -> Sequence:
    """One object crossing at constant velocity, hidden for long enough that
    its track expires from the book entirely, then reappearing -- so keeping
    its id requires having remembered it, not merely having kept it."""
    rng = Random(seed)
    color = _OBJECT_COLORS[0]
    gap_end = LONG_OCCLUSION_GAP_START + LONG_OCCLUSION_GAP_FRAMES  # exclusive

    frames = []
    for index in range(LONG_OCCLUSION_FRAME_COUNT):
        hidden = LONG_OCCLUSION_GAP_START <= index < gap_end
        x = LONG_OCCLUSION_TRAVEL_START + LONG_OCCLUSION_SPEED * index
        jitter = rng.uniform(-POSITION_JITTER, POSITION_JITTER)
        box = _lane_box(x, LONG_OCCLUSION_LANE, jitter)

        def underlay(image: "np.ndarray", hidden=hidden) -> None:
            if hidden:
                _draw_bar(image, 0.0, 1.0, OCCLUSION_BAR_COLOR)

        objects = (
            GroundTruthObject(gt_id=1, label=OBJECT_LABEL, box=box, visible=not hidden),
        )
        frames.append(_render_frame(index, objects, {1: color}, underlay=underlay))
    return Sequence(
        name="long_occlusion",
        fps=DEFAULT_FPS,
        width=FRAME_WIDTH,
        height=FRAME_HEIGHT,
        frames=tuple(frames),
        primary_gt_id=1,
    )


# -- scenario: clutter ------------------------------------------------------
#
# The scenario that decides whether `cost` may be the default. Every other
# scenario has one to four objects, and a globally-optimal assignment is
# trivially right when there is almost nothing to confuse it with. The risk
# `cost` carries that `bytetrack` does not is precisely a CROWD: a solver that
# minimises total cost can buy a cheap overall assignment out of individually
# absurd pairs, and a permissive IoU gate is what lets it. Ten objects at
# close spacing, several sharing a colour, is where that shows up if it is
# going to.

CLUTTER_FRAME_COUNT = 60
CLUTTER_OBJECT_COUNT = 10
CLUTTER_COLUMNS = 5
CLUTTER_COLUMN_PITCH = 0.17
CLUTTER_ROW_PITCH = 0.22
CLUTTER_MARGIN = 0.06
# Alternating directions, so neighbours converge and cross rather than
# travelling in convoy -- a crowd moving in parallel is not a hard problem.
CLUTTER_SPEED = 0.006


def clutter(seed: int = DEFAULT_SEED) -> Sequence:
    """Ten similarly-sized objects at close spacing, half of them sharing a
    colour with a neighbour, moving in alternating directions so they
    repeatedly converge and separate."""
    rng = Random(seed)
    colors = {
        gt_id: _OBJECT_COLORS[(gt_id - 1) % len(_OBJECT_COLORS)]
        for gt_id in range(1, CLUTTER_OBJECT_COUNT + 1)
    }

    frames = []
    for index in range(CLUTTER_FRAME_COUNT):
        objects = []
        for gt_id in range(1, CLUTTER_OBJECT_COUNT + 1):
            slot = gt_id - 1
            column, row = slot % CLUTTER_COLUMNS, slot // CLUTTER_COLUMNS
            direction = 1.0 if slot % 2 == 0 else -1.0
            x = CLUTTER_MARGIN + column * CLUTTER_COLUMN_PITCH + direction * CLUTTER_SPEED * index
            lane = CLUTTER_MARGIN + CLUTTER_ROW_PITCH * (row + 1)
            jitter = rng.uniform(-POSITION_JITTER, POSITION_JITTER)
            box = _lane_box(x, lane, jitter)
            objects.append(
                GroundTruthObject(gt_id=gt_id, label=OBJECT_LABEL, box=box, visible=box.valid)
            )
        frames.append(_render_frame(index, tuple(objects), colors))
    return Sequence(
        name="clutter",
        fps=DEFAULT_FPS,
        width=FRAME_WIDTH,
        height=FRAME_HEIGHT,
        frames=tuple(frames),
        primary_gt_id=1,
    )


# -- scenario: crowd_recall -------------------------------------------------
#
# The adversarial test for the dormant gallery, and the one `clutter` cannot
# perform: `clutter` is short enough that no track ever reaches expiry, so its
# gallery is never populated and its clean numbers say nothing about memory.
#
# A false recovery is worse than a missed one -- it puts the operator's
# attention on the wrong object while telling them it is the right one -- so
# what has to be tested is not "can it recover" but "can it be made to recover
# WRONGLY". Hence three PAIRS of objects that share a colour: appearance alone
# cannot tell a pair apart, and a gallery matching on appearance would have a
# coin-flip between them. Every object then vanishes for long enough that its
# track is deleted, and returns having continued its own motion -- so the only
# thing separating the correct identity from its twin is where each one could
# plausibly have got to.
#
# A swap inside a pair shows up directly as IDSW.

CROWD_RECALL_FRAME_COUNT = 150
CROWD_RECALL_GAP_START = 40
CROWD_RECALL_GAP_FRAMES = 90
CROWD_RECALL_SPEED = 0.0015
CROWD_RECALL_LANES = (0.22, 0.50, 0.78)
CROWD_RECALL_START_X = (0.08, 0.50)


def crowd_recall(seed: int = DEFAULT_SEED) -> Sequence:
    """Three same-coloured pairs, all hidden long enough for their tracks to
    be deleted, all returning under their own continued motion -- so telling
    a twin from its partner needs more than appearance."""
    rng = Random(seed)
    gap_end = CROWD_RECALL_GAP_START + CROWD_RECALL_GAP_FRAMES  # exclusive
    specs = []
    for lane_index, lane in enumerate(CROWD_RECALL_LANES):
        for slot, start_x in enumerate(CROWD_RECALL_START_X):
            specs.append((len(specs) + 1, start_x, lane, lane_index))
    colors = {gt_id: _OBJECT_COLORS[lane_index % len(_OBJECT_COLORS)]
              for gt_id, _x, _lane, lane_index in specs}

    frames = []
    for index in range(CROWD_RECALL_FRAME_COUNT):
        hidden = CROWD_RECALL_GAP_START <= index < gap_end

        def underlay(image: "np.ndarray", hidden=hidden) -> None:
            if hidden:
                _draw_bar(image, 0.0, 1.0, OCCLUSION_BAR_COLOR)

        objects = tuple(
            GroundTruthObject(
                gt_id=gt_id,
                label=OBJECT_LABEL,
                box=_lane_box(
                    start_x + CROWD_RECALL_SPEED * index,
                    lane,
                    rng.uniform(-POSITION_JITTER, POSITION_JITTER),
                ),
                visible=not hidden,
            )
            for gt_id, start_x, lane, _lane_index in specs
        )
        frames.append(_render_frame(index, objects, colors, underlay=underlay))
    return Sequence(
        name="crowd_recall",
        fps=DEFAULT_FPS,
        width=FRAME_WIDTH,
        height=FRAME_HEIGHT,
        frames=tuple(frames),
        primary_gt_id=1,
    )


# -- scenario: small_target -------------------------------------------------
#
# Detection recall rather than identity: the review's other half, and the one
# every other scenario is silent about because they all use objects large
# enough that a detector never struggles with them.
#
# A drone watching a road from altitude is looking at objects a few dozen
# pixels across, and after the frame is downscaled to `imgsz` they are a
# handful. That is why they go undetected -- not noise, not occlusion, but
# apparent SIZE, which is exactly what `DetectorNoiseConfig.reliable_size`
# models and what a crop-and-re-detect pass around a predicted box is for.
# The object here is deliberately well under the reliable size, so a
# full-frame pass misses it most of the time and the track survives only if
# something asks a second, closer question.

SMALL_TARGET_FRAME_COUNT = 80
SMALL_TARGET_SIZE = 0.035
SMALL_TARGET_LANE = 0.5
SMALL_TARGET_TRAVEL_START = 0.10
SMALL_TARGET_SPEED = 0.008
# Below `SMALL_TARGET_SIZE`, so a full-frame pass is unreliable on it while a
# crop a few times the object's own size is not.
SMALL_TARGET_RELIABLE_SIZE = 0.10


def small_target(seed: int = DEFAULT_SEED) -> Sequence:
    """One object far below the detector's reliable size, crossing steadily
    -- a distant vehicle seen from altitude, where recall and not identity is
    what fails."""
    rng = Random(seed)
    color = _OBJECT_COLORS[0]
    frames = []
    for index in range(SMALL_TARGET_FRAME_COUNT):
        x = SMALL_TARGET_TRAVEL_START + SMALL_TARGET_SPEED * index
        jitter = rng.uniform(-POSITION_JITTER, POSITION_JITTER)
        box = Box(x, SMALL_TARGET_LANE + jitter, SMALL_TARGET_SIZE, SMALL_TARGET_SIZE)
        objects = (GroundTruthObject(gt_id=1, label=OBJECT_LABEL, box=box, visible=box.valid),)
        frames.append(_render_frame(index, objects, {1: color}))
    return Sequence(
        name="small_target",
        fps=DEFAULT_FPS,
        width=FRAME_WIDTH,
        height=FRAME_HEIGHT,
        frames=tuple(frames),
        primary_gt_id=1,
    )


# -- scenario: nonlinear -----------------------------------------------------
#
# The scenario that isolates what breaks CONSTANT-VELOCITY extrapolation
# itself, as opposed to `occlusion`'s "does anything extrapolate at all"
# question. `occlusion`'s object holds its pre-gap heading throughout the
# gap, so the constant-velocity assumption `predict.py` makes is exactly
# right by construction there -- which is exactly why wave C4's dormant
# gallery already resolves it perfectly (see this file's own `occlusion`,
# and `BASELINE.md`). Here the object REVERSES heading the instant it is
# hidden and never turns back, so the extrapolated box and the true
# reappearance position end up on OPPOSITE sides of where it was last
# confirmed -- precisely the failure TRACKING-V3-PLAN wave V3's ORU
# (`reupdate.py`) exists to correct, by re-deriving the gap from the two REAL
# observations that bracket it instead of trusting one stale pre-gap
# velocity all the way through.

NONLINEAR_FRAME_COUNT = 70
NONLINEAR_LANE = 0.5
NONLINEAR_START_X = 0.10
# Deliberately WITHIN `DEFAULT_TRACK_MAX_AGE_FRAMES` (30, `cv_service/
# config.py`) -- unlike `occlusion`, this scenario is not about whether a
# LOST track can be remembered (wave C4 already answers that), it is about
# whether the box a live, still-COASTING track predicts is anywhere near
# right. A gap that pushed the track to LOST would route re-anchoring
# through `memory.py`'s gallery instead of `predict.py`'s extrapolation --
# the wrong mechanism for what this scenario isolates.
NONLINEAR_GAP_START = 25
NONLINEAR_GAP_FRAMES = 20
NONLINEAR_SPEED = 0.004  # frame-widths/frame, same magnitude before and after the reversal
NONLINEAR_BAR_MARGIN_FRACTION = 0.15


def nonlinear(seed: int = DEFAULT_SEED) -> Sequence:
    """One object travels right, then reverses to travel left at the SAME
    speed the instant it is hidden -- so constant-velocity extrapolation from
    the pre-gap heading points one way while the object is actually headed
    the other, and the two diverge for the whole gap (REVIEW finding C1,
    sharpened: not "does a prediction exist", but "is the model that
    produces it still valid")."""
    rng = Random(seed)
    color = _OBJECT_COLORS[0]
    gap_end = NONLINEAR_GAP_START + NONLINEAR_GAP_FRAMES  # exclusive
    apex_x = NONLINEAR_START_X + NONLINEAR_SPEED * NONLINEAR_GAP_START

    def x_at(index: int) -> float:
        if index < NONLINEAR_GAP_START:
            return NONLINEAR_START_X + NONLINEAR_SPEED * index
        # One reversal, exactly at the moment visibility is lost -- both
        # during the gap and after re-emerging, the object keeps heading the
        # OPPOSITE way from before, never turning a third time.
        return apex_x - NONLINEAR_SPEED * (index - NONLINEAR_GAP_START)

    hidden_positions = [x_at(index) for index in range(NONLINEAR_GAP_START, gap_end)]
    margin = OBJECT_WIDTH_FRACTION * NONLINEAR_BAR_MARGIN_FRACTION
    bar_left = min(hidden_positions) - margin
    bar_right = max(hidden_positions) + OBJECT_WIDTH_FRACTION + margin

    def underlay(image: "np.ndarray") -> None:
        _draw_bar(image, bar_left, bar_right, OCCLUSION_BAR_COLOR)

    frames = []
    for index in range(NONLINEAR_FRAME_COUNT):
        hidden = NONLINEAR_GAP_START <= index < gap_end
        jitter = rng.uniform(-POSITION_JITTER, POSITION_JITTER)
        box = _lane_box(x_at(index), NONLINEAR_LANE, jitter)
        objects = (GroundTruthObject(gt_id=1, label=OBJECT_LABEL, box=box, visible=not hidden),)
        frames.append(_render_frame(index, objects, {1: color}, underlay=underlay))
    return Sequence(
        name="nonlinear",
        fps=DEFAULT_FPS,
        width=FRAME_WIDTH,
        height=FRAME_HEIGHT,
        frames=tuple(frames),
        primary_gt_id=1,
    )


# -- scenario: tiny_fast -----------------------------------------------------
#
# Small targets, fast ego-motion -- `docs/conclusions/CV-RATE-BUDGET.md` §2's
# own numbers: at 200 m a 15 m/s vehicle contributes ~4.2 px/frame of its OWN
# motion while 30 deg/s of camera yaw contributes ~32 px/frame, a ratio
# dominated by the CAMERA, not the vehicle. `pan` already proves ego-motion
# compensation works at a modest, steady pan rate on normally-sized objects
# (`BASELINE.md`: MT 4/4). This scenario asks the harder question `pan`
# cannot: does that same compensation still leave enough of a TINY box's own
# extent uncovered by whatever residual estimation error it does not correct
# exactly? A box a "handful of pixels" wide has almost no IoU budget left to
# spend on that residual, so the same few-pixel wobble that `pan`'s
# `OBJECT_WIDTH_FRACTION`-sized landmarks absorb for free can cost this
# scenario the whole match.
#
# Structurally this is `pan`'s own multi-landmark shape (several world-fixed
# objects the camera sweeps past in turn), not a single object under an
# oscillating pan -- an earlier version tried that and found LK's own
# `_MIN_TRACKED_CORNERS` floor (`engines/lk.py`) simply cannot init on a box
# this small regardless of how it moves, which tests "does the box exist at
# all", not "does fast ego-motion break a tiny box's association". Multiple
# landmarks also give ASSOCIATE something `nonlinear`/`pan_occlusion` structurally
# cannot: with only one object, one candidate and one target are always
# force-matched regardless of geometric quality (nothing else to prefer), so
# a single-object scenario can never show an id switch under `cost` no matter
# how bad the drift is. Three competing candidates can.

TINY_FAST_FRAME_COUNT = 70
# ~8 x 6 px on this harness's 320x240 canvas -- "a handful of pixels", and
# measured (see the scenario block comment above) to be BELOW LK's own
# minimum trackable size, which is deliberate: FOLLOW's total loss here is
# as real a finding as ASSOCIATE's id switches, not a harness artifact.
TINY_FAST_SIZE = 0.025
TINY_FAST_LANE = 0.5
# Steady, not oscillating (see the block comment) -- `pan`'s own camera
# velocity (0.035, ~11 px/frame) doubled to ~19 px/frame, in CV-RATE-BUDGET's
# own ballpark for a fast yaw. Measured while tuning this baseline: IDSW
# stays 2 across the whole 0.055-0.07 neighbourhood of this value, so this is
# a robust choice, not a knife-edge one (the same bar `crossing`'s own jitter
# constant was held to).
TINY_FAST_CAMERA_VELOCITY = 0.06
_TINY_FAST_LANDMARKS: tuple[tuple[int, float], ...] = (
    # (gt_id, start_world_x) -- world-STATIC, same convention as `pan`'s
    # first three objects, spread so each is visible in turn as the camera
    # sweeps rather than all three competing on-screen at once.
    (1, 0.30),
    (2, 0.85),
    (3, 1.40),
)


def tiny_fast(seed: int = DEFAULT_SEED) -> Sequence:
    """Three handful-of-pixels, world-static landmarks under a FAST, steady
    pan -- isolates whether a tiny box's own extent survives whatever
    residual error ego-motion compensation leaves uncorrected, and gives
    ASSOCIATE competing candidates to confuse, which a single tiny object
    structurally cannot."""
    rng = Random(seed)
    colors = {gt_id: _OBJECT_COLORS[(gt_id - 1) % len(_OBJECT_COLORS)] for gt_id, _world_x in _TINY_FAST_LANDMARKS}

    frames = []
    for index in range(TINY_FAST_FRAME_COUNT):
        camera_left = TINY_FAST_CAMERA_VELOCITY * index
        objects = []
        for gt_id, start_world_x in _TINY_FAST_LANDMARKS:
            x = start_world_x - camera_left
            jitter = rng.uniform(-POSITION_JITTER, POSITION_JITTER)
            box = Box(x, TINY_FAST_LANE - TINY_FAST_SIZE / 2.0 + jitter, TINY_FAST_SIZE, TINY_FAST_SIZE)
            objects.append(GroundTruthObject(gt_id=gt_id, label=OBJECT_LABEL, box=box, visible=box.valid))

        def underlay(image: "np.ndarray", camera_left=camera_left) -> None:
            _draw_world_texture(image, camera_left)

        frames.append(_render_frame(index, objects, colors, underlay=underlay))
    return Sequence(
        name="tiny_fast",
        fps=DEFAULT_FPS,
        width=FRAME_WIDTH,
        height=FRAME_HEIGHT,
        frames=tuple(frames),
        primary_gt_id=1,
    )


# -- scenario: pan_occlusion -------------------------------------------------
#
# Ego-motion AND occlusion at the same time. Today `pan` and `occlusion` are
# separate scenarios and each is survivable alone: `pan`'s object is never
# hidden, so every frame's fresh detection corrects whatever the previous
# frame's ego-motion warp got slightly wrong before the error can compound;
# `occlusion`'s camera never moves, so the constant-velocity coast through
# the gap has nothing but the object's own (unchanging) heading to get
# wrong. Superposing them removes both safety nets at once: for the whole
# occlusion window there is no fresh detection to correct a warp, AND the
# warp itself is being applied every single one of those frames
# (`TrackBook.warp()`, TRACKING-V2-PLAN wave C2) -- so whatever residual
# per-frame compensation error exists gets a gap-length chance to compound
# before anything checks it, rather than the one-frame chance `pan` alone
# ever gives it.

PAN_OCCLUSION_FRAME_COUNT = 90
# A world-fixed target -- own_velocity 0, same convention as `pan`'s first
# three landmarks -- so every bit of on-screen motion is the CAMERA's,
# isolating the ego-motion/occlusion interaction from a third variable.
PAN_OCCLUSION_TARGET_WORLD_X = 0.5
PAN_OCCLUSION_LANE = 0.5
PAN_OCCLUSION_CAMERA_VELOCITY = 0.02
# Within `DEFAULT_TRACK_MAX_AGE_FRAMES` (30) for the same reason
# `nonlinear`'s gap is -- this scenario is about whether a still-COASTING
# track's ego-motion-warped prediction stays right, not about dormant-gallery
# recovery after LOST.
PAN_OCCLUSION_GAP_START = 30
PAN_OCCLUSION_GAP_FRAMES = 25


def pan_occlusion(seed: int = DEFAULT_SEED) -> Sequence:
    """A world-static target, swept across frame by a continuous camera pan,
    hidden behind an image-fixed bar for a genuine occlusion window in the
    middle of that pan -- ego-motion compensation and gap-coasting stacked,
    rather than exercised one at a time."""
    rng = Random(seed)
    color = _OBJECT_COLORS[0]
    gap_end = PAN_OCCLUSION_GAP_START + PAN_OCCLUSION_GAP_FRAMES  # exclusive

    def camera_left_at(index: int) -> float:
        return PAN_OCCLUSION_CAMERA_VELOCITY * index

    def x_at(index: int) -> float:
        return PAN_OCCLUSION_TARGET_WORLD_X - camera_left_at(index)

    hidden_positions = [x_at(index) for index in range(PAN_OCCLUSION_GAP_START, gap_end)]
    bar_left = min(hidden_positions) - _BAR_MARGIN
    bar_right = max(hidden_positions) + OBJECT_WIDTH_FRACTION + _BAR_MARGIN

    frames = []
    for index in range(PAN_OCCLUSION_FRAME_COUNT):
        camera_left = camera_left_at(index)
        hidden = PAN_OCCLUSION_GAP_START <= index < gap_end
        jitter = rng.uniform(-POSITION_JITTER, POSITION_JITTER)
        box = _lane_box(x_at(index), PAN_OCCLUSION_LANE, jitter)
        objects = (GroundTruthObject(gt_id=1, label=OBJECT_LABEL, box=box, visible=not hidden),)

        def underlay(image: "np.ndarray", camera_left=camera_left, hidden=hidden) -> None:
            _draw_world_texture(image, camera_left)
            if hidden:
                _draw_bar(image, bar_left, bar_right, OCCLUSION_BAR_COLOR)

        frames.append(_render_frame(index, objects, {1: color}, underlay=underlay))
    return Sequence(
        name="pan_occlusion",
        fps=DEFAULT_FPS,
        width=FRAME_WIDTH,
        height=FRAME_HEIGHT,
        frames=tuple(frames),
        primary_gt_id=1,
    )


# -- scenario: latency -------------------------------------------------------
#
# Detector results delivered N frames late -- the detection landing on frame
# `i` describes where the object was on frame `i - N`, not where it is now.
# Wave V6's target (TRACKING-V3-PLAN §4.5), and the normal case for an
# offboard detector (§5.2, "L1 RELAY"): the box travels a network hop and a
# detector pass before it comes back, and applying it to "now" is a
# systematic lag bias, not noise. Geometrically identical to a single
# `linear` lane -- exactly like `dropout`, the failure mode lives entirely in
# `replay.py`'s `DetectorNoiseConfig.latency_frames`, never in this ground
# truth (see `__main__.py`'s `DEFAULT_NOISE_BY_SCENARIO`).

LATENCY_FRAME_COUNT = 60
LATENCY_LANE = 0.5
LATENCY_TRAVEL_START = 0.05
LATENCY_TRAVEL_END = 0.85


def latency(seed: int = DEFAULT_SEED) -> Sequence:
    """One object at constant velocity -- the ground truth a `linear` lane
    would also produce. The lag is injected by the detector, not the scene;
    see the module comment above."""
    rng = Random(seed)
    color = _OBJECT_COLORS[0]
    velocity = (LATENCY_TRAVEL_END - LATENCY_TRAVEL_START) / (LATENCY_FRAME_COUNT - 1)

    frames = []
    for index in range(LATENCY_FRAME_COUNT):
        x = LATENCY_TRAVEL_START + velocity * index
        jitter = rng.uniform(-POSITION_JITTER, POSITION_JITTER)
        box = _lane_box(x, LATENCY_LANE, jitter)
        objects = (GroundTruthObject(gt_id=1, label=OBJECT_LABEL, box=box, visible=True),)
        frames.append(_render_frame(index, objects, {1: color}))
    return Sequence(
        name="latency",
        fps=DEFAULT_FPS,
        width=FRAME_WIDTH,
        height=FRAME_HEIGHT,
        frames=tuple(frames),
        primary_gt_id=1,
    )


# -- scenario: crossing_similar ----------------------------------------------
#
# `crossing`'s own failure mode (REVIEW finding B1), sharpened along the axis
# `engines/histogram.py` (wave C3) was built to close: there, the two objects
# have DIFFERENT colours, and a small amount of realistic detector jitter is
# enough to swap their ids on pure geometry (`BASELINE.md`'s pre-C1 story) --
# but appearance now tells them apart, and `crossing`'s current baseline is
# clean. Here the two objects are the SAME colour, so the histogram distance
# between them is near zero regardless of which is which: appearance
# contributes nothing to the decision, same as before C3 existed. They also
# both go invisible for a few frames centred on the crossing point -- both
# COAST through the moment geometry is most ambiguous, rather than staying
# continuously detector-confirmed across it the way `crossing` does.

CROSSING_SIMILAR_FRAME_COUNT = 50
CROSSING_SIMILAR_LANE = 0.5
CROSSING_SIMILAR_GAP_FRAMES = 6


def crossing_similar(seed: int = DEFAULT_SEED) -> Sequence:
    """Two identically-coloured objects crossing paths, both hidden for a
    brief window centred on the crossing point -- neither colour nor
    geometry-at-the-moment-of-crossing is available to disambiguate them."""
    rng = Random(seed)
    colors = {1: _OBJECT_COLORS[0], 2: _OBJECT_COLORS[0]}  # SAME colour -- appearance is uninformative
    left_start = CROSSING_TRAVEL_MARGIN
    left_end = 1.0 - OBJECT_WIDTH_FRACTION - CROSSING_TRAVEL_MARGIN
    midpoint = (CROSSING_SIMILAR_FRAME_COUNT - 1) / 2.0
    gap_start = int(round(midpoint - CROSSING_SIMILAR_GAP_FRAMES / 2.0))
    gap_end = gap_start + CROSSING_SIMILAR_GAP_FRAMES  # exclusive

    frames = []
    for index in range(CROSSING_SIMILAR_FRAME_COUNT):
        t = index / (CROSSING_SIMILAR_FRAME_COUNT - 1)
        x1 = left_start + (left_end - left_start) * t
        x2 = left_end - (left_end - left_start) * t
        jitter1 = rng.uniform(-POSITION_JITTER, POSITION_JITTER)
        jitter2 = rng.uniform(-POSITION_JITTER, POSITION_JITTER)
        hidden = gap_start <= index < gap_end
        objects = (
            GroundTruthObject(
                gt_id=1, label=OBJECT_LABEL, box=_lane_box(x1, CROSSING_SIMILAR_LANE, jitter1), visible=not hidden
            ),
            GroundTruthObject(
                gt_id=2, label=OBJECT_LABEL, box=_lane_box(x2, CROSSING_SIMILAR_LANE, jitter2), visible=not hidden
            ),
        )
        frames.append(_render_frame(index, objects, colors))
    return Sequence(
        name="crossing_similar",
        fps=DEFAULT_FPS,
        width=FRAME_WIDTH,
        height=FRAME_HEIGHT,
        frames=tuple(frames),
        primary_gt_id=1,
    )


SCENARIOS: dict[str, Callable[[int], Sequence]] = {
    "linear": linear,
    "occlusion": occlusion,
    "crossing": crossing,
    "pan": pan,
    "dropout": dropout,
    "pan_step": pan_step,
    "clutter": clutter,
    "long_occlusion": long_occlusion,
    "crowd_recall": crowd_recall,
    "small_target": small_target,
    "nonlinear": nonlinear,
    "tiny_fast": tiny_fast,
    "pan_occlusion": pan_occlusion,
    "latency": latency,
    "crossing_similar": crossing_similar,
}
