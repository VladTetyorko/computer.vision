"""Replay driver: feeds a synthetic `Sequence` through the REAL
`cv_service.tracking.session.StreamTrackingSession` -- not a reimplementation.

`docs/plans/done/TRACKING-V2-PLAN.md` §4 (wave C0). This is the one place
the harness talks to production tracking code: everything upstream
(`sequences.py`) and downstream (`metrics.py`) is plain data, so a session
bug shows up here and nowhere else has to reimplement
`StreamTrackingSession`'s behaviour to be trustworthy.

**Why a synthetic detector, not a real one.** A real `YoloDetector` needs
ultralytics/torch and a loaded model, and turns "does association hold an
id" into two entangled questions -- "did the model see it" and "did the
tracker keep it". `SyntheticDetector` answers exactly the second question:
it reports the scenario's OWN ground truth, perturbed by an explicit,
seeded `DetectorNoiseConfig`, so a metrics regression can only mean the
identity/motion code got worse, never that a model did.

**Why no fake clock is needed.** `StreamTrackingSession.process` takes
`now_millis` as a plain argument -- nothing in `cv_service/tracking/` reads
the wall clock for a DECISION (every cadence/threshold lives in
`TrackingParams`, resolved once). This driver hands it
`frame_index * (1000 / fps)`, so the scheduler's cadence math runs
deterministically at the scenario's own frame rate no matter how fast this
process actually executes. `tracker_millis` on the response IS a genuine
`time.perf_counter()` measurement inside `session.py`, and is reported
as-is -- it is a COST number, not an identity one, and real wall-clock noise
on it is expected and already labelled that way everywhere else in this
codebase.

**`detection_lag_millis` is now wired too (instrument-repair, 2026-08).**
`session.process()` has taken an optional `detection_lag_millis` since wave
V6 (`cv_service/tracking/session.py` §"Late-detection back-correction"), but
this driver never passed one -- the `latency` scenario shifted WHICH ground
truth `SyntheticDetector` returned without ever telling the session it was
stale, which made the scenario unwinnable by construction (a late,
constant-velocity stream is mathematically indistinguishable from an
on-time one starting further back; `L` is unidentifiable from
(position, arrival-time) alone). `_detection_lag_millis_for` below closes
that gap the same way pull mode does: production computes its lag from
`CaptureClock.capture_time` (`cv_service/pull/clock.py`, `capture_skew_millis`
= `now_wall - captured_at`), a same-process, same-clock estimate threaded
into `session.process()` by `grpc/servicers.py`'s `_handle_request`. This
harness knows the injected lag EXACTLY (it is the config that shifted the
ground truth), so the default (`detection_lag_jitter_millis=0.0`) reports it
exactly -- the IDEALISED case, perfect lag knowledge a real worker's
estimate never quite has. `detection_lag_jitter_millis` > 0 adds the spread
`pull/clock.py`'s own M0 measurement recorded around that estimate, for a
companion reading that shows whether the correction degrades gracefully
under the real signal's own noise rather than only a noiseless one. See
`BASELINE.md` §2's `latency` writeup for both readings.

Pure stdlib at module scope, deliberately: `cv_service.config`,
`cv_service.tracking.{params,registry,session}` are all pure stdlib
(`cv_service/tracking/__init__.py`'s own invariant), and `_SyntheticDetection`
below is defined locally rather than importing
`cv_service.inference.detector.Detection`, which would pull `cv2`/`numpy` in
for no reason a synthetic replay needs. `run_replay` still WORKS without the
`cv` extra installed -- `TrackerRegistry.probe()` just degrades every engine
off its roster and the session falls back down the `FOLLOW -> ASSOCIATE ->
OFF` ladder, exactly as it would in production on a box missing a
dependency. Building a `Sequence` to feed it, though, needs `numpy` (see
`sequences.py`).
"""

from __future__ import annotations

import random
from dataclasses import dataclass
from typing import Optional
from typing import Sequence as TypingSequence

