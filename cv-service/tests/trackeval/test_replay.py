"""`tools.trackeval.replay` -- the REAL `StreamTrackingSession`, fed
synthetic frames.

Needs `numpy` to render (see `test_sequences.py`). The tracker engines it
exercises degrade gracefully (`TrackerRegistry.probe()`) if `cv2`/
`ultralytics`/`lap` happen to be missing, so this file never needs to skip
for THAT reason -- only for `numpy`, which frame rendering hard-requires.
"""

from __future__ import annotations

import pytest

pytest.importorskip("numpy")

from cv_service.tracking.params import MODE_ASSOCIATE, MODE_FOLLOW

from tools.trackeval.replay import DetectorNoiseConfig, run_replay
from tools.trackeval.sequences import SCENARIOS

_MODES = (MODE_ASSOCIATE, MODE_FOLLOW)


@pytest.mark.parametrize("mode", _MODES)
@pytest.mark.parametrize("name", sorted(SCENARIOS))
def test_every_scenario_replays_in_every_mode_without_raising(name: str, mode: str) -> None:
    sequence = SCENARIOS[name](seed=0)
    result = run_replay(sequence, mode=mode)
    assert len(result.outcomes) == len(sequence.frames)
    assert result.scored_gt_ids
    if mode == MODE_FOLLOW:
        assert result.scored_gt_ids == frozenset({sequence.primary_gt_id})
    else:
        assert result.scored_gt_ids == sequence.gt_ids


def test_replay_is_deterministic_for_a_fixed_seed() -> None:
    sequence = SCENARIOS["crossing"](seed=3)
    first = run_replay(sequence, mode=MODE_ASSOCIATE)
    second = run_replay(sequence, mode=MODE_ASSOCIATE)
    assert _track_id_sequence(first) == _track_id_sequence(second)


def test_follow_replay_is_also_deterministic() -> None:
    sequence = SCENARIOS["occlusion"](seed=5)
    first = run_replay(sequence, mode=MODE_FOLLOW)
    second = run_replay(sequence, mode=MODE_FOLLOW)
    assert _track_id_sequence(first) == _track_id_sequence(second)


def test_detector_dropout_actually_drops_detections() -> None:
    sequence = SCENARIOS["dropout"](seed=0)
    clean = run_replay(sequence, mode=MODE_ASSOCIATE, detector_config=DetectorNoiseConfig())
    dropped = run_replay(
        sequence,
        mode=MODE_ASSOCIATE,
        detector_config=DetectorNoiseConfig(seed=1, dropout_probability=1.0),
    )
    clean_boxes = sum(len(outcome.boxes or []) for outcome in clean.outcomes)
    dropped_boxes = sum(len(outcome.boxes or []) for outcome in dropped.outcomes)
    assert dropped_boxes < clean_boxes


def test_false_positives_add_extra_untracked_or_new_boxes() -> None:
    sequence = SCENARIOS["linear"](seed=0)
    clean = run_replay(sequence, mode=MODE_ASSOCIATE, detector_config=DetectorNoiseConfig())
    noisy = run_replay(
        sequence,
        mode=MODE_ASSOCIATE,
        detector_config=DetectorNoiseConfig(seed=2, false_positive_probability=1.0),
    )
    clean_boxes = sum(len(outcome.boxes or []) for outcome in clean.outcomes)
    noisy_boxes = sum(len(outcome.boxes or []) for outcome in noisy.outcomes)
    assert noisy_boxes > clean_boxes


def _track_id_sequence(result) -> list[tuple[int, ...]]:  # type: ignore[no-untyped-def]
    return [
        tuple(sorted(tracked.track.track_id for tracked in outcome.boxes if tracked.track is not None))
        for outcome in result.outcomes
        if outcome.boxes is not None
    ]


# -- TRACKING-V3-PLAN wave V0: latency ----------------------------------------


def test_ground_truth_for_detection_reports_an_older_frame() -> None:
    """The unit the `latency` scenario's whole failure mode rests on --
    isolated from the session so a regression here is unambiguous."""
    from tools.trackeval.replay import _ground_truth_for_detection

    sequence = SCENARIOS["linear"](seed=0)
    late = _ground_truth_for_detection(sequence, frame_index=10, latency_frames=4)
    assert late == sequence.frames[6].ground_truth
    assert late != sequence.frames[10].ground_truth


def test_ground_truth_for_detection_is_empty_before_history_exists() -> None:
    from tools.trackeval.replay import _ground_truth_for_detection

    sequence = SCENARIOS["linear"](seed=0)
    assert _ground_truth_for_detection(sequence, frame_index=2, latency_frames=4) == ()


def test_ground_truth_for_detection_is_a_no_op_at_zero_latency() -> None:
    """0 (the default) must reproduce today's behaviour exactly -- every
    scenario written before `latency_frames` existed relies on this."""
    from tools.trackeval.replay import _ground_truth_for_detection

    sequence = SCENARIOS["crossing"](seed=0)
    for frame in sequence.frames:
        assert _ground_truth_for_detection(sequence, frame.index, latency_frames=0) == frame.ground_truth


def test_latency_frames_reports_a_stale_position() -> None:
    """End-to-end: a track fed through a replay with `latency_frames` set
    should be reporting boxes noticeably behind the true, current position
    -- not because it lost the object, but because the DETECTOR itself is
    behind."""
    sequence = SCENARIOS["latency"](seed=0)
    lag = 8
    result = run_replay(sequence, mode=MODE_ASSOCIATE, detector_config=DetectorNoiseConfig(latency_frames=lag))

    checked = 0
    for frame, outcome in zip(sequence.frames, result.outcomes):
        if frame.index < lag or not outcome.boxes:
            continue
        true_x = frame.ground_truth[0].box.x
        reported_x = outcome.boxes[0].box.x
        stale_x = sequence.frames[frame.index - lag].ground_truth[0].box.x
        # Reported position sits on what a detector `lag` frames behind would
        # have seen (no jitter in this config, so this is exact), and clearly
        # NOT on the true, current position.
        assert reported_x == pytest.approx(stale_x)
        assert abs(reported_x - true_x) > 0.05
        checked += 1
    assert checked > 0, "the scenario must actually produce some confirmed boxes to check"
