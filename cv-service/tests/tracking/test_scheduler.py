"""`cv_service.tracking.scheduler` -- TRACKING-PLAN §3.1's table, exhaustively.

The whole point of splitting the policy out of `session.py` is that these
tests need a fake clock and nothing else: no frames, no OpenCV, no gRPC.
"""

from __future__ import annotations

import pytest

from cv_service.tracking.params import MODE_ASSOCIATE, MODE_FOLLOW, MODE_OFF, TrackingParams
from cv_service.tracking.scheduler import (
    REASON_ALWAYS,
    REASON_BOX_INVALID,
    REASON_CADENCE,
    REASON_COASTED_OUT,
    REASON_NO_LOCK,
    REASON_TRACKER_FAILED,
    REASON_UNSPECIFIED,
    DutyCycleScheduler,
    SchedulerState,
)


def params(mode: str, **overrides) -> TrackingParams:
    base = dict(
        mode=mode,
        engine_id="e",
        verify_every_millis=2000,
        redetect_iou_threshold=0.3,
        max_age_frames=30,
        min_hits=3,
        # Neither knob is read by `DutyCycleScheduler` -- `TrackBook` and
        # `StreamTrackingSession` are the only consumers -- so any value is
        # inert here; included only because `TrackingParams` requires it.
        track_max_age_millis=3000,
        min_tracker_confidence=0.5,
    )
    base.update(overrides)
    return TrackingParams(**base)


@pytest.mark.parametrize("mode", [MODE_OFF, MODE_ASSOCIATE])
def test_off_and_associate_detect_on_every_frame(mode):
    scheduler = DutyCycleScheduler(params(mode))

    for now in (0.0, 1.0, 100000.0):
        decision = scheduler.decide(now, SchedulerState(mode=mode))
        assert decision.run_detector is True
        assert decision.reason == REASON_ALWAYS


def test_follow_without_a_lock_re_acquires():
    scheduler = DutyCycleScheduler(params(MODE_FOLLOW))

    decision = scheduler.decide(0.0, SchedulerState(mode=MODE_FOLLOW, has_lock=False))

    assert decision.run_detector is True
    assert decision.reason == REASON_NO_LOCK


def test_follow_holds_the_detector_until_the_cadence_elapses():
    scheduler = DutyCycleScheduler(params(MODE_FOLLOW, verify_every_millis=2000))
    held = SchedulerState(mode=MODE_FOLLOW, has_lock=True, last_detector_millis=1000.0)

    assert scheduler.decide(1500.0, held) == scheduler.decide(2999.0, held)
    assert scheduler.decide(2999.0, held).run_detector is False
    assert scheduler.decide(2999.0, held).reason == REASON_UNSPECIFIED

    fired = scheduler.decide(3000.0, held)
    assert fired.run_detector is True
    assert fired.reason == REASON_CADENCE


def test_a_lock_with_no_pass_yet_reports_cadence_not_a_special_reason():
    scheduler = DutyCycleScheduler(params(MODE_FOLLOW))

    decision = scheduler.decide(
        0.0, SchedulerState(mode=MODE_FOLLOW, has_lock=True, last_detector_millis=None)
    )

    assert decision.run_detector is True
    assert decision.reason == REASON_CADENCE


def test_a_failed_tracker_forces_a_pass_before_the_cadence():
    scheduler = DutyCycleScheduler(params(MODE_FOLLOW))

    decision = scheduler.decide(
        1.0,
        SchedulerState(
            mode=MODE_FOLLOW, has_lock=True, last_detector_millis=0.0, tracker_failed=True
        ),
    )

    assert decision.run_detector is True
    assert decision.reason == REASON_TRACKER_FAILED


def test_an_invalid_box_forces_a_pass_before_the_cadence():
    scheduler = DutyCycleScheduler(params(MODE_FOLLOW))

    decision = scheduler.decide(
        1.0,
        SchedulerState(
            mode=MODE_FOLLOW, has_lock=True, last_detector_millis=0.0, box_invalid=True
        ),
    )

    assert decision.run_detector is True
    assert decision.reason == REASON_BOX_INVALID


def test_coasting_past_max_age_forces_a_pass():
    scheduler = DutyCycleScheduler(params(MODE_FOLLOW, max_age_frames=4))
    state = SchedulerState(
        mode=MODE_FOLLOW, has_lock=True, last_detector_millis=0.0, coasting_frames=4
    )

    assert scheduler.decide(1.0, state).run_detector is False

    decision = scheduler.decide(1.0, SchedulerState(**{**state.__dict__, "coasting_frames": 5}))
    assert decision.run_detector is True
    assert decision.reason == REASON_COASTED_OUT


def test_the_most_actionable_reason_wins_when_several_triggers_agree():
    scheduler = DutyCycleScheduler(params(MODE_FOLLOW, verify_every_millis=1, max_age_frames=1))
    everything = SchedulerState(
        mode=MODE_FOLLOW,
        has_lock=False,
        last_detector_millis=0.0,
        tracker_failed=True,
        box_invalid=True,
        coasting_frames=99,
    )

    assert scheduler.decide(10_000.0, everything).reason == REASON_NO_LOCK


def test_retune_adopts_a_changed_cadence_without_a_new_scheduler():
    scheduler = DutyCycleScheduler(params(MODE_FOLLOW, verify_every_millis=2000))
    held = SchedulerState(mode=MODE_FOLLOW, has_lock=True, last_detector_millis=0.0)

    assert scheduler.decide(500.0, held).run_detector is False

    scheduler.retune(params(MODE_FOLLOW, verify_every_millis=100))

    assert scheduler.decide(500.0, held).run_detector is True