from cv_service.config import Settings
from cv_service.tracking.engines.base import SOURCE_DETECTOR, Box
from cv_service.tracking.params import MODE_FOLLOW, LockRequest, TrackingRequest
from cv_service.tracking.registry import TrackerRegistry, build_default_registry
from cv_service.tracking.session import FrameOutcome, StreamTrackingSession

from tools.trackeval.sequences import GroundTruthObject, Sequence

MILLIS_PER_SECOND = 1000.0
# The one lock_seq this driver ever issues -- `LockArbiter` only requires
# strictly-increasing values (`lock.py`), and a replay never re-locks.
_LOCK_SEQ = 1
_WIRE_TOKEN = "trackeval"

# Typical per-frame spread of a REAL `capture_skew_millis` estimate around
# its true value, for `DetectorNoiseConfig.detection_lag_jitter_millis`.
# Sourced from `cv_service/pull/clock.py`'s own module docstring: "M0
# measured anchor ... at +/-15 ms typical spread over 10 minutes, worst
# spike ~80 ms -- inside the plan's 100 ms/10-min gate". The worst spike
# stayed under that gate's 100 ms re-anchor threshold
# (`DEFAULT_REANCHOR_THRESHOLD_MILLIS`), so no re-anchor transient fires
# within a measurement window this short (`latency`'s own 60 frames / 6 s at
# 10 fps, against M0's 10-minute run) -- modelling the typical spread alone
# is therefore the right level of realism here, not a simplification chosen
# because it is convenient.
DETECTION_LAG_JITTER_TYPICAL_MILLIS = 15.0


@dataclass(frozen=True)
class _SyntheticDetection:
    """The exact duck shape `cv_service.tracking.track.observation_for` and
    every engine adapter expect: `label`/`confidence`/`x`/`y`/`width`/
    `height`. Defined locally instead of importing
    `cv_service.inference.detector.Detection` (see the module docstring)."""

    label: str
    confidence: float
    x: float
    y: float
    width: float
    height: float


