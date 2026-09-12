"""`FrameBudget` -- who is allowed to run this frame, and why not.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.2. `DutyCycleScheduler.
decide()` becomes the core of this: it still answers "run the detector this
frame and why" with the SAME `DetectorReason` values it always has, and it
additionally answers "which contributors are eligible" from mode, engine and
level.

**Frame-free, still.** `decide()` never touched a frame and neither does
this -- `BudgetPolicy.decide` reads a clock, a mode, an engine id and four
session counters. That is what keeps the policy exhaustively testable with
no pixels, and it is why the ROI second pass can be a BUDGET decision with
its own ledger entry instead of hiding inside `tracker_millis` (R3
surprise 4).

The duty ratio a flow strip computes counts `detect.full` only; `detect.roi`
is its own entry beside it, never folded in (plan §4.2, O1 Q13).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Mapping, Optional

from cv_service.tracking.params import MODE_ASSOCIATE, MODE_FOLLOW, MODE_OFF, TrackingParams
from cv_service.tracking.scheduler import (
    REASON_UNSPECIFIED,
    Decision,
    DutyCycleScheduler,
    SchedulerState,
)

# Contributor ids. Declared here rather than on each contributor class so the
# budget's eligibility table and the registration table cannot drift apart --
# both import these names.
DETECT_FULL = "detect.full"
DETECT_ROI = "detect.roi"
EGOMOTION = "egomotion"
PREDICT_CV = "predict.cv"
APPEARANCE = "appearance"
ASSOC = "assoc"
MEMORY_GALLERY = "memory.gallery"
FOLLOW = "follow"
EMIT_RAW = "emit.raw"
#: The fold. Never refused: the ledger must be complete on every frame,
#: including the frames that book nothing.
AGGREGATE = "aggregate"

#: `assign.CostAssociator.engine_id`, spelled here to keep this module free
#: of an import cycle through `session.py`.
_COST_ENGINE_ID = "cost"
_ENGINE_OFF = "off"


@dataclass(frozen=True)
class BudgetState:
    """Everything eligibility is allowed to look at beyond `TrackingParams`.

    A value, not a reference to the session, for exactly the reason
    `SchedulerState` is one: a state this small is what makes the policy
    testable with no engine, no frame and no clock but the one passed in.
    """

    scheduler: SchedulerState
    #: False when `_resolve_engine` found nothing constructible this frame --
    #: the session's own OFF/degraded path.
    engine_resolved: bool = True
    #: The associator/follower id actually serving, `""` when none is.
    engine_id: str = ""


@dataclass(frozen=True)
class FrameBudget:
    """This frame's eligibility set, with a reason for every refusal."""

    decision: Decision
    eligible: "frozenset[str]"
    reasons: "Mapping[str, str]" = field(default_factory=dict)

    @property
    def run_detector(self) -> bool:
        return self.decision.run_detector

    @property
    def detector_reason(self) -> str:
        return self.decision.reason

    def allows(self, contributor_id: str) -> bool:
        return contributor_id in self.eligible

    def reason(self, contributor_id: str) -> str:
        """Why this contributor is not eligible -- `""` when it is."""
        return self.reasons.get(contributor_id, "")


