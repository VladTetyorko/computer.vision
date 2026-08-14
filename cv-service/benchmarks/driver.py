"""Drives the REAL `StreamTrackingSession` over one MOT17 composition.

`docs/conclusions/TRACKING-BENCHMARKS.md` §5.2's own diagram names this
role: "a close sibling" of `tools/trackeval/replay.run_replay`, not a
reimplementation of `StreamTrackingSession` -- every frame in the loop
below calls the identical production `session.process(...)` entry point
`replay.py` does. What differs is upstream of that call: `replay.py`'s
`SyntheticDetector.detect(ground_truth, roi)` takes the SCENARIO'S OWN
ground truth as its input parameter and returns it perturbed; `Mot17Sequence`
carries a real dataset's real detector output (`det/det.txt`), and
`_detect_fn_for_frame` below is built from THAT alone.

**Blocker 1, enforced structurally, not by discipline.** `Mot17Detection`
and every function in this module that touches detections (`_detection_
from_row`, `_detect_fn_for_frame`) has no parameter, field, or import of
`tools.trackeval.sequences.GroundTruthObject` anywhere in its signature --
grep this file for `GroundTruthObject` and the only hits are the import and
`run_mot17_replay`'s own `ground_truth_by_frame` list, built from
`ignore_matching.scored_ground_truth` and fed straight into
`ReplayResult.ground_truth_by_frame` (read by `metrics.py` to SCORE the
replay). That list is never once passed to `_detect_fn_for_frame`,
`_detection_from_row`, or anything either of them calls. The ground truth
reaches the metrics code; it cannot reach the session, because nothing in
the detection path is TYPED to accept it.

**Why this is a sibling file and not a call into `replay.run_replay`
itself.** `run_replay`'s per-frame `detect` closure is hard-coded to build
a `SyntheticDetector` and call `.detect(ground_truth, roi)` -- there is no
parameter on `run_replay` that swaps the detection SOURCE, only ones that
tune the synthetic detector's noise. Reusing it unmodified against MOT17
would mean either (a) it is impossible to feed real detections through it
at all, or (b) feeding MOT17's ground truth in as `run_replay`'s own
"scenario" ground truth, which is exactly the circularity Blocker 1 exists
to prevent. This file's loop is therefore a new, parallel implementation of
`run_replay`'s FRAME LOOP (which frame, which timestamp, when to call
`session.process`) -- but the thing that loop calls, on every frame, is
still the one real `StreamTrackingSession`, imported and driven exactly as
`replay.py` drives it, never reimplemented.

**No pixels are available, so three engines are forced off structurally,
not merely left at a default.** `build_settings` below always sets
`track_motion_engine="off"`, `track_appearance_engine="off"`, and
`track_roi_enabled=False`, regardless of what a caller's own environment
resolves -- MOT17 ships annotations only (`benchmarks/__init__.py`'s scope
note), and:

* `track_motion_engine` (default `"flow"`) needs a decoded frame every call
  when the resolved ASSOCIATE engine is `cost`
  (`cv_service/tracking/session.py`'s `_estimate_motion`, called
  unconditionally for `cost`, never for `bytetrack`).
* `track_appearance_engine` (default `"histogram"`) needs a decoded frame
  to extract a descriptor from, when `cost` is resolved and an extractor is
  active.
* `track_roi_enabled` (default `True` since TRACKING-V2 wave C5c) drives
  `_roi_rescue`, which asks the detector to re-examine a CROP of the
  current frame -- a question `det.txt`'s fixed, already-recorded,
  full-frame output cannot honestly answer (there is no way to know what a
  real detector would have found in a crop it was never run on). Faking an
  answer would silently invent detector behaviour never measured on real
  pixels, exactly the failure mode this whole benchmark exists to avoid.

`_no_pixels` (the `frame` callable) and `_detect_fn_for_frame`'s own
`roi is not None` guard both raise loudly rather than degrade quietly if
any of this is ever wrong -- a loud crash during development is strictly
better than a silent, wrong number in a result row.
"""