@dataclass(frozen=True)
class DetectorNoiseConfig:
    """How `SyntheticDetector` deviates from perfect ground truth.

    Every field defaults to "perfect detector" -- a scenario opts into noise
    explicitly (see `__main__.py`'s `DEFAULT_NOISE_BY_SCENARIO`) rather than
    a hidden default fuzzing every replay.
    """

    seed: int = 0
    dropout_probability: float = 0.0
    position_jitter: float = 0.0
    false_positive_probability: float = 0.0
    confidence: float = 0.9
    # Apparent size (the larger box dimension, as a fraction of the extent
    # actually handed to the detector) at or above which recall is
    # unaffected. Below it, an object is progressively more likely to be
    # missed. 0.0 disables the model entirely, which is what every scenario
    # written before ROI re-detection existed relies on.
    #
    # This is the ONE property of a real detector that a uniform dropout
    # cannot express, and without it a crop-and-re-detect pass is
    # unmeasurable: the whole reason such a pass works is that a distant
    # object occupying a handful of pixels after downscaling to `imgsz`
    # occupies a useful fraction of a crop.
    reliable_size: float = 0.0
    max_miss_probability: float = 0.9
    # How weak a detection gets as its apparent size shrinks. Real detectors
    # do not fail a target cleanly at some size -- they report it with falling
    # confidence first, and only then stop reporting it. Modelling only the
    # cliff (`reliable_size` above) makes the whole low-confidence regime
    # invisible, which is precisely the regime the acquisition/continuation
    # split exists for (docs/conclusions/CV-RATE-BUDGET.md §4).
    #
    # 0.0 disables the model: confidence stays flat at `confidence`, which is
    # what every scenario written before this existed relies on. When > 0 and
    # `reliable_size > 0`, confidence interpolates linearly from `confidence`
    # at/above `reliable_size` down to `confidence_floor` at zero apparent size.
    confidence_floor: float = 0.0
    # The threshold the DETECTOR runs at -- boxes weaker than this are never
    # emitted, exactly as `model.predict(conf=)` never creates them. 0.0 = emit
    # everything, the pre-existing behaviour.
    #
    # This is the knob the confidence split moves: before it, the operator's
    # own threshold landed here (0.4 by default); after it, the low
    # `CV_DETECT_FLOOR` does, and the operator's threshold applies to the
    # RESPONSE instead. A/B this to measure the change.
    detect_threshold: float = 0.0
    # TRACKING-V3-PLAN wave V0, the `latency` scenario -- how many frames
    # late the detector's answer arrives. 0 = instant (the pre-existing
    # behaviour: the detector always describes the CURRENT frame). N > 0
    # means a `detect()` call issued while processing frame `i` reports the
    # ground truth as it was on frame `i - N`, modelling an offboard
    # detector that takes real wall-clock time to return -- the normal case
    # TRACKING-V3-PLAN §5.2 ("L1 RELAY") describes, not an edge case. This
    # knob lives on the CONFIG, not on `SyntheticDetector`, because which
    # frame's ground truth a call sees has to be decided by `run_replay`'s
    # own frame-index loop (see `_ground_truth_for_detection`) -- doing it
    # inside `SyntheticDetector.detect()` would tie the lag to CALL COUNT
    # instead of FRAME INDEX, which silently breaks the moment a scenario
    # also uses ROI re-detection (a second, conditional `detect()` call on
    # the same frame).
    #
    # N > 0 ALSO makes `run_replay` pass this same lag to `session.process()`
    # as `detection_lag_millis` (see `_detection_lag_millis_for`) -- mirroring
    # how pull mode's worker obtains that signal for real
    # (`cv_service/pull/clock.py`'s `capture_skew_millis`), rather than
    # injecting a stale stream and leaving the session with no way to know
    # it. See `detection_lag_jitter_millis` below for how exact that report is.
    latency_frames: int = 0
    # How much spread to add around `latency_frames`' own exact lag when
    # reporting `detection_lag_millis` -- 0.0 (default) reports the exact
    # value, the IDEALISED case: this harness knows precisely how many
    # frames late the detector is (it is the config that shifted the ground
    # truth), which a real pull-mode worker's `capture_skew_millis` estimate
    # never quite does. > 0 draws a per-frame `uniform(-jitter, +jitter)`
    # offset instead (via `lag_jitter_seed`'s own RNG, independent of
    # `SyntheticDetector`'s), modelling that estimate's real measurement
    # error. `DETECTION_LAG_JITTER_TYPICAL_MILLIS` below is the value
    # sourced from `pull/clock.py`'s own M0 measurement, not tuned to make
    # any scenario's numbers look better.
    detection_lag_jitter_millis: float = 0.0
    # Seeds the RNG `detection_lag_jitter_millis` draws from -- deliberately
    # a SEPARATE stream from `SyntheticDetector`'s own `seed` (used for
    # dropout/position-jitter/false-positive draws), so enabling lag jitter
    # can never re-roll a scenario's existing detection draw sequence, the
    # same reasoning `_miss_probability`'s "draw taken only when in play"
    # comment gives for `reliable_size`.
    lag_jitter_seed: int = 0
    # TRACK-IDENTITY-PLAN wave L1's own harness addition -- models an open-
    # vocabulary model re-rolling its one argmax label independently of
    # position/confidence (`TRACK-IDENTITY-RESEARCH.md` §1), the exact
    # defect `track.elected_label` exists to smooth over. 0.0 (default, no
    # relabeling) reproduces every scenario written before this existed
    # byte-for-byte; the draw is taken ONLY when > 0.0, the SAME "draw only
    # when in play" idiom `reliable_size`'s own comment documents above for
    # the recall model, so enabling label noise can never re-roll an
    # existing scenario's position-jitter/dropout/false-positive sequence.
    label_noise_probability: float = 0.0
    # The alternate labels a noisy pass may report instead of the object's
    # own true label -- drawn uniformly via this detector's OWN `_rng`, not
    # a separate stream (unlike `detection_lag_jitter_millis`'s deliberately
    # independent one): a label re-roll is exactly the kind of per-pass
    # noise the position/confidence draws already model, not an external
    # measurement-error source that needs isolating from them. Empty (the
    # default) with noise enabled falls back to the object's own label -- a
    # no-op relabeling, never a crash on a misconfigured scenario.
    label_noise_pool: "tuple[str, ...]" = ()


