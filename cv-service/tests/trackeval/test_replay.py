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


def test_latency_frames_reports_a_stale_position_for_exactly_two_frames_before_any_correction_bracket_exists() -> None:
    """A track this young has no REAL prior observation for `late_correction`
    to bracket against yet (`reupdate.late_correction`'s own "brand-new
    track has nothing to bracket against" contract) -- so the birth frame
    and the frame that forms the SECOND ring entry are both booked verbatim,
    exactly the stale position `latency_frames` behind, same as before
    `detection_lag_millis` was ever wired through.

    **Renamed and rewritten for the 2026-08-14 `ObservationRing` timestamp
    fix** (see `test_per_frame_lag_correction_converges_instead_of_
    diverging_past_the_dead_zone` above for the fix itself). This test used
    to claim the dead zone was `2 * lag` frames long -- true only of the OLD
    ring, which timestamped entries at ARRIVAL: a bracket needed the
    SECOND entry's arrival to clear the query, and arrival lagged capture
    by `lag` on every entry, pushing that past roughly `2 * lag` frames
    after birth. With entries honestly timestamped at CAPTURE time instead,
    two entries on CONSECUTIVE frames already straddle any query between
    them, so the dead zone COLLAPSED to exactly two frames -- the birth
    frame (`lag`, the earliest frame with any detection at all -- see
    `_ground_truth_for_detection`) and the frame that forms the second
    entry (`lag + 1`) -- independent of `lag`'s own magnitude (checked
    directly against `run_replay` for `lag` in `{2, 4, 8, 12}` while
    rewriting this test: the dead zone was exactly `{lag, lag + 1}` every
    time, never wider or narrower).

    This test's job is narrower than before: pin that the dead zone is
    still real and still exactly two frames, not `2 * lag`, and not zero --
    a regression that widened it back toward `2 * lag`, or one that made it
    vanish outright, should both fail here.
    """
    sequence = SCENARIOS["latency"](seed=0)
    lag = 8
    result = run_replay(sequence, mode=MODE_ASSOCIATE, detector_config=DetectorNoiseConfig(latency_frames=lag))
    outcome_by_index = {frame.index: outcome for frame, outcome in zip(sequence.frames, result.outcomes)}

    dead_zone = (lag, lag + 1)
    for frame_index in dead_zone:
        outcome = outcome_by_index[frame_index]
        assert outcome.boxes, f"frame {frame_index} (inside the dead zone) must still produce a confirmed box"
        true_x = sequence.frames[frame_index].ground_truth[0].box.x
        reported_x = outcome.boxes[0].box.x
        stale_x = sequence.frames[frame_index - lag].ground_truth[0].box.x
        assert reported_x == pytest.approx(stale_x)
        assert abs(reported_x - true_x) > 0.05

    first_bracketed_index = lag + 2
    outcome = outcome_by_index[first_bracketed_index]
    assert outcome.boxes, f"frame {first_bracketed_index} (right after the dead zone) must produce a confirmed box"
    reported_x = outcome.boxes[0].box.x
    stale_x = sequence.frames[first_bracketed_index - lag].ground_truth[0].box.x
    assert reported_x != pytest.approx(stale_x), (
        "correction should already have kicked in by the frame right after the dead zone -- "
        "if this still matches the stale reading, the dead zone grew back past two frames"
    )


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


