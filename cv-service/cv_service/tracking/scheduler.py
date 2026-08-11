"""The duty-cycle scheduler: pure policy, nothing else.

`docs/plans/done/TRACKING-PLAN.md` §3.1 (the table this file implements),
`docs/extracts/TRACKING-ORCHESTRATION.md` §2.1, §3.1.

    | Mode      | Detector pass when                                        |
    |-----------|-----------------------------------------------------------|
    | OFF       | every received frame (today's behavior, unchanged)        |
    | ASSOCIATE | every received frame -- cv-service only ever receives     |
    |           | *sampled* frames, so there is nothing to duty-cycle       |
    | FOLLOW    | any of (a) cadence elapsed, (b) tracker failed,           |
    |           | (c) no lock held, (d) box invalid, (e) coasted out        |

`decide()` reads a clock, a lock flag and three counters. **It never touches
a frame**, so it can never be the slow thing, and it can be tested
exhaustively without one -- which is the whole reason this is a separate
module rather than a branch inside `session.py`.

The reason is part of the decision, not a second code path: `Decision` carries
it, the session copies it onto the response as `detector_reason`, and nothing
recomputes it. That is what makes "why is my detector still running in FOLLOW
mode?" answerable from the wire instead of from logs on a box that may be
flying (TRACKING-ORCHESTRATION §5.1).

Pure stdlib. **No cadence, threshold or count literal appears in this file** --
every number comes from `TrackingParams` (TRACKING-ORCHESTRATION §4.2).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

from cv_service.tracking.params import MODE_FOLLOW, TrackingParams

# Reason names match `cv_pb2.DetectorReason`'s enum VALUE NAMES one-for-one
# and map to TRACKING-PLAN §3.1's trigger list (a)-(e) exactly.
REASON_UNSPECIFIED = "DETECTOR_REASON_UNSPECIFIED"
REASON_ALWAYS = "DETECTOR_REASON_ALWAYS"
REASON_CADENCE = "DETECTOR_REASON_CADENCE"
REASON_TRACKER_FAILED = "DETECTOR_REASON_TRACKER_FAILED"
REASON_NO_LOCK = "DETECTOR_REASON_NO_LOCK"
REASON_BOX_INVALID = "DETECTOR_REASON_BOX_INVALID"
REASON_COASTED_OUT = "DETECTOR_REASON_COASTED_OUT"


@dataclass(frozen=True)
class Decision:
    """Whether to spend a detector pass on this frame, and why.

    `reason` is `REASON_UNSPECIFIED` exactly when `run_detector` is False --
    matching the wire's own "UNSPECIFIED when it did not" contract
    (TRACKING-PLAN §4.A).
    """

    run_detector: bool
    reason: str = REASON_UNSPECIFIED


@dataclass(frozen=True)
class SchedulerState:
    """Everything `decide()` is allowed to look at.

    Deliberately a value, not a reference to the session: a state this small
    is what makes the policy exhaustively testable with no frames, no engine
    and no clock but the one passed in.
    """

    mode: str
    has_lock: bool = False
    last_detector_millis: Optional[float] = None
    tracker_failed: bool = False
    box_invalid: bool = False
    coasting_frames: int = 0


class DutyCycleScheduler:
    """`decide(now_millis, state) -> Decision`.

    Holds the resolved `TrackingParams` so the caller does not restate them
    per frame; the session replaces `params` (via `retune`) when the wire
    config changes, never per frame.
    """

    def __init__(self, params: TrackingParams) -> None:
        self._params = params

    @property
    def params(self) -> TrackingParams:
        return self._params

    def retune(self, params: TrackingParams) -> None:
        """Adopt a newly-resolved `TrackingParams`. Called on config change only."""
        self._params = params

    def decide(self, now_millis: float, state: SchedulerState) -> Decision:
        """Apply §3.1's table. O(1), allocation-light, frame-free.

        In FOLLOW, the triggers are evaluated most-specific-first so the
        reason that reaches the operator is the informative one: a frame
        that is *both* past its cadence *and* holding no lock reports
        `NO_LOCK`, because "the detector is running because it has nothing
        to follow" is the actionable fact and "2 seconds elapsed" is not.
        """
        if self._params.mode != MODE_FOLLOW:
            # OFF and ASSOCIATE both detect on every received frame: the
            # Java sampler already thinned the stream to `inferenceFps`, so
            # there is nothing left here to duty-cycle.
            return Decision(True, REASON_ALWAYS)

        if not state.has_lock:
            return Decision(True, REASON_NO_LOCK)  # (c)
        if state.tracker_failed:
            return Decision(True, REASON_TRACKER_FAILED)  # (b)
        if state.box_invalid:
            return Decision(True, REASON_BOX_INVALID)  # (d)
        if state.coasting_frames > self._params.max_age_frames:
            return Decision(True, REASON_COASTED_OUT)  # (e)
        if state.last_detector_millis is None:
            # No pass has ever run for this lock; treat the cadence as
            # already elapsed rather than inventing a separate reason.
            return Decision(True, REASON_CADENCE)  # (a)
        if now_millis - state.last_detector_millis >= self._params.verify_every_millis:
            return Decision(True, REASON_CADENCE)  # (a)

        return Decision(False)
