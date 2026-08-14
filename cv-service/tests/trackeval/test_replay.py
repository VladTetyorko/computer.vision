"""`tools.trackeval.replay` -- the REAL `StreamTrackingSession`, fed
synthetic frames.

Needs `numpy` to render (see `test_sequences.py`). The tracker engines it
exercises degrade gracefully (`TrackerRegistry.probe()`) if `cv2`/
`ultralytics`/`lap` happen to be missing, so this file never needs to skip
for THAT reason -- only for `numpy`, which frame rendering hard-requires.
"""

from __future__ import annotations

import random

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


def test_latency_frames_reports_a_stale_position_before_any_correction_bracket_exists() -> None:
    """A track this young has no REAL prior observation for `late_correction`
    to bracket against yet (`reupdate.late_correction`'s own "brand-new
    track has nothing to bracket against" contract) -- so every frame up to
    and including the one that forms the SECOND ring entry is booked
    verbatim, exactly the stale position `latency_frames` behind, same as
    before `detection_lag_millis` was ever wired through.

    The dead zone is exactly `2 * lag` frames long: the track is born at
    frame `lag` (the earliest frame with any detection at all -- see
    `_ground_truth_for_detection`), and the earliest a SECOND real entry can
    exist strictly before `captured_at = now - lag` is frame `2 * lag + 1`
    (verified directly against `run_replay`'s own trace while diagnosing
    this instrument's repair -- see `test_persistent_per_frame_lag_
    correction_diverges_past_the_dead_zone` below for what happens right
    after this window closes).
    """
    sequence = SCENARIOS["latency"](seed=0)
    lag = 8
    result = run_replay(sequence, mode=MODE_ASSOCIATE, detector_config=DetectorNoiseConfig(latency_frames=lag))

    checked = 0
    for frame, outcome in zip(sequence.frames, result.outcomes):
        if frame.index < lag or frame.index > 2 * lag or not outcome.boxes:
            continue
        true_x = frame.ground_truth[0].box.x
        reported_x = outcome.boxes[0].box.x
        stale_x = sequence.frames[frame.index - lag].ground_truth[0].box.x
        assert reported_x == pytest.approx(stale_x)
        assert abs(reported_x - true_x) > 0.05
        checked += 1
    assert checked > 0, "the scenario must actually produce some confirmed boxes to check"


def test_detection_lag_millis_for_is_zero_with_no_latency_configured() -> None:
    """0 (the default) must reproduce today's behaviour exactly -- every
    scenario that never sets `latency_frames` relies on `session.process()`
    never being told a lag it has no way to know."""
    from tools.trackeval.replay import _detection_lag_millis_for

    rng = random.Random(0)
    config = DetectorNoiseConfig()
    for frame_index in (0, 1, 10, 59):
        assert _detection_lag_millis_for(frame_index, fps=10.0, detector_config=config, lag_rng=rng) == 0


def test_detection_lag_millis_for_is_zero_before_history_exists() -> None:
    """Mirrors `_ground_truth_for_detection`'s own `frame_index < latency_frames`
    guard -- a lag estimate for a detection that never happened (the
    detector has not produced its first answer yet) is not a real signal
    either, so this reports `0` (unknown), not a lag for evidence that does
    not exist."""
    from tools.trackeval.replay import _detection_lag_millis_for

    rng = random.Random(0)
    config = DetectorNoiseConfig(latency_frames=8)
    for frame_index in (0, 1, 7):
        assert _detection_lag_millis_for(frame_index, fps=10.0, detector_config=config, lag_rng=rng) == 0


def test_detection_lag_millis_for_reports_the_exact_injected_lag_by_default() -> None:
    """`detection_lag_jitter_millis=0.0` (the default) reports the lag this
    harness injected EXACTLY -- the idealised case, perfect lag knowledge a
    real pull-mode worker's `capture_skew_millis` estimate never quite has
    (see `DetectorNoiseConfig.detection_lag_jitter_millis`'s own docstring
    and `BASELINE.md`'s `latency` writeup)."""
    from tools.trackeval.replay import _detection_lag_millis_for

    rng = random.Random(0)
    config = DetectorNoiseConfig(latency_frames=8)
    assert _detection_lag_millis_for(8, fps=10.0, detector_config=config, lag_rng=rng) == 800
    assert _detection_lag_millis_for(20, fps=10.0, detector_config=config, lag_rng=rng) == 800


