"""Pure-stdlib mirror of `cv_pb2.ObjectState` -- the wire-level snapshot of
internal tracking state (CV-ORCHESTRATION wave W1, plan §4.5).

**What this is, and is not.** Every field here has the same name, the same
group, and the same unit as its `cv_pb2.ObjectState` counterpart, verified
against `proto/vision/v1/cv.proto` field-by-field. It is deliberately NOT a
wrapper around the generated protobuf type: this module imports nothing from
`cv_service.gen` and nothing from `grpc`, so `orchestration/mirror.py` (the
only writer of these dataclasses) and every test that exercises it can run
with no proto stubs generated at all. `grpc/servicers.py` is the ONE place
that ever converts an `ObjectState` into a `cv_pb2.ObjectState` message --
the same sole-touchpoint discipline `tests/tracking/test_registry.py`
already greps for on every other `cv_pb2` symbol in this package, now
extended to this one.

**Absent group vs. zero field (plan §4.5's own distinction, load-bearing).**
`None` on a group (`identity`, `kinematics`, `belief`, `provenance`,
`memory`, `lock`, `timing`) means "this configuration does not compute this
at all" -- e.g. `lock` is `None` for every object on a stream that has never
had a lock applied, not `Lock(locked=False, ...)`. A GROUP that IS present
with a zero-valued FIELD inside it (`Memory(recovered=False,
identity_confidence=0.0, ...)` for a live track the gallery was asked about
but did not recover) is a genuine, meaningful zero, not an absence. Losing
this distinction on the wire would make "nobody asked" and "asked and found
nothing" indistinguishable, which is exactly the honesty gap
`docs/plans/active/CV-ORCHESTRATION-PLAN.md`'s ledger work (W0) already
closed for the debug surface -- W1 extends the same discipline to the wire.

**Enum values cross this module's boundary as their own value-name
strings**, mirroring `track.py`'s `STATE_*` / `engines/base.py`'s `SOURCE_*`
convention exactly: `OBJECT_LIFECYCLE_CONFIRMED == cv_pb2.ObjectLifecycle.
Name(cv_pb2.OBJECT_LIFECYCLE_CONFIRMED)`, verbatim, so `grpc/servicers.py`'s
mapping is a `cv_pb2.ObjectLifecycle.Value(state.lifecycle)` call and
nothing else -- no lookup table anywhere to drift out of sync with the
`.proto` file.

**Boxes** (`Kinematics.box`/`.detector_box`/`.tracker_box`/`.predicted_box`)
are `cv_service.tracking.engines.base.Box` (normalized `[0, 1]`, same units
as `cv_pb2.BoundingBox`) or `None` -- never a zero-valued `Box`, for the same
absent-vs-zero reason as the groups above: a track this frame never had a
detector-sourced box for reports `detector_box=None`, not a box collapsed to
the origin.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

from cv_service.tracking.engines.base import Box

# Value-name strings, one-for-one with `cv_pb2.ObjectLifecycle`'s own enum
# value names (`track.py`'s `STATE_*` / `engines/base.py`'s `SOURCE_*`
# convention, extended here). Never imported from `cv_pb2` -- see this
# module's own docstring on why -- so if the `.proto` file's value names
# ever change, this list must be updated by hand alongside it; nothing
# machine-checks the match other than `grpc/servicers.py`'s own
# `cv_pb2.ObjectLifecycle.Value(...)` call raising at runtime.
OBJECT_LIFECYCLE_UNSPECIFIED = "OBJECT_LIFECYCLE_UNSPECIFIED"
OBJECT_LIFECYCLE_TENTATIVE = "OBJECT_LIFECYCLE_TENTATIVE"
OBJECT_LIFECYCLE_CONFIRMED = "OBJECT_LIFECYCLE_CONFIRMED"
OBJECT_LIFECYCLE_COASTING = "OBJECT_LIFECYCLE_COASTING"
OBJECT_LIFECYCLE_LOST = "OBJECT_LIFECYCLE_LOST"
OBJECT_LIFECYCLE_DORMANT = "OBJECT_LIFECYCLE_DORMANT"

# Value-name strings, one-for-one with `cv_pb2.EvidenceSource`. Same caveat
# as above.
EVIDENCE_SOURCE_UNSPECIFIED = "EVIDENCE_SOURCE_UNSPECIFIED"
EVIDENCE_SOURCE_DETECTOR = "EVIDENCE_SOURCE_DETECTOR"
EVIDENCE_SOURCE_TRACKER = "EVIDENCE_SOURCE_TRACKER"
EVIDENCE_SOURCE_PREDICTED = "EVIDENCE_SOURCE_PREDICTED"
EVIDENCE_SOURCE_MEMORY = "EVIDENCE_SOURCE_MEMORY"
EVIDENCE_SOURCE_REUPDATE = "EVIDENCE_SOURCE_REUPDATE"


@dataclass(frozen=True)
class LabelCandidate:
    """`ObjectState.Identity.LabelCandidate` -- one entry in the decayed vote
    tally `track.py`'s `_update_label_election` already computes every call
    (`Track.label_tally`), before this wave discarded it once the winner was
    picked.
    """

    label: str
    weight: float


@dataclass(frozen=True)
class Identity:
    """`ObjectState.Identity`. `label` is the ELECTED label
    (`Track.elected_label`); `label_raw` is the raw, unelected label this
    frame's own observation carried -- the same "stable name vs. one
    detector pass's own word" split `assign.py`'s candidate/target already
    draws.
    """

    label: str
    label_raw: str
    candidates: "tuple[LabelCandidate, ...]" = ()
    stability: int = 0


@dataclass(frozen=True)
class Kinematics:
    """`ObjectState.Kinematics`. `box` is the track's SETTLED box for this
    frame (`Track.box`, post-`_observe`); `detector_box`/`tracker_box`/
    `predicted_box` are the same "what did each SOURCE actually see"
    breakdown `session.py`/`outcome.py` already keep separate elsewhere,
    each `None` when this frame has no evidence of that kind for this
    object -- never a box collapsed to zero.
    """

    box: Optional[Box] = None
    detector_box: Optional[Box] = None
    tracker_box: Optional[Box] = None
    predicted_box: Optional[Box] = None
    horizon_ms: int = 0
    velocity_x: float = 0.0
    velocity_y: float = 0.0
    displacement_x: float = 0.0
    displacement_y: float = 0.0
    motion_compensated: bool = False


@dataclass(frozen=True)
class Belief:
    """`ObjectState.Belief`. `confidence_raw` is this frame's own observed
    confidence (`Track.confidence`); `confidence_smoothed` is the EMA
    (`Track.confidence_smoothed`, `track.py`'s `_CONFIDENCE_SMOOTHING`).
    """

    confidence_raw: float = 0.0
    confidence_smoothed: float = 0.0
    existence: float = 0.0
    since_confirmed_ms: int = 0


@dataclass(frozen=True)
class Provenance:
    """`ObjectState.Provenance`. `source` is one of the `EVIDENCE_SOURCE_*`
    constants above; `contributors` are contributor ids from this frame's
    own `FrameLedger.objects[track_id]` (read-only -- see `mirror.py`'s
    module docstring for why the ledger is read here and never mutated).
    """

    source: str = EVIDENCE_SOURCE_UNSPECIFIED
    contributors: "tuple[str, ...]" = ()
    assoc_cost: float = 0.0
    reupdated: bool = False


@dataclass(frozen=True)
class Memory:
    """`ObjectState.Memory`. Present (never `None`) for every LIVE object
    once a gallery is resolved for the stream at all, whether or not this
    particular object was recovered this particular frame -- see
    `mirror.py`'s module docstring on why that is a deliberate reading of
    plan §4.5's "absent group" rule, not a contradiction of it.
    """

    recovered: bool = False
    identity_confidence: float = 0.0
    dormant_ms: int = 0
    gallery_matches: int = 0
    match_distance: float = 1.0


@dataclass(frozen=True)
class Lock:
    """`ObjectState.Lock`. Present for every live object once `LockArbiter`
    has ever applied a lock on this stream (`applied_seq > 0`), `None`
    before that -- see `mirror.py`'s module docstring.
    """

    locked: bool = False
    lock_seq_applied: int = 0


@dataclass(frozen=True)
class Timing:
    """`ObjectState.Timing`. All four of `first_seen_ms`/`last_seen_ms`/
    `last_confirmed_ms` are MILLIS on the wire; `Track.first_seen`/
    `.last_seen`/`.last_confirmed` are monotonic SECONDS
    (`TrackBook.apply`'s own docstring), so `mirror.py` is responsible for
    the `* 1000.0` conversion on every live-track field -- `DormantIdentity`.
    lost_at_millis`/`.first_seen_millis` are already millis (`track.py`'s
    `_retire`), so a dormant entry's `Timing` needs no such conversion.
    """

    first_seen_ms: int = 0
    last_seen_ms: int = 0
    last_confirmed_ms: int = 0
    age_frames: int = 0
    hits: int = 0
    misses: int = 0


@dataclass(frozen=True)
class ObjectState:
    """`cv_pb2.ObjectState`'s pure-stdlib mirror -- see this module's own
    docstring for the absent-group/zero-field distinction, the box-is-
    `None`-not-zero rule, and the enum-as-value-name-string convention that
    govern every field below.

    `id`/`lifecycle`/`stream_id` are the only fields every entry (live or
    dormant) always populates; every group below them is `Optional` and
    `None` exactly when `orchestration/mirror.py` had nothing to compute for
    it this frame.
    """

    id: int
    lifecycle: str
    stream_id: str
    identity: Optional[Identity] = None
    kinematics: Optional[Kinematics] = None
    belief: Optional[Belief] = None
    provenance: Optional[Provenance] = None
    memory: Optional[Memory] = None
    lock: Optional[Lock] = None
    timing: Optional[Timing] = None
