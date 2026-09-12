"""`BudgetPolicy` -- the eligibility table, exhaustively, with no pixels.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.2. The detector half is
`DutyCycleScheduler`, unchanged and already covered by
`tests/tracking/test_scheduler.py`; what is new here is the per-contributor
half, and the whole point of it being frame-free is that this file needs a
clock and nothing else.

The eligibility rows mirror `session.process()`'s own branches one for one.
A test that disagrees with `session.py` is a behavioural delta, which is what
W0 is not allowed to ship -- so each row below cites the branch it mirrors.
"""

from __future__ import annotations

import pytest

from cv_service.orchestration.budget import (
    APPEARANCE,
    ASSOC,
    DETECT_FULL,
    DETECT_ROI,
    EGOMOTION,
    EMIT_RAW,
    FOLLOW,
    MEMORY_GALLERY,
    PREDICT_CV,
    BudgetPolicy,
    BudgetState,
    scheduler_state,
)
from cv_service.tracking.params import MODE_ASSOCIATE, MODE_FOLLOW, MODE_OFF
from cv_service.tracking.scheduler import (
    REASON_ALWAYS,
    REASON_NO_LOCK,
    REASON_UNSPECIFIED,
    SchedulerState,
)

from tests.orchestration.params import params

EVERY_CONTRIBUTOR = (
    DETECT_FULL,
    DETECT_ROI,
    EGOMOTION,
    PREDICT_CV,
    APPEARANCE,
    ASSOC,
    MEMORY_GALLERY,
    FOLLOW,
    EMIT_RAW,
)


def decide(mode: str, *, engine_id: str = "cost", engine_resolved: bool = True, **overrides):
    resolved = params(mode, **overrides)
    policy = BudgetPolicy(resolved)
    state = BudgetState(
        scheduler=SchedulerState(mode=mode, has_lock=False),
        engine_resolved=engine_resolved,
        engine_id=engine_id,
    )
    return policy.decide(0.0, state)


# --- the detector half is the scheduler's, byte for byte -------------------


def test_the_detector_decision_is_the_schedulers_own() -> None:
    budget = decide(MODE_ASSOCIATE)

    assert budget.run_detector is True
    assert budget.detector_reason == REASON_ALWAYS
    assert budget.allows(DETECT_FULL) is True


def test_follow_without_a_lock_still_reports_the_schedulers_reason() -> None:
    budget = decide(MODE_FOLLOW, engine_id="lk")

    assert budget.detector_reason == REASON_NO_LOCK


def test_a_refused_detector_pass_carries_a_reason_the_wire_can_repeat() -> None:
    resolved = params(MODE_FOLLOW)
    policy = BudgetPolicy(resolved)
    state = BudgetState(
        scheduler=scheduler_state(
            params=resolved,
            has_lock=True,
            last_detector_millis=0.0,
            tracker_failed=False,
            box_invalid=False,
            coasting_frames=0,
        ),
        engine_id="lk",
    )

    budget = policy.decide(1.0, state)

    assert budget.run_detector is False
    assert budget.detector_reason == REASON_UNSPECIFIED
    # The ledger must not invent a second vocabulary for the same fact.
    assert budget.reason(DETECT_FULL) == "duty cycle: not due"


# --- ASSOCIATE + cost: the fully-populated frame ---------------------------


def test_cost_associate_runs_the_whole_ladder() -> None:
    budget = decide(MODE_ASSOCIATE, engine_id="cost")

    for contributor in (
        DETECT_FULL,
        EGOMOTION,
        PREDICT_CV,
        APPEARANCE,
        ASSOC,
        DETECT_ROI,
        MEMORY_GALLERY,
    ):
        assert budget.allows(contributor) is True, contributor
    assert budget.allows(FOLLOW) is False
    assert budget.allows(EMIT_RAW) is False


@pytest.mark.parametrize(
    "contributor", [EGOMOTION, PREDICT_CV, APPEARANCE, DETECT_ROI, MEMORY_GALLERY]
)
def test_bytetrack_associate_gets_the_associator_and_nothing_around_it(contributor) -> None:
    # `session.py`'s own reason: bytetrack's state lives inside a third-party
    # Kalman filter with no seam to warp, predict against or describe.
    budget = decide(MODE_ASSOCIATE, engine_id="bytetrack")

    assert budget.allows(ASSOC) is True
    assert budget.allows(contributor) is False
    assert budget.reason(contributor) != ""


