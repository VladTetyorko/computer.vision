"""`StreamTrackingSession`: composition only. One instance per stream.

`docs/extracts/TRACKING-ORCHESTRATION.md` §3.1 (the per-frame hot path this file runs),
§3.4 (degradation), `docs/plans/done/TRACKING-PLAN.md` §3.1/§3.2.

    frame
      |- params            ALREADY RESOLVED -- no parsing, no dict build
      |- decision          scheduler.decide(now, state): PURE, before any pixel
      |- if run_detector:  detect()   <-- THE ONLY GATE ACQUISITION, and it is
      |                                    the servicer's callable, not ours
      |- ASSOCIATE:        associator.associate(dets, now)
      |  FOLLOW:           re-anchor on best-IoU det, else tracker.update(frame)
      |                                   <-- NEVER TOUCHES THE GATE
      |- book.apply(obs, now)
      `- FrameOutcome{boxes, detector_ran, detector_reason, tracker_millis,
                      engine_id, locked_track_id}

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

import logging
from dataclasses import dataclass
from time import perf_counter
from typing import Any, Callable, Optional, Sequence

from cv_service.tracking import lock as lock_module
from cv_service.tracking import params as params_module
from cv_service.tracking.engines.base import SOURCE_TRACKER, Box, Observation
from cv_service.tracking.lock import LockArbiter
from cv_service.tracking.params import (
    MODE_ASSOCIATE,
    MODE_FOLLOW,
    MODE_OFF,
    TrackingParams,
    TrackingRequest,
)
from cv_service.tracking.predict import predict
from cv_service.tracking.registry import TrackerRegistry
from cv_service.tracking.scheduler import (
    REASON_UNSPECIFIED,
    DutyCycleScheduler,
    SchedulerState,
)
from cv_service.tracking.track import STATE_LOST, Track, TrackBook, observation_for

LOGGER = logging.getLogger("cv_service.tracking.session")

# `detect` returns whatever `InferenceServicer._detect_via_registry` returns:
# `(detections, inference_millis)`, with `detections is None` meaning "no
# model resolved at all -- echo this frame".
DetectFn = Callable[[], "tuple[Optional[list], int]"]
FrameFn = Callable[[], Any]


@dataclass(frozen=True)
class TrackedBox:
    """One box on the response, with its track facts or `None` for untracked.

    `track is None` is the ONE spelling of untracked on this side of the
    wire; the servicer turns it into `track_id == 0` and leaves every other
    track field at its proto3 zero value (TRACKING-ORCHESTRATION §6 rule 2).
    """

    label: str
    confidence: float
    box: Box
    track: Optional[Track] = None


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
        if self._lock.apply(request.lock):
            self._followed = None
        if not self._params.active:
            self._reset_state()

    # -- the per-frame hot path --------------------------------------------

    def process(self, *, now_millis: float, detect: DetectFn, frame: FrameFn) -> FrameOutcome:
        """Run one frame through §3.1's sequence."""
        engine = self._resolve_engine()

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
        started = perf_counter()
        if engine is None or self._params.mode == MODE_OFF:
            boxes = [_box_for(detection) for detection in (detections or [])]
        elif self._params.mode == MODE_ASSOCIATE:
            boxes = self._run_associate(engine, detections or [], now)
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
        )

    # -- mode A: associate --------------------------------------------------

    def _run_associate(self, engine: Any, detections: Sequence[Any], now: float) -> list[TrackedBox]:
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
        else:
            try:
                update = engine.update(frame())
            except Exception as exc:  # noqa: BLE001
                self._reset_engine(exc)
                return None

            if update is None or not update.box.valid:
                # Same predict-don't-freeze fix as above, for the one-off
                # failure that has not yet latched `_tracker_stalled`.
                box = predict(held, now).box
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

        The held/known box fed into the re-anchor test is the PREDICTED one,
        not the stale last-committed one (review finding C1's other half):
        matching this frame's detections against where the target physically
        was several coasted frames ago is exactly the freeze that used to
        make a legitimate re-anchor fail its own IoU test.
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


def _box_for(detection: Any, track: Optional[Track] = None) -> TrackedBox:
    return TrackedBox(
        label=detection.label,
        confidence=detection.confidence,
        box=Box(detection.x, detection.y, detection.width, detection.height),
        track=track,
    )


def _from_track(track: Track) -> TrackedBox:
    return TrackedBox(label=track.label, confidence=track.confidence, box=track.box, track=track)
