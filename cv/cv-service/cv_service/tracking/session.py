"""`StreamTrackingSession`: composition only. One instance per stream.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` wave W0, `docs/extracts/
TRACKING-ORCHESTRATION.md` §3.1/§3.4, `docs/plans/done/TRACKING-PLAN.md`
§3.1/§3.2.

    process(frame)
      |- engines.resolve_*   capability level, engine, dormant gallery
      |- budget.decide       run the detector? who else may run, and why not?
      |- FrameContext        seed the GIVEN facts: frame, pose, lock
      |- orchestrator.run    every contributor in declared order, one ledger
      |                      entry each -- the chain once hand-written here
      `- FrameOutcome        from the aggregator's boxes and StreamState

**Everything that decides anything now lives elsewhere, by name.** Policy is
`scheduler.py` (through `orchestration/budget.py`), identity is `track.py`,
target selection is `lock.py`, pixels are `engines/`, and every per-frame
step is a contributor in `cv_service/orchestration/contributors/`. The
`TrackBook` is written in exactly one place -- `orchestration/aggregator.py`
-- at most once per frame. If this file grows a branch that decides an
outcome, that branch belongs in a contributor.

**On size, honestly.** TRACKING-ORCHESTRATION §2.1 budgeted "~80 lines" for
this class; it reached 2 362 before wave W0. The charter held on POLICY (no
scheduling rule, no lifecycle rule, no geometry ever lived here) but said
nothing about SEQUENCING, and FOLLOW alone has five per-frame outcomes, each
ordering engine + book + lock. W0 does not shrink the sequencing; it gives
each step an owner, a declared contract and a ledger row, so the sequence is
data the orchestrator walks rather than prose this file recites.

**The tracker path never acquires `InferenceGate`.** The gate bounds
concurrent *YOLO* passes across streams; a 0.4 ms tracker update queueing
behind a 343 ms `orion12l` pass would destroy the whole design. This class
holds no reference to a gate -- only the `detect` callable the servicer
passes in, which it hands to `orchestration/detector.py`, the package's one
door, with `tests/orchestration/test_gate_seam.py` grepping to prove it.

Pure stdlib -- the frame arrives as an opaque object from a `frame` callable
the servicer supplies (it does the `cv2` decode lazily and memoizes it), so
nothing here, or anywhere under `orchestration/`, imports `cv2` or `numpy`.

**Reconnect (TRACKING-V2-PLAN wave C5b, review finding B5).** This class does
not assume it lives for exactly one `DetectStream` call -- `cv_service.
tracking.sessions.SessionRegistry` pools instances by `stream_id` across a
disconnect, and `reset_for_reconnect()` is the ONE thing that happens at that
boundary. See its own docstring for why the split is exactly there.

**`detection_lag_millis` (TRACKING-V3-PLAN wave V6, §4.5)** is the caller's
measurement of how stale this frame's detection is by the time it lands (pull
mode's `capture_skew_millis`; `0` for every push-mode frame and this stream's
first). The mechanism and its no-op contract are in
`cv_service/orchestration/corrections.py`.
"""

from __future__ import annotations

import logging
from typing import Any, Callable, Optional

from cv_service.orchestration.aggregator import Aggregator
from cv_service.orchestration.budget import BudgetPolicy, BudgetState, scheduler_state
from cv_service.orchestration.contract import FrameContext
from cv_service.orchestration.contributors import roster
from cv_service.orchestration.contributors import signature as roster_signature
from cv_service.orchestration.detector import LocalDetectorClient
from cv_service.orchestration.engines import EngineSet
from cv_service.orchestration.keys import Key
from cv_service.orchestration.ledger import FrameLedger, LedgerRing
from cv_service.orchestration.orchestrator import Orchestrator
from cv_service.orchestration.state import StreamState
from cv_service.tracking import params as params_module
from cv_service.tracking.engines.base import Box, CameraPose
from cv_service.tracking.lock import LockArbiter
# Re-exported, not re-defined (`tracking/outcome.py` owns them): every
# `from cv_service.tracking.session import FrameOutcome, TrackedBox` in
# `tools/trackeval/` and the servicer keeps working, so the split costs no
# call site and the golden fixtures stay comparable across it.
from cv_service.tracking.outcome import (
    FrameOutcome,
    TrackedBox,
    box_for,
    box_for as _box_for,
    from_track as _from_track,
)
from cv_service.tracking.params import TrackingParams, TrackingRequest
from cv_service.tracking.registry import TrackerRegistry
from cv_service.tracking.track import Track, TrackBook

