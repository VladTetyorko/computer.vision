"""Accuracy metrics for the tracking evaluation harness.

`docs/plans/active/TRACKING-V2-PLAN.md` §4 (wave C0), closing
`docs/conclusions/TRACKING-REVIEW.md` finding E: every number
`cv-service/MODULE.md`'s "Tracking engine" section reported before this
package existed is a COST number (ms, CPU%, duty ratio); nothing measured
whether tracking lost the subject. This module is the scoreboard waves
C1-C5 are judged against (`BASELINE.md`).

**Matching rule.** Greedy per-frame IoU matching of ground truth to emitted
tracks at IoU >= `MATCH_IOU_THRESHOLD`, highest-IoU pairs assigned first,
each ground-truth object and each emitted track used at most once per
frame. This is the standard CLEAR-MOT approximation (Bernardin &
Stiefelhagen 2008): the exact Hungarian-on-negative-IoU formulation is more
correct at the margin, but greedy is a page of pure stdlib instead of a
solver dependency -- the same trade-off TRACKING-V2-PLAN D6 already makes
for the production `assign.py`, and this module holds itself to the same
bar precisely because it has to stay trustworthy without the `cv` extra.

**IDSW subsumes "did a re-acquisition keep its id".** `IDSW` is computed by
walking each ground-truth object's timeline of MATCHED frames in order and
counting every place the assigned track id changes -- across an occlusion
gap exactly as much as between two immediately-adjacent frames. That is
deliberate, not an approximation: "the object came back with a different
id" and "two crossing objects swapped ids while both stayed visible" are
the SAME event from a track-identity point of view, and `recovery_rate`
below is the other half of that same walk, reporting the gap-crossing case
specifically because it is this plan's headline number.

Pure stdlib: consumes `cv_service.tracking.session.FrameOutcome`/
`TrackedBox` and `tools.trackeval.sequences.GroundTruthObject`, all of which
are themselves pure stdlib at module scope -- so a metrics regression can be
reproduced and debugged with no `cv` extra installed at all.
"""

from __future__ import annotations

from dataclasses import dataclass
from statistics import mean, median
from typing import Optional
from typing import Sequence as TypingSequence

from cv_service.tracking.engines.base import Box
from cv_service.tracking.session import FrameOutcome

from tools.trackeval.replay import ReplayResult
from tools.trackeval.sequences import GroundTruthObject

MATCH_IOU_THRESHOLD = 0.5

# MOTChallenge's own MT/PT/ML convention: fraction of a ground-truth
# object's existence window (from its first frame in the scene to its last)
# spent matched to SOME track id, regardless of how many times that id
# changed -- coverage and identity-continuity are reported as separate axes.
MOSTLY_TRACKED_COVERAGE = 0.8
MOSTLY_LOST_COVERAGE = 0.2

TRACKER_MILLIS_PERCENTILE = 0.95


@dataclass(frozen=True)
class Metrics:
    """One scenario x mode's scoreboard row.

    Every field is a plain number, except where the underlying concept does
    not apply to this replay: `recovery_rate` is `None` when no
    ground-truth object ever had an internal coverage gap, and the two
    lifetime fields are `None` when nothing was ever tracked at all.
    """

    scenario: str
    mode: str
    engine_id: str
    total_frames: int
    gt_object_count: int
    idsw: int
    fragmentations: int
    mostly_tracked: int
    partially_tracked: int
    mostly_lost: int
    gap_count: int
    recovered_count: int
    recovery_rate: Optional[float]
    mean_track_lifetime_frames: Optional[float]
    median_track_lifetime_frames: Optional[float]
    detector_passes: int
    detector_passes_per_sec: float
    mean_tracker_millis: float
    p95_tracker_millis: float


