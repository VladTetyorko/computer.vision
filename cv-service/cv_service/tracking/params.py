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


@dataclass(frozen=True)
class TrackingParams:
    """Fully-resolved per-stream tracking configuration. No sentinels left.

    Held by `StreamTrackingSession` and read by `DutyCycleScheduler` and
    `TrackBook`; re-created only when the wire config changes.
    """

    mode: str
    engine_id: str
    verify_every_millis: int
    redetect_iou_threshold: float
    max_age_frames: int
    min_hits: int
    # TRACKING-V2-PLAN wave C1 additions. Neither has a wire counterpart yet
    # (the frozen diff in TRACKING-V2-PLAN §2 does not add one) -- both are
    # deployment-only knobs, resolved straight from `Settings` with no
    # per-request sentinel to interpret, unlike every field above.
    track_max_age_millis: int
    min_tracker_confidence: float

    @property
    def active(self) -> bool:
        """Whether any tracking work happens at all (mode is not OFF)."""
        return self.mode in _ACTIVE_MODES

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
    )
