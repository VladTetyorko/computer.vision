"""`StreamTrackingSession`: composition only. One instance per stream.

`docs/extracts/TRACKING-ORCHESTRATION.md` §3.1 (the per-frame hot path this file runs),
§3.4 (degradation), `docs/plans/done/TRACKING-PLAN.md` §3.1/§3.2.

    frame
      |- params            ALREADY RESOLVED -- no parsing, no dict build
      |- decision          scheduler.decide(now, state): PURE, before any pixel
      |- if run_detector:  detect()   <-- THE ONLY GATE ACQUISITION, and it is
      |                                    the servicer's callable, not ours
      |- if FOLLOW, or ASSOCIATE with `cost`: motion.estimate(frame, pose)
      |                     ONCE, before the mode branches -- ASSOCIATE with
      |                     `bytetrack` still never compensates (C2's own
      |                     reasoning: that engine's state lives inside a
      |                     third-party Kalman filter with no seam to warp;
      |                     `cost`'s candidates ARE the book's own tracks, so
      |                     C2's warp is what wave C3 gives ASSOCIATE a seam
      |                     for)
      |- book.warp(transform)   EVERY live track, before ANYTHING reads one --
      |                     this is what makes a stalled track accumulate N
      |                     frames of camera motion instead of one frame's
      |                     worth read once (the coordinator's C2 fix)
      |- ASSOCIATE `bytetrack`: associator.associate(dets, now)
      |  ASSOCIATE `cost`:      Candidate per live track (warped+predicted,
      |                     with its own descriptor) x Target per detection
      |                     (with a fresh descriptor when appearance is
      |                     active) -> CostAssociator.assign(...)
      |  FOLLOW:           re-anchor on best-IoU det (against the ALREADY-
      |                     warped held box), else tracker.update(frame)
      |                                   <-- NEVER TOUCHES THE GATE
      |- book.apply(obs, now)
      `- FrameOutcome{boxes, detector_ran, detector_reason, tracker_millis,
                      engine_id, locked_track_id, motion_millis, motion_engine_id}

Every branch above delegates: policy is `scheduler.py`, identity is
`track.py`, target selection is `lock.py`, pixels are `engines/`. If this
file grows a policy branch, that branch belongs in `scheduler.py`.

**On size, honestly.** TRACKING-ORCHESTRATION §2.1 budgeted "~80 lines"; this
is ~300 code lines. The charter itself held -- no scheduling policy, no
lifecycle rule and no geometry lives here, and two blocks that did (target
selection, the degradation ladder's parameter copy) were moved to `lock.py`
and `params.py` where they belong. What the estimate did not account for is
that FOLLOW alone has five distinct per-frame outcomes (re-anchor, coast,
tracker-lost, box-invalid, lost-past-max-age), each of which has to sequence
engine + book + lock, plus the `FOLLOW -> ASSOCIATE -> OFF` ladder and the
engine lifecycle. Splitting those across a ninth file would move the seam
without moving a responsibility. Recorded rather than hidden, so the next
person to read the charter is not surprised.

**The tracker path never acquires `InferenceGate`.** The gate bounds
concurrent *YOLO* passes across streams; a 0.4 ms tracker update queueing
behind a 343 ms `orion12l` pass on another stream would destroy the entire
design. Structurally, this file cannot acquire it even by accident: it holds
no reference to a gate at all, only the `detect` callable the servicer passes
in, which is the one place the gate is taken.

Pure stdlib -- the frame arrives as an opaque object from a `frame` callable
the servicer supplies (it does the `cv2` decode lazily and memoizes it), so
nothing here imports `cv2` or `numpy`.
"""

from __future__ import annotations

import dataclasses
import logging
from dataclasses import dataclass
from time import perf_counter
from typing import Any, Callable, Optional, Sequence

from cv_service.tracking import lock as lock_module
from cv_service.tracking import params as params_module
from cv_service.tracking.assign import Candidate, CostAssociator, Target
from cv_service.tracking.engines.base import (
    IDENTITY,
    SOURCE_TRACKER,
    Box,
    CameraPose,
    Descriptor,
    Observation,
    Transform,
)
from cv_service.tracking.lock import LockArbiter
from cv_service.tracking.memory import ObjectMemory, Recovery
from cv_service.tracking.params import (
    MODE_ASSOCIATE,
    MODE_FOLLOW,
    MODE_OFF,
    TrackingParams,
    TrackingRequest,
)
from cv_service.tracking.predict import predict
from cv_service.tracking.registry import MOTION_ENGINE_FLOW, MOTION_ENGINE_POSE, TrackerRegistry
from cv_service.tracking.scheduler import (
    REASON_UNSPECIFIED,
    DutyCycleScheduler,
    SchedulerState,
)
from cv_service.tracking.track import (
    STATE_LOST,
    STATE_TENTATIVE,
    RecoveredIdentity,
    Track,
    TrackBook,
    observation_for,
    observe_descriptor,
)

LOGGER = logging.getLogger("cv_service.tracking.session")

# `detect` returns whatever `InferenceServicer._detect_via_registry` returns:
# `(detections, inference_millis)`, with `detections is None` meaning "no
# model resolved at all -- echo this frame".
DetectFn = Callable[[], "tuple[Optional[list], int]"]
FrameFn = Callable[[], Any]

# `TrackingParams.motion_engine_id`'s one non-roster value (TRACKING-V2-PLAN
# §3, wire docstring "off disables") -- checked here, not in `params.py`,
# because it is this module that decides what to DO with the resolved
# value, the same division of labour `resolve()`'s own docstring draws
# between "turn a sentinel into a value" and "decide what serves it".
_MOTION_ENGINE_OFF = "off"