# A false positive's box: small, and placed uniformly within this leading
# sub-region of the frame so it never accidentally coincides with a real
# ground-truth box at the resolution these scenarios use.
_FALSE_POSITIVE_SIZE = 0.08
_FALSE_POSITIVE_PLACEMENT_SPAN = 0.7


class SyntheticDetector:
    """Ground truth, perturbed -- stands in for one `YoloDetector.detect()` pass.

    Deterministic given `DetectorNoiseConfig.seed`: reseeded once at
    construction, and this is the ONLY randomness `run_replay` introduces
    (`Sequence`s are already frozen data by the time they reach here).
    """

    def __init__(self, config: DetectorNoiseConfig) -> None:
        self._config = config
        self._rng = random.Random(config.seed)

    def detect(
        self,
        ground_truth: TypingSequence[GroundTruthObject],
        roi: Optional[Box] = None,
    ) -> list[_SyntheticDetection]:
        """One detector pass, optionally over a crop.

        `roi` models the production ROI pass: the detector is shown only that
        region, so an object inside it is magnified by the crop factor and
        becomes correspondingly easier to see. Objects outside the crop are
        not reported at all -- a crop cannot detect what it does not contain,
        and pretending otherwise would let a ROI pass look strictly better
        than a full frame, which it is not.
        """
        detections: list[_SyntheticDetection] = []
        for obj in ground_truth:
            if not obj.visible:
                continue
            if roi is not None and obj.box.iou(roi) <= 0.0:
                continue
            if self._rng.random() < self._config.dropout_probability:
                continue
            # The draw is taken ONLY when the recall model is actually in
            # play. Consuming one unconditionally would advance this
            # detector's deterministic stream and silently re-roll the
            # jitter of every scenario written before ROI re-detection
            # existed -- which is not a hypothetical: it flipped
            # `crossing`'s id-swap count the first time this was written.
            miss_probability = self._miss_probability(obj, roi)
            if miss_probability > 0.0 and self._rng.random() < miss_probability:
                continue
            detection = self._jittered(obj, self._confidence_for(obj, roi))
            # Applied AFTER the box is built so the draw sequence is identical
            # whether or not a threshold is configured -- otherwise A/B-ing the
            # threshold would also re-roll every jitter, and the comparison
            # would measure two different sequences.
            if detection.confidence < self._config.detect_threshold:
                continue
            detections.append(detection)
        if roi is None and self._rng.random() < self._config.false_positive_probability:
            detections.append(self._false_positive())
        return detections

    def _miss_probability(self, obj: GroundTruthObject, roi: Optional[Box]) -> float:
        """How likely this pass is to miss `obj`, from its APPARENT size.

        Linear from zero at `reliable_size` to `max_miss_probability` at zero
        size. Linear rather than anything cleverer on purpose: the point is
        the monotonic relationship between apparent size and recall, and a
        curve fitted to nothing would only look more authoritative than it is.
        """
        reliable = self._config.reliable_size
        if reliable <= 0.0:
            return 0.0
        extent = max(roi.width, roi.height) if roi is not None else 1.0
        if extent <= 0.0:
            return 0.0
        apparent = max(obj.box.width, obj.box.height) / extent
        if apparent >= reliable:
            return 0.0
        shortfall = (reliable - apparent) / reliable
        return min(self._config.max_miss_probability, shortfall * self._config.max_miss_probability)

    def _confidence_for(self, obj: GroundTruthObject, roi: Optional[Box]) -> float:
        """This detection's confidence, from its APPARENT size.

        Linear from `confidence` at/above `reliable_size` down to
        `confidence_floor` at zero, mirroring `_miss_probability`'s shape
        deliberately: the same physical fact (a small object is harder to see)
        shows up first as a weaker score and then as a miss, and modelling the
        two with different curves would invent a relationship neither has.

        Consumes NO randomness, so enabling it cannot re-roll any scenario's
        existing draw sequence.
        """
        floor = self._config.confidence_floor
        reliable = self._config.reliable_size
        if floor <= 0.0 or reliable <= 0.0:
            return self._config.confidence
        extent = max(roi.width, roi.height) if roi is not None else 1.0
        if extent <= 0.0:
            return self._config.confidence
        apparent = max(obj.box.width, obj.box.height) / extent
        if apparent >= reliable:
            return self._config.confidence
        span = self._config.confidence - floor
        return floor + span * (apparent / reliable)

    def _jittered(self, obj: GroundTruthObject, confidence: float) -> _SyntheticDetection:
        jitter = self._config.position_jitter
        dx = self._rng.uniform(-jitter, jitter)
        dy = self._rng.uniform(-jitter, jitter)
        return _SyntheticDetection(
            self._label_for(obj.label),
            confidence,
            obj.box.x + dx,
            obj.box.y + dy,
            obj.box.width,
            obj.box.height,
        )

    def _label_for(self, true_label: str) -> str:
        """This detection's reported label -- `true_label` unless the L1
        noise model rerolls it. The draw is taken ONLY when `label_noise_
        probability > 0.0` (`DetectorNoiseConfig.label_noise_probability`'s
        own comment: the SAME "draw only when in play" idiom `reliable_
        size` uses), so a scenario that never enables label noise draws
        nothing extra from `self._rng` and stays byte-identical to before
        this method existed."""
        if self._config.label_noise_probability <= 0.0:
            return true_label
        if self._rng.random() >= self._config.label_noise_probability:
            return true_label
        pool = self._config.label_noise_pool or (true_label,)
        return self._rng.choice(pool)

    def _false_positive(self) -> _SyntheticDetection:
        return _SyntheticDetection(
            "false-positive",
            self._config.confidence,
            self._rng.uniform(0.0, _FALSE_POSITIVE_PLACEMENT_SPAN),
            self._rng.uniform(0.0, _FALSE_POSITIVE_PLACEMENT_SPAN),
            _FALSE_POSITIVE_SIZE,
            _FALSE_POSITIVE_SIZE,
        )


