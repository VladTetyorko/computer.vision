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

from dataclasses import dataclass
from typing import TYPE_CHECKING, Protocol, Sequence

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
