"""A fully-populated `TrackingParams` for tests that only care about a few fields.

Every field `TrackingParams` requires, none of them meaningful beyond the
ones a given test overrides -- the same pattern `tests/tracking/test_scheduler.py`
established, copied rather than imported so the orchestration suite does not
depend on another suite's private helper.
"""

from __future__ import annotations

from cv_service.tracking.assign import AssignGates, AssignWeights
from cv_service.tracking.memory import MemoryParams
from cv_service.tracking.params import TrackingParams


def params(mode: str, **overrides) -> TrackingParams:
    base = dict(
        mode=mode,
        engine_id="cost",
        verify_every_millis=2000,
        reacquire_every_millis=250,
        redetect_iou_threshold=0.3,
        max_age_frames=30,
        min_hits=1,
        track_max_age_millis=3000,
        min_tracker_confidence=0.5,
        motion_engine_id="flow",
        appearance_engine_id="histogram",
        cost_weights=AssignWeights(),
        cost_gates=AssignGates(),
        memory_params=MemoryParams(),
        follow_top_k=1,
        roi_enabled=True,
        roi_crop_factor=4.0,
        roi_min_iou=0.2,
        capability_level=0,
        reupdate_max_gap_millis=10_000,
        detection_lag_correction_enabled=True,
        reupdate_max_velocity_per_second=0.0,
        reupdate_max_track_count=0,
        reupdate_max_shape_log_ratio=0.0,
        reupdate_max_motion_center_distance=0.0,
        label_vote_window=10,
        label_switch_margin=1.5,
        label_switch_streak=3,
    )
    base.update(overrides)
    return TrackingParams(**base)
