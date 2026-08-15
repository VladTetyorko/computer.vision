"""`tools.trackeval.metrics` -- correctness on hand-built inputs.

Deliberately pure stdlib: every collaborator (`GroundTruthObject`,
`FrameOutcome`/`TrackedBox`, `Track`, `Box`) is pure stdlib at module scope,
so this file needs neither the `cv` extra nor a real replay to prove the
counting logic is right. Each test builds a tiny, fully-known timeline where
the expected IDSW/FM/recovery numbers are obvious by inspection.

Every ground-truth object gets its OWN, well-separated box
(`_BOX_FOR_GT`) even in single-object tests -- greedy IoU matching cannot
tell two objects apart if their boxes coincide, so reusing one box across
multiple gt ids would make which-track-matched-which ambiguous by
construction instead of by the algorithm being tested.

`_FakeTrack` stands in for `cv_service.tracking.track.Track` rather than
constructing the real (larger, evolving) dataclass: `metrics.py` only ever
reads `tracked.track.track_id` off it (`TrackedBox.track` is duck-typed,
not isinstance-checked), and pinning to the real `Track`'s full constructor
would make this file break every time a later wave (C1-C5) adds a field to
it for reasons that have nothing to do with metrics correctness.
"""

from __future__ import annotations

from dataclasses import dataclass

import pytest

from cv_service.tracking.engines.base import Box
from cv_service.tracking.params import MODE_ASSOCIATE, MODE_FOLLOW
from cv_service.tracking.session import FrameOutcome, TrackedBox

from tools.trackeval.metrics import MAX_PLAUSIBLE_VELOCITY_PER_SECOND, compute
from tools.trackeval.replay import ReplayResult
from tools.trackeval.sequences import GroundTruthObject

_LABEL = "object"

# One well-separated box per gt id (IoU 0.0 between any two of these), so
# greedy matching can never confuse which track was meant for which object.
_BOX_FOR_GT: dict[int, Box] = {
    1: Box(0.05, 0.05, 0.1, 0.1),
    2: Box(0.50, 0.50, 0.1, 0.1),
    3: Box(0.80, 0.05, 0.1, 0.1),
}


@dataclass(frozen=True)
class _FakeTrack:
    track_id: int


def _gt(gt_id: int, visible: bool = True) -> GroundTruthObject:
    return GroundTruthObject(gt_id=gt_id, label=_LABEL, box=_BOX_FOR_GT[gt_id], visible=visible)


def _tracked(track_id: int, gt_id: int) -> TrackedBox:
    """A track claiming `gt_id`'s box exactly -- an IoU=1.0 match by
    construction, so every test controls matching outcomes directly instead
    of depending on IoU-threshold arithmetic."""
    return TrackedBox(label=_LABEL, confidence=0.9, box=_BOX_FOR_GT[gt_id], track=_FakeTrack(track_id))


def _outcome(
    matches: dict[int, int], *, detector_ran: bool = True, tracker_millis: int = 1
) -> FrameOutcome:
    """One frame: `{gt_id: track_id}` for every gt object matched this
    frame; any gt id not present here is simply unmatched (occluded,
    dropped, or never tracked -- the caller's own concern, not this
    helper's)."""
    return FrameOutcome(
        boxes=[_tracked(track_id, gt_id) for gt_id, track_id in matches.items()],
        detector_ran=detector_ran,
        tracker_millis=tracker_millis,
        engine_id="unit-test",
    )


def _result(
    gt_per_frame: list[list[GroundTruthObject]],
    outcomes: list[FrameOutcome],
    *,
    gt_ids: frozenset[int],
    fps: float = 10.0,
    mode: str = MODE_ASSOCIATE,
    coast_track_ids: tuple[frozenset[int], ...] = (),
    track_velocities: tuple[dict[int, tuple[float, float]], ...] = (),
    width: int = 100,
    height: int = 100,
) -> ReplayResult:
    return ReplayResult(
        scenario="unit",
        mode=mode,
        engine_id="unit-test",
        fps=fps,
        outcomes=tuple(outcomes),
        ground_truth_by_frame=tuple(tuple(frame) for frame in gt_per_frame),
        scored_gt_ids=gt_ids,
        coast_track_ids=coast_track_ids,
        track_velocities=track_velocities,
        width=width,
        height=height,
    )