# `TrackingParams.appearance_engine_id`'s equivalent (TRACKING-V2-PLAN wave
# C3) -- same division of labour as `_MOTION_ENGINE_OFF` above.
_APPEARANCE_ENGINE_OFF = "off"


@dataclass(frozen=True)
class TrackedBox:
    """One box on the response, with its track facts or `None` for untracked.

    `track is None` is the ONE spelling of untracked on this side of the
    wire; the servicer turns it into `track_id == 0` and leaves every other
    track field at its proto3 zero value (TRACKING-ORCHESTRATION §6 rule 2).

    `identity_confidence`/`dormant_millis` (TRACKING-V2-PLAN wave C4,
    `Detection` wire fields 10/11) live HERE, not on `Track`: a recovery is
    an EVENT that happened on this one frame, not a property of the
    identity that persists across many frames the way `track_id`/`state` do.
    Both are `0.0`/`0` on every frame that is not the exact frame a track
    was recovered on -- `_run_cost_associate` is the only place either is
    ever set to something else.
    """

    label: str
    confidence: float
    box: Box
    track: Optional[Track] = None
    identity_confidence: float = 0.0
    dormant_millis: int = 0


@dataclass(frozen=True)
class FrameOutcome:
    """Everything the servicer needs to build one `DetectionResponse`.

    `boxes is None` means "echo this frame" -- the same degradation the
    servicer already applies when no model resolves.
    """

    boxes: Optional[list[TrackedBox]]
    inference_millis: int = 0
    detector_ran: bool = False
    detector_reason: str = REASON_UNSPECIFIED
    tracker_millis: int = 0
    engine_id: str = ""
    locked_track_id: int = 0
    # TRACKING-V2-PLAN wave C2/C3 -- 0 / "" on every frame this platform does
    # not compensate: OFF always; ASSOCIATE unless `cost` is the resolved
    # engine (see the module docstring); and any FOLLOW/`cost`-ASSOCIATE
    # frame where nothing was constructible.
    motion_millis: int = 0
    motion_engine_id: str = ""