def compute(result: ReplayResult) -> Metrics:
    """Score one `run_replay` result. See the module docstring for the
    matching rule and what each field means."""
    matches_by_frame = [
        _match_frame(objects, _tracked_boxes(outcome))
        for objects, outcome in zip(result.ground_truth_by_frame, result.outcomes)
    ]
    windows = _existence_windows(result.ground_truth_by_frame, result.scored_gt_ids)

    idsw = 0
    fragmentations = 0
    mostly_tracked = partially_tracked = mostly_lost = 0
    gap_edges: list[tuple[int, int]] = []
    for gt_id, (start, end) in windows.items():
        timeline = [matches_by_frame[frame_index].get(gt_id) for frame_index in range(start, end + 1)]
        object_idsw, object_fragmentations, object_gap_edges = _walk_timeline(timeline)
        idsw += object_idsw
        fragmentations += object_fragmentations
        gap_edges.extend(object_gap_edges)

        coverage = sum(1 for entry in timeline if entry is not None) / len(timeline)
        if coverage >= MOSTLY_TRACKED_COVERAGE:
            mostly_tracked += 1
        elif coverage < MOSTLY_LOST_COVERAGE:
            mostly_lost += 1
        else:
            partially_tracked += 1

    recovered_count = sum(1 for before, after in gap_edges if before == after)
    recovery_rate = recovered_count / len(gap_edges) if gap_edges else None

    lifetimes = list(_track_lifetimes(result.outcomes).values())
    mean_lifetime = mean(lifetimes) if lifetimes else None
    median_lifetime = median(lifetimes) if lifetimes else None

    detector_passes = sum(1 for outcome in result.outcomes if outcome.detector_ran)
    duration_seconds = len(result.outcomes) / result.fps if result.fps > 0 else 0.0
    passes_per_sec = detector_passes / duration_seconds if duration_seconds > 0 else 0.0
    tracker_millis = [outcome.tracker_millis for outcome in result.outcomes]

    return Metrics(
        scenario=result.scenario,
        mode=result.mode,
        engine_id=result.engine_id,
        total_frames=len(result.outcomes),
        gt_object_count=len(windows),
        idsw=idsw,
        fragmentations=fragmentations,
        mostly_tracked=mostly_tracked,
        partially_tracked=partially_tracked,
        mostly_lost=mostly_lost,
        gap_count=len(gap_edges),
        recovered_count=recovered_count,
        recovery_rate=recovery_rate,
        mean_track_lifetime_frames=mean_lifetime,
        median_track_lifetime_frames=median_lifetime,
        detector_passes=detector_passes,
        detector_passes_per_sec=passes_per_sec,
        mean_tracker_millis=mean(tracker_millis) if tracker_millis else 0.0,
        p95_tracker_millis=_percentile(tracker_millis, TRACKER_MILLIS_PERCENTILE),
    )


# -- matching -----------------------------------------------------------------


def _tracked_boxes(outcome: FrameOutcome) -> list[tuple[int, Box]]:
    """`(track_id, box)` for every emitted box that carries an id this frame.

    An untracked box (`track is None`, e.g. a below-`min_hits` detection, or
    an `OFF`/echoed frame) contributes nothing: it cannot preserve or switch
    an identity it never had.
    """
    if outcome.boxes is None:
        return []
    return [(tracked.track.track_id, tracked.box) for tracked in outcome.boxes if tracked.track is not None]


def _match_frame(
    ground_truth: TypingSequence[GroundTruthObject],
    tracked: TypingSequence[tuple[int, Box]],
) -> dict[int, int]:
    """Greedy IoU matching for one frame -- see the module docstring."""
    candidates: list[tuple[float, int, int]] = []
    for gt in ground_truth:
        if not gt.visible:
            continue
        for track_id, box in tracked:
            iou = gt.box.iou(box)
            if iou >= MATCH_IOU_THRESHOLD:
                candidates.append((iou, gt.gt_id, track_id))
    candidates.sort(key=lambda candidate: candidate[0], reverse=True)

    matched: dict[int, int] = {}
    used_tracks: set[int] = set()
    for _iou, gt_id, track_id in candidates:
        if gt_id in matched or track_id in used_tracks:
            continue
        matched[gt_id] = track_id
        used_tracks.add(track_id)
    return matched


# -- per-object timelines -----------------------------------------------------


def _existence_windows(
    ground_truth_by_frame: TypingSequence[TypingSequence[GroundTruthObject]],
    scored_gt_ids: frozenset[int],
) -> dict[int, tuple[int, int]]:
    """`{gt_id: (first_frame_index, last_frame_index)}` -- the span this
    object exists in the scene, visible or not, restricted to
    `scored_gt_ids` (FOLLOW only scores the one object it locked onto)."""
    first: dict[int, int] = {}
    last: dict[int, int] = {}
    for index, objects in enumerate(ground_truth_by_frame):
        for obj in objects:
            if obj.gt_id not in scored_gt_ids:
                continue
            first.setdefault(obj.gt_id, index)
            last[obj.gt_id] = index
    return {gt_id: (first[gt_id], last[gt_id]) for gt_id in first}