def test_detection_lag_millis_for_jitter_is_bounded_deterministic_and_independent_of_the_detector_rng() -> None:
    """`detection_lag_jitter_millis > 0` spreads the reported lag around the
    exact value -- bounded by the configured jitter, reproducible for a
    fixed `lag_jitter_seed`, and drawn from a stream that never touches
    `SyntheticDetector`'s own RNG (constructing one here, seeded the same,
    and never calling it, proves the two are independent: if `_detection_
    lag_millis_for` secretly consumed it, a later assertion against a fresh,
    unconsumed `unused_detector_rng` would still have to agree, which it
    only can if nothing here ever drew from it)."""
    from tools.trackeval.replay import _detection_lag_millis_for

    jitter = 15.0
    config = DetectorNoiseConfig(latency_frames=8, detection_lag_jitter_millis=jitter, lag_jitter_seed=7)
    unused_detector_rng = random.Random(0)  # never touched below

    first_run = [
        _detection_lag_millis_for(i, fps=10.0, detector_config=config, lag_rng=random.Random(7))
        for i in range(8, 20)
    ]
    second_run = [
        _detection_lag_millis_for(i, fps=10.0, detector_config=config, lag_rng=random.Random(7))
        for i in range(8, 20)
    ]
    assert first_run == second_run  # deterministic given the seed
    assert first_run != [800] * len(first_run)  # jitter actually moved something
    for value in first_run:
        assert 800 - jitter <= value <= 800 + jitter
    assert unused_detector_rng.random() == random.Random(0).random()  # truly never consumed


def test_persistent_per_frame_lag_correction_diverges_past_the_dead_zone() -> None:
    """**Documents a genuine defect this repair task's fix exposed, in code
    outside this file's scope to repair (`cv_service/tracking/reupdate.py`
    + `track.py`), not a property of this harness.**

    Once wired (`detection_lag_millis` now reaches `session.process()` --
    see the module docstring's "detection_lag_millis is now wired too"
    section), `latency`/ASSOCIATE's box does not merely stay stale past the
    dead zone above: it diverges, unboundedly, to physically meaningless
    magnitudes (traced directly while diagnosing this: `1e14`-`1e32` within
    a few dozen frames, both with `detection_lag_jitter_millis=0` and with
    jitter on -- reproduced independently with round numbers directly
    against `cv_service.tracking.session.StreamTrackingSession`, bypassing
    this package entirely, while writing this test).

    **Root cause, traced by hand.** `ObservationRing.record()` (`history.py`)
    timestamps every entry with the frame it was PROCESSED at (`now`), not
    the instant its content was actually TRUE -- correct when a `late_
    correction` succeeds (a corrected box legitimately represents "position
    AT now"), but wrong for any entry `late_correction` could not correct
    (no bracket existed yet, e.g. every entry through this scenario's own
    dead zone above): that entry's stale content gets filed under a
    timestamp `lag_seconds` LATER than when it was actually true, with
    nothing recording the mislabelling. A later `reupdate()` call that
    brackets against it divides a REAL position delta (spanning close to a
    full `lag_seconds` of true motion) by an ARTIFICIALLY SMALL elapsed time
    (the bracket's inflated timestamp), inflating the reconstructed velocity
    by roughly `lag_seconds / true_elapsed`. The resulting (wrong, often
    off-frame) box is then written back into the SAME ring, poisoning every
    later bracket the same way -- a positive feedback loop, not a one-off
    error, which is why this diverges rather than merely staying imprecise.
    This is not `nonlinear`'s/`pan_occlusion`'s "single ORU application"
    story -- it is `late_correction` invoked EVERY matched-detection frame
    (`_run_cost_associate`'s own per-candidate call), so the poisoned
    bracket a wrong correction creates gets used again within one frame.

    This does not move `latency`/ASSOCIATE's own `BASELINE.md` row: `ML=1`
    already scored the pre-wiring stale case at the metric's floor (IoU
    against the true box was already ~0 every frame -- `BASELINE.md`'s own
    `latency` writeup), and an even-further-off box cannot score lower than
    that floor. See `BASELINE.md`'s `latency` writeup for the full report.

    **If `cv_service/tracking/` is ever fixed** to reconcile `ObservationRing`'s
    arrival-time timestamps with `late_correction`'s implicit capture-time
    assumption, this assertion should start FAILING -- that failure is the
    signal to replace this test with one that checks the corrected box
    actually converges, not a regression to chase blindly.
    """
    sequence = SCENARIOS["latency"](seed=0)
    lag = 8
    result = run_replay(sequence, mode=MODE_ASSOCIATE, detector_config=DetectorNoiseConfig(latency_frames=lag))

    # Well past the dead zone (frame `2 * lag + 1` onward, see the test
    # above) and well past the entire 60-frame scenario: if the mechanism
    # were merely imprecise rather than diverging, every box would stay
    # within a few frame-widths of the normalized [0, 1] canvas.
    late_boxes = [
        outcome.boxes[0].box.x
        for frame, outcome in zip(sequence.frames, result.outcomes)
        if frame.index > 2 * lag + 5 and outcome.boxes
    ]
    assert late_boxes, "the scenario must produce some confirmed boxes past the dead zone to check"
    assert max(abs(x) for x in late_boxes) > 10.0, (
        "expected the KNOWN divergence defect (see this test's own docstring) -- if this no "
        "longer reproduces, `cv_service/tracking/`'s correction may have been fixed; update "
        "BASELINE.md's `latency` writeup and replace this test accordingly, do not just relax it"
    )
