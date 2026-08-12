"""Tracking configuration: the one place a wire sentinel becomes a number.

`docs/extracts/TRACKING-ORCHESTRATION.md` §2.1 (charter), §4.1-§4.3 (the layering and
the resolve-once rule).

Configuration is a **layer**, not a field on the thing being configured, and
precedence runs strictly left to right::

    per-stream request  >  deployment env  >  code default
    TrackingConfig         CV_TRACK_*         cv_service/config.py

`resolve()` is the only function in cv-service that turns the wire's
``<=0 = server default`` sentinels into numbers, and `cv_service/config.py`
is the only place the ``CV_TRACK_*`` environment variables are read (this
module never touches ``os.environ``). Together those two facts are what make
"no cadence, threshold or count literal in `scheduler.py`, `session.py` or an
engine" checkable rather than aspirational -- every such value in this
package traces back to a `TrackingParams` field.

**Resolved on config CHANGE, never per frame.** `TrackingConfig` is restated
on *every* `FrameRequest` (deliberately -- TRACKING-PLAN invariant P2: the
mailbox may silently drop a frame, so a one-shot control message could be
lost with no error, while a restated desired state is self-healing). The
per-frame cost of that design is one protobuf equality check in
`grpc/servicers.py`; `resolve()` runs only when the check says the config
actually changed. See `StreamTrackingSession.apply_config`.

Pure stdlib. `TrackingRequest`/`LockRequest` are the plain, protobuf-free
counterparts of `cv_pb2.TrackingConfig`/`cv_pb2.TargetLock` -- the servicer
builds them; nothing here knows protobuf exists.
"""

from __future__ import annotations

import dataclasses
from dataclasses import dataclass
from typing import TYPE_CHECKING, Optional, Tuple

from cv_service.tracking.assign import AssignGates, AssignWeights
from cv_service.tracking.memory import MemoryParams

if TYPE_CHECKING:  # pragma: no cover - typing only
    from cv_service.config import Settings

# Mode names match `cv_pb2.TrackingMode`'s enum VALUE NAMES one-for-one, so
# the servicer translates with `cv_pb2.TrackingMode.Name(msg.mode)` in one
# direction and never needs a mapping table -- the same convention
# `cv_service/inference/detector.py`'s ENCODING_* constants already use for
# `ImageEncoding`.
MODE_UNSPECIFIED = "TRACKING_MODE_UNSPECIFIED"
MODE_OFF = "TRACKING_MODE_OFF"
MODE_ASSOCIATE = "TRACKING_MODE_ASSOCIATE"
MODE_FOLLOW = "TRACKING_MODE_FOLLOW"

# UNSPECIFIED (an old client, or a `tracking` field that was never set) is
# treated as OFF -- TRACKING-PLAN §4.A's proto3-additivity contract.
# How many verify opportunities a FOLLOW track gets before the wall clock may
# declare it LOST. Structural, not an operator knob: the knob is
# `CV_TRACK_MAX_AGE_MILLIS`, and this is what stops that knob from meaning
# something different in the two modes -- see `effective_max_age_millis`.
_LOST_VERIFY_OPPORTUNITIES = 3

_ACTIVE_MODES = frozenset({MODE_ASSOCIATE, MODE_FOLLOW})
_KNOWN_MODES = frozenset({MODE_UNSPECIFIED, MODE_OFF, MODE_ASSOCIATE, MODE_FOLLOW})


@dataclass(frozen=True)
class LockRequest:
    """Plain counterpart of `cv_pb2.TargetLock` (TRACKING-PLAN §4.A).

    Exactly one of `track_id` / `point_x`+`point_y` / `box` identifies the
    target, and `release` overrides all of them. The one-of-three rule is
    validated Java-side (`PATCH .../config` returns 400); this side is
    deliberately forgiving and just applies the most specific form it was
    given, because a stricter cv-service would turn a client bug into a dead
    stream instead of a rejected request.
    """

    lock_seq: int = 0
    track_id: int = 0
    point_x: float = 0.0
    point_y: float = 0.0
    box: Optional[Tuple[float, float, float, float]] = None
    release: bool = False