@dataclass(frozen=True)
class ReplayResult:
    """Everything `metrics.py` needs, and nothing it has to recompute.

    `width`/`height` default to 0 and `coast_track_ids` to `()` so hand-built
    `ReplayResult`s in `tests/trackeval/test_metrics.py` that predate
    TRACKING-V3-PLAN wave V0 keep constructing without change -- `metrics.py`
    treats a length mismatch between `coast_track_ids` and `outcomes` as "no
    coast data available" rather than an error (see `metrics.compute`).
    """

    scenario: str
    mode: str
    engine_id: str
    fps: float
    outcomes: tuple[FrameOutcome, ...]
    ground_truth_by_frame: tuple[tuple[GroundTruthObject, ...], ...]
    scored_gt_ids: frozenset[int]
    width: int = 0
    height: int = 0
    # TRACKING-V3-PLAN wave V0 -- `coast_track_ids[i]` is the set of emitted
    # track ids on frame `i` whose box this frame came from the TRACKER, not
    # a fresh `SOURCE_DETECTOR` observation (`metrics.py`'s coast ADE/FDE).
    # Captured HERE, immediately after each `session.process()` call, rather
    # than read back out of `FrameOutcome.boxes[i].track` later: `Track` is a
    # mutable, per-stream-unique object (`track.py`'s own docstring -- "the
    # book's own entry, updated in place once per frame") and `TrackedBox`
    # holds a REFERENCE to it, not a snapshot. Every past frame's `TrackedBox`
    # for a still-live track therefore aliases the SAME object the book keeps
    # mutating -- reading `.source`/`.state` off it after the replay has
    # finished would report this frame's status for EVERY frame that track
    # ever appeared in, not each frame's own. `TrackedBox.box` has no such
    # problem (`Box` is frozen, and `track.box = ...` REBINDS the attribute
    # rather than mutating the old `Box` in place), which is exactly why
    # every metric before this one could read `outcome.boxes[i].box` safely
    # after the fact and this is the first one that could not just do the
    # same for `.track`.
    coast_track_ids: tuple[frozenset[int], ...] = ()
    # TRACKING-V3-PLAN §6b finding O3 -- `track_velocities[i]` is
    # `{track_id: (velocity_x, velocity_y)}` for every box `outcome.boxes[i]`
    # emitted, captured the SAME way and for the SAME reason `coast_track_ids`
    # is: `track.velocity_x`/`_y` lives on the identical mutable, aliased
    # `Track` object `coast_track_ids`'s own docstring above describes, so
    # reading it back out of `FrameOutcome.boxes[i].track` after the whole
    # replay has finished would report only the LAST frame's velocity for
    # every frame that track ever appeared in -- silently hiding the exact
    # defect (a reconstructed velocity diverging to 1e14-1e32, `BASELINE.md`'s
    # `latency` writeup) this field exists to let `metrics.py` see. Defaults
    # to `()`, read by `metrics.py` as "no velocity data" (count 0), so
    # hand-built `ReplayResult`s predating this field keep constructing
    # unchanged.
    track_velocities: tuple[dict[int, tuple[float, float]], ...] = ()
    # TRACK-IDENTITY-PLAN wave L1 -- `track_labels[i]` is
    # `{track_id: elected_label}` for every box `outcome.boxes[i]` emitted,
    # captured the SAME way and for the SAME reason `coast_track_ids`/
    # `track_velocities` are: `track.elected_label` lives on the identical
    # mutable, aliased `Track` object those two fields' own docstrings
    # describe, so reading it back out of `FrameOutcome.boxes[i].track`
    # after the whole replay finished would report only the LAST frame's
    # elected label for every frame that track ever appeared in --
    # `metrics.py`'s flip counter needs each frame's OWN value. Defaults to
    # `()`, read by `metrics.py` as "no label data" (count 0), so hand-built
    # `ReplayResult`s predating this field keep constructing unchanged.
    track_labels: tuple[dict[int, str], ...] = ()