from __future__ import annotations

import dataclasses
import time
from dataclasses import dataclass
from statistics import mean
from typing import Optional

from cv_service.config import Settings
from cv_service.tracking.engines.base import SOURCE_DETECTOR, Box
from cv_service.tracking.params import MODE_ASSOCIATE, TrackingRequest
from cv_service.tracking.registry import TrackerRegistry, build_default_registry
from cv_service.tracking.session import FrameOutcome, StreamTrackingSession

from tools.trackeval.replay import ReplayResult
from tools.trackeval.sequences import GroundTruthObject

from benchmarks import ignore_matching, thresholds
from benchmarks.mot17 import (
    DetRow,
    Mot17Sequence,
    det_rows_by_frame,
    frame_numbers,
    frame_timestamp_millis,
    gt_rows_by_frame,
    normalize_box,
)

_WIRE_TOKEN = "mot17-benchmark"

_MOTION_ENGINE_OFF = "off"
_APPEARANCE_ENGINE_OFF = "off"

ENGINE_IDS: tuple[str, ...] = ("cost", "bytetrack")
CAPABILITY_LEVELS: tuple[int, ...] = (1, 2, 3, 4, 5)

# TRACKER_MILLIS_PERCENTILE mirrors tools/trackeval/metrics.py's own
# TRACKER_MILLIS_PERCENTILE (0.95) -- the same reporting convention, for the
# wall-clock number THIS module measures independently (see
# `Mot17ReplayStats`'s own docstring for why there are two).
WALL_MILLIS_PERCENTILE = 0.95


@dataclass(frozen=True)
class Mot17Detection:
    """The exact duck shape `cv_service.tracking.track.observation_for` and
    every engine adapter expect: `label`/`confidence`/`x`/`y`/`width`/
    `height` -- same shape `tools/trackeval/replay.py`'s `_SyntheticDetection`
    uses, defined SEPARATELY here (not imported -- that class is private to
    `replay.py`) so this module's own detection type can never, even by an
    import accident, be constructed from a `GroundTruthObject` (Blocker 1).

    `box` is a derived convenience for THIS package's own ignore-region
    filtering (`ignore_matching.filter_ignored_detections`) -- session.py
    never reads it; production code only ever reads the six flat fields
    above (see `track.observation_for`).
    """

    label: str
    confidence: float
    x: float
    y: float
    width: float
    height: float

    @property
    def box(self) -> Box:
        return Box(self.x, self.y, self.width, self.height)


def _detection_from_row(row: DetRow, image_width: int, image_height: int) -> Mot17Detection:
    """One thresholded `mot17.DetRow` (pixel coordinates) -> `Mot17Detection`
    (normalized `[0, 1]`), via `mot17.normalize_box` -- the SAME conversion
    `ignore_matching.scored_ground_truth`/`ignore_regions` use for ground
    truth, so a detection and a ground-truth box computed from the same
    pixel rectangle produce the identical normalized geometry (this is what
    makes the coordinate-normalization round-trip test meaningful across
    both call sites, not just one)."""
    box = normalize_box(row.x, row.y, row.width, row.height, image_width, image_height)
    return Mot17Detection(
        label=ignore_matching.OBJECT_LABEL,
        confidence=row.confidence,
        x=box.x,
        y=box.y,
        width=box.width,
        height=box.height,
    )


def _no_pixels() -> "None":
    """The `frame` callable handed to `session.process()`. MOT17 has no
    image data at all -- see the module docstring's "no pixels" section for
    exactly which settings must be forced off to guarantee this is never
    actually called. Raising here, rather than returning a placeholder
    array, turns "a pixel-consuming path ran anyway" into an immediate,
    loud failure instead of a silently-wrong result row.
    """
    raise RuntimeError(
        "benchmarks.driver: a pixel-consuming code path called frame(), but MOT17 is "
        "annotations-only (no images). build_settings() forces track_motion_engine, "
        "track_appearance_engine and track_roi_enabled off specifically to prevent this "
        "-- if you see this, one of those overrides did not take effect."
    )


