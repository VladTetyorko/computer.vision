"""The two tracker-engine protocols, and the vocabulary they speak.

`docs/extracts/TRACKING-ORCHESTRATION.md` §2.2 (the interface-segregation fix),
`docs/plans/done/TRACKING-PLAN.md` §5.A.

**Two protocols, not one.** The two modes need genuinely different things:
`ASSOCIATE` hands N boxes to something that assigns ids and never looks at a
pixel; `FOLLOW` hands one frame to something that moves one box and never
sees a detection. `bytetrack` can not implement `update(frame)` and `lk` can
not implement `associate(detections)` -- a single `TrackerEngine` protocol
would force every engine to stub half of itself, on the exact seam the whole
extensibility story rests on. `GET /api/cv/trackers` already returns a
`modes: []` array per engine (TRACKING-PLAN §4.F): the wire anticipated this
before the protocol did. An engine that legitimately serves both modes
implements both protocols; nothing forces it to.

Pure stdlib at runtime. `np.ndarray` and the detector's `Detection` appear
only in annotations, which `from __future__ import annotations` keeps as
strings -- so importing this module (and therefore `track.py`, `lock.py` and
`session.py`, which share its `Box`/`Observation` vocabulary) never pulls in
`cv2`/`numpy`.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import TYPE_CHECKING, Optional, Protocol, Sequence

if TYPE_CHECKING:  # pragma: no cover - typing only
    import numpy as np

    from cv_service.inference.detector import Detection

# Source names match `cv_pb2.DetectionSource`'s enum VALUE NAMES one-for-one
# (see the package docstring for why enum values cross this boundary as
# strings).
SOURCE_DETECTOR = "DETECTION_SOURCE_DETECTOR"
SOURCE_TRACKER = "DETECTION_SOURCE_TRACKER"


@dataclass(frozen=True)
class Box:
    """A normalized bounding box: origin top-left, `[0, 1]`, same units as
    `cv_pb2.BoundingBox` and `cv_service.inference.detector.Detection`.

    Normalized rather than pixel geometry because that is what crosses the
    wire, and because it is the least cv-service can emit that every sink
    (UI, geolocation, a future centering controller) can use with only its
    own local knowledge -- TRACKING-PLAN §3.4.2 / §5.K.
    """

    x: float
    y: float
    width: float
    height: float

    @property
    def center(self) -> tuple[float, float]:
        return self.x + self.width / 2.0, self.y + self.height / 2.0

    @property
    def valid(self) -> bool:
        """False when the box has collapsed or left the frame entirely.

        This is scheduler trigger (d) of TRACKING-PLAN §3.1, and it is
        deliberately threshold-free: "collapsed" means non-positive extent
        and "left the frame" means zero overlap with the unit square. A
        tunable minimum area would be configuration, and configuration lives
        in `TrackingParams`, not in a geometry type.
        """
        if not (self.width > 0.0 and self.height > 0.0):
            return False
        return self.x < 1.0 and self.y < 1.0 and self.x + self.width > 0.0 and self.y + self.height > 0.0

    def iou(self, other: "Box") -> float:
        """Intersection-over-union with `other`; 0.0 when either is degenerate."""
        if not (self.width > 0.0 and self.height > 0.0 and other.width > 0.0 and other.height > 0.0):
            return 0.0
        left = max(self.x, other.x)
        top = max(self.y, other.y)
        right = min(self.x + self.width, other.x + other.width)
        bottom = min(self.y + self.height, other.y + other.height)
        if right <= left or bottom <= top:
            return 0.0
        intersection = (right - left) * (bottom - top)
        union = self.width * self.height + other.width * other.height - intersection
        return intersection / union if union > 0.0 else 0.0

    def contains(self, x: float, y: float) -> bool:
        return self.x <= x <= self.x + self.width and self.y <= y <= self.y + self.height


@dataclass(frozen=True)
class Observation:
    """One object seen on one frame, as an engine reports it.

    `key` is the engine's own identity token for this object -- an opaque
    value the `TrackBook` maps to a stable per-stream track id. It is
    deliberately NOT the id that reaches the wire: an engine reset (see
    TRACKING-ORCHESTRATION §3.4's degradation rule) would otherwise restart
    the engine's own numbering and silently re-use a track id inside one
    stream's life, which TRACKING-PLAN §3.2 forbids.

    `det_index` is the index of the detection this observation came from, so
    the session can put track facts back on the right box without matching
    geometry a second time. `-1` means the observation was synthesized by a
    tracker-only frame and has no detector box behind it.

    `predicted` means this box is the system's OWN extrapolation rather than
    evidence -- what a coasting track emits when the visual tracker has
    stalled and there is nothing left but the motion model. It is never set
    by an engine, only by the session, and it exists because feeding a
    prediction back as if it were a measurement makes the motion model
    self-confirming: `_observe` would re-derive from the predicted box
    exactly the velocity it had just used to produce it, so a velocity that
    came from nothing but measurement noise could never decay or be
    corrected. Measured, it drifts a static target by a fifth of its own
    height across one occlusion -- enough to fail the re-anchor on its own.

    `authoritative` means "this box is ground truth for its identity", which
    skips the `min_hits` anti-flicker gate. **Engines never set it** -- it
    exists for FOLLOW's operator-chosen lock, where TRACKING-PLAN §3.1 says a
    re-anchor makes the track `CONFIRMED` outright. Making a locked target
    serve three verify passes (six seconds at the default cadence) before it
    stops rendering as tentative would be the gate misapplied: the gate
    exists to stop a one-frame *false positive* from earning an id, and an
    operator clicking a box is not a false positive.
    """

    key: object
    box: Box
    label: str
    confidence: float
    source: str = SOURCE_DETECTOR
    det_index: int = -1
    authoritative: bool = False
    predicted: bool = False


@dataclass(frozen=True)
class TrackerUpdate:
    """What a `SingleObjectTracker` produced for one frame.

    `confidence` is the engine's own belief in this update (an NCC score, a
    ratio of surviving optical-flow points) on `[0, 1]`, NOT a detector
    class confidence. `None` from `update()` means lost; a low `confidence`
    here means "still tracking, but shaky" -- which is scheduler trigger (b)
    of TRACKING-PLAN §3.1.
    """

    box: Box
    confidence: float


class Associator(Protocol):
    """ASSOCIATE -- many objects, no pixels.

    Assigns a stable identity to each of a frame's detections. Never sees a
    frame: everything it needs is in the boxes.
    """

    engine_id: str

    def associate(self, detections: Sequence["Detection"], now: float) -> list[Observation]:
        """Associate this frame's detections to ongoing identities.

        `now` is a monotonic seconds timestamp. Returns one `Observation`
        per detection the engine actually identified -- possibly fewer than
        it was given (an engine may withhold a detection it considers too
        weak); anything omitted is reported untracked by the session.
        """
        ...

    def reset(self) -> None:
        """Drop all state. Called after the engine raises, per the degradation rule."""
        ...


class SingleObjectTracker(Protocol):
    """FOLLOW -- one object, needs pixels."""

    engine_id: str

    def init(self, frame: "np.ndarray", box: Box) -> bool:
        """Anchor on `box` in `frame`. False when the box is untrackable
        (too small, featureless) -- the session treats that as a failed
        lock rather than an exception."""
        ...

    def update(self, frame: "np.ndarray") -> TrackerUpdate | None:
        """Move the box onto `frame`. `None` means lost."""
        ...

    def reset(self) -> None:
        """Drop all state. Called after the engine raises, per the degradation rule."""
        ...


# -- ego-motion vocabulary (TRACKING-V2-PLAN §3.1) --------------------------

# Descriptor metric names. Each engine declares the metric its own values are
# meant to be compared under, so a descriptor and the way to compare it never
# travel separately -- adding an embedding engine later is a new constant plus
# one branch, not a change to everything that holds a descriptor.
METRIC_HELLINGER = "hellinger"  # non-negative, sums to 1 (a histogram)
METRIC_COSINE = "cosine"        # unit-norm vector (an embedding)


@dataclass(frozen=True)
class CameraPose:
    """Camera attitude at capture, the plain counterpart of `cv_pb2.CameraPose`.

    `known` gates the whole message: without a horizontal FOV an attitude
    delta cannot be turned into a pixel shift, so a pose-based compensator
    reports itself unavailable rather than inventing a scale. An all-default
    `CameraPose` is what a client that never learned to send one produces,
    and it is a normal state, not an error.
    """

    yaw_degrees: float = 0.0
    pitch_degrees: float = 0.0
    roll_degrees: float = 0.0
    hfov_degrees: float = 0.0
    vfov_degrees: float = 0.0
    timestamp_millis: int = 0

    @property
    def known(self) -> bool:
        return self.hfov_degrees > 0.0


@dataclass(frozen=True)
class Transform:
    """How the image moved between two frames, in normalized coordinates.

        x' = a*x + b*y + c
        y' = d*x + e*y + f

    **Direction is part of the contract:** a `Transform` maps a point in the
    PREVIOUS frame to where that same physical point appears in the CURRENT
    one. That is the direction a track's predicted box has to be warped in,
    and stating it here is what stops a compensator from silently returning
    the inverse -- a sign error no test of the compensator alone would catch,
    because both directions look equally plausible in isolation.

    `IDENTITY` means "no ego-motion, or none could be estimated". It is
    returned exactly, so `identity` is an exact comparison and this type
    needs no epsilon: a compensator that finds motion returns numbers, and
    one that finds none returns this constant.
    """

    a: float = 1.0
    b: float = 0.0
    c: float = 0.0
    d: float = 0.0
    e: float = 1.0
    f: float = 0.0

    @property
    def identity(self) -> bool:
        return (self.a, self.b, self.c, self.d, self.e, self.f) == (1.0, 0.0, 0.0, 0.0, 1.0, 0.0)

    def apply_point(self, x: float, y: float) -> tuple[float, float]:
        return (self.a * x + self.b * y + self.c, self.d * x + self.e * y + self.f)

    def apply_box(self, box: Box) -> Box:
        """Warp a box by transforming its four corners and re-axis-aligning.

        Corner-transform rather than centre-plus-size because roll and any
        shear term rotate the box: moving only the centre would keep a stale
        extent, and the extent is exactly what the IoU gate downstream is
        about to measure.
        """
        if self.identity:
            return box
        corners = (
            self.apply_point(box.x, box.y),
            self.apply_point(box.x + box.width, box.y),
            self.apply_point(box.x, box.y + box.height),
            self.apply_point(box.x + box.width, box.y + box.height),
        )
        xs = [point[0] for point in corners]
        ys = [point[1] for point in corners]
        left, right = min(xs), max(xs)
        top, bottom = min(ys), max(ys)
        return Box(left, top, right - left, bottom - top)

    def compose(self, later: "Transform") -> "Transform":
        """`later` applied after `self` -- accumulating motion across frames."""
        return Transform(
            a=later.a * self.a + later.b * self.d,
            b=later.a * self.b + later.b * self.e,
            c=later.a * self.c + later.b * self.f + later.c,
            d=later.d * self.a + later.e * self.d,
            e=later.d * self.b + later.e * self.e,
            f=later.d * self.c + later.e * self.f + later.f,
        )


IDENTITY = Transform()


@dataclass(frozen=True)
class Descriptor:
    """An appearance signature, with the metric it is meant to be compared under.

    `engine_id` guards every comparison: two descriptors produced by different
    engines are a max-distance no-match, never a silently meaningless number.
    That matters because the one thing a descriptor is ever used for is
    deciding whether to hand an operator back a track id they recognise.

    Pure stdlib and plain floats, deliberately: `ObjectMemory` compares these
    with no `cv` extra installed, and a plain tuple is serializable, which is
    what keeps a future cross-stream identity tier reachable (§1 D4).
    """

    engine_id: str
    values: tuple[float, ...]
    metric: str = METRIC_HELLINGER

    def distance(self, other: "Optional[Descriptor]") -> float:
        """Distance on [0, 1]; 1.0 means "no usable comparison" or "no match".

        Never raises and never returns a number outside the range: an absent
        descriptor, a different engine, a different metric and a length
        mismatch are all the same answer -- "this tells you nothing" -- which
        every gate downstream already treats as a rejection.
        """
        if other is None or other.engine_id != self.engine_id or other.metric != self.metric:
            return 1.0
        if len(other.values) != len(self.values) or not self.values:
            return 1.0
        if self.metric == METRIC_HELLINGER:
            coefficient = sum(
                math.sqrt(max(0.0, left) * max(0.0, right))
                for left, right in zip(self.values, other.values)
            )
            return math.sqrt(max(0.0, 1.0 - min(1.0, coefficient)))
        if self.metric == METRIC_COSINE:
            dot = sum(left * right for left, right in zip(self.values, other.values))
            left_norm = math.sqrt(sum(value * value for value in self.values))
            right_norm = math.sqrt(sum(value * value for value in other.values))
            if left_norm <= 0.0 or right_norm <= 0.0:
                return 1.0
            similarity = dot / (left_norm * right_norm)
            return max(0.0, min(1.0, (1.0 - similarity) / 2.0))
        return 1.0

    def blend(self, other: "Optional[Descriptor]", alpha: float) -> "Descriptor":
        """Exponential moving average toward `other`, renormalized for the metric.

        Renormalization is not cosmetic: an un-normalized blend drifts off the
        simplex (or off the unit sphere) and the metric stops being bounded,
        which would silently widen every gate that reads it.
        """
        if other is None or other.engine_id != self.engine_id or other.metric != self.metric:
            return self
        if len(other.values) != len(self.values) or not self.values:
            return self
        weight = max(0.0, min(1.0, alpha))
        blended = tuple(
            weight * left + (1.0 - weight) * right
            for left, right in zip(self.values, other.values)
        )
        return Descriptor(self.engine_id, _renormalized(blended, self.metric), self.metric)


def _renormalized(values: tuple[float, ...], metric: str) -> tuple[float, ...]:
    if metric == METRIC_HELLINGER:
        total = sum(max(0.0, value) for value in values)
        if total <= 0.0:
            return values
        return tuple(max(0.0, value) / total for value in values)
    if metric == METRIC_COSINE:
        norm = math.sqrt(sum(value * value for value in values))
        if norm <= 0.0:
            return values
        return tuple(value / norm for value in values)
    return values


class MotionCompensator(Protocol):
    """Estimates how the CAMERA moved between the previous frame and this one.

    A third protocol rather than a mode on an existing one, for the same
    reason `Associator` and `SingleObjectTracker` are separate: the two
    implementations that ship share no input. `flow` needs pixels and no
    telemetry; `pose` needs telemetry and no pixels -- and it is the second
    one that makes ego-motion compensation available on a build with no
    OpenCV at all.
    """

    engine_id: str

    def available(self, pose: CameraPose) -> bool:
        """Whether this compensator can produce anything for this stream.

        Checked before a frame is decoded, so a pose engine on a client that
        sends no pose costs nothing per frame instead of failing per frame.
        """
        ...

    def estimate(self, frame: "Optional[np.ndarray]", pose: CameraPose) -> Transform:
        """Previous frame -> current frame. `IDENTITY` when nothing is known.

        Never raises for an unusable input: a first frame, a featureless
        scene, or a pose that went backwards all return `IDENTITY`, which
        degrades the association to exactly today's behavior.
        """
        ...

    def reset(self) -> None:
        """Drop inter-frame state. Called after the engine raises."""
        ...


class AppearanceExtractor(Protocol):
    """Turns image regions into comparable signatures.

    Returns one entry per input box, positionally, with `None` for any box it
    could not describe (off-frame, sub-pixel). Callers must treat `None` as
    "no appearance evidence for this box" and fall back to geometry, never as
    an error -- that is what keeps appearance a *contribution* to the identity
    decision rather than a precondition for one.
    """

    engine_id: str

    def describe(
        self, frame: "np.ndarray", boxes: Sequence[Box]
    ) -> "list[Optional[Descriptor]]":
        ...

    def reset(self) -> None:
        ...