class BudgetPolicy:
    """Turns `TrackingParams` + `BudgetState` into a `FrameBudget`.

    Wraps `DutyCycleScheduler` rather than replacing it: the detector
    decision and its `DetectorReason` are unchanged, byte for byte, and this
    class only adds the per-contributor half the plan asks for.
    """

    def __init__(self, params: TrackingParams) -> None:
        self._scheduler = DutyCycleScheduler(params)
        self._params = params

    @property
    def params(self) -> TrackingParams:
        return self._params

    def retune(self, params: TrackingParams) -> None:
        """Adopt a newly-resolved `TrackingParams`. Config change only."""
        self._params = params
        self._scheduler.retune(params)

    def decide(self, now_millis: float, state: BudgetState) -> FrameBudget:
        decision = self._scheduler.decide(now_millis, state.scheduler)
        params = self._params
        eligible: "set[str]" = set()
        reasons: "dict[str, str]" = {}

        def allow(contributor_id: str, ok: bool, why: str) -> None:
            if ok:
                eligible.add(contributor_id)
            else:
                reasons[contributor_id] = why

        mode = params.mode
        tracking_off = mode == MODE_OFF or not state.engine_resolved
        cost_associate = (
            mode == MODE_ASSOCIATE
            and state.engine_id == _COST_ENGINE_ID
            and not tracking_off
        )
        following = mode == MODE_FOLLOW and not tracking_off

        allow(DETECT_FULL, decision.run_detector, _detector_refusal(decision))
        allow(
            EGOMOTION,
            (following or cost_associate) and params.motion_engine_id != _ENGINE_OFF,
            _motion_refusal(mode, state, params),
        )
        allow(PREDICT_CV, following or cost_associate, "no reader at this mode/engine")
        allow(
            APPEARANCE,
            cost_associate and params.appearance_engine_id != _ENGINE_OFF,
            "appearance is off" if params.appearance_engine_id == _ENGINE_OFF else "cost associator only",
        )
        allow(ASSOC, mode == MODE_ASSOCIATE and not tracking_off, _mode_refusal(mode, state))
        allow(
            DETECT_ROI,
            cost_associate and params.roi_enabled,
            "roi rescue disabled" if not params.roi_enabled else "cost associator only",
        )
        allow(MEMORY_GALLERY, cost_associate, "cost associator only")
        allow(FOLLOW, following, _mode_refusal(mode, state))
        allow(EMIT_RAW, tracking_off, "tracking is active")
        # Unconditional, and the one row with no refusal reason: a frame
        # that folds nothing still has to SAY so, and the aggregator is
        # what says it (`Proposal.fold`, not eligibility, is how "book
        # nothing" is expressed).
        eligible.add(AGGREGATE)

        return FrameBudget(
            decision=decision, eligible=frozenset(eligible), reasons=dict(reasons)
        )


def _detector_refusal(decision: Decision) -> str:
    """Why no detector pass -- the scheduler's own vocabulary, not a second
    one. `REASON_UNSPECIFIED` is exactly what the wire already carries when
    `run_detector` is False, so the ledger says the same thing the response
    does rather than inventing a parallel explanation."""
    return decision.reason if decision.reason != REASON_UNSPECIFIED else "duty cycle: not due"


def _mode_refusal(mode: str, state: BudgetState) -> str:
    if not state.engine_resolved:
        return "no engine constructible"
    return f"mode is {mode}"


def _motion_refusal(mode: str, state: BudgetState, params: TrackingParams) -> str:
    if params.motion_engine_id == _ENGINE_OFF:
        return "motion engine is off"
    if not state.engine_resolved:
        return "no engine constructible"
    if mode == MODE_ASSOCIATE:
        # Stated precisely rather than as "wrong mode": `cost` is the only
        # associator this build wires ego-motion into (`contributors/
        # __init__.py`'s `roster()`). CV-ORCHESTRATION W4 (decision E16)
        # retired the other one, `bytetrack`, whose association state lived
        # inside a third-party Kalman filter with no seam to warp at all, so
        # compensating it would have cost a frame decode for a transform
        # nothing read. `params.py#resolve()` now aliases a wire/env
        # `bytetrack` to `cost` before it gets this far, so any OTHER
        # associator id reaching here is unreachable against the real
        # registry (it serves only `cost`) but still gets this same honest
        # refusal from a test or future registry that resolves one anyway.
        return f"associator {state.engine_id!r} has no seam to warp"
    return f"mode is {mode}"


def scheduler_state(
    *,
    params: TrackingParams,
    has_lock: bool,
    last_detector_millis: Optional[float],
    tracker_failed: bool,
    box_invalid: bool,
    coasting_frames: int,
) -> SchedulerState:
    """`SchedulerState` from the session's own per-frame flags, unchanged."""
    return SchedulerState(
        mode=params.mode,
        has_lock=has_lock,
        last_detector_millis=last_detector_millis,
        tracker_failed=tracker_failed,
        box_invalid=box_invalid,
        coasting_frames=coasting_frames,
    )