def _tracked_at(track_id: int, box: Box) -> TrackedBox:
    """A track's box at an EXPLICIT position -- unlike `_tracked`, not tied
    to any gt's own box, for building drifted/coasting samples."""
    return TrackedBox(label=_LABEL, confidence=0.9, box=box, track=_FakeTrack(track_id))


def _outcome_with_boxes(
    boxes: list[TrackedBox], *, detector_ran: bool = True, tracker_millis: int = 1
) -> FrameOutcome:
    return FrameOutcome(boxes=boxes, detector_ran=detector_ran, tracker_millis=tracker_millis, engine_id="unit-test")


def _offset(gt_id: int, dx: float = 0.0, dy: float = 0.0) -> Box:
    base = _BOX_FOR_GT[gt_id]
    return Box(base.x + dx, base.y + dy, base.width, base.height)


def test_perfect_tracking_has_no_switches_or_gaps_and_is_mostly_tracked() -> None:
    gt = [[_gt(1)]] * 5
    outcomes = [_outcome({1: 7}) for _ in range(5)]
    metrics = compute(_result(gt, outcomes, gt_ids=frozenset({1})))

    assert metrics.idsw == 0
    assert metrics.fragmentations == 0
    assert metrics.gap_count == 0
    assert metrics.recovery_rate is None  # no gap ever happened -- the concept does not apply
    assert metrics.mostly_tracked == 1
    assert metrics.partially_tracked == 0
    assert metrics.mostly_lost == 0


def test_a_continuous_id_change_with_no_gap_is_an_idsw_but_not_a_fragmentation() -> None:
    """One gt object, visible and matched every frame, but the id it is
    matched to changes partway through -- no coverage gap, a pure identity
    switch. This is the `crossing` scenario's failure mode in miniature."""
    gt = [[_gt(1)]] * 5
    outcomes = [_outcome({1: 1}), _outcome({1: 1}), _outcome({1: 1}), _outcome({1: 2}), _outcome({1: 2})]
    metrics = compute(_result(gt, outcomes, gt_ids=frozenset({1})))

    assert metrics.idsw == 1
    assert metrics.fragmentations == 0
    assert metrics.gap_count == 0
    assert metrics.mostly_tracked == 1  # coverage is still 5/5 -- MT measures coverage, not identity


def test_a_gap_that_resumes_with_the_same_id_is_a_recovery() -> None:
    gt = [[_gt(1)]] * 5
    outcomes = [_outcome({1: 5}), _outcome({1: 5}), _outcome({}), _outcome({1: 5}), _outcome({1: 5})]
    metrics = compute(_result(gt, outcomes, gt_ids=frozenset({1})))

    assert metrics.gap_count == 1
    assert metrics.fragmentations == 1
    assert metrics.recovered_count == 1
    assert metrics.recovery_rate == 1.0
    assert metrics.idsw == 0  # same id before and after the gap -- not a switch


def test_a_gap_that_resumes_with_a_different_id_is_not_a_recovery() -> None:
    """This is `occlusion`'s pre-C4 failure mode: the coverage gap ends, but
    the emitted id is a brand new one (`recovery_rate` == 0.0 is the plan's
    headline pre-C4 number)."""
    gt = [[_gt(1)]] * 5
    outcomes = [_outcome({1: 5}), _outcome({1: 5}), _outcome({}), _outcome({1: 9}), _outcome({1: 9})]
    metrics = compute(_result(gt, outcomes, gt_ids=frozenset({1})))

    assert metrics.gap_count == 1
    assert metrics.fragmentations == 1
    assert metrics.recovered_count == 0
    assert metrics.recovery_rate == 0.0
    assert metrics.idsw == 1  # the resumed id differs from the one before the gap