@dataclass(frozen=True)
class TrackingRequest:
    """Plain counterpart of `cv_pb2.TrackingConfig` -- sentinels intact.

    Every numeric field still carries the wire's raw value, including the
    ``<=0 = server default`` sentinel. Nothing but `resolve()` may interpret
    them.
    """

    mode: str = MODE_UNSPECIFIED
    engine_id: str = ""
    verify_every_millis: int = 0
    redetect_iou_threshold: float = 0.0
    max_age_frames: int = 0
    min_hits: int = 0
    lock: Optional[LockRequest] = None
    # TRACKING-V2-PLAN wave C2 -- proto field 8, frozen at C0. "" = server
    # default for the deployment (`CV_TRACK_MOTION_ENGINE`); "off" disables
    # ego-motion compensation outright rather than falling back to a
    # different engine, which is why `resolve()` below does not fold it into
    # the blank-sentinel branch the way `engine_id` is.
    motion_engine_id: str = ""
    # TRACKING-V2-PLAN wave C3 -- proto field 9, frozen at C0 (same shape as
    # `motion_engine_id` above: "" = server default, "off" is a legitimate
    # resolved value, not a sentinel `resolve()` rewrites).
    appearance_engine_id: str = ""
    # TRACKING-V2-PLAN wave C4 -- proto field 10, frozen at C0. `<=0 =
    # server default` (`CV_TRACK_MEMORY_TTL_MILLIS`), the SAME sentinel
    # shape as `verify_every_millis`/`max_age_frames` above -- a positive
    # request value asks for that exact TTL, and `<=0` falls back to the
    # deployment default. Unlike `motion_engine_id`/`appearance_engine_id`'s
    # string `"off"`, there is no distinct value this int field could carry
    # to mean "disable the gallery for THIS request only" as opposed to
    # "use the deployment's own choice" -- disabling memory is therefore a
    # DEPLOYMENT decision (`CV_TRACK_MEMORY_TTL_MILLIS<=0`, a legitimate
    # resolved value, not a sentinel -- see `resolve()` below).
    memory_ttl_millis: int = 0


