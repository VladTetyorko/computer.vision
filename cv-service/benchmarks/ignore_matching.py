"""Ignore-aware matching for MOT17 ground truth -- Blocker 2.

`docs/conclusions/TRACKING-BENCHMARKS.md` §5.3: `tools/trackeval/metrics.py`
(read, never edited -- see `benchmarks/__init__.py`) has no concept of an
ignore region at all. `metrics.compute()` scores every id
`ReplayResult.scored_gt_ids` names, unconditionally, against every box
`ReplayResult.ground_truth_by_frame` carries for it.

**The rule this module enforces, in one sentence:** a `gt.txt` row whose
7th ("consider") column is `0` is not a target -- failing to track it costs
nothing, and a real detection landing on it is neither a true nor a false
positive, because it is excluded from the pipeline before either question
can even be asked.

That rule protects two different actors, so it is enforced in two different
places, both here:

1. **The scorer.** `scored_ground_truth` below returns ONLY the
   flag-considered rows, converted to `GroundTruthObject`s. A caller that
   builds `ReplayResult.ground_truth_by_frame`/`scored_gt_ids` from this
   function's output alone (never from the raw, unfiltered `GtRow`s) makes
   `metrics.compute()`'s existence-window walk structurally unable to open
   a window for an ignored id -- there is nothing in the data for it to
   score, not merely a value it has been told to skip.
2. **The tracker itself.** A real detector's real output can, and MOT17's
   own design assumes will, land boxes on top of an ignore region --
   distractor classes, static crowds, reflections are exactly the ~45%
   pooled fraction (42-56% per individual scene) `gt.txt` flags this way in
   this benchmark's own seven sequences. `filter_ignored_detections` drops
   any detection whose box overlaps a flagged region at IoU >=
   `IGNORE_MATCH_IOU`, called by `driver.py` BEFORE a frame's detections
   are ever handed to `StreamTrackingSession.process()`. Never reaching the
   tracker at all is what makes "neither a TP nor an FP" true by
   construction, rather than a label this harness would otherwise have no
   field to attach after the fact -- `metrics.py` has no per-detection
   TP/FP concept either, so excluding it upstream is the only place this
   benchmark COULD enforce the rule.

Pure stdlib except for `cv_service.tracking.engines.base.Box`'s own `.iou`
-- no `numpy`/`cv2`, matching every other module in this package.
"""

from __future__ import annotations

from typing import Protocol
from typing import Sequence as TypingSequence

from cv_service.tracking.engines.base import Box

from benchmarks.mot17 import GtRow, normalize_box
from tools.trackeval.metrics import MATCH_IOU_THRESHOLD
from tools.trackeval.sequences import GroundTruthObject

# Reuses the harness's OWN "same box" bar (metrics.py's greedy matcher
# already uses this to decide whether a track covers a ground-truth
# object) rather than picking a second, separate threshold for "close
# enough to an ignore region to be part of it" -- one number, one meaning,
# throughout this benchmark.
IGNORE_MATCH_IOU = MATCH_IOU_THRESHOLD

# `tools/trackeval/sequences.py` uses one constant label per scenario
# (`OBJECT_LABEL = "object"`); MOT17's considered rows are exclusively
# class 1 (pedestrian) in this dataset (verified: every flag=1 row across
# all seven -FRCNN gt.txt files carries class_id=1) -- matching that
# module's own "one label per sequence" simplification
# (`TRACKING-BENCHMARKS.md` §5.3's class-handling note), not a
# per-detection classifier this benchmark does not have.
OBJECT_LABEL = "pedestrian"


class _HasBox(Protocol):
    box: Box


def scored_ground_truth(
    gt_rows: TypingSequence[GtRow], image_width: int, image_height: int
) -> tuple[GroundTruthObject, ...]:
    """Considered (`flag == 1`) rows only, normalized -- see the module
    docstring's point 1. Never includes an ignored row, so nothing built
    from this function's output can score one.

    `visible = visibility > 0.0`: MOT17's `visibility` is a continuous
    0..1 fraction of the box actually unoccluded, not the boolean
    `GroundTruthObject.visible` this codebase's synthetic scenarios use --
    `> 0.0` is the natural boundary between "some part of this object could
    in principle be seen this frame" and "annotated as present but
    currently 0% visible", matching `GroundTruthObject.visible`'s own
    documented meaning ("False while the object exists but cannot be
    SEEN") more closely than an arbitrary partial-occlusion cutoff would.
    """
    considered = [row for row in gt_rows if not row.ignore]
    return tuple(
        GroundTruthObject(
            gt_id=row.track_id,
            label=OBJECT_LABEL,
            box=normalize_box(row.x, row.y, row.width, row.height, image_width, image_height),
            visible=row.visibility > 0.0,
        )
        for row in considered
    )


def ignore_regions(gt_rows: TypingSequence[GtRow], image_width: int, image_height: int) -> tuple[Box, ...]:
    """Every flagged-0 row on this frame, normalized -- the regions
    `filter_ignored_detections` checks real detections against. Deliberately
    NOT filtered by `visibility` (an ignore region's own visibility field
    describes ITS box, not whether it should be excluded -- the `ignore`
    flag alone answers that, per the module docstring's rule)."""
    ignored = [row for row in gt_rows if row.ignore]
    return tuple(
        normalize_box(row.x, row.y, row.width, row.height, image_width, image_height) for row in ignored
    )


def is_ignored(box: Box, regions: TypingSequence[Box]) -> bool:
    """Whether `box` overlaps any of `regions` at IoU >= `IGNORE_MATCH_IOU`
    -- `False` (never ignored) when `regions` is empty, without iterating,
    since `any(())` is already `False` but a frame with zero ignore rows is
    the common case and worth not paying even one `.iou()` call for."""
    if not regions:
        return False
    return any(box.iou(region) >= IGNORE_MATCH_IOU for region in regions)


def filter_ignored_detections(detections: TypingSequence[_HasBox], regions: TypingSequence[Box]) -> list:
    """`detections` with every entry overlapping an ignore region dropped --
    see the module docstring's point 2. `detections` may be any sequence of
    objects exposing a `.box: Box` attribute (`driver.Mot17Detection` is
    the one caller today); generic on purpose, so this function stays
    reusable if a second detection representation is ever added without
    this module needing to know about it.

    Returns every detection unchanged (a plain list copy, not a filtered
    generator) when `regions` is empty -- the common case, and cheaper than
    calling `is_ignored` once per detection only to always get `False`.
    """
    if not regions:
        return list(detections)
    return [detection for detection in detections if not is_ignored(detection.box, regions)]