LOGGER = logging.getLogger("cv_service.tracking.session")

# `detect(roi=None)` returns whatever `InferenceServicer._run_detector`
# returns: `(detections, inference_millis)`, with `detections is None`
# meaning "no model resolved at all -- echo this frame". `roi`
# (TRACKING-V2-PLAN wave C5c, review §4.6) asks for a SECOND, cropped pass
# around a candidate's predicted box instead of the full frame -- optional,
# so every pre-C5c caller (every harness/test `detect()` written before this
# wave) keeps working unchanged. `session.py` is the only caller that ever
# passes one, and only from `_roi_rescue`.
DetectFn = Callable[[Optional[Box]], "tuple[Optional[list], int]"]
FrameFn = Callable[[], Any]



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
        self._budget = BudgetPolicy(self._params)
        self._book = TrackBook(self._params)
        self._lock = LockArbiter()
        # What survives a frame and has more than one reader (the held
        # target, its extras, the scheduler triggers, this frame's lag).
        self._state = StreamState()
        # Every lazily-resolved collaborator, plus the FOLLOW -> ASSOCIATE ->
        # OFF ladder. `on_params` is how a degradation gets back here: the
        # engine set rewrites `TrackingParams`, this class re-tunes the budget
        # and the book from it, and neither owns both.
        self._engines = EngineSet(
            settings=settings,
            registry_provider=registry_provider,
            params=self._params,
            book=self._book,
            lock=self._lock,
            state=self._state,
            on_params=self._adopt_params,
        )
        # The one door to the inference gate (`orchestration/detector.py`),
        # rebound per frame to the servicer's own `detect` callable.
        self._client = LocalDetectorClient()
        # The fold, built once and reused across roster rebuilds: it holds
        # this frame's response boxes, which `process()` reads back.
        self._aggregator = Aggregator(
            self._book,
            raw_boxes=lambda detections: [box_for(detection) for detection in detections],
        )
        self._roster_signature: "Optional[tuple[str, ...]]" = None
        self._built: Optional[Orchestrator] = None
        #: Per-frame evidence, bounded by `CV_LEDGER_RING` -- the `Inspect`
        #: surface, and the only thing this class keeps from a finished frame.
        self._ledgers = LedgerRing(settings.ledger_ring)
        self._sequence = 0
        #: Stamped by `SessionRegistry.acquire`; `""` for an un-keyed stream
        #: (which never pools either). Names this session in every ledger.
        self.stream_id: str = ""
        # The last wire `TrackingConfig` applied, held opaquely and compared
        # by equality (a protobuf `==`, no allocation) so the restated-every-
        # frame config costs one comparison and `resolve()` runs only on a
        # real change. The servicer owns the comparison: it is a wire type.
        self.applied_wire_config: Any = None

    def _adopt_params(self, params: TrackingParams) -> None:
        """A degradation rewrote the resolved params: re-tune what reads them.

        The budget and the book each hold their own reference; the engine set
        already holds the new value, which is why it is not told again.
        """
        self._params = params
        self._budget.retune(params)
        self._book.retune(params)

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
        self._budget.retune(self._params)
        self._book.retune(self._params)
        # WHICH collaborator a changed field invalidates is `EngineSet`'s
        # own rule -- it owns every one of those releases, so this class
        # would only be a second place to get them wrong.
        self._engines.reconfigure(previous, self._params)
        if self._lock.apply(request.lock):
            self._state.followed = None
        if not self._params.active:
            self._engines.reset_state()

    # -- the per-frame hot path --------------------------------------------

    def process(
        self,
        *,
        now_millis: float,
        detect: DetectFn,
        frame: FrameFn,
        pose: CameraPose = CameraPose(),
        detection_lag_millis: int = 0,
    ) -> FrameOutcome:
        """Run one frame: resolve, budget, seed, orchestrate, fold.

        Every branch that used to live here is a contributor now
        (`cv_service/orchestration/`), so what remains is composition: this
        method resolves the lazily-built collaborators, asks the budget who
        may run, puts the frame's GIVEN facts on the blackboard, and turns
        what the aggregator produced into a `FrameOutcome`.

        `detection_lag_millis` reaches `orchestration/corrections.py` through
        `StreamState.lag_seconds`; see the module docstring above.
        """
        # TRACKING-V3-PLAN wave V1: resolved FIRST, unconditionally -- every
        # engine roster call reads the served level, so it has to be current
        # before `resolve_engine()` asks the registry for anything. Cached
        # until `apply_config` sees the requested ceiling change.
        self._engines.resolve_capability_level()
        self._engines.resolve_engine()
        # Unconditional, not only for `cost` (TRACKING-V2-PLAN wave C4):
        # `TrackBook._retire` must be able to remember a LOST track whatever
        # the mode, so the book's reference to the gallery has to be current
        # before anything below could expire one.
        self._engines.resolve_memory()
        self._client.bind(detect)

        # Decided BEFORE the per-frame reset: `tracker_failed`/`box_invalid`
        # are triggers the PREVIOUS frame raised, and clearing them first
        # would lose exactly the signal the scheduler exists to react to.
        budget = self._budget.decide(
            now_millis,
            BudgetState(
                scheduler=scheduler_state(
                    params=self._params,
                    has_lock=self._state.followed is not None,
                    last_detector_millis=self._state.last_detector_millis,
                    tracker_failed=self._state.tracker_failed,
                    box_invalid=self._state.box_invalid,
                    coasting_frames=(
                        self._state.followed.misses if self._state.followed else 0
                    ),
                ),
                engine_resolved=self._engines.engine is not None,
                engine_id=self._engines.engine_id,
            ),
        )
        self._state.reset_frame(lag_millis=detection_lag_millis)
        # TRACKING-V3-PLAN wave V3 -- reset before the run, which may or may
        # not fold (see `TrackBook.reset_reupdate_stats`'s own docstring for
        # why the reset cannot live inside `apply()` itself).
        self._book.reset_reupdate_stats()

        ctx = FrameContext(
            stream_id=self.stream_id,
            sequence=self._sequence,
            now_millis=now_millis,
            level_served=self._engines.capability_level_served,
            params=self._params,
            lag_millis=self._state.lag_millis,
        )
        # The frame's GIVEN facts (`keys.SEEDED`): not anybody's contribution,
        # so no contributor may declare them as a write.
        ctx.seed(Key.FRAME, frame)
        ctx.seed(Key.POSE, pose)
        ctx.seed(Key.LOCK, self._lock)

        ledger = FrameLedger(
            stream_id=self.stream_id,
            sequence=self._sequence,
            captured_at_millis=now_millis,
            level_served=self._engines.capability_level_served,
            detector_reason=budget.detector_reason,
            eligible=tuple(sorted(budget.eligible)),
        )
        self._sequence += 1

        tracker_millis = self._orchestrator().run(ctx, budget, ledger)
        ledger.gate_wait_ms = self._client.wait_ms
        ledger.total_ms = tracker_millis + self._client.wait_ms
        self._ledgers.append(ledger)

        if ledger.halted:
            # `detect.full` is the only contributor that halts, and only for
            # "no model resolved at all" -- the servicer's echo degradation,
            # byte-identical to what `process()` returned inline before.
            return FrameOutcome(boxes=None)

        # TRACKING-V2-PLAN wave C5c: an ROI pass is a real, additional
        # detector cost -- summed into the SAME `inference_millis` total
        # (`StreamState.inference_millis`), the "one number, whatever ran"
        # convention `detect_composite` already uses.
        return FrameOutcome(
            boxes=self._aggregator.boxes,
            inference_millis=self._state.inference_millis,
            detector_ran=budget.run_detector,
            detector_reason=budget.detector_reason,
            tracker_millis=int(round(tracker_millis)),
            engine_id=self._engines.engine_id,
            locked_track_id=self._lock.bound_track_id,
            motion_millis=self._state.motion_millis,
            motion_engine_id=self._state.motion_engine_id,
            detector_roi=self._state.roi_ran,
            capability_level_served=self._engines.capability_level_served,
            capability_level_reason=self._engines.capability_level_reason,
            # TRACKING-V3-PLAN wave V3 -- read AFTER the run, which is where
            # `TrackBook.apply()` (the only thing that ever advances these) is
            # called, at most once, by the aggregator.
            reupdate_millis=self._book.last_reupdate_millis,
            reupdated_tracks=self._book.last_reupdated_tracks,
            # TRACKING-V3-PLAN wave V6 -- echoed straight from this call's own
            # `detection_lag_millis` argument, already clamped.
            detection_lag_millis=self._state.lag_millis,
        )

    # -- the roster (CV-ORCHESTRATION §4.1) --------------------------------

    def _orchestrator(self) -> Orchestrator:
        """This configuration's ordered contributors, rebuilt only when the
        configuration that decides membership actually changes.

        Not built at `apply_config` as the plan assumed: engines resolve
        LAZILY, so the frame that first builds a `cost` associator is the
        frame the roster must change on, and that is a strict superset of the
        config-change moments. Recorded in `cv/cv-service/MODULE.md`.
        """
        current = roster_signature(self._engines)
        if self._roster_signature != current or self._built is None:
            self._roster_signature = current
            self._built = Orchestrator(
                roster(
                    engines=self._engines,
                    book=self._book,
                    lock=self._lock,
                    state=self._state,
                    client=self._client,
                    registry_provider=self._registry_provider,
                    aggregator=self._aggregator,
                )
            )
        return self._built

    @property
    def ledgers(self) -> LedgerRing:
        """The last `CV_LEDGER_RING` frames' evidence -- `Inspect`'s source,
        and the only reason this class holds anything a frame produced."""
        return self._ledgers

    def declarations(self) -> "list[tuple[str, tuple[str, ...], tuple[str, ...]]]":
        """`(id, reads, writes)` per registered contributor, in run order."""
        return self._orchestrator().declarations()

    # -- reconnect (TRACKING-V2-PLAN wave C5b, review finding B5) -----------

    def reset_for_reconnect(self) -> None:
        """A stream just disconnected: drop every per-frame ENGINE, keep
        everything else.

        Called by `cv_service.tracking.sessions.SessionRegistry.release()`
        the moment a `DetectStream` call ends, so a session sitting inside
        its grace window is already clean and a reconnect that DOES arrive
        resumes into a ready-to-rebuild state rather than paying the reset
        cost on the resumed stream's first frame.

        **What this resets, and why.** `lk`/`flow` each hold a previous
        DECODED FRAME; after a reconnect the next frame to arrive is not
        adjacent to whatever they last saw, so feeding it in would compute
        optical flow (or a "camera motion" transform) across a discontinuity
        -- a large, bogus estimate applied to every live track at exactly the
        moment the stream is most fragile. The three releases below force
        `EngineSet` to build fresh instances on the resumed stream's next
        active frame, the SAME lazy build-once path a brand-new session takes.

        **What this does NOT reset, and why that is the whole point.**
        `_book` (ids, lifecycle, ages), the dormant gallery and `_lock`'s
        TARGET (what the operator asked FOLLOW to hold -- `release_engine`'s
        own `lock.unbind()` clears only the BOUND track id) are left alone.
        Resuming the SAME `TrackBook`/`ObjectMemory`/`LockArbiter` OBJECTS is
        what lets wave C4's gallery -- built to survive a NINE-SECOND
        occlusion -- survive a two-second reconnect too. `_params`/
        `applied_wire_config` are untouched as well: the wire restates
        `TrackingConfig` on the very next frame regardless.
        """
        # The epoch stops an ENGINE's restarted key numbering from landing on
        # tracks that are still live -- `bytetrack` mints its own keys and
        # restarts them at 1. On the `cost` path the BOOK mints the keys, so
        # bumping would orphan every track in the scene instead: measured
        # before this guard, ids 1,2 became 3,4 across a reconnect -- pooling
        # working perfectly and buying the operator nothing.
        self._engines.release_engine(bump_epoch=not self._engines.book_owns_keys())
        self._engines.release_motion_compensator()
        self._engines.release_appearance_extractor()

