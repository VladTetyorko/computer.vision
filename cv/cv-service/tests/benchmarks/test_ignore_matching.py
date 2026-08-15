"""`benchmarks.ignore_matching` -- MOT17's ignore-flag rule, both directions.

Blocker 2 (`docs/conclusions/TRACKING-BENCHMARKS.md` §5.3): a flag=0 row
must not cost a miss when untracked (the SCORER side), and a real detection
landing on one must never reach the tracker at all (the DETECTION side).
Both are exercised here on tiny hand-built rows -- no dependency on
`cv-service/benchmarks/data/mot17/` being present.
"""

from __future__ import annotations

from dataclasses import dataclass

from cv_service.tracking.engines.base import Box

from benchmarks.ignore_matching import (
    filter_ignored_detections,
    ignore_regions,
    is_ignored,
    scored_ground_truth,
)
from benchmarks.mot17 import GtRow

_WIDTH, _HEIGHT = 1000, 1000


def _gt_row(track_id: int, *, ignore: bool, x: float = 100.0, y: float = 100.0) -> GtRow:
    return GtRow(
        frame=1,
        track_id=track_id,
        x=x,
        y=y,
        width=50.0,
        height=50.0,
        ignore=ignore,
        class_id=1 if not ignore else 8,
        visibility=1.0,
    )


@dataclass(frozen=True)
class _Detection:
    box: Box


def test_scored_ground_truth_excludes_ignored_rows() -> None:
    """Side 1: an ignored id must not exist in the scorer's own input at
    all -- not merely be flagged, since metrics.py (untouched, no ignore
    concept) would score anything present unconditionally."""
    rows = [_gt_row(1, ignore=False), _gt_row(2, ignore=True)]
    objects = scored_ground_truth(rows, _WIDTH, _HEIGHT)
    assert [obj.gt_id for obj in objects] == [1]


def test_scored_ground_truth_keeps_every_considered_row() -> None:
    rows = [_gt_row(1, ignore=False), _gt_row(2, ignore=False)]
    objects = scored_ground_truth(rows, _WIDTH, _HEIGHT)
    assert {obj.gt_id for obj in objects} == {1, 2}


def test_ignore_regions_returns_only_the_ignored_rows_boxes() -> None:
    rows = [_gt_row(1, ignore=False), _gt_row(2, ignore=True, x=500.0, y=500.0)]
    regions = ignore_regions(rows, _WIDTH, _HEIGHT)
    assert len(regions) == 1
    assert regions[0].x * _WIDTH == 500.0


def test_is_ignored_true_for_overlapping_box_false_otherwise() -> None:
    region = Box(0.1, 0.1, 0.1, 0.1)
    overlapping = Box(0.1, 0.1, 0.1, 0.1)  # identical box, IoU = 1.0
    elsewhere = Box(0.8, 0.8, 0.05, 0.05)
    assert is_ignored(overlapping, [region]) is True
    assert is_ignored(elsewhere, [region]) is False


def test_is_ignored_false_when_there_are_no_regions() -> None:
    assert is_ignored(Box(0.1, 0.1, 0.1, 0.1), []) is False


def test_filter_ignored_detections_drops_only_the_overlapping_ones() -> None:
    """Side 2: a real detection that lands on an ignore region must never
    survive to reach the tracker -- proven here by checking it is absent
    from this function's OUTPUT, the exact list `driver.py` hands to
    `session.process()`."""
    region = Box(0.5, 0.5, 0.1, 0.1)
    on_region = _Detection(box=Box(0.5, 0.5, 0.1, 0.1))
    elsewhere = _Detection(box=Box(0.05, 0.05, 0.05, 0.05))
    kept = filter_ignored_detections([on_region, elsewhere], [region])
    assert kept == [elsewhere]


def test_filter_ignored_detections_is_a_no_op_with_no_regions() -> None:
    detections = [_Detection(box=Box(0.1, 0.1, 0.1, 0.1)), _Detection(box=Box(0.5, 0.5, 0.1, 0.1))]
    assert filter_ignored_detections(detections, []) == detections