def _ground_truth_for_detection(
    sequence: Sequence, frame_index: int, latency_frames: int
) -> "TypingSequence[GroundTruthObject]":
    """What a detector running `latency_frames` behind would be reporting on,
    while processing frame `frame_index` -- the ground truth from an OLDER
    frame, not the current one (TRACKING-V3-PLAN wave V6's target, modelled
    here for wave V0's harness; see `DetectorNoiseConfig.latency_frames`).

    Before enough history exists (`frame_index < latency_frames`) a real late
    detector would not have produced anything yet either -- returns no
    detections at all rather than repeating frame 0, which would invent
    evidence a real pipeline warming up does not have.
    """
    if latency_frames <= 0:
        return sequence.frames[frame_index].ground_truth
    source_index = frame_index - latency_frames
    if source_index < 0:
        return ()
    return sequence.frames[source_index].ground_truth


def _detection_lag_millis_for(
    frame_index: int,
    fps: float,
    detector_config: DetectorNoiseConfig,
    lag_rng: random.Random,
) -> int:
    """This frame's `detection_lag_millis` -- `session.process()`'s own
    signal for late-detection back-correction (`cv_service/tracking/
    session.py` §"Late-detection back-correction", wave V6), modelled the
    way pull mode's worker actually obtains it: `CaptureClock.capture_time`
    (`cv_service/pull/clock.py`) reports `now_wall - captured_at`, an
    ESTIMATE of the same quantity `latency_frames` injects here, not a copy
    of the config read back out.

    `0` (unknown) whenever there is nothing to report: no latency injected
    (`latency_frames <= 0`), or not enough history yet for
    `_ground_truth_for_detection` to have produced a real detection to
    correct (`frame_index < latency_frames` -- the SAME guard, because a lag
    estimate for a detection that never happened is not a real signal
    either).

    Otherwise the exact injected lag (`latency_frames` frames, in millis at
    this sequence's own fps), plus a `uniform(-jitter, +jitter)` offset when
    `detection_lag_jitter_millis` is positive -- see that field's own
    docstring for what 0 vs positive means and where the magnitude comes
    from. The offset is drawn from `lag_jitter_seed`'s own RNG, so this is
    deterministic given a seed but never perturbs `SyntheticDetector`'s
    independent draw sequence.
    """
    latency_frames = detector_config.latency_frames
    if latency_frames <= 0 or frame_index < latency_frames:
        return 0
    exact_millis = latency_frames * (MILLIS_PER_SECOND / fps)
    jitter = detector_config.detection_lag_jitter_millis
    if jitter <= 0.0:
        return round(exact_millis)
    return round(exact_millis + lag_rng.uniform(-jitter, jitter))