@dataclass(frozen=True)
class CompositionConfig:
    """One matrix cell's knobs -- everything `runner.py`'s CLI exposes."""

    engine_id: str  # "cost" | "bytetrack"
    capability_level: int  # 1..5
    reupdate_enabled: bool  # False -> CV_TRACK_REUPDATE_MAX_GAP_MILLIS=0 equivalent
    detection_threshold: float  # per-detector confidence floor, see thresholds.py

    def __post_init__(self) -> None:
        if self.engine_id not in ENGINE_IDS:
            raise ValueError(f"engine_id must be one of {ENGINE_IDS}, got {self.engine_id!r}")
        if self.capability_level not in CAPABILITY_LEVELS:
            raise ValueError(f"capability_level must be one of {CAPABILITY_LEVELS}, got {self.capability_level!r}")


def build_settings(config: CompositionConfig, *, base: Optional[Settings] = None) -> Settings:
    """`base` (defaults to `Settings.from_env()`, matching every other
    `CV_TRACK_*`-respecting entry point in this codebase) with exactly the
    fields this benchmark must control overridden -- see the module
    docstring for why each one is forced, not merely defaulted.

    `track_reupdate_max_gap_millis`: `config.reupdate_enabled=False` forces
    `0`, verified against `cv_service/tracking/session.py`'s own gate
    (`0.0 < gap_millis <= self._params.reupdate_max_gap_millis` -- a
    non-positive ceiling can never satisfy `<= ceiling` since a gap is by
    definition positive, so ORU never fires) and
    `cv_service/config.py`'s `_parse_int_allow_nonpositive` (a non-positive
    value is accepted AS-IS, never replaced by the default) -- the same
    mechanism `CV_TRACK_REUPDATE_MAX_GAP_MILLIS=0` uses on a live
    deployment. `True` leaves `base`'s own resolved value untouched (whatever
    `CV_TRACK_REUPDATE_MAX_GAP_MILLIS` was in the environment `base` was
    built from, or the 15s code default), so "ORU on" measures this
    process's actual deployment default rather than a second hard-coded
    number.
    """
    base = base or Settings.from_env()
    return dataclasses.replace(
        base,
        track_motion_engine=_MOTION_ENGINE_OFF,
        track_appearance_engine=_APPEARANCE_ENGINE_OFF,
        track_roi_enabled=False,
        track_capability_level=config.capability_level,
        track_reupdate_max_gap_millis=(base.track_reupdate_max_gap_millis if config.reupdate_enabled else 0),
    )


@dataclass(frozen=True)
class Mot17ReplayStats:
    """Everything about the RUN itself that `tools/trackeval/metrics.py`'s
    `Metrics` (an accuracy scoreboard) has no field for -- retained-detection
    density and the wall-clock throughput this module measures independently
    of `Metrics.mean_tracker_millis`/`p95_tracker_millis` (which are also
    reported, read straight off `Metrics` by `runner.py` -- the two should
    agree closely; `Metrics`' pair is `outcome.tracker_millis`, a
    `time.perf_counter()` window recorded INSIDE `session.py` around the
    engine dispatch only, while this dataclass's pair is measured HERE,
    bracketing the entire `session.process()` call from this module's own
    call site -- an independent cross-check via a different vantage point on
    the same real code path, not a second implementation of the same
    measurement).
    """

    frame_count: int
    dets_retained_total: int
    dets_per_frame: float
    below_threshold_dropped: int
    ignore_region_dropped: int
    mean_process_wall_millis: float
    p95_process_wall_millis: float
    association_fps: float
    capability_level_requested: int
    capability_level_served: int
    capability_level_reason: str


def _percentile(values: "list[float]", fraction: float) -> float:
    """Same shape as `tools/trackeval/metrics.py`'s own private
    `_percentile` -- reimplemented rather than imported (that one is
    module-private to `metrics.py`), a few lines of generic statistics, not
    a domain rule this benchmark could get wrong."""
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, int(round(fraction * (len(ordered) - 1))))
    return ordered[index]