def test_per_frame_lag_correction_converges_instead_of_diverging_past_the_dead_zone() -> None:
    """**Guards the 2026-08-14 fix for the divergence this test used to
    document as a KNOWN, un-repaired defect** (`cv_service/tracking/
    history.py` + `reupdate.py` + `track.py`, out of THIS repair task's own
    file scope -- see the commit that replaced this test's body for the
    fix itself). Formerly named `test_persistent_per_frame_lag_correction_
    diverges_past_the_dead_zone`; renamed because it now asserts the
    opposite of what it did before the fix landed.

    **What this used to guard.** `ObservationRing.record()` (`history.py`)
    timestamped every entry with the frame it was PROCESSED at (`now`), not
    the instant its content was actually TRUE. A `reupdate()` bracket built
    from one of those mistimed entries divided a REAL position delta by an
    ARTIFICIALLY SMALL elapsed time, inflating the reconstructed velocity;
    the wrong box that produced was then written back into the SAME ring,
    poisoning the next bracket the same way -- a positive feedback loop that
    ran `latency`/ASSOCIATE's box to `1e14`-`1e32` within a few dozen
    frames. This test used to assert exactly that runaway, as the signal
    that the defect was still present.

    **What it guards now.** The fix threads the CAPTURE instant (not the
    arrival instant) down to `ObservationRing.record()` from every call
    site that knows of a lag (`history.py`'s `TimedObservation`/`record`
    docstrings carry the full account); `reupdate.py` also gained a floor
    on the elapsed time it will ever divide by
    (`_MIN_RECONSTRUCTION_GAP_SECONDS`), insurance against the general
    shape of this defect, not only its one traced cause. With both clocks
    aligned, `late_correction`'s reconstructed velocity is an honest
    estimate of the true one instead of an inflated one, and the projected
    box tracks the true position instead of running away.

    **A second, unplanned finding this fix surfaced (reported, not chased
    here): the dead zone itself collapses.** Before the fix, EVERY ring
    entry was timestamped at arrival, so a bracket needed roughly `2 *
    lag_frames` frames of accumulated history to exist at all (an entry's
    arrival time had to fall a full `lag` behind a LATER frame's own query)
    -- see `test_latency_frames_reports_a_stale_position_before_any_
    correction_bracket_exists` above, whose own dead-zone-length reasoning
    predates this fix. Once entries are honestly timestamped at CAPTURE
    time instead, any two REAL entries recorded on consecutive frames are
    already close enough together to bracket a query almost immediately --
    correction starts succeeding by roughly the SECOND post-birth frame,
    not `2 * lag_frames` later. That is a second test (`test_latency_
    frames_...`) and `tools/trackeval/BASELINE.md`'s own `latency`/
    ASSOCIATE scoreboard row both going stale as an unavoidable, CORRECT
    consequence of this fix, not a defect in it -- both are out of this
    repair's file scope (`tools/trackeval/**`, and only this one test
    inside this file, are all this task was scoped to touch), so they are
    reported here rather than silently patched.
    """
    sequence = SCENARIOS["latency"](seed=0)
    lag = 8
    result = run_replay(sequence, mode=MODE_ASSOCIATE, detector_config=DetectorNoiseConfig(latency_frames=lag))

    # `+ 2`, not `2 * lag + 5` as this test used before the fix: the dead
    # zone itself collapsed (see this test's own docstring) to roughly two
    # frames past birth (`lag`), so this margin only needs to clear THAT,
    # generously, not the old (now-incorrect) `2 * lag` estimate.
    checked = [
        (frame.ground_truth[0].box.x, outcome.boxes[0].box.x)
        for frame, outcome in zip(sequence.frames, result.outcomes)
        if frame.index > lag + 2 and outcome.boxes
    ]
    assert checked, "the scenario must produce some confirmed boxes past the dead zone to check"

    # The bound is derived, not a round number picked to make this pass.
    # `velocity` is this scenario's own constant per-frame rate, read off
    # its ground truth rather than re-imported from `sequences.py` (out of
    # this repair's file scope) -- `latency`'s own module comment states it
    # is geometrically identical to a single `linear` lane. The worst any
    # ONE frame can ever be off by, even with the fix in place, is a single
    # RAW (uncorrected) reading -- `late_correction`'s own contract returns
    # `None`, never a fabricated value, whenever a bracket is not honestly
    # available that frame (P5), and a raw reading is stale by exactly
    # `lag` frames of true motion by construction. Doubled for headroom
    # against jitter/interpolation slop, not because a single miss is
    # expected to cost more than that.
    first_x, last_x = sequence.frames[0].ground_truth[0].box.x, sequence.frames[-1].ground_truth[0].box.x
    velocity = (last_x - first_x) / (len(sequence.frames) - 1)
    max_single_raw_reading_error = lag * abs(velocity)
    bound = 2.0 * max_single_raw_reading_error

    worst = max(abs(reported_x - true_x) for true_x, reported_x in checked)
    assert worst < bound, (
        f"expected the reconstructed box to stay within {bound:.4f} of the true position "
        f"(twice one raw reading's own {max_single_raw_reading_error:.4f} staleness) -- got "
        f"{worst:.4f}. Either the fix regressed, or the scenario's own dynamics changed enough "
        "that this bound needs re-deriving, not merely widening"
    )