@dataclass(frozen=True)
class TrackingParams:
    """Fully-resolved per-stream tracking configuration. No sentinels left.

    Held by `StreamTrackingSession` and read by `DutyCycleScheduler` and
    `TrackBook`; re-created only when the wire config changes.
    """

    mode: str
    engine_id: str
    verify_every_millis: int
    reacquire_every_millis: int
    redetect_iou_threshold: float
    max_age_frames: int
    min_hits: int
    # TRACKING-V2-PLAN wave C1 additions. Neither has a wire counterpart yet
    # (the frozen diff in TRACKING-V2-PLAN §2 does not add one) -- both are
    # deployment-only knobs, resolved straight from `Settings` with no
    # per-request sentinel to interpret, unlike every field above.
    track_max_age_millis: int
    min_tracker_confidence: float
    # TRACKING-V2-PLAN wave C2 addition. Unlike `engine_id`, this is never
    # rewritten to a concrete roster id here -- `"off"` is a legitimate
    # resolved value, not a sentinel, and it is `session.py`'s job (not
    # `resolve()`'s) to decide what engine, if any, actually serves it,
    # because that decision also needs a live `CameraPose` (see
    # `TrackerRegistry.compensator` and `StreamTrackingSession`'s
    # pose-unavailable-falls-back-to-flow policy).
    motion_engine_id: str
    # TRACKING-V2-PLAN wave C3 addition. Same "not fully resolved here" shape
    # as `motion_engine_id`: "off" is a legitimate resolved value, and
    # deciding whether anything actually SERVES a non-off request is
    # `session.py`'s `_resolve_appearance_extractor` (no live signal to check
    # like `pose`'s `CameraPose`, so no fallback ladder -- constructibility
    # IS availability here).
    appearance_engine_id: str
    # TRACKING-V2-PLAN wave C3 additions. Unlike every field above, these
    # have no wire sentinel to interpret at all (§2's frozen diff adds none
    # for cost weights/gates) -- `resolve()` builds the two frozen
    # `assign.py` dataclasses directly from `Settings`, with no per-request
    # override to fall back FROM. `session.py`'s `_run_cost_associate` is
    # what may further zero `cost_weights.appearance` for a stream where no
    # appearance extractor actually resolved -- that decision needs runtime
    # state `resolve()` does not have, same division of labour `motion_
    # engine_id`'s own docstring draws.
    cost_weights: AssignWeights
    cost_gates: AssignGates
    # TRACKING-V2-PLAN wave C4 addition. Built DIRECTLY by `resolve()`, same
    # shape as `cost_weights`/`cost_gates` above -- `ttl_millis` is the one
    # field with a wire sentinel to interpret (`memory_ttl_millis`, `Tracking
    # Request` above); the rest come straight from `Settings` with no
    # per-request override to fall back FROM. `ttl_millis <= 0` is memory
    # DISABLED for this stream -- `session.py`'s `_resolve_memory` is what
    # turns that into "no `ObjectMemory` is even constructed", never an
    # empty gallery still being consulted every frame (P5).
    memory_params: MemoryParams
    # TRACKING-V2-PLAN wave C5b addition (review finding C6). Same
    # "deployment-only, no wire field" shape as `track_max_age_millis`/
    # `min_tracker_confidence` above -- `session.py` reads it as "the LOCKED
    # target plus up to `follow_top_k - 1` other targets, each with its own
    # `SingleObjectTracker` instance". `1` reproduces FOLLOW's pre-C5b
    # single-target behaviour exactly -- see `config.py`'s `DEFAULT_TRACK_
    # FOLLOW_TOP_K` for why that, not a larger number, is what ships.
    follow_top_k: int

    @property
    def active(self) -> bool:
        """Whether any tracking work happens at all (mode is not OFF)."""
        return self.mode in _ACTIVE_MODES

    @property
    def effective_reacquire_millis(self) -> int:
        """How often FOLLOW may spend a pass re-acquiring, never slower than
        it would ordinarily verify.

        A rate limit that could exceed `verify_every_millis` would be
        backwards: an operator who asks for a very short cadence is asking
        the detector to look often, and re-acquiring a target they have LOST
        is more urgent than re-confirming one they still hold, not less. So
        this bites only where it was meant to -- a long cadence, where trigger
        (c) would otherwise run the detector on every single frame.
        """
        return min(self.reacquire_every_millis, self.verify_every_millis)

    @property
    def effective_max_age_millis(self) -> int:
        """The wall-clock LOST deadline, floored at N verify opportunities.

        `track_max_age_millis` alone unifies the two modes' *rule* but not
        their *meaning*, because the two modes do not offer the detector
        opportunities at the same rate. In ASSOCIATE a pass runs on every
        received frame, so the default is ~30 chances at the documented
        sample rate. In FOLLOW passes arrive on cadence, so the same flat
        number is barely one and a half chances -- and a FOLLOW track going
        LOST unbinds the lock, which makes scheduler trigger (c) spend a
        detector pass on EVERY frame until something is re-acquired. A knob
        meant to bound staleness would therefore have quietly destroyed the
        duty cycle precisely when the target is hardest, which is the failure
        this floor exists to prevent.

        Expressed as a floor rather than a replacement so an operator who
        deliberately raises `CV_TRACK_MAX_AGE_MILLIS` still gets what they
        asked for, and so ASSOCIATE -- where `verify_every_millis` is not
        consulted at all -- is left exactly as it was.
        """
        if self.mode != MODE_FOLLOW:
            return self.track_max_age_millis
        return max(
            self.track_max_age_millis,
            _LOST_VERIFY_OPPORTUNITIES * self.verify_every_millis,
        )

    def with_mode(self, mode: str, engine_id: str) -> "TrackingParams":
        """A copy one step down the degradation ladder.

        The `FOLLOW -> ASSOCIATE -> OFF` fallback changes the mode and the
        engine and nothing else -- cadence, IoU and the counts stay whatever
        the operator asked for, so a stream that recovers does not silently
        come back with different tuning.
        """
        return dataclasses.replace(self, mode=mode, engine_id=engine_id)