def _coast_ids_this_frame(outcome: FrameOutcome) -> "frozenset[int]":
    """Mirrors `tools/trackeval/replay.py`'s private `_coast_ids_this_frame`
    exactly (same reasoning: `Track` is mutable and handed out by reference,
    so this must be read immediately after `process()` returns, never later
    -- see that function's own docstring for the full account). Not
    imported: it is module-private to `replay.py`."""
    if outcome.boxes is None:
        return frozenset()
    return frozenset(
        tracked.track.track_id
        for tracked in outcome.boxes
        if tracked.track is not None and tracked.track.source != SOURCE_DETECTOR
    )


def _track_velocities_this_frame(outcome: FrameOutcome) -> "dict[int, tuple[float, float]]":
    """Mirrors `tools/trackeval/replay.py`'s private
    `_track_velocities_this_frame` exactly, for the same reason
    `_coast_ids_this_frame` above does."""
    if outcome.boxes is None:
        return {}
    return {
        tracked.track.track_id: (tracked.track.velocity_x, tracked.track.velocity_y)
        for tracked in outcome.boxes
        if tracked.track is not None
    }


def run_mot17_replay(
    sequence: Mot17Sequence,
    config: CompositionConfig,
    *,
    settings: Optional[Settings] = None,
    registry: Optional[TrackerRegistry] = None,
) -> "tuple[ReplayResult, Mot17ReplayStats]":
    """Run one MOT17 composition through one real `StreamTrackingSession`.

    ASSOCIATE mode only (`MODE_ASSOCIATE`, hard-coded) -- FOLLOW needs
    `lk`/`ncc`, which need image patches this dataset does not carry (see
    `benchmarks/__init__.py`'s scope note); there is no `mode` parameter to
    accidentally misuse here.
    """
    settings = build_settings(config, base=settings)
    registry = registry or build_default_registry(settings, probe=True)
    session = StreamTrackingSession(settings=settings, registry_provider=lambda: registry)
    session.apply_config(
        TrackingRequest(mode=MODE_ASSOCIATE, engine_id=config.engine_id),
        wire_token=_WIRE_TOKEN,
    )

    gt_by_frame = gt_rows_by_frame(sequence)
    det_by_frame = det_rows_by_frame(sequence)

    outcomes: "list[FrameOutcome]" = []
    coast_track_ids: "list[frozenset[int]]" = []
    track_velocities: "list[dict[int, tuple[float, float]]]" = []
    ground_truth_by_frame: "list[tuple[GroundTruthObject, ...]]" = []
    scored_gt_ids: "set[int]" = set()
    process_wall_millis: "list[float]" = []
    dets_retained_total = 0
    below_threshold_dropped = 0
    ignore_region_dropped = 0

    for frame_number in frame_numbers(sequence.info):
        frame_index = frame_number - 1  # 0-based, matching replay.py's frame.index convention
        now_millis = frame_timestamp_millis(frame_index, sequence.info.frame_rate)

        raw_gt_rows = gt_by_frame.get(frame_number, ())
        scored_gt = ignore_matching.scored_ground_truth(raw_gt_rows, sequence.info.width, sequence.info.height)
        ignore_boxes = ignore_matching.ignore_regions(raw_gt_rows, sequence.info.width, sequence.info.height)
        ground_truth_by_frame.append(scored_gt)
        scored_gt_ids.update(obj.gt_id for obj in scored_gt)

        raw_det_rows = det_by_frame.get(frame_number, ())
        above_threshold = thresholds.apply_threshold(list(raw_det_rows), config.detection_threshold)
        below_threshold_dropped += len(raw_det_rows) - len(above_threshold)
        candidate_detections = [
            _detection_from_row(row, sequence.info.width, sequence.info.height) for row in above_threshold
        ]
        retained_detections = ignore_matching.filter_ignored_detections(candidate_detections, ignore_boxes)
        ignore_region_dropped += len(candidate_detections) - len(retained_detections)
        dets_retained_total += len(retained_detections)

        # Monotonic clock strictly bracketing the session call ONLY --
        # loading (already done, before this loop starts), per-frame
        # threshold/ignore filtering (above, outside this bracket), and
        # metrics computation (after this whole function returns, in
        # runner.py) are all excluded on purpose. This is the wall-clock
        # `Mot17ReplayStats.association_fps` is derived from.
        started = time.perf_counter()
        outcome = session.process(
            now_millis=now_millis,
            detect=_detect_fn_for_frame(retained_detections),
            frame=_no_pixels,
            detection_lag_millis=0,
        )
        process_wall_millis.append((time.perf_counter() - started) * 1000.0)
        outcomes.append(outcome)
        coast_track_ids.append(_coast_ids_this_frame(outcome))
        track_velocities.append(_track_velocities_this_frame(outcome))

    engine_id_served = next((outcome.engine_id for outcome in reversed(outcomes) if outcome.engine_id), "")
    capability_level_served = outcomes[-1].capability_level_served if outcomes else 0
    capability_level_reason = outcomes[-1].capability_level_reason if outcomes else ""

    result = ReplayResult(
        scenario=sequence.name,
        mode=MODE_ASSOCIATE,
        engine_id=engine_id_served,
        fps=sequence.info.frame_rate,
        outcomes=tuple(outcomes),
        ground_truth_by_frame=tuple(ground_truth_by_frame),
        scored_gt_ids=frozenset(scored_gt_ids),
        width=sequence.info.width,
        height=sequence.info.height,
        coast_track_ids=tuple(coast_track_ids),
        track_velocities=tuple(track_velocities),
    )

    frame_count = len(outcomes)
    stats = Mot17ReplayStats(
        frame_count=frame_count,
        dets_retained_total=dets_retained_total,
        dets_per_frame=(dets_retained_total / frame_count) if frame_count else 0.0,
        below_threshold_dropped=below_threshold_dropped,
        ignore_region_dropped=ignore_region_dropped,
        mean_process_wall_millis=mean(process_wall_millis) if process_wall_millis else 0.0,
        p95_process_wall_millis=_percentile(process_wall_millis, WALL_MILLIS_PERCENTILE),
        association_fps=(
            1000.0 / mean(process_wall_millis) if process_wall_millis and mean(process_wall_millis) > 0.0 else 0.0
        ),
        capability_level_requested=config.capability_level,
        capability_level_served=capability_level_served,
        capability_level_reason=capability_level_reason,
    )
    return result, stats


