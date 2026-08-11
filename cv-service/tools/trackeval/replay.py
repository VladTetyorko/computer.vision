"""Replay driver: feeds a synthetic `Sequence` through the REAL
`cv_service.tracking.session.StreamTrackingSession` -- not a reimplementation.

`docs/plans/active/TRACKING-V2-PLAN.md` §4 (wave C0). This is the one place
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
from cv_service.tracking.params import MODE_FOLLOW, LockRequest, TrackingRequest
from cv_service.tracking.registry import TrackerRegistry, build_default_registry
from cv_service.tracking.session import FrameOutcome, StreamTrackingSession

from tools.trackeval.sequences import GroundTruthObject, Sequence

MILLIS_PER_SECOND = 1000.0
# The one lock_seq this driver ever issues -- `LockArbiter` only requires
# strictly-increasing values (`lock.py`), and a replay never re-locks.
_LOCK_SEQ = 1
_WIRE_TOKEN = "trackeval"


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

    def detect(self, ground_truth: TypingSequence[GroundTruthObject]) -> list[_SyntheticDetection]:
        detections: list[_SyntheticDetection] = []
        for obj in ground_truth:
            if not obj.visible:
                continue
            if self._rng.random() < self._config.dropout_probability:
                continue
            detections.append(self._jittered(obj))
        if self._rng.random() < self._config.false_positive_probability:
            detections.append(self._false_positive())
        return detections

    def _jittered(self, obj: GroundTruthObject) -> _SyntheticDetection:
        jitter = self._config.position_jitter
        dx = self._rng.uniform(-jitter, jitter)
        dy = self._rng.uniform(-jitter, jitter)
        return _SyntheticDetection(
            obj.label,
            self._config.confidence,
            obj.box.x + dx,
            obj.box.y + dy,
            obj.box.width,
            obj.box.height,
        )

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
    """Everything `metrics.py` needs, and nothing it has to recompute."""

    scenario: str
    mode: str
    engine_id: str
    fps: float
    outcomes: tuple[FrameOutcome, ...]
    ground_truth_by_frame: tuple[tuple[GroundTruthObject, ...], ...]
    scored_gt_ids: frozenset[int]


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
    for frame in sequence.frames:
        now_millis = frame.index * (MILLIS_PER_SECOND / sequence.fps)

        def detect(ground_truth=frame.ground_truth) -> "tuple[list[_SyntheticDetection], int]":
            return detector.detect(ground_truth), 0

        def load_frame(image=frame.image):
            return image

        outcomes.append(session.process(now_millis=now_millis, detect=detect, frame=load_frame))

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
    )


def _lock_onto(sequence: Sequence) -> LockRequest:
    for frame in sequence.frames:
        for obj in frame.ground_truth:
            if obj.gt_id == sequence.primary_gt_id and obj.visible:
                cx, cy = obj.box.center
                return LockRequest(lock_seq=_LOCK_SEQ, point_x=cx, point_y=cy)
    raise ValueError(f"sequence {sequence.name!r}: primary_gt_id {sequence.primary_gt_id} is never visible")