def _coast_ids_this_frame(outcome: FrameOutcome) -> frozenset[int]:
    """Emitted track ids on this ALREADY-RETURNED `FrameOutcome` whose most
    recent touch was the tracker, not the detector -- see `ReplayResult.
    coast_track_ids` for why this must be read right after `process()`
    returns, not later."""
    if outcome.boxes is None:
        return frozenset()
    return frozenset(
        tracked.track.track_id
        for tracked in outcome.boxes
        if tracked.track is not None and tracked.track.source != SOURCE_DETECTOR
    )


def _track_velocities_this_frame(outcome: FrameOutcome) -> dict[int, tuple[float, float]]:
    """`{emitted track_id: (velocity_x, velocity_y)}` on this
    ALREADY-RETURNED `FrameOutcome` -- see `ReplayResult.track_velocities`
    for why this must be read right after `process()` returns, not later
    (the same `Track`-aliasing trap `_coast_ids_this_frame` above exists
    to avoid)."""
    if outcome.boxes is None:
        return {}
    return {
        tracked.track.track_id: (tracked.track.velocity_x, tracked.track.velocity_y)
        for tracked in outcome.boxes
        if tracked.track is not None
    }


def _track_labels_this_frame(outcome: FrameOutcome) -> dict[int, str]:
    """`{emitted track_id: label}` on this ALREADY-RETURNED `FrameOutcome` --
    see `ReplayResult.track_labels` for why this must be read right after
    `process()` returns, not later (the same `Track`-aliasing trap
    `_coast_ids_this_frame` above exists to avoid).

    Reads `tracked.label` -- the box's OWN emitted label -- not
    `tracked.track.elected_label` directly: TRACK-IDENTITY-PLAN wave L1 has
    `session.py`'s `_box_for`/`_from_track` put the elected label on the box
    for tracked detections, so `tracked.label` already IS the elected label
    here and matches exactly what went out on the wire (metrics.py's flip
    counter counts flips in the emitted stream, not in `Track` internals)."""
    if outcome.boxes is None:
        return {}
    return {
        tracked.track.track_id: tracked.label
        for tracked in outcome.boxes
        if tracked.track is not None
    }