def test_leading_and_trailing_gaps_are_not_recovery_questions() -> None:
    """Never matched at all, or matched once then never again before the
    window ends: real lost coverage (folds into ML), but there is nothing on
    one side of the gap to recover TOWARD, so it must not count toward
    `gap_count`/`recovery_rate`."""
    gt = [[_gt(1)]] * 4
    outcomes = [_outcome({}), _outcome({}), _outcome({1: 3}), _outcome({})]
    metrics = compute(_result(gt, outcomes, gt_ids=frozenset({1})))

    assert metrics.gap_count == 0
    assert metrics.recovery_rate is None
    assert metrics.fragmentations == 0
    assert metrics.idsw == 0


def test_mostly_lost_and_partially_tracked_thresholds() -> None:
    # gt 1: matched 1/10 frames -> mostly lost (< 20%)
    # gt 2: matched 5/10 frames -> partially tracked
    # gt 3: matched 9/10 frames -> mostly tracked (>= 80%)
    frame_count = 10
    gt = [[_gt(1), _gt(2), _gt(3)] for _ in range(frame_count)]
    outcomes = []
    for index in range(frame_count):
        matches: dict[int, int] = {}
        if index == 0:
            matches[1] = 101
        if index % 2 == 0:
            matches[2] = 102
        if index != 5:
            matches[3] = 103
        outcomes.append(_outcome(matches))

    metrics = compute(_result(gt, outcomes, gt_ids=frozenset({1, 2, 3})))

    assert metrics.mostly_lost == 1
    assert metrics.partially_tracked == 1
    assert metrics.mostly_tracked == 1


def test_track_lifetime_counts_frame_appearances_not_span() -> None:
    # track 1 lives frames 0-2 (3 appearances), track 2 lives only frame 3.
    gt = [[_gt(1)]] * 4
    outcomes = [_outcome({1: 1}), _outcome({1: 1}), _outcome({1: 1}), _outcome({1: 2})]
    metrics = compute(_result(gt, outcomes, gt_ids=frozenset({1})))

    assert metrics.mean_track_lifetime_frames == 2.0  # mean(3, 1)
    assert metrics.median_track_lifetime_frames == 2.0


def test_lifetime_is_none_when_nothing_was_ever_tracked() -> None:
    gt = [[_gt(1)]] * 3
    outcomes = [_outcome({}) for _ in range(3)]
    metrics = compute(_result(gt, outcomes, gt_ids=frozenset({1})))

    assert metrics.mean_track_lifetime_frames is None
    assert metrics.median_track_lifetime_frames is None


def test_invisible_ground_truth_can_never_be_matched() -> None:
    """A detection that happens to land on the same box as an invisible gt
    object (e.g. a stray false positive) must not count as a match -- an
    invisible object has, by definition, produced no real detection."""
    gt = [[_gt(1, visible=False)]] * 3
    outcomes = [_outcome({1: 1}) for _ in range(3)]
    metrics = compute(_result(gt, outcomes, gt_ids=frozenset({1})))

    assert metrics.gap_count == 0  # never matched at all -- a leading/trailing non-gap
    # ...and an object that is never visible ANYWHERE is not scored in either
    # direction. It used to count as `mostly_lost`, which charged the tracker
    # for missing something nothing could have seen.
    assert metrics.mostly_lost == 0
    assert metrics.gt_object_count == 0


def test_coverage_is_measured_over_visible_frames_not_the_whole_window() -> None:
    """The metric defect that made `pan`'s MOSTLY_TRACKED unreachable.

    This object exists for ten frames and is visible for two, both of which
    are tracked perfectly. That is 100% of what could be tracked, and it must
    score as such -- under the old denominator it scored 20% and landed in
    MOSTLY_LOST, so a perfect tracker read as a failure and no amount of work
    could ever have moved the row.
    """
    gt = [[_gt(1, visible=index < 2)] for index in range(10)]
    outcomes = [_outcome({1: 1} if index < 2 else {}) for index in range(10)]
    metrics = compute(_result(gt, outcomes, gt_ids=frozenset({1})))

    assert metrics.mostly_tracked == 1
    assert metrics.mostly_lost == 0