def _walk_timeline(timeline: TypingSequence[Optional[int]]) -> tuple[int, int, list[tuple[int, int]]]:
    """One ground-truth object's per-frame matched-track-id sequence (`None`
    where unmatched, whether from invisibility or a tracking failure) ->
    `(idsw, fragmentations, internal_gap_edges)`.

    `internal_gap_edges` is `(id_before_gap, id_after_gap)` for every gap
    bounded by a match on BOTH sides -- a leading gap (never yet matched) or
    a trailing one (matched, then never again before the window ends) is
    real lost coverage, folded into ML by the caller, but it is not a
    "recovery" question because there is nothing on one side to recover
    TOWARD.
    """
    idsw = 0
    fragmentations = 0
    gap_edges: list[tuple[int, int]] = []
    previous_id: Optional[int] = None
    in_gap = False
    gap_started_at_id: Optional[int] = None

    for entry in timeline:
        if entry is None:
            if previous_id is not None and not in_gap:
                in_gap = True
                gap_started_at_id = previous_id
            continue
        if previous_id is not None and entry != previous_id:
            idsw += 1
        if in_gap:
            fragmentations += 1
            gap_edges.append((gap_started_at_id, entry))  # type: ignore[arg-type]
            in_gap = False
            gap_started_at_id = None
        previous_id = entry

    return idsw, fragmentations, gap_edges


def _track_lifetimes(outcomes: TypingSequence[FrameOutcome]) -> dict[int, int]:
    """`{emitted track_id: frame count it appeared in}` across the whole
    replay -- deliberately an appearance COUNT, not a first-to-last span, so
    a fragmented identity (gap, then a NEW id) shows up as several
    short-lived ids rather than one long one with a hole in it. Matches
    `cv-service/MODULE.md`'s own T8 reporting convention (e.g. "105 of 150
    frames" for an id that survived an occlusion gap)."""
    counts: dict[int, int] = {}
    for outcome in outcomes:
        for track_id, _box in _tracked_boxes(outcome):
            counts[track_id] = counts.get(track_id, 0) + 1
    return counts


def _percentile(values: TypingSequence[float], fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, int(round(fraction * (len(ordered) - 1))))
    return ordered[index]


# -- rendering ------------------------------------------------------------

_COLUMN_HEADERS: tuple[str, ...] = (
    "scenario",
    "mode",
    "engine",
    "frames",
    "gt",
    "IDSW",
    "FM",
    "MT",
    "PT",
    "ML",
    "gaps",
    "recov",
    "recov%",
    "life_mean",
    "life_med",
    "det/s",
    "trk_ms_avg",
    "trk_ms_p95",
)


def render_table(rows: TypingSequence[Metrics]) -> str:
    """A fixed-width text table -- readable in a terminal or pasted into a
    markdown fence verbatim (`BASELINE.md` does exactly that)."""
    table = [_COLUMN_HEADERS] + [_row_cells(row) for row in rows]
    widths = [max(len(row[column]) for row in table) for column in range(len(_COLUMN_HEADERS))]

    def format_row(cells: TypingSequence[str]) -> str:
        return " | ".join(cell.ljust(width) for cell, width in zip(cells, widths))

    lines = [format_row(_COLUMN_HEADERS), "-+-".join("-" * width for width in widths)]
    lines.extend(format_row(_row_cells(row)) for row in rows)
    return "\n".join(lines)


def _row_cells(metrics: Metrics) -> list[str]:
    recovery = "n/a" if metrics.recovery_rate is None else f"{metrics.recovery_rate * 100:.0f}%"
    life_mean = "n/a" if metrics.mean_track_lifetime_frames is None else f"{metrics.mean_track_lifetime_frames:.1f}"
    life_median = (
        "n/a" if metrics.median_track_lifetime_frames is None else f"{metrics.median_track_lifetime_frames:.1f}"
    )
    return [
        metrics.scenario,
        metrics.mode,
        metrics.engine_id or "-",
        str(metrics.total_frames),
        str(metrics.gt_object_count),
        str(metrics.idsw),
        str(metrics.fragmentations),
        str(metrics.mostly_tracked),
        str(metrics.partially_tracked),
        str(metrics.mostly_lost),
        str(metrics.gap_count),
        str(metrics.recovered_count),
        recovery,
        life_mean,
        life_median,
        f"{metrics.detector_passes_per_sec:.2f}",
        f"{metrics.mean_tracker_millis:.2f}",
        f"{metrics.p95_tracker_millis:.2f}",
    ]
