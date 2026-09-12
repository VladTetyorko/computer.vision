"""`detect.full` -- this frame's detector pass, and the only door to one.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.1. The whole contributor is
"ask the `DetectorClient`, put the answer on the board": the decision of
WHETHER to ask is the budget's (`DutyCycleScheduler`, unchanged), and the
gate the ask goes through is `orchestration/detector.py`'s, so there is
nothing left here but the wiring.

`halt` is used exactly once in this package, and it is here: `detections is
None` means no model resolved at all, which the servicer degrades to an echo
response. That is the one outcome where continuing the frame would be
dishonest rather than merely degraded -- everything else (a raise, a refused
budget) leaves the key absent and lets the readers cope.
"""

from __future__ import annotations

from typing import Any

from cv_service.orchestration.budget import DETECT_FULL
from cv_service.orchestration.contract import PHASE_PRE, Contribution, FrameContext
from cv_service.orchestration.detector import DetectorClient, served_by_summary
from cv_service.orchestration.keys import Key
from cv_service.tracking.levels import LEVEL_L3
from cv_service.orchestration.state import StreamState


class DetectFull:
    """One full-frame detector pass."""

    id = DETECT_FULL
    family = DETECT_FULL
    reads: "frozenset[Key]" = frozenset()
    writes = frozenset({Key.DETECTIONS})
    min_level = LEVEL_L3
    #: Before the tracker clock starts: the detector's cost is
    #: `inference_millis` on the wire, never part of `tracker_millis`.
    phase = PHASE_PRE

    def __init__(self, client: DetectorClient, state: StreamState) -> None:
        self._client = client
        self._state = state

    def contribute(self, ctx: FrameContext, budget: Any) -> Contribution:
        result = self._client.detect()
        # Stamped even when the model turns out to be unresolved, exactly as
        # `process()` always did: the cadence measures when a pass was SPENT,
        # not whether it produced anything.
        self._state.last_detector_millis = ctx.now_millis
        self._state.inference_millis += result.inference_millis
        if result.unavailable:
            return Contribution(
                reason="no model resolved",
                summary={"detections": "none", "wait_ms": f"{result.wait_ms:.3f}"},
                cost_ms=float(result.inference_millis),
                halt=True,
            )
        return Contribution(
            outputs={Key.DETECTIONS: result.detections},
            reason=budget.detector_reason,
            summary={
                "detections": str(len(result.detections)),
                "wait_ms": f"{result.wait_ms:.3f}",
                **served_by_summary(result),
            },
            # The detector's OWN measurement, not the orchestrator's clock:
            # this is the number that reaches the wire.
            cost_ms=float(result.inference_millis),
        )