def test_every_scored_object_lands_in_exactly_one_coverage_bucket() -> None:
    gt = [[_gt(1, visible=True), _gt(2, visible=index > 5)] for index in range(10)]
    outcomes = [_outcome({1: 1}) for _ in range(10)]
    metrics = compute(_result(gt, outcomes, gt_ids=frozenset({1, 2})))

    assert (
        metrics.mostly_tracked + metrics.partially_tracked + metrics.mostly_lost
        == metrics.gt_object_count
    )


def test_cost_metrics_from_detector_ran_and_tracker_millis() -> None:
    outcomes = [
        _outcome({1: 1}, detector_ran=True, tracker_millis=2),
        _outcome({1: 1}, detector_ran=False, tracker_millis=4),
        _outcome({1: 1}, detector_ran=True, tracker_millis=6),
        _outcome({1: 1}, detector_ran=False, tracker_millis=8),
    ]
    gt = [[_gt(1)]] * 4
    metrics = compute(_result(gt, outcomes, gt_ids=frozenset({1}), fps=2.0))

    assert metrics.total_frames == 4
    assert metrics.detector_passes == 2
    # 4 frames at 2 fps = 2 seconds of simulated stream time -> 1 pass/sec
    assert metrics.detector_passes_per_sec == 1.0
    assert metrics.mean_tracker_millis == 5.0
    assert metrics.p95_tracker_millis == 8.0


def test_follow_mode_scores_only_the_locked_gt_id() -> None:
    """`scored_gt_ids` restricts everything -- a second, unlocked gt object
    present in the scene must be invisible to the metrics entirely, not
    silently counted as mostly-lost."""
    gt = [[_gt(1), _gt(2)]] * 3
    outcomes = [_outcome({1: 1}) for _ in range(3)]
    metrics = compute(_result(gt, outcomes, gt_ids=frozenset({1}), mode=MODE_FOLLOW))

    assert metrics.gt_object_count == 1
    assert metrics.mostly_tracked == 1
    assert metrics.mostly_lost == 0


# -- coast ADE/FDE (TRACKING-V3-PLAN wave V0) ---------------------------------


def test_coast_metrics_are_none_without_coast_data() -> None:
    """`coast_track_ids` defaulting to `()` (older/hand-built `ReplayResult`s
    that predate this metric) must read as "nothing to report", not as
    zero drift -- `None` is the same "concept does not apply" convention
    `recovery_rate` already uses."""
    gt = [[_gt(1)]] * 3
    outcomes = [_outcome({1: 1}) for _ in range(3)]
    metrics = compute(_result(gt, outcomes, gt_ids=frozenset({1})))

    assert metrics.coast_sample_count == 0
    assert metrics.coast_ade_norm is None
    assert metrics.coast_fde_norm is None
    assert metrics.coast_ade_px is None
    assert metrics.coast_fde_px is None


def test_coast_ade_is_the_mean_and_fde_is_the_final_sample() -> None:
    """Three coasting frames with GROWING drift -- ADE averages all three,
    FDE is only the last (worst) one, and the two must therefore differ."""
    gt = [[_gt(1)]] * 3
    outcomes = [
        _outcome_with_boxes([_tracked_at(1, _offset(1, dx))], detector_ran=True) for dx in (0.005, 0.010, 0.015)
    ]
    coast_track_ids = (frozenset({1}), frozenset({1}), frozenset({1}))
    metrics = compute(_result(gt, outcomes, gt_ids=frozenset({1}), coast_track_ids=coast_track_ids, width=100, height=50))

    assert metrics.coast_sample_count == 3
    assert metrics.coast_ade_norm == pytest.approx((0.005 + 0.010 + 0.015) / 3)
    assert metrics.coast_fde_norm == pytest.approx(0.015)
    # width=100 (dy=0 throughout) -- px distance is just dx * width.
    assert metrics.coast_ade_px == pytest.approx((0.5 + 1.0 + 1.5) / 3)
    assert metrics.coast_fde_px == pytest.approx(1.5)


def test_coast_pixel_distance_uses_height_for_the_y_axis() -> None:
    gt = [[_gt(1)]]
    outcomes = [_outcome_with_boxes([_tracked_at(1, _offset(1, dy=0.02))])]
    metrics = compute(
        _result(
            gt, outcomes, gt_ids=frozenset({1}), coast_track_ids=(frozenset({1}),), width=100, height=25
        )
    )
    assert metrics.coast_ade_norm == pytest.approx(0.02)
    assert metrics.coast_ade_px == pytest.approx(0.02 * 25)


