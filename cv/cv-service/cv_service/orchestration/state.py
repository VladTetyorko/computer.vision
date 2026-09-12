"""The per-stream mutable facts more than one owner reads.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` wave W0. Before this package
these were eleven attributes on `StreamTrackingSession`, which is what made
that class the only possible home for every mode branch that touched them.
Naming the set is what lets the FOLLOW slice, the engine set and the session
each own their own file while still agreeing about the same target.

**This is deliberately not a blackboard key.** `FrameContext` holds what ONE
frame produced and is thrown away at the end of it; this holds what SURVIVES
a frame -- the held target, its extras, the scheduler triggers the next
frame will read. Mixing the two is how a per-frame cache becomes per-stream
state by accident.

Pure stdlib, like the rest of the package.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Optional

from cv_service.tracking.track import Track


@dataclass(frozen=True)
class ExtraFollow:
    """One auto-selected, non-locked FOLLOW target -- situational awareness
    only, never the operator's lock (TRACKING-V2-PLAN wave C5b).

    `track` is the SAME mutable `Track` object `TrackBook` holds for this
    slot's booking key, so reading `.box`/`.label`/`.confidence` off it
    always reflects this frame's warp/observation with no second copy to
    keep in sync -- the same reason `StreamState.followed` holds a `Track`
    reference rather than caching its own box. Frozen: a change of engine,
    key or track is always a NEW slot (a re-anchor or a promotion), never a
    mutation of an existing one.
    """

    key: str
    engine: Any
    track: Track


@dataclass(frozen=True)
class ExtraCandidate:
    """One extra observation offered to a batched `TrackBook.apply()` call,
    before the book has told us which `Track` it became.

    `apply()` ages EVERY live track once per call, so the locked target's own
    observation and every extra's must be booked in ONE call -- this is the
    record that lets the caller zip `apply()`'s returned tracks back onto
    (`key`, `engine`) pairs once that one call returns.
    """

    key: str
    engine: Any
    det_index: int


@dataclass
class StreamState:
    """What survives a frame, for the owners that all need to see the same it.

    Split in two by lifetime rather than by subject: the fields above the
    `reset_frame` line persist across frames, the ones below are this
    frame's own and are cleared at its start -- exactly the reset
    `session.process()` did inline before this package existed.
    """

    #: The LOCKED target's own `Track`, or `None` when nothing is held.
    followed: Optional[Track] = None
    #: Up to `follow_top_k - 1` non-locked targets, each with its own engine.
    extras: "list[ExtraFollow]" = field(default_factory=list)
    #: Mints a permanently-unique book key per PROMOTED extra.
    extra_key_seq: int = 0
    #: The tracker said it lost the target AND the verify pass that followed
    #: could not re-anchor it: predict rather than ask the engine again.
    tracker_stalled: bool = False
    #: `DutyCycleScheduler` triggers (b) and (d), raised on tracker-only
    #: frames only -- see `_build_coast_observation` for why that matters.
    tracker_failed: bool = False
    box_invalid: bool = False
    #: When the last detector pass ran, for the FOLLOW cadence.
    last_detector_millis: Optional[float] = None

    # -- this frame's own, cleared by `reset_frame` -------------------------

    #: The detector's own measurement, summed over the full-frame pass and
    #: the ROI one -- `inference_millis` on the wire, the same "one number,
    #: whatever ran" convention `detect_composite` already uses.
    inference_millis: int = 0
    #: Set by the ROI contributor; read once to build this frame's outcome.
    roi_ran: bool = False
    roi_millis: int = 0
    #: The ego-motion contributor's own measurement and served engine id --
    #: `motion_millis`/`motion_engine_id` on the wire, which is why they are
    #: reported by the contributor rather than re-derived by the orchestrator.
    motion_millis: int = 0
    motion_engine_id: str = ""
    #: This frame's measured capture -> association lag, already clamped.
    lag_millis: int = 0
    lag_seconds: float = 0.0

    def reset_frame(self, *, lag_millis: int) -> None:
        """Start a frame: clear the per-frame half, adopt this frame's lag.

        The clamp is here, not at the caller: a negative reading can only be
        clock jitter (`pull/clock.py`'s own skew estimate can dip below
        zero), never a genuine "detection from the future" to correct toward.
        """
        self.tracker_failed = False
        self.box_invalid = False
        self.inference_millis = 0
        self.roi_ran = False
        self.roi_millis = 0
        self.motion_millis = 0
        self.motion_engine_id = ""
        self.lag_millis = max(0, int(lag_millis))
        self.lag_seconds = self.lag_millis / 1000.0