def normalize_mode(mode: str) -> str:
    """Map a wire mode name to a known one; anything unrecognized -> OFF.

    A mode name this build does not know can only come from a *newer* client
    against an older cv-service. Treating it as OFF is the same defensive
    posture proto3 additivity already gives an unset field, and it keeps the
    "never a dead stream" rule (TRACKING-ORCHESTRATION §3.4).
    """
    if mode not in _KNOWN_MODES:
        return MODE_OFF
    if mode == MODE_UNSPECIFIED:
        return MODE_OFF
    return mode


def resolve(request: TrackingRequest, settings: "Settings") -> TrackingParams:
    """Resolve one wire request against deployment defaults.

    THE ONLY PLACE a `<=0` sentinel becomes a number. Each field falls back
    to its `CV_TRACK_*`-backed `Settings` value; the engine id falls back to
    the default engine *for the resolved mode*, which is why the two engine
    knobs are separate (`CV_TRACK_ASSOCIATE_ENGINE` / `CV_TRACK_FOLLOW_ENGINE`)
    -- a single "default engine" would be meaningless across two protocols
    that share no method.
    """
    mode = normalize_mode(request.mode)
    engine_id = request.engine_id.strip() if request.engine_id else ""
    if not engine_id:
        engine_id = (
            settings.track_follow_engine
            if mode == MODE_FOLLOW
            else settings.track_associate_engine
        )
    return TrackingParams(
        mode=mode,
        engine_id=engine_id,
        verify_every_millis=(
            request.verify_every_millis
            if request.verify_every_millis > 0
            else settings.track_verify_millis
        ),
        # No wire field: this bounds a runaway rather than expressing an
        # operator preference, so it is deployment-only, like the memory and
        # cost knobs.
        reacquire_every_millis=settings.track_reacquire_millis,
        redetect_iou_threshold=(
            request.redetect_iou_threshold
            if request.redetect_iou_threshold > 0
            else settings.track_iou
        ),
        max_age_frames=(
            request.max_age_frames if request.max_age_frames > 0 else settings.track_max_age_frames
        ),
        min_hits=(request.min_hits if request.min_hits > 0 else settings.track_min_hits),
        # No wire field exists for either of these yet (TRACKING-V2-PLAN
        # §2's frozen diff does not add one) -- both come straight from the
        # deployment layer, same shape `resolve()` already gives every other
        # `CV_TRACK_*` default, just with no request-level override to fall
        # back FROM.
        track_max_age_millis=settings.track_max_age_millis,
        min_tracker_confidence=settings.track_min_tracker_confidence,
        # Blank -> the deployment default (`CV_TRACK_MOTION_ENGINE`, itself
        # possibly "off"); anything else, including an explicit "off",
        # passes through untouched -- same blank-only-sentinel shape
        # `engine_id` above uses, just with a value space ("off" included)
        # `session.py` interprets rather than a roster id `resolve()` could
        # validate here.
        motion_engine_id=(
            request.motion_engine_id.strip()
            if request.motion_engine_id
            else settings.track_motion_engine
        ),
        appearance_engine_id=(
            request.appearance_engine_id.strip()
            if request.appearance_engine_id
            else settings.track_appearance_engine
        ),
        cost_weights=AssignWeights(
            iou=settings.track_cost_weight_iou,
            appearance=settings.track_cost_weight_appearance,
            label=settings.track_cost_weight_label,
        ),
        cost_gates=AssignGates(
            min_iou=settings.track_cost_gate_min_iou,
            max_appearance=settings.track_cost_gate_max_appearance,
            max_cost=settings.track_cost_gate_max_cost,
            high_confidence=settings.track_cost_gate_high_confidence,
        ),
        memory_params=MemoryParams(
            ttl_millis=(
                request.memory_ttl_millis
                if request.memory_ttl_millis > 0
                else settings.track_memory_ttl_millis
            ),
            capacity=settings.track_memory_capacity,
            gallery_size=settings.track_memory_gallery_size,
            max_appearance_distance=settings.track_memory_max_appearance_distance,
            max_speed=settings.track_memory_max_speed,
            blend_alpha=settings.track_memory_blend_alpha,
            min_confidence=settings.track_memory_min_confidence,
        ),
        # No wire field (TRACKING-V2-PLAN wave C5b) -- straight from
        # `Settings`, same shape as `track_max_age_millis` above.
        follow_top_k=settings.track_follow_top_k,
    )