def test_bytetracks_egomotion_refusal_names_the_associator_not_the_mode() -> None:
    budget = decide(MODE_ASSOCIATE, engine_id="bytetrack")

    assert budget.reason(EGOMOTION) == "associator 'bytetrack' has no seam to warp"


def test_roi_rescue_is_refused_when_the_knob_is_off() -> None:
    budget = decide(MODE_ASSOCIATE, roi_enabled=False)

    assert budget.allows(DETECT_ROI) is False
    assert budget.reason(DETECT_ROI) == "roi rescue disabled"


def test_appearance_is_refused_when_the_extractor_is_off() -> None:
    budget = decide(MODE_ASSOCIATE, appearance_engine_id="off")

    assert budget.allows(APPEARANCE) is False
    assert budget.reason(APPEARANCE) == "appearance is off"


def test_egomotion_is_refused_when_the_motion_engine_is_off() -> None:
    budget = decide(MODE_ASSOCIATE, motion_engine_id="off")

    assert budget.allows(EGOMOTION) is False
    assert budget.reason(EGOMOTION) == "motion engine is off"


# --- FOLLOW ----------------------------------------------------------------


def test_follow_gets_motion_prediction_and_the_follower() -> None:
    budget = decide(MODE_FOLLOW, engine_id="lk")

    assert budget.allows(FOLLOW) is True
    assert budget.allows(EGOMOTION) is True
    assert budget.allows(PREDICT_CV) is True
    assert budget.allows(ASSOC) is False
    assert budget.allows(APPEARANCE) is False
    assert budget.allows(DETECT_ROI) is False
    assert budget.allows(MEMORY_GALLERY) is False
    assert budget.allows(EMIT_RAW) is False


def test_follows_refusals_name_the_mode() -> None:
    budget = decide(MODE_FOLLOW, engine_id="lk")

    assert budget.reason(ASSOC) == f"mode is {MODE_FOLLOW}"


# --- OFF and degraded ------------------------------------------------------


def test_off_emits_raw_boxes_and_nothing_else() -> None:
    budget = decide(MODE_OFF, engine_id="")

    assert budget.allows(DETECT_FULL) is True
    assert budget.allows(EMIT_RAW) is True
    for contributor in (EGOMOTION, PREDICT_CV, APPEARANCE, ASSOC, DETECT_ROI, MEMORY_GALLERY, FOLLOW):
        assert budget.allows(contributor) is False, contributor


def test_an_unresolvable_engine_degrades_to_the_off_path_with_its_own_reason() -> None:
    budget = decide(MODE_ASSOCIATE, engine_resolved=False)

    assert budget.allows(EMIT_RAW) is True
    assert budget.allows(ASSOC) is False
    assert budget.reason(ASSOC) == "no engine constructible"


def test_every_refused_contributor_has_a_reason_in_every_mode() -> None:
    for mode, engine in ((MODE_OFF, ""), (MODE_ASSOCIATE, "cost"), (MODE_ASSOCIATE, "bytetrack"), (MODE_FOLLOW, "lk")):
        budget = decide(mode, engine_id=engine)
        for contributor in EVERY_CONTRIBUTOR:
            if budget.allows(contributor):
                assert budget.reason(contributor) == ""
            else:
                assert budget.reason(contributor), f"{mode}/{engine}: {contributor} refused silently"


def test_retune_adopts_a_new_configuration_without_a_new_policy() -> None:
    policy = BudgetPolicy(params(MODE_ASSOCIATE))
    state = BudgetState(scheduler=SchedulerState(mode=MODE_ASSOCIATE), engine_id="cost")
    assert policy.decide(0.0, state).allows(DETECT_ROI) is True

    policy.retune(params(MODE_ASSOCIATE, roi_enabled=False))

    assert policy.decide(0.0, state).allows(DETECT_ROI) is False
