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

**Coast ADE/FDE (TRACKING-V3-PLAN wave V0).** IDSW/FM/MT all read the
MATCHED-frame timeline -- did SOME id cover this frame -- and are blind to
how far off that id's own box is. A track can hold its id perfectly and
still be 40 px away from the truth the entire time; the average and final
displacement error over COASTING frames (`ReplayResult.coast_track_ids`,
`track.source != SOURCE_DETECTOR`) is what wave V3-V5's drift reductions
move and what this file's other metrics cannot see. **Populated for FOLLOW,
essentially never for ASSOCIATE**: `_run_cost_associate` builds `boxes_out`
from `detections`, one box per DETECTION (`session.py`), so an unmatched
candidate that is merely coasting is never emitted at all in ASSOCIATE mode
-- there is no box to measure drift on. FOLLOW's `_coast`/`_build_coast_
observation` emit a box on EVERY frame, coasting or not, which is exactly
why this number exists there and reads `n/a` everywhere else. That
asymmetry is a fact about today's shipped associator, not a defect in this
metric -- recorded here because it is easy to mistake for one.
"""

from __future__ import annotations

import math
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
    # TRACKING-V3-PLAN wave V0 -- see the module docstring's "Coast ADE/FDE"
    # section. `coast_sample_count` is how many (matched, coasting) frames
    # contributed; the four error fields are `None` exactly when it is 0
    # (nothing to average), same "concept does not apply" convention
    # `recovery_rate`/the lifetime fields already use above.
    coast_sample_count: int = 0
    coast_ade_norm: Optional[float] = None
    coast_fde_norm: Optional[float] = None
    coast_ade_px: Optional[float] = None
    coast_fde_px: Optional[float] = None


def compute(result: ReplayResult) -> Metrics:
    """Score one `run_replay` result. See the module docstring for the
    matching rule and what each field means."""
    matches_by_frame = [
        _match_frame(objects, _tracked_boxes(outcome))
        for objects, outcome in zip(result.ground_truth_by_frame, result.outcomes)
    ]
    windows = _existence_windows(result.ground_truth_by_frame, result.scored_gt_ids)
    visible_counts = _visible_frame_counts(result.ground_truth_by_frame, windows)
    # An object that is never visible ANYWHERE in the clip is not a tracking
    # outcome in either direction -- scoring it would charge a perfect
    # tracker for missing something nothing could have seen. Dropped from the
    # scored set rather than merely skipped, so `gt_object_count` keeps
    # agreeing with MT + PT + ML.
    windows = {gt_id: span for gt_id, span in windows.items() if visible_counts[gt_id] > 0}

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

        # Denominator is the frames the object could actually BE tracked on,
        # not every frame it exists in the scene. `_match_frame` already
        # refuses to match an invisible object, so counting invisible frames
        # below charged the tracker for failing to track something nothing
        # could have seen. In `pan`, whose objects sweep through the frame
        # and are visible for 14-33 of 70 frames, that made
        # MOSTLY_TRACKED's 80% threshold UNREACHABLE -- a perfect tracker
        # scored 0 of 4, and the row read as a tracking failure that no
        # amount of work could ever have moved. Found while trying to move
        # exactly that number.
        visible_frames = visible_counts[gt_id]
        matched_visible = sum(
            1
            for offset, entry in enumerate(timeline)
            if entry is not None
            and any(
                obj.gt_id == gt_id and obj.visible
                for obj in result.ground_truth_by_frame[start + offset]
            )
        )
        coverage = matched_visible / visible_frames
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

    coast_all, coast_finals = _coast_samples(result, matches_by_frame, windows)

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
        coast_sample_count=len(coast_all),
        coast_ade_norm=mean(sample.norm for sample in coast_all) if coast_all else None,
        coast_fde_norm=mean(sample.norm for sample in coast_finals) if coast_finals else None,
        coast_ade_px=mean(sample.px for sample in coast_all) if coast_all else None,
        coast_fde_px=mean(sample.px for sample in coast_finals) if coast_finals else None,
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


# -- coast ADE/FDE (TRACKING-V3-PLAN wave V0) ---------------------------------


@dataclass(frozen=True)
class _CoastSample:
    """One (matched, coasting) frame's displacement error, in both units at
    once -- computed together because a normalized Euclidean distance cannot
    be rescaled into pixels after the fact when width != height (x and y
    scale by different factors), so both have to come from the same dx/dy."""

    norm: float
    px: float


def _center_distance(track_box: Box, gt_box: Box, width: int, height: int) -> _CoastSample:
    dx = track_box.center[0] - gt_box.center[0]
    dy = track_box.center[1] - gt_box.center[1]
    return _CoastSample(norm=math.hypot(dx, dy), px=math.hypot(dx * width, dy * height))


def _gt_box(objects: TypingSequence[GroundTruthObject], gt_id: int) -> Optional[Box]:
    for obj in objects:
        if obj.gt_id == gt_id:
            return obj.box
    return None


def _box_for_track(outcome: FrameOutcome, track_id: int) -> Optional[Box]:
    for candidate_id, box in _tracked_boxes(outcome):
        if candidate_id == track_id:
            return box
    return None


def _coast_samples(
    result: ReplayResult,
    matches_by_frame: TypingSequence[dict[int, int]],
    windows: dict[int, tuple[int, int]],
) -> "tuple[list[_CoastSample], list[_CoastSample]]":
    """`(every coast-frame sample, one FINAL sample per maximal coasting run)`
    -- ADE is the mean of the first list, FDE the mean of the second (`compute`).

    **Deliberately follows the identity through INVISIBLE frames, unlike
    `matches_by_frame`.** `_match_frame` only ever links a gt object to a
    track on a frame that object is VISIBLE (an invisible object cannot be
    IoU-matched to anything, correctly -- IDSW/FM/MT's own "coverage" concept
    has nothing to say about a frame nobody could see on). Drift is a
    different question: a coasting box's distance from the TRUE (possibly
    hidden) position is exactly what a full occlusion gap is FOR measuring,
    and it is the single highest-value case this metric exists to catch --
    `occlusion`/`long_occlusion`/`nonlinear`/`pan_occlusion` would otherwise
    contribute nothing here at all, despite being the scenarios the whole
    metric was built for. So this walk tracks its OWN notion of "current
    identity": the last track id a REAL (visible, IoU-confirmed) match
    established, carried forward across however many invisible/unmatched
    frames follow, for as long as that same track id keeps emitting a box.
    The true gt box (`_gt_box`, visibility-blind) is what its drift is
    measured against throughout.

    A "run" is a maximal stretch of CONSECUTIVE frames scored this way --
    mirroring `_walk_timeline`'s own gap-run bookkeeping below, just walking
    coast/non-coast instead of matched/unmatched. `result.coast_track_ids`
    defaulting to `()` (older/hand-built `ReplayResult`s, or a mode with
    nothing to report -- see the module docstring) is treated as "no coast
    data", not an error: every scored object then simply contributes zero
    samples.

    **Stops at the object's LAST VISIBLE frame, not its last matched one.**
    Those are different questions, and only one of them bounds a fair
    measurement. `pan`'s world-fixed landmarks leave the frame for good once
    the camera has panned past them -- past that point there is no true
    on-screen position left to measure drift against, and a coasting box
    compared to a ground truth that is running off to infinity would report
    an unbounded, meaningless number. But `nonlinear`'s object is fully
    VISIBLE again from the moment it re-emerges: if the coasting box has
    drifted far enough that it never satisfies `_match_frame`'s IoU gate
    again for the rest of the clip, that is not "the object left the scene",
    it is the tracker permanently losing something that stayed in full view
    the whole time -- the single most important case this metric exists to
    catch, and cutting the walk off at the last MATCH (rather than the last
    VISIBLE frame) would have hidden exactly that.
    """
    if len(result.coast_track_ids) != len(result.outcomes):
        return [], []

    all_samples: list[_CoastSample] = []
    final_samples: list[_CoastSample] = []
    for gt_id, (start, end) in windows.items():
        visible_frames = [
            frame_index
            for frame_index in range(start, end + 1)
            if any(obj.gt_id == gt_id and obj.visible for obj in result.ground_truth_by_frame[frame_index])
        ]
        if not visible_frames:
            continue  # never visible at all -- `windows` already excludes this, guarded anyway
        last_visible_frame = visible_frames[-1]

        current_run: list[_CoastSample] = []
        active_track_id: Optional[int] = None
        for frame_index in range(start, last_visible_frame + 1):
            fresh_match = matches_by_frame[frame_index].get(gt_id)
            if fresh_match is not None:
                active_track_id = fresh_match

            sample = None
            if active_track_id is not None:
                track_box = _box_for_track(result.outcomes[frame_index], active_track_id)
                if track_box is None:
                    # This identity has stopped emitting a box at all (ASSOCIATE
                    # never coasts one for an unmatched candidate -- see the
                    # module docstring -- or the track was finally retired):
                    # nothing left to keep scoring under it.
                    active_track_id = None
                elif active_track_id in result.coast_track_ids[frame_index]:
                    gt_box = _gt_box(result.ground_truth_by_frame[frame_index], gt_id)
                    if gt_box is not None:
                        sample = _center_distance(track_box, gt_box, result.width, result.height)

            if sample is not None:
                all_samples.append(sample)
                current_run.append(sample)
                continue
            if current_run:
                final_samples.append(current_run[-1])
                current_run = []
        if current_run:
            final_samples.append(current_run[-1])
    return all_samples, final_samples


# -- per-object timelines -----------------------------------------------------


def _visible_frame_counts(
    ground_truth_by_frame: TypingSequence[TypingSequence[GroundTruthObject]],
    windows: dict[int, tuple[int, int]],
) -> dict[int, int]:
    """`{gt_id: frames on which it could actually be seen}` -- the coverage
    denominator. See `compute` for why it is not the window length."""
    counts = {gt_id: 0 for gt_id in windows}
    for gt_id, (start, end) in windows.items():
        for frame_index in range(start, end + 1):
            for obj in ground_truth_by_frame[frame_index]:
                if obj.gt_id == gt_id and obj.visible:
                    counts[gt_id] += 1
    return counts


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


# -- real-footage recordings (TRACKING-V3-PLAN wave V0) -----------------------


@dataclass(frozen=True)
class RecordingSummary:
    """What can be measured about a real-footage replay with NO ground truth
    to score against -- `tools.trackeval.recording.replay_recording`'s
    counterpart to `Metrics` above. Cost + structural facts only: the same
    axis `cv-service/MODULE.md`'s "Tracking engine" section already reports
    in production, not an accuracy number. IDSW/FM/MT/coast-ADE all need a
    KNOWN true position to compare against, which real footage does not
    carry unless it has also been hand-labelled -- a separate, later concern
    this summary deliberately does not pretend to answer.
    """

    name: str
    total_frames: int
    distinct_track_ids: int
    mean_track_lifetime_frames: Optional[float]
    median_track_lifetime_frames: Optional[float]
    detector_passes: int
    detector_passes_per_sec: float
    mean_tracker_millis: float
    p95_tracker_millis: float
    # Fraction of frames that emitted at least one COASTING box, of the
    # frames that emitted any tracked box at all -- the one thing this
    # summary can say about drift-worthiness without a ground truth: how
    # often the tracker was extrapolating rather than freshly confirmed.
    # `None` when `coast_track_ids` was not supplied or nothing was ever
    # tracked (nothing to take a fraction OF).
    coast_frame_fraction: Optional[float]


def summarize_recording(
    outcomes: TypingSequence[FrameOutcome],
    *,
    fps: float,
    coast_track_ids: TypingSequence[frozenset[int]] = (),
    name: str = "",
) -> RecordingSummary:
    """Score one `recording.replay_recording` result. Reuses the SAME
    private helpers `compute` above does for the cost/lifetime axis
    (`_track_lifetimes`, `_percentile`) rather than a second implementation
    of either -- `compute` itself is untouched by this function existing."""
    lifetimes_by_track = _track_lifetimes(outcomes)
    lifetimes = list(lifetimes_by_track.values())
    detector_passes = sum(1 for outcome in outcomes if outcome.detector_ran)
    duration_seconds = len(outcomes) / fps if fps > 0 else 0.0
    passes_per_sec = detector_passes / duration_seconds if duration_seconds > 0 else 0.0
    tracker_millis = [outcome.tracker_millis for outcome in outcomes]

    coast_frame_fraction = None
    if len(coast_track_ids) == len(outcomes):
        tracked_frame_count = sum(1 for outcome in outcomes if _tracked_boxes(outcome))
        if tracked_frame_count > 0:
            coasted_frame_count = sum(1 for frame_ids in coast_track_ids if frame_ids)
            coast_frame_fraction = coasted_frame_count / tracked_frame_count

    return RecordingSummary(
        name=name,
        total_frames=len(outcomes),
        distinct_track_ids=len(lifetimes_by_track),
        mean_track_lifetime_frames=mean(lifetimes) if lifetimes else None,
        median_track_lifetime_frames=median(lifetimes) if lifetimes else None,
        detector_passes=detector_passes,
        detector_passes_per_sec=passes_per_sec,
        mean_tracker_millis=mean(tracker_millis) if tracker_millis else 0.0,
        p95_tracker_millis=_percentile(tracker_millis, TRACKER_MILLIS_PERCENTILE),
        coast_frame_fraction=coast_frame_fraction,
    )


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
    "coast_n",
    "cADE",
    "cFDE",
    "cADE_px",
    "cFDE_px",
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
    coast_ade = "n/a" if metrics.coast_ade_norm is None else f"{metrics.coast_ade_norm:.3f}"
    coast_fde = "n/a" if metrics.coast_fde_norm is None else f"{metrics.coast_fde_norm:.3f}"
    coast_ade_px = "n/a" if metrics.coast_ade_px is None else f"{metrics.coast_ade_px:.1f}"
    coast_fde_px = "n/a" if metrics.coast_fde_px is None else f"{metrics.coast_fde_px:.1f}"
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
        str(metrics.coast_sample_count),
        coast_ade,
        coast_fde,
        coast_ade_px,
        coast_fde_px,
    ]