def test_coast_sample_continues_through_an_invisible_gap() -> None:
    """The whole reason this walk does not reuse `matches_by_frame` as-is:
    a coasting box's drift during a FULL OCCLUSION (the object invisible,
    not merely unmatched) is exactly what `nonlinear`/`pan_occlusion` need
    this metric to see, and an invisible gt object can never be IoU-matched
    to anything."""
    gt = [
        [_gt(1, visible=True)],
        [_gt(1, visible=True)],
        [_gt(1, visible=False)],
        [_gt(1, visible=False)],
        [_gt(1, visible=False)],
        [_gt(1, visible=True)],
    ]
    outcomes = [
        _outcome_with_boxes([_tracked(1, 1)]),  # frame 0: confirmed, exact
        _outcome_with_boxes([_tracked(1, 1)]),  # frame 1: confirmed, exact
        _outcome_with_boxes([_tracked_at(1, _offset(1, 0.01))]),  # frame 2: coasting, hidden
        _outcome_with_boxes([_tracked_at(1, _offset(1, 0.02))]),  # frame 3: coasting, hidden
        _outcome_with_boxes([_tracked_at(1, _offset(1, 0.03))]),  # frame 4: coasting, hidden
        _outcome_with_boxes([_tracked(1, 1)]),  # frame 5: re-anchored, exact
    ]
    coast_track_ids = (
        frozenset(),
        frozenset(),
        frozenset({1}),
        frozenset({1}),
        frozenset({1}),
        frozenset(),
    )
    metrics = compute(_result(gt, outcomes, gt_ids=frozenset({1}), coast_track_ids=coast_track_ids))

    assert metrics.coast_sample_count == 3
    assert metrics.coast_ade_norm == pytest.approx((0.01 + 0.02 + 0.03) / 3)
    assert metrics.coast_fde_norm == pytest.approx(0.03)


def test_coast_sample_stops_at_the_object_s_last_visible_frame() -> None:
    """Mirrors `pan`'s own world-fixed landmarks, which leave the frame for
    good and never return: a coasting box that keeps drifting AFTER the
    object's own last visible frame is not a gap to recover from, and must
    not be scored -- see `_coast_samples`'s own docstring."""
    gt = [
        [_gt(1, visible=True)],
        [_gt(1, visible=False)],
        [_gt(1, visible=False)],
        [_gt(1, visible=False)],
    ]
    outcomes = [
        _outcome_with_boxes([_tracked(1, 1)]),  # frame 0: the object's only visible frame
        _outcome_with_boxes([_tracked_at(1, _offset(1, 0.05))]),
        _outcome_with_boxes([_tracked_at(1, _offset(1, 0.10))]),
        _outcome_with_boxes([_tracked_at(1, _offset(1, 0.15))]),
    ]
    coast_track_ids = (frozenset(), frozenset({1}), frozenset({1}), frozenset({1}))
    metrics = compute(_result(gt, outcomes, gt_ids=frozenset({1}), coast_track_ids=coast_track_ids))

    assert metrics.coast_sample_count == 0
    assert metrics.coast_ade_norm is None


def test_coast_run_ends_when_the_track_stops_emitting_a_box() -> None:
    """Once a coasting identity stops appearing in `outcome.boxes` at all
    (ASSOCIATE never coasts an unmatched candidate -- ends the run there,
    rather than treating its last-known box as still current."""
    gt = [[_gt(1)]] * 4
    outcomes = [
        _outcome_with_boxes([_tracked(1, 1)]),  # frame 0: confirmed
        _outcome_with_boxes([_tracked_at(1, _offset(1, 0.01))]),  # frame 1: coasting
        _outcome_with_boxes([]),  # frame 2: this identity stops emitting anything
        _outcome_with_boxes([]),  # frame 3: still nothing
    ]
    coast_track_ids = (frozenset(), frozenset({1}), frozenset(), frozenset())
    metrics = compute(_result(gt, outcomes, gt_ids=frozenset({1}), coast_track_ids=coast_track_ids))

    assert metrics.coast_sample_count == 1
    assert metrics.coast_ade_norm == pytest.approx(0.01)
    assert metrics.coast_fde_norm == pytest.approx(0.01)