class StreamTrackingSession:
    """Per-stream tracking state, created inside `DetectStream`.

    Created in the same place, and for the same reason, as `_StreamReader`:
    it is state that belongs to one bidi call, not to the servicer, which is
    shared by every stream. Construction is free -- no engine is built until
    a frame actually asks for an active mode.
    """

    def __init__(
        self, *, settings: Any, registry_provider: Callable[[], Optional[TrackerRegistry]]
    ) -> None:
        self._settings = settings
        self._registry_provider = registry_provider
        self._params = params_module.resolve(TrackingRequest(), settings)
        self._scheduler = DutyCycleScheduler(self._params)
        self._book = TrackBook(self._params)
        self._lock = LockArbiter()
        self._engine: Any = None
        self._engine_id = ""
        self._last_detector_millis: Optional[float] = None
        self._tracker_failed = False
        self._box_invalid = False
        self._tracker_stalled = False
        self._followed: Optional[Track] = None
        self._degraded_engine_ids: set[str] = set()
        # Ego-motion compensator (TRACKING-V2-PLAN wave C2) -- resolved
        # lazily on the first active FOLLOW frame, same build-once shape as
        # `_engine`/`_engine_id` above, but independent of it: which SOT
        # holds the target and which engine measures the camera's own motion
        # are orthogonal choices (`motion_engine_id` is its own wire field).
        self._motion_engine: Any = None
        self._motion_engine_id = ""
        self._motion_resolved = False
        self._degraded_motion_ids: set[str] = set()
        # Appearance extractor (TRACKING-V2-PLAN wave C3) -- resolved lazily
        # the first time `cost` actually asks for one (`_run_cost_associate`,
        # only reached when `engine_id == "cost"`), same build-once shape as
        # the motion compensator above but simpler: an extractor needs no
        # live signal to check (no `pose`-style availability gate), so there
        # is no fallback ladder, only "off" or "the one thing asked for".
        self._appearance_engine: Any = None
        self._appearance_engine_id = ""
        self._appearance_resolved = False
        self._degraded_appearance_ids: set[str] = set()
        # Dormant gallery (TRACKING-V2-PLAN wave C4) -- resolved lazily on
        # the first ACTIVE frame regardless of mode/engine (unlike appearance
        # above): `TrackBook._retire` must be able to remember a LOST track
        # no matter which associator produced it, so this is built (or
        # genuinely disabled) once near the top of `process()`, not only
        # from within `_run_cost_associate` (`_resolve_memory` below).
        self._memory: Optional[ObjectMemory] = None
        self._memory_resolved = False
        # The last wire `TrackingConfig` message applied, held opaquely and
        # compared by equality (a protobuf `==`, no allocation) so the
        # restated-every-frame config costs one comparison per frame and
        # `resolve()` runs only on a real change. The servicer owns the
        # comparison because the message is a wire type.
        self.applied_wire_config: Any = None

    # -- configuration ------------------------------------------------------

    @property
    def active(self) -> bool:
        """False in OFF: the servicer takes its untouched pre-tracking path."""
        return self._params.active

    @property
    def params(self) -> TrackingParams:
        return self._params

    @property
    def tracks(self) -> list[Track]:
        """Live tracks -- diagnostics and tests, never the hot path."""
        return self._book.tracks

    def apply_config(self, request: TrackingRequest, wire_token: Any = None) -> None:
        """Adopt a changed wire config. Called on change only, never per frame."""
        previous = self._params
        self._params = params_module.resolve(request, self._settings)
        self.applied_wire_config = wire_token
        self._scheduler.retune(self._params)
        self._book.retune(self._params)
        if (
            self._params.mode != previous.mode
            or self._params.engine_id != previous.engine_id
            or self._params.max_age_frames != previous.max_age_frames
        ):
            # Mode, engine and `max_age_frames` are all baked into the engine
            # instance (the last is ByteTrack's own lost-track buffer), so
            # changing any of them rebuilds it. Cadence/IoU/min-hits changes
            # do not: those are read per frame from `TrackingParams` and must
            # not cost the operator their track ids.
            self._release_engine()
        if self._params.motion_engine_id != previous.motion_engine_id:
            # A separate check from the block above: the motion compensator
            # is independent of the SOT engine (and of `mode` -- an operator
            # may switch `lk` <-> `ncc` without ever touching this), so it
            # is only rebuilt when ITS OWN wire field actually changes.
            self._release_motion_compensator()
        if self._params.appearance_engine_id != previous.appearance_engine_id:
            # Same independence as motion above -- appearance is orthogonal
            # to which associator or SOT engine is active.
            self._release_appearance_extractor()
        if self._params.memory_params != previous.memory_params:
            # `MemoryParams` (TRACKING-V2-PLAN wave C4) is a value, so this
            # is one dataclass `==` -- cheap, and it covers every knob
            # (`ttl_millis` included) with one check. A gallery that is
            # already built and STAYS enabled can adopt the new numbers in
            # place (`ObjectMemory.retune`, itself immediate-capacity-
            # enforcing); anything else -- not yet resolved, or the TTL
            # crossing the enabled/disabled line in either direction --
            # funnels through `_release_memory`, which is always safe to
            # call (a no-op if nothing was built yet) and leaves the next
            # active frame's `_resolve_memory` to decide fresh.
            if self._memory is not None and self._params.memory_params.ttl_millis > 0:
                self._memory.retune(self._params.memory_params)
            else:
                self._release_memory()
        if self._lock.apply(request.lock):
            self._followed = None
        if not self._params.active:
            self._reset_state()

    # -- the per-frame hot path --------------------------------------------

    def process(
        self,
        *,
        now_millis: float,
        detect: DetectFn,
        frame: FrameFn,
        pose: CameraPose = CameraPose(),
    ) -> FrameOutcome:
        """Run one frame through §3.1's sequence."""
        engine = self._resolve_engine()
        # Resolved unconditionally, not only when `cost` is the serving
        # associator (TRACKING-V2-PLAN wave C4): `TrackBook._retire` must be
        # able to remember a LOST track regardless of mode/engine, so the
        # book's own reference to the gallery has to be current before
        # anything below could possibly expire one.
        self._resolve_memory()

        state = SchedulerState(
            mode=self._params.mode,
            has_lock=self._followed is not None,
            last_detector_millis=self._last_detector_millis,
            tracker_failed=self._tracker_failed,
            box_invalid=self._box_invalid,
            coasting_frames=self._followed.misses if self._followed else 0,
        )
        decision = self._scheduler.decide(now_millis, state)
        self._tracker_failed = False
        self._box_invalid = False

        detections: Optional[list] = None
        inference_millis = 0
        if decision.run_detector:
            detections, inference_millis = detect()
            self._last_detector_millis = now_millis
            if detections is None:
                return FrameOutcome(boxes=None)

        now = now_millis / 1000.0

        # Ego-motion, once per frame, before the mode branches.
        #
        # ASSOCIATE is compensated ONLY when `cost` is the resolved engine
        # (TRACKING-V2-PLAN wave C3). `bytetrack` still never compensates --
        # its association state lives inside a third-party engine
        # (`ByteTrackEngine`'s own Kalman filters) with no seam to warp, and
        # this ultralytics version has no `STrack.multi_gmc`, so warping
        # `TrackBook`'s own copy of a `bytetrack` track would change nothing
        # `bytetrack` itself reads. `cost`'s candidates ARE `TrackBook`'s own
        # tracks (`_run_cost_associate`), which is exactly the seam this
        # wave gives ASSOCIATE. Gating this precisely -- not on "ASSOCIATE"
        # broadly -- is also what keeps the DEFAULT (`bytetrack`) ASSOCIATE
        # path from paying a new per-frame frame-decode cost for a transform
        # it could never use (see `_estimate_motion`'s own note).
        transform = IDENTITY
        motion_millis = 0
        motion_engine_id = ""
        if self._params.mode == MODE_FOLLOW or (
            self._params.mode == MODE_ASSOCIATE and self._engine_id == CostAssociator.engine_id
        ):
            transform, motion_millis, motion_engine_id = self._estimate_motion(pose, frame, now)

        # Warp every LIVE track's stored state through this frame's
        # transform, BEFORE anything reads a track box this frame --
        # prediction, the re-anchor test, a lock-by-id lookup, coasting.
        # This is what makes compensation accumulate correctly across a
        # stall instead of undercorrecting: a track nobody reads for N
        # frames still gets N single-frame warps, one per `process()` call,
        # matching N frames of real camera motion. No-op for `IDENTITY`
        # (OFF/ASSOCIATE, or FOLLOW with nothing to compensate with) -- see
        # `TrackBook.warp()`'s own docstring for the full reasoning and the
        # defect this replaced (warping only the READ, once, at whatever
        # frame happened to call `predict()`).
        self._book.warp(transform)

        started = perf_counter()
        if engine is None or self._params.mode == MODE_OFF:
            boxes = [_box_for(detection) for detection in (detections or [])]
        elif self._params.mode == MODE_ASSOCIATE:
            boxes = self._run_associate(engine, detections or [], now, frame)
        elif decision.run_detector:
            boxes = self._follow_verify(engine, detections or [], frame, now)
        else:
            boxes = self._follow_predict(engine, frame, now)
        tracker_millis = int(round((perf_counter() - started) * 1000.0))

        return FrameOutcome(
            boxes=boxes,
            inference_millis=inference_millis,
            detector_ran=decision.run_detector,
            detector_reason=decision.reason,
            tracker_millis=tracker_millis,
            engine_id=self._engine_id,
            locked_track_id=self._lock.bound_track_id,
            motion_millis=motion_millis,
            motion_engine_id=motion_engine_id,
        )

    # -- mode A: associate --------------------------------------------------

    def _run_associate(
        self, engine: Any, detections: Sequence[Any], now: float, frame: FrameFn
    ) -> list[TrackedBox]:
        if self._engine_id == CostAssociator.engine_id:
            return self._run_cost_associate(engine, detections, now, frame)

        try:
            observations = engine.associate(detections, now)
        except Exception as exc:  # noqa: BLE001 - one bad frame, not a dead stream
            self._reset_engine(exc)
            return [_box_for(detection) for detection in detections]

        tracks = self._book.apply(observations, now, detector_ran=True)
        by_index = {
            observation.det_index: track
            for observation, track in zip(observations, tracks)
            if observation.det_index >= 0
        }
        return [_box_for(detection, by_index.get(index)) for index, detection in enumerate(detections)]

    def _run_cost_associate(
        self, engine: CostAssociator, detections: Sequence[Any], now: float, frame: FrameFn
    ) -> list[TrackedBox]:
        """ASSOCIATE via `assign.CostAssociator`: the platform owns the match.

        Unlike `bytetrack`, which holds its own Kalman state and only hands
        the book finished observations to rename, `cost` has no state of its
        own at all -- the CANDIDATES it ranks are `TrackBook`'s OWN live
        tracks, predicted to `now` and (for ASSOCIATE, only when `cost` is
        the resolved engine -- see `process()`) already ego-motion-warped
        this frame. That is what makes this the one associator for which
        TRACKING-V2-PLAN wave C2's `TrackBook.warp()` changes the
        association DECISION rather than only what a coasting box displays.
        """
        extractor = self._resolve_appearance_extractor()
        boxes = [Box(d.x, d.y, d.width, d.height) for d in detections]
        descriptors = self._describe(extractor, frame, boxes)

        targets = [
            Target(
                box=boxes[index],
                label=detection.label,
                confidence=detection.confidence,
                descriptor=descriptors[index],
                det_index=index,
            )
            for index, detection in enumerate(detections)
        ]
        candidates = [
            Candidate(
                key=track.key,
                box=predict(track, now).box,
                label=track.label,
                descriptor=track.descriptor,
                confirmed=track.state != STATE_TENTATIVE,
            )
            for track in self._book.tracks
        ]

        # No appearance extractor active on this stream -> pure geometry,
        # never geometry plus a constant (`assign.py`'s own `test_appearance_
        # is_ignored_entirely_when_it_is_not_weighted`): the configured
        # weight is a deployment default for "when `cost` AND an appearance
        # engine are both active", not a promise appearance always counts.
        # Checked on the EXTRACTOR, not on whether this particular frame
        # produced a descriptor -- a frame with no describable boxes must
        # not be treated as "appearance is off for this stream", since
        # `assign.py`'s own missing-descriptor handling (neutral 0.5, never
        # a rejection) already covers that case correctly.
        weights = self._params.cost_weights
        if extractor is None and weights.appearance > 0.0:
            weights = dataclasses.replace(weights, appearance=0.0)
        engine.retune(weights=weights, gates=self._params.cost_gates)

        assignment = engine.assign(candidates, targets)

        observations: list[Observation] = []
        observation_descriptors: list[Optional[Descriptor]] = []
        for candidate_index, target_index in assignment.matches:
            target = targets[target_index]
            observations.append(
                Observation(
                    key=candidates[candidate_index].key,
                    box=target.box,
                    label=target.label,
                    confidence=target.confidence,
                    det_index=target.det_index,
                )
            )
            observation_descriptors.append(target.descriptor)

        # TRACKING-V2-PLAN wave C4: an unmatched target has no live candidate
        # claiming it, but that is not the same question as "is this a
        # BRAND-NEW object" -- it may be one this book itself retired
        # earlier. Offered to the dormant gallery BEFORE `TrackBook.apply()`
        # runs, so a match can be booked under the remembered id in the SAME
        # call that books everything else, rather than as a second pass.
        recoveries: "dict[object, RecoveredIdentity]" = {}
        recovery_by_index: "dict[int, Recovery]" = {}
        for target_index in assignment.unmatched_targets:
            target = targets[target_index]
            # A fresh, permanently-unique token: `cost` allocates no identity
            # of its own (unlike `bytetrack`'s own key counter), so a
            # brand-new candidate needs a key nothing else could ever
            # collide with. `TrackBook._namespaced` only requires it be
            # hashable and stable across frames (it becomes `track.key`,
            # read back on the NEXT frame's `self._book.tracks` loop above)
            # -- an `object()` sentinel satisfies both with no counter to
            # manage. It also doubles as `recoveries`' own key, below.
            key = object()
            observations.append(
                Observation(
                    key=key,
                    box=target.box,
                    label=target.label,
                    confidence=target.confidence,
                    det_index=target.det_index,
                )
            )
            observation_descriptors.append(target.descriptor)
            recovered = self._attempt_recovery(target, now)
            if recovered is not None:
                identity, recovery = recovered
                recoveries[key] = identity
                recovery_by_index[target.det_index] = recovery

        tracks = self._book.apply(observations, now, detector_ran=True, recoveries=recoveries)
        for track, descriptor in zip(tracks, observation_descriptors):
            observe_descriptor(track, descriptor)

        by_index = {
            observation.det_index: track
            for observation, track in zip(observations, tracks)
            if observation.det_index >= 0
        }
        return [
            _box_for(
                detection,
                by_index.get(index),
                identity_confidence=(
                    recovery_by_index[index].confidence if index in recovery_by_index else 0.0
                ),
                dormant_millis=(
                    recovery_by_index[index].dormant_millis if index in recovery_by_index else 0
                ),
            )
            for index, detection in enumerate(detections)
        ]

    def _attempt_recovery(
        self, target: Target, now: float
    ) -> "Optional[tuple[RecoveredIdentity, Recovery]]":
        """Offer one unmatched target to the dormant gallery, and claim it on
        a hit (TRACKING-V2-PLAN wave C4).

        `ObjectMemory.match()` is deliberately read-only (a candidate may
        still lose to a better-scoring one this same frame, or simply not be
        worth taking), so only a caller that has decided to TAKE the
        recovery calls `.claim()`. `claim()` returning `None` here means a
        same-frame race, not an error: TWO unmatched targets can each score
        above threshold against the same dormant entry (a crowd is exactly
        where this matters -- see `MODULE.md`'s clutter finding), and
        `ObjectMemory` only pops an identity once. Whichever target's turn
        comes first in `assignment.unmatched_targets` wins it; every other
        target that would have matched the SAME identity falls back to a
        fresh id on this call, exactly as if nothing had matched -- never a
        forced double-claim of one operator-recognised number onto two
        different objects.
        """
        memory = self._memory
        if memory is None:
            return None
        recovery = memory.match(
            box=target.box,
            label=target.label,
            descriptor=target.descriptor,
            now_millis=now * 1000.0,
        )
        if recovery is None:
            return None
        identity = memory.claim(recovery.track_id)
        if identity is None:
            return None
        recovered = RecoveredIdentity(
            track_id=recovery.track_id,
            first_seen=identity.first_seen_millis / 1000.0,
            descriptor=identity.descriptor,
            velocity=identity.velocity,
        )
        return recovered, recovery

    def _describe(
        self, extractor: Any, frame: FrameFn, boxes: Sequence[Box]
    ) -> "list[Optional[Descriptor]]":
        """This frame's appearance descriptors, one per box, positionally.

        `extractor` is already resolved by the caller (`_run_cost_associate`,
        which also needs to know WHETHER one resolved to decide the cost
        weight, not just what it returns) -- `None` for every box when it is
        `None`, the common case until an operator opts BOTH `cost` and an
        appearance engine in (see `config.py`'s `DEFAULT_TRACK_APPEARANCE_
        ENGINE` note on why that default only matters once `cost` is already
        chosen). Never decodes a frame unless an extractor actually
        resolved, and never raises: a describing failure costs this frame's
        appearance evidence, never the stream (P5), same posture `_estimate_
        motion` already takes for a raising compensator.
        """
        if not boxes or extractor is None:
            return [None] * len(boxes)
        try:
            described = extractor.describe(frame(), boxes)
        except Exception as exc:  # noqa: BLE001 - a bad extractor costs accuracy, never the stream
            if self._appearance_engine_id not in self._degraded_appearance_ids:
                self._degraded_appearance_ids.add(self._appearance_engine_id)
                LOGGER.warning(
                    "appearance extractor %r raised (%s); this frame runs without appearance evidence",
                    self._appearance_engine_id,
                    exc,
                )
            self._appearance_engine = None
            self._appearance_resolved = False
            return [None] * len(boxes)
        return list(described)

    # -- mode B: follow -----------------------------------------------------

    def _follow_verify(
        self, engine: Any, detections: Sequence[Any], frame: FrameFn, now: float
    ) -> list[TrackedBox]:
        """A detector pass ran: re-anchor the held target, or start coasting."""
        boxes = [Box(d.x, d.y, d.width, d.height) for d in detections]
        index = self._select_target(boxes, now)

        if index >= 0:
            try:
                anchored = engine.init(frame(), boxes[index])
            except Exception as exc:  # noqa: BLE001
                self._reset_engine(exc)
                return [_box_for(detection) for detection in detections]
            if anchored:
                observation = observation_for(
                    detections[index],
                    self._follow_key(),
                    det_index=index,
                    # A re-anchor is CONFIRMED outright (TRACKING-PLAN §3.1):
                    # the operator chose this target and the detector just
                    # re-approved it, so `min_hits` -- an anti-flicker gate
                    # for ambient association -- does not apply.
                    authoritative=True,
                )
                track = self._book.apply([observation], now, detector_ran=True)[0]
                self._followed = track
                self._lock.bind(track.track_id)
                self._tracker_stalled = False
                return [
                    _box_for(detection, track if position == index else None)
                    for position, detection in enumerate(detections)
                ]

        if self._followed is None:
            # Nothing held and nothing to acquire. The operator's target
            # request (if any) stands, so the next frame tries again --
            # scheduler trigger (c).
            self._lock.unbind()
            return [_box_for(detection) for detection in detections]

        # The verify pass ran and did not re-confirm the held target
        # (TRACKING-PLAN §3.1, "IoU < threshold -> keep tracking, state
        # COASTING"). The coasted box is emitted BESIDE the detector's boxes,
        # because by definition it is not one of them.
        coasted = self._coast(engine, frame, now, detector_ran=True)
        emitted = [_box_for(detection) for detection in detections]
        if coasted is not None:
            emitted.append(coasted)
        return emitted

    def _follow_predict(self, engine: Any, frame: FrameFn, now: float) -> list[TrackedBox]:
        """A tracker-only frame: move the held box and emit it alone."""
        if self._followed is None:  # pragma: no cover - the scheduler forbids it
            return []
        coasted = self._coast(engine, frame, now, detector_ran=False)
        return [coasted] if coasted is not None else []

    def _coast(
        self, engine: Any, frame: FrameFn, now: float, *, detector_ran: bool
    ) -> Optional[TrackedBox]:
        """Advance the held target by the tracker alone.

        A tracker that reports lost, or returns a box that has collapsed or
        left the frame, keeps the last known box for this frame and raises
        the corresponding scheduler trigger for the next one -- degrading a
        frame is always preferred to dropping the target on a single bad
        update.

        Those triggers are raised **only on tracker-only frames**, and that
        is load-bearing rather than incidental. Triggers (b) and (d) exist to
        BRING FORWARD the next verify pass; re-raising them on a verify frame
        that already ran and still could not re-anchor would ask for a pass
        that has just happened, and the detector would then run on every
        single frame for as long as the tracker stayed unhappy -- the duty
        cycle collapsing back into continuous detection, silently, exactly
        when the target is hardest. Once a pass has been spent and failed,
        the miss counter and trigger (e) own the outcome: the track coasts
        and goes LOST on schedule.
        """
        held = self._followed
        if held is None:  # pragma: no cover - guarded by both callers
            return None

        if self._tracker_stalled:
            # The tracker has already told us it lost this target AND the
            # verify pass that followed could not re-anchor it. Asking it
            # again every frame buys nothing: it PREDICTS the box forward at
            # constant velocity rather than asking the engine again (review
            # finding C1) -- a stalled target keeps moving, and freezing it
            # here is exactly what used to make the eventual re-anchor test
            # fail against a position the object had long since left.
            box = predict(held, now).box
            predicted = True
        else:
            predicted = False
            try:
                update = engine.update(frame())
            except Exception as exc:  # noqa: BLE001
                self._reset_engine(exc)
                return None

            if update is None or not update.box.valid:
                # Same predict-don't-freeze fix as above, for the one-off
                # failure that has not yet latched `_tracker_stalled`.
                box = predict(held, now).box
                predicted = True
                if detector_ran:
                    self._tracker_stalled = True
                elif update is None:
                    self._tracker_failed = True
                else:
                    self._box_invalid = True
            else:
                box = update.box
                if not detector_ran and update.confidence < self._params.min_tracker_confidence:
                    # Review finding C2: both engines compute `confidence`
                    # and document it as the earliest honest signal that a
                    # verify pass is worth spending (trigger (b)) -- LK's
                    # surviving-corner fraction, NCC's own match score
                    # weakening before the update actually fails outright.
                    # Wiring it in here, rather than only reacting to a hard
                    # `None`, is what "wire TrackerUpdate.confidence into
                    # trigger (b)" means. Restricted to tracker-only frames
                    # for the exact reason the hard-failure branch above is:
                    # raising it on a verify frame that just ran would ask
                    # for a pass that has already happened and collapse the
                    # duty cycle into continuous detection while the target
                    # is hardest to hold (same rule this method's docstring
                    # states for triggers (b)/(d)).
                    self._tracker_failed = True

        observation = Observation(
            key=self._follow_key(),
            box=box,
            label=held.label,
            confidence=held.confidence,
            source=SOURCE_TRACKER,
            # True exactly when `box` came from `predict()` rather than from
            # the engine -- see `Observation.predicted`.
            predicted=predicted,
        )
        track = self._book.apply([observation], now, detector_ran=detector_ran)[0]
        self._followed = track
        if track.state == STATE_LOST:
            # `max_age_frames` consecutive unconfirmed verify passes: the
            # lock is dropped and the next pass re-acquires per policy
            # (TRACKING-PLAN §3.1 trigger (e)). The track itself stays in the
            # book until it expires, so a re-acquisition of the same target
            # recovers the same id.
            self._followed = None
            self._lock.unbind()
        return _from_track(track)

    def _select_target(self, boxes: Sequence[Box], now: float) -> int:
        """Which detection FOLLOW should hold on this pass, or -1. All the
        actual selection is `lock.py`'s; this only supplies the state.

        The held/known box fed into the re-anchor test is the PREDICTED one
        (review finding C1's other half): matching this frame's detections
        against where the target physically was several coasted frames ago
        is exactly the freeze that used to make a legitimate re-anchor fail
        its own IoU test. `TrackBook.warp()` (TRACKING-V2-PLAN C2) already
        carried the underlying track through every frame of camera motion
        since it was last read, so `predict()` here needs no transform of
        its own -- `self._followed.box` is already expressed in THIS
        frame's coordinates.
        """
        held_box = predict(self._followed, now).box if self._followed is not None else None
        return lock_module.select_target(
            boxes,
            held_box=held_box,
            target=self._lock.target,
            min_iou=self._params.redetect_iou_threshold,
            box_of_track=lambda track_id: self._box_of_track(track_id, now),
        )

    def _box_of_track(self, track_id: int, now: float) -> Optional[Box]:
        known = self._book.get(track_id)
        return predict(known, now).box if known is not None else None

    def _follow_key(self) -> str:
        """A fresh `TrackBook` key per applied lock, so re-acquiring after a
        release yields a NEW id instead of resurrecting the previous one."""
        return f"follow:{self._lock.generation}"

    # -- engines and degradation -------------------------------------------

    def _resolve_engine(self) -> Any:
        """The engine serving this stream, building it on first use.

        Degrades per TRACKING-ORCHESTRATION §3.4 -- `FOLLOW -> ASSOCIATE ->
        OFF`, logged once per engine id, never an exception, never a dead
        stream. A degradation is visible on the wire as a
        `tracker_engine_id` that is empty, or different from the one asked
        for, rather than as silence.
        """
        if self._engine is not None:
            return self._engine
        if not self._params.active:
            return None
        registry = self._registry_provider()
        if registry is None:
            self._degrade_to(MODE_OFF, "no tracker registry on this host")
            return None

        created = self._create_engine(registry)
        if created is None and self._params.mode == MODE_FOLLOW:
            self._degrade_to(MODE_ASSOCIATE, "no FOLLOW engine constructible")
            created = self._create_engine(registry)
        if created is None:
            self._degrade_to(MODE_OFF, "no tracking engine constructible")
            return None

        self._engine_id, self._engine = created
        return self._engine

    def _create_engine(self, registry: TrackerRegistry) -> Optional[tuple[str, Any]]:
        if self._params.mode == MODE_FOLLOW:
            return registry.follower(
                self._params.engine_id, max_age_frames=self._params.max_age_frames
            )
        return registry.associator(
            self._params.engine_id, max_age_frames=self._params.max_age_frames
        )

    def _degrade_to(self, mode: str, why: str) -> None:
        """One step down the `FOLLOW -> ASSOCIATE -> OFF` ladder, logged once
        per engine id (TRACKING-ORCHESTRATION §3.4)."""
        engine_id = "" if mode == MODE_OFF else self._settings.track_associate_engine
        if self._params.engine_id not in self._degraded_engine_ids:
            self._degraded_engine_ids.add(self._params.engine_id)
            LOGGER.warning(
                "tracking: engine_id=%r %s; degrading %s -> %s",
                self._params.engine_id,
                why,
                self._params.mode,
                mode,
            )
        self._params = self._params.with_mode(mode, engine_id)
        self._scheduler.retune(self._params)
        self._book.retune(self._params)

    # -- ego-motion (TRACKING-V2-PLAN wave C2, wave C3 extends to ASSOCIATE) -

    def _estimate_motion(
        self, pose: CameraPose, frame: FrameFn, now: float
    ) -> "tuple[Transform, int, str]":
        """This frame's camera-motion transform, timed, plus who served it.

        Called for FOLLOW always, and for ASSOCIATE only when `cost` is the
        resolved engine (see `process()`) -- resolving a compensator here is
        cheap (no pixels touched yet), but actually running `flow` needs a
        decoded frame. For FOLLOW that frame decode is free: `frame()` is the
        servicer's MEMOIZED loader and the SOT engine already needs pixels
        this frame. For ASSOCIATE with `cost` it is a genuine NEW per-frame
        cost that did not exist before wave C3 -- paid deliberately, only for
        the one engine that can use the result, and reused (not re-decoded)
        by `_describe`'s own `frame()` call in the same frame when appearance
        is also active. `pose` engines ignore `frame` entirely but are handed
        it anyway -- harmless, since it is the same memoized call either way.
        """
        engine = self._resolve_motion_compensator(pose)
        if engine is None:
            return IDENTITY, 0, ""
        started = perf_counter()
        try:
            transform = engine.estimate(frame(), pose)
        except Exception as exc:  # noqa: BLE001 - a bad estimate costs accuracy, never the stream
            LOGGER.warning(
                "motion compensator %r raised (%s); this frame runs uncompensated",
                self._motion_engine_id,
                exc,
            )
            self._motion_engine = None
            self._motion_resolved = False
            return IDENTITY, int(round((perf_counter() - started) * 1000.0)), ""
        motion_millis = int(round((perf_counter() - started) * 1000.0))
        return transform, motion_millis, self._motion_engine_id

    def _resolve_motion_compensator(self, pose: CameraPose) -> Any:
        """The compensator serving this stream, built and pose-checked once.

        Mirrors `_resolve_engine`'s build-once shape, plus a policy that one
        does not need: `pose` is always CONSTRUCTIBLE (it needs no cv2, no
        telemetry, to build) but can still be UNAVAILABLE for this stream --
        no FOV on the wire, which is the documented normal state until the
        Java one-liner of TRACKING-V2-PLAN §2.1 lands. That is a runtime fact
        about this stream's OWN poses, not a startup constructibility result,
        so it is checked here against a live `CameraPose` rather than by
        `TrackerRegistry.probe()`. Falls back to `flow`, logged once; if
        nothing at all is constructible, no compensation -- never an
        exception, never a dead stream (P5).
        """
        if self._motion_resolved:
            return self._motion_engine
        self._motion_resolved = True

        requested = self._params.motion_engine_id
        if requested == _MOTION_ENGINE_OFF:
            return None
        registry = self._registry_provider()
        if registry is None:
            return None

        created = registry.compensator(requested)
        if created is None:
            return None
        engine_id, engine = created
        if engine_id == MOTION_ENGINE_POSE and not engine.available(pose):
            if engine_id not in self._degraded_motion_ids:
                self._degraded_motion_ids.add(engine_id)
                LOGGER.info(
                    "motion: engine_id=%r has no usable camera_pose on this stream yet; "
                    "falling back to %r",
                    engine_id,
                    MOTION_ENGINE_FLOW,
                )
            created = registry.compensator(MOTION_ENGINE_FLOW)
            if created is None:
                return None
            engine_id, engine = created

        self._motion_engine_id = engine_id
        self._motion_engine = engine
        return engine

    def _release_motion_compensator(self) -> None:
        """Drop the current compensator so the next active FOLLOW frame
        rebuilds (and re-checks pose availability) from scratch."""
        self._motion_engine = None
        self._motion_engine_id = ""
        self._motion_resolved = False

    # -- appearance (TRACKING-V2-PLAN wave C3) -------------------------------

    def _resolve_appearance_extractor(self) -> Any:
        """The appearance extractor serving this stream, built once.

        Mirrors `_resolve_motion_compensator`'s build-once shape, but
        simpler: an extractor needs no live signal to check like `pose`'s
        `CameraPose` -- constructibility IS availability here, so there is
        no fallback ladder, only "off" or the one thing actually asked for.
        Only ever reached from `_run_cost_associate` (i.e. only when `cost`
        is the resolved associator) -- `bytetrack` has no descriptor input to
        feed, so a stream using it never resolves this and never decodes a
        frame for appearance purposes either.
        """
        if self._appearance_resolved:
            return self._appearance_engine
        self._appearance_resolved = True

        requested = self._params.appearance_engine_id
        if requested == _APPEARANCE_ENGINE_OFF:
            return None
        registry = self._registry_provider()
        if registry is None:
            return None
        created = registry.appearance(requested)
        if created is None:
            return None
        self._appearance_engine_id, self._appearance_engine = created
        return self._appearance_engine

    def _release_appearance_extractor(self) -> None:
        """Drop the current extractor so the next active `cost` frame
        rebuilds from scratch."""
        self._appearance_engine = None
        self._appearance_engine_id = ""
        self._appearance_resolved = False

    # -- object memory (TRACKING-V2-PLAN wave C4) ----------------------------

    def _resolve_memory(self) -> Optional[ObjectMemory]:
        """This stream's dormant gallery, built once memory is enabled.

        Mirrors `_resolve_engine`'s shape -- NOT `_resolve_appearance_
        extractor`'s -- deliberately: that one is reached only from `cost`'s
        own path, but `TrackBook._retire` has to be able to remember a LOST
        track regardless of which associator produced it, so this is called
        unconditionally, once, near the top of `process()`. While `self.
        _params.active` is False nothing is decided yet and the next active
        frame tries again, the same early return `_resolve_engine` itself
        uses. Once active, resolved exactly once: `TrackingParams.memory_
        params.ttl_millis <= 0` (deployment or per-request choice, `params.
        py`'s `resolve()`) means running with NO gallery at all --
        `self._memory` stays `None`, and every call site downstream
        (`TrackBook._retire`, `_attempt_recovery`) already treats `None` as
        the genuine no-op P5 requires, never an empty object still being
        consulted every frame.
        """
        if self._memory_resolved:
            return self._memory
        if not self._params.active:
            return None
        self._memory_resolved = True
        if self._params.memory_params.ttl_millis > 0:
            self._memory = ObjectMemory(self._params.memory_params)
        self._book.set_memory(self._memory)
        return self._memory

    def _release_memory(self) -> None:
        """Drop the current gallery so the next active frame rebuilds (or
        stays off) from `TrackingParams.memory_params` as it stands then.

        Called on a genuine stop (`_reset_state`) and whenever `memory_
        params` changes to something `apply_config` cannot simply `.retune()`
        in place -- same build-once-lazily shape as `_release_motion_
        compensator`/`_release_appearance_extractor`. Safe to call when
        nothing was ever built (both assignments are then no-ops).
        """
        self._memory = None
        self._memory_resolved = False
        self._book.set_memory(None)

    def _reset_engine(self, exc: BaseException) -> None:
        """An engine raised mid-frame: that frame loses its track facts, the
        engine is reset, and the stream continues (TRACKING-PLAN §5.I).

        Review finding D1: this used to call `TrackBook.forget_keys()`,
        wiping every track in the stream over one target's transient OpenCV
        error. It now bumps the book's key epoch instead (see that method's
        docstring) -- every track this session was NOT touching when the
        exception happened keeps its id and keeps coasting untouched, and
        even the one that hit the exception is only cut off from the
        restarted engine's future keys, not deleted outright.
        """
        LOGGER.warning(
            "tracker engine %r raised (%s); resetting it and reporting this frame untracked",
            self._engine_id,
            exc,
        )
        engine = self._engine
        try:
            if engine is not None:
                engine.reset()
        except Exception:  # noqa: BLE001 - a failed reset costs the engine, not the stream
            self._engine = None
            self._engine_id = ""
        self._book.bump_epoch()
        self._followed = None
        self._tracker_stalled = False
        self._lock.unbind()

    def _release_engine(self) -> None:
        """Drop the current engine instance so the next frame rebuilds one.

        Called on a config change that invalidates the engine (mode/
        engine_id/max_age_frames) while tracking STAYS active -- review
        finding D2, the config-change twin of D1 above: an operator
        switching FOLLOW's engine from `lk` to `ncc` mid-stream keeps every
        track's id instead of losing the whole scene's numbering. `OFF`
        (`_reset_state`, below) is the one caller that wants a real wipe.
        """
        self._engine = None
        self._engine_id = ""
        self._book.bump_epoch()
        self._followed = None
        self._tracker_stalled = False
        self._lock.unbind()

    def _reset_state(self) -> None:
        """Tracking just went OFF (or degraded all the way down to it).

        Unlike `_release_engine`'s config-change case, this is a genuine
        stop -- there is no "still active, still coasting" state for a track
        to survive as, so the book is actually wiped (`forget_keys`), not
        just epoch-namespaced.
        """
        self._release_engine()
        self._book.forget_keys()
        self._last_detector_millis = None
        self._tracker_failed = False
        self._box_invalid = False
        self._tracker_stalled = False
        self._release_motion_compensator()
        self._release_appearance_extractor()
        self._release_memory()


def _box_for(
    detection: Any,
    track: Optional[Track] = None,
    *,
    identity_confidence: float = 0.0,
    dormant_millis: int = 0,
) -> TrackedBox:
    return TrackedBox(
        label=detection.label,
        confidence=detection.confidence,
        box=Box(detection.x, detection.y, detection.width, detection.height),
        track=track,
        identity_confidence=identity_confidence,
        dormant_millis=dormant_millis,
    )


def _from_track(track: Track) -> TrackedBox:
    return TrackedBox(label=track.label, confidence=track.confidence, box=track.box, track=track)