def run_replay(
    sequence: Sequence,
    *,
    mode: str,
    engine_id: str = "",
    detector_config: DetectorNoiseConfig = DetectorNoiseConfig(),
    settings: Optional[Settings] = None,
    registry: Optional[TrackerRegistry] = None,
) -> ReplayResult:
    """Run `sequence` through one real `StreamTrackingSession`.

    ASSOCIATE scores every ground-truth object the scenario ever declares;
    FOLLOW is single-target by charter (TRACKING-PLAN §3.1), so it locks
    onto and scores only `sequence.primary_gt_id`, clicking the centre of
    its box on the first frame that object is visible -- exactly what an
    operator's click does in production (`lock.select_by_point`).
    """
    # `from_env()`, NOT a bare `Settings()`: production resolves every CV_*
    # knob from the environment (`YoloDetector`, `process_gate()`), and a
    # harness that quietly ignored them would make every configuration
    # experiment a silent no-op -- an A/B comparison would return two
    # identical rows and read as "this feature changes nothing" when in fact
    # neither arm ever applied the setting. Found exactly that way.
    settings = settings or Settings.from_env()
    registry = registry or build_default_registry(settings, probe=True)
    session = StreamTrackingSession(settings=settings, registry_provider=lambda: registry)
    detector = SyntheticDetector(detector_config)

    lock_request = _lock_onto(sequence) if mode == MODE_FOLLOW else None
    session.apply_config(
        TrackingRequest(mode=mode, engine_id=engine_id, lock=lock_request),
        wire_token=_WIRE_TOKEN,
    )

    outcomes: list[FrameOutcome] = []
    coast_track_ids: list[frozenset[int]] = []
    track_velocities: list[dict[int, tuple[float, float]]] = []
    track_labels: list[dict[int, str]] = []
    # One stream for the WHOLE replay, not one per frame -- a fresh
    # `Random()` every frame would make every draw independent of the ones
    # around it, which is not what "spread around a slowly-drifting skew
    # estimate" means; a single seeded stream at least keeps the sequence
    # reproducible frame-to-frame, same discipline `SyntheticDetector.
    # __init__` uses for its own RNG.
    lag_rng = random.Random(detector_config.lag_jitter_seed)
    for frame in sequence.frames:
        now_millis = frame.index * (MILLIS_PER_SECOND / sequence.fps)
        detection_lag_millis = _detection_lag_millis_for(frame.index, sequence.fps, detector_config, lag_rng)

        def detect(
            roi: Optional[Box] = None, frame_index=frame.index
        ) -> "tuple[list[_SyntheticDetection], int]":
            # Optional-argument, so this harness works against a session that
            # asks for a crop and one that does not -- the production side of
            # ROI re-detection lands separately. `frame_index`, not
            # `frame.ground_truth` directly (as this used to read), so
            # `detector_config.latency_frames` can redirect which frame's
            # truth is reported -- see `_ground_truth_for_detection`.
            ground_truth = _ground_truth_for_detection(sequence, frame_index, detector_config.latency_frames)
            return detector.detect(ground_truth, roi), 0

        def load_frame(image=frame.image):
            return image

        outcome = session.process(
            now_millis=now_millis,
            detect=detect,
            frame=load_frame,
            detection_lag_millis=detection_lag_millis,
        )
        outcomes.append(outcome)
        # Captured immediately, before the NEXT iteration's `process()` call
        # mutates the same live `Track` objects -- see `ReplayResult.
        # coast_track_ids`'s own docstring for why this cannot be done later.
        coast_track_ids.append(_coast_ids_this_frame(outcome))
        # Same reasoning, same frame, same reason it cannot wait -- see
        # `ReplayResult.track_velocities`'s own docstring.
        track_velocities.append(_track_velocities_this_frame(outcome))
        # Same reasoning, same frame, same reason it cannot wait -- see
        # `ReplayResult.track_labels`'s own docstring.
        track_labels.append(_track_labels_this_frame(outcome))

    engine_id_served = next((outcome.engine_id for outcome in reversed(outcomes) if outcome.engine_id), "")
    scored_gt_ids = frozenset({sequence.primary_gt_id}) if mode == MODE_FOLLOW else sequence.gt_ids

    return ReplayResult(
        scenario=sequence.name,
        mode=mode,
        engine_id=engine_id_served,
        fps=sequence.fps,
        outcomes=tuple(outcomes),
        ground_truth_by_frame=tuple(frame.ground_truth for frame in sequence.frames),
        scored_gt_ids=scored_gt_ids,
        width=sequence.width,
        height=sequence.height,
        coast_track_ids=tuple(coast_track_ids),
        track_velocities=tuple(track_velocities),
        track_labels=tuple(track_labels),
    )


def _lock_onto(sequence: Sequence) -> LockRequest:
    for frame in sequence.frames:
        for obj in frame.ground_truth:
            if obj.gt_id == sequence.primary_gt_id and obj.visible:
                cx, cy = obj.box.center
                return LockRequest(lock_seq=_LOCK_SEQ, point_x=cx, point_y=cy)
    raise ValueError(f"sequence {sequence.name!r}: primary_gt_id {sequence.primary_gt_id} is never visible")