# -- velocity plausibility (TRACKING-V3-PLAN §6b finding O3) ------------------


def test_implausible_velocity_count_is_zero_without_velocity_data() -> None:
    """`track_velocities` defaulting to `()` (older/hand-built `ReplayResult`s
    that predate this metric, or a length mismatch) must read as "nothing to
    report" -- zero, the same convention `coast_track_ids`'s own guard uses,
    never an error."""
    gt = [[_gt(1)]] * 3
    outcomes = [_outcome({1: 1}) for _ in range(3)]
    metrics = compute(_result(gt, outcomes, gt_ids=frozenset({1})))

    assert metrics.implausible_velocity_count == 0


def test_implausible_velocity_count_is_zero_when_every_report_is_within_bound() -> None:
    gt = [[_gt(1)]] * 3
    outcomes = [_outcome({1: 1}) for _ in range(3)]
    track_velocities = ({1: (0.01, 0.0)}, {1: (0.02, -0.01)}, {1: (0.0, 0.0)})
    metrics = compute(
        _result(gt, outcomes, gt_ids=frozenset({1}), track_velocities=track_velocities)
    )

    assert metrics.implausible_velocity_count == 0


def test_implausible_velocity_count_fires_on_a_diverged_report() -> None:
    """A constant, plausible velocity for two frames, then one frame whose
    reported velocity has diverged far past `MAX_PLAUSIBLE_VELOCITY_PER_
    SECOND` -- the exact shape TRACKING-V3-PLAN §6b finding O3 describes
    (`latency`'s own real defect reconstructed a velocity of `1e14`-`1e32`,
    `BASELINE.md`'s own writeup). A metric that has never been observed to
    fire is not evidence -- this is that observation."""
    gt = [[_gt(1)]] * 3
    outcomes = [_outcome({1: 1}) for _ in range(3)]
    track_velocities = (
        {1: (0.01, 0.0)},
        {1: (0.01, 0.0)},
        {1: (1e16, 0.0)},  # the diverged report
    )
    metrics = compute(
        _result(gt, outcomes, gt_ids=frozenset({1}), track_velocities=track_velocities)
    )

    assert metrics.implausible_velocity_count == 1


def test_implausible_velocity_count_checks_the_y_axis_too() -> None:
    gt = [[_gt(1)]]
    outcomes = [_outcome({1: 1})]
    track_velocities = ({1: (0.0, 9.0)},)
    metrics = compute(
        _result(gt, outcomes, gt_ids=frozenset({1}), track_velocities=track_velocities)
    )

    assert metrics.implausible_velocity_count == 1


def test_implausible_velocity_count_is_strict_at_the_boundary() -> None:
    """Exactly AT the bound does not count -- the bound is "beyond", not
    "at or beyond"."""
    gt = [[_gt(1)]]
    outcomes = [_outcome({1: 1})]
    track_velocities = ({1: (MAX_PLAUSIBLE_VELOCITY_PER_SECOND, 0.0)},)
    metrics = compute(
        _result(gt, outcomes, gt_ids=frozenset({1}), track_velocities=track_velocities)
    )

    assert metrics.implausible_velocity_count == 0


def test_implausible_velocity_count_sums_every_track_every_frame() -> None:
    """Two implausible tracks on one frame plus one on another -- a plain
    count of `(track, frame)` occurrences, not a per-frame flag."""
    gt = [[_gt(1)], [_gt(1)]]
    outcomes = [_outcome({1: 1}), _outcome({1: 1})]
    track_velocities = (
        {1: (100.0, 0.0), 2: (0.0, 200.0)},
        {1: (300.0, 0.0)},
    )
    metrics = compute(
        _result(gt, outcomes, gt_ids=frozenset({1}), track_velocities=track_velocities)
    )

    assert metrics.implausible_velocity_count == 3