def _detect_fn_for_frame(retained_detections: "list[Mot17Detection]"):
    """One frame's `detect: DetectFn` -- built fresh per frame (default
    argument binds THIS frame's already-filtered list, not a shared mutable
    one), returning `(detections, inference_millis=0)`: `0` because this is
    a fixed, already-recorded lookup, not a real inference pass with a real
    cost to report (`tools/trackeval/replay.py`'s `SyntheticDetector`-backed
    `detect` closure does the identical `, 0` for the identical reason).

    Raises if ever called with `roi is not None` -- see the module
    docstring's "no pixels" section: `track_roi_enabled=False`
    (`build_settings`) means `_roi_rescue` never fires, so this should be
    structurally unreachable; a loud failure here is a broken guarantee
    surfacing immediately, never a fabricated crop-limited answer.
    """

    def detect(roi: Optional[Box] = None) -> "tuple[list, int]":
        if roi is not None:
            raise RuntimeError(
                "benchmarks.driver: detect() was called with an ROI -- ROI rescue must be "
                "forced off (track_roi_enabled=False) for every MOT17 run, since det.txt "
                "cannot honestly answer a crop-limited re-detect question. If you see this, "
                "that guarantee broke; see build_settings()."
            )
        return list(retained_detections), 0

    return detect
