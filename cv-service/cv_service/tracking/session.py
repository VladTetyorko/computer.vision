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

**Reconnect (TRACKING-V2-PLAN wave C5b, review finding B5).** This class no
longer assumes it lives for exactly one `DetectStream` call -- `cv_service.
tracking.sessions.SessionRegistry` now pools instances by `stream_id` across
a disconnect, and `reset_for_reconnect()` (below) is the ONE thing that
happens at that boundary: every per-frame ENGINE is dropped (a fresh build
is forced on the resumed stream's next active frame) while the book, the
dormant gallery and the lock are left completely untouched. See that
method's own docstring for why the split is exactly there and not somewhere
looser.

**Multi-target FOLLOW (TRACKING-V2-PLAN wave C5b, review finding C6).**
`TrackingParams.follow_top_k` (default `1`, today's exact behaviour) raises
the ceiling on how many boxes a tracker-only frame emits: the locked target,
ALWAYS first and never displaced, plus up to `follow_top_k - 1` other,
non-locked "extra" targets, each with its OWN `SingleObjectTracker`
instance -- the registry's existing "a new engine per call, never shared"
contract, extended to mean "never shared between TARGETS" either. Extras are
opportunistic situational awareness, not a second lock: one that a verify
pass cannot re-anchor, or whose engine fails between verify passes, is
simply dropped (its engine released) rather than coasted/predicted/LOST --
see `_extras_verify_observations`'s docstring for the full policy, and
`_ExtraFollow` for the per-slot state this needs.

**Late-detection back-correction (TRACKING-V3-PLAN wave V6, §4.5).**
`process()` takes an optional `detection_lag_millis` -- the caller's own
measurement of how stale this frame's detection is by the time it lands
(pull mode's `capture_skew_millis` today; `0` when the caller has no way to
know, which is every push-mode frame and this stream's first). When it is
positive, `_late_corrected_box` (used by both `_run_cost_associate`'s
matched candidates and `_follow_verify`'s re-anchor) binds the just-arrived
box to its OWN capture instant and re-propagates it forward via `reupdate.
late_correction` -- mechanically the SAME ORU interpolant §4.2 already
ships, run per-detection instead of only after a miss. See `reupdate.py`'s
own module docstring for why this reuses that function rather than a second
interpolator, and `_late_corrected_box`'s for the full no-op contract.

**2026-08-14 repair.** `_late_corrected_box` now returns a `(box, captured_
at)` pair, not just `box` -- the SAME `box` it always returned, plus the
instant ITS content is actually true, for `TrackBook.apply()`'s own
`captured_at` parameter to carry down into `ObservationRing.record`
(`history.py`). Booking every one of this method's boxes under `now`
regardless -- including the RAW, never-corrected ones `late_correction`
declined to touch -- was the defect: a mistimed ring entry poisons every
later bracket built against it, which is why a PERSISTENT lag (this
mechanism's own steady-state case, not an occasional gap) used to diverge
rather than merely stay imprecise. See `history.py`'s `TimedObservation`
docstring for the full account.
"""

from __future__ import annotations

import dataclasses
import logging
from dataclasses import dataclass
from time import perf_counter
from typing import Any, Callable, Optional, Sequence

from cv_service.tracking import levels as levels_module
from cv_service.tracking import lock as lock_module
from cv_service.tracking import params as params_module
from cv_service.tracking import reupdate as reupdate_module
from cv_service.tracking.assign import Assignment, Candidate, CostAssociator, Target
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
class _ExtraFollow:
    """One auto-selected, non-locked FOLLOW target (TRACKING-V2-PLAN wave
    C5b, review finding C6) -- situational awareness only, never the
    operator's lock.

    `track` is the SAME mutable `Track` object `TrackBook` holds for this
    slot's booking key -- reading `.box`/`.label`/`.confidence` off it
    always reflects this frame's warp/observation with no second copy to
    keep in sync, the same reason `StreamTrackingSession._followed` holds a
    `Track` reference rather than caching its own box. Frozen: a change of
    engine, key or track is always a NEW slot (a re-anchor or a promotion),
    never a mutation of an existing one -- `session.py`'s own tracks
    themselves are the only mutable state here.
    """

    key: str
    engine: Any
    track: Track


@dataclass(frozen=True)
class _ExtraCandidate:
    """One extra observation offered to a batched `TrackBook.apply()` call,
    before the book has told us which `Track` it became.

    Exists only inside `_extras_verify_observations` and its caller: `apply()`
    ages EVERY live track once per call (TRACKING-ORCHESTRATION), so the
    locked target's own observation and every extra's must be booked in ONE
    call, not one apply() per extra -- this is the record that lets the
    caller zip `apply()`'s returned tracks back onto (`key`, `engine`) pairs
    once that one call returns.
    """

    key: str
    engine: Any
    det_index: int


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
    # TRACKING-V2-PLAN wave C5c -- `DetectionResponse.detector_roi` (wire
    # field 13). `True` exactly when `_roi_rescue` actually invoked `detect`
    # with a region THIS frame -- always in ADDITION to, never instead of,
    # the frame's own full-frame/duty-cycle detector pass (`detector_ran`
    # above): ASSOCIATE always runs full-frame every received frame, so a
    # `True` here means TWO detector passes served this one response, not
    # one. `False` on every frame the rescue never fired, including every
    # frame `roi_enabled` is off on (the deployment default) and every
    # frame it fired on but found nothing worth merging.
    detector_roi: bool = False
    # TRACKING-V3-PLAN wave V1 -- the capability ladder (§5). `served` is
    # always <= the resolved `TrackingParams.capability_level` (decision
    # E12's ceiling arithmetic, `cv_service.tracking.levels.resolve`);
    # `reason` is `""` exactly when nothing was actually capped, the same
    # "empty string = no degradation" convention `motion_engine_id`'s own
    # docstring establishes for that field.
    capability_level_served: int = 0
    capability_level_reason: str = ""
    # TRACKING-V3-PLAN wave V3 -- ORU (§4.2), `DetectionResponse.reupdate_
    # millis`/`.reupdated_tracks` (wire fields 22/23). Both `0` on every
    # frame that never calls `TrackBook.apply()` at all (OFF, or no engine
    # constructible) -- see `TrackBook.reset_reupdate_stats`'s own docstring
    # for why that is guaranteed rather than merely usual.
    reupdate_millis: int = 0
    reupdated_tracks: int = 0
    # TRACKING-V3-PLAN wave V6 -- `DetectionResponse.detection_lag_millis`
    # (wire field 24), §4.5. Echoed straight from the `detection_lag_millis`
    # `process()` was called with (clamped, never negative) -- independent
    # of whether back-correction actually ran this frame: this is what
    # makes the bias VISIBLE, never merely assumed corrected. `0` on a
    # stream whose caller measures no lag (every push-mode frame today).
    detection_lag_millis: int = 0


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
        # Multi-target FOLLOW (TRACKING-V2-PLAN wave C5b) -- up to
        # `follow_top_k - 1` non-locked targets, each carrying its own
        # engine instance. `_extra_key_seq` mints a fresh, permanently-
        # unique book key per PROMOTED extra (mirrors `_run_cost_associate`'s
        # own `object()`-sentinel reasoning for a brand-new candidate, just
        # spelled as a readable string since this key also has to survive
        # into log lines).
        self._extras: "list[_ExtraFollow]" = []
        self._extra_key_seq = 0
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
        # Capability level (TRACKING-V3-PLAN wave V1) -- resolved lazily,
        # same build-once-until-config-changes shape as the motion/
        # appearance engines above, but resolved BEFORE any of them: every
        # engine roster call below is filtered by the served level, so this
        # has to be current before `_resolve_engine`/`_resolve_motion_
        # compensator`/`_resolve_appearance_extractor` run (see `process()`).
        self._capability_resolved = False
        self._capability_level_served = 0
        self._capability_level_reason = ""
        self._degraded_capability_reasons: "set[str]" = set()
        # ROI re-detection bookkeeping (TRACKING-V2-PLAN wave C5c) -- reset
        # at the top of every `process()` call, read once at the bottom to
        # build this frame's `FrameOutcome`. No build-once-lazily state to
        # hold here (unlike the engines above): `_roi_rescue` needs nothing
        # but `self._params` and the book, both already current every frame.
        self._roi_ran = False
        self._roi_millis = 0
        # TRACKING-V3-PLAN wave V6 -- same "reset every frame, read (or, for
        # `_frame_lag_seconds`, consult) within the same call" shape as the
        # ROI fields directly above. Initialized here only so a call into
        # `_late_corrected_box` before this stream's first `process()` (not
        # a real path today) reads `0.0`, never an `AttributeError`.
        self._frame_detection_lag_millis = 0
        self._frame_lag_seconds = 0.0
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
        if self._params.capability_level != previous.capability_level:
            # TRACKING-V3-PLAN wave V1: the served level gates every engine
            # roster below (`_create_engine`/`_resolve_motion_compensator`/
            # `_resolve_appearance_extractor`), so a changed REQUESTED
            # ceiling invalidates whatever was already built through the OLD
            # one -- exactly like a mode/engine_id change (`_release_engine`
            # below), just triggered by a different field.
            self._release_capability()
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
        detection_lag_millis: int = 0,
    ) -> FrameOutcome:
        """Run one frame through §3.1's sequence.

        `detection_lag_millis` (TRACKING-V3-PLAN wave V6, §4.5) is the
        CALLER's own measurement of how stale this frame's detection already
        is by the time it lands -- `0` (the default) when there is nothing
        to measure, which is every push-mode frame today and this stream's
        very first. It drives `_late_corrected_box`, read by both
        `_run_cost_associate` and `_follow_verify`; see the module docstring
        for the mechanism.
        """
        # TRACKING-V3-PLAN wave V1: resolved FIRST, unconditionally -- every
        # engine roster call below reads `self._capability_level_served`, so
        # it has to be current before `_resolve_engine()` (next line) ever
        # asks the registry for anything. Cheap after the first call: cached
        # until `apply_config` sees the wire's requested ceiling change.
        self._resolve_capability_level()
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
        # TRACKING-V2-PLAN wave C5c -- reset every frame, set (at most) by
        # `_roi_rescue` below, read once at the bottom to build this frame's
        # `FrameOutcome`/`inference_millis`.
        self._roi_ran = False
        self._roi_millis = 0
        # TRACKING-V3-PLAN wave V3 -- reset every frame, BEFORE the mode
        # dispatch below (which may or may not call `self._book.apply()` --
        # see `TrackBook.reset_reupdate_stats`'s own docstring for why the
        # reset cannot live inside `apply()` itself).
        self._book.reset_reupdate_stats()
        # TRACKING-V3-PLAN wave V6 -- this frame's measured capture ->
        # association lag, read once and used by BOTH `_late_corrected_box`
        # below and the `FrameOutcome` this call returns. Clamped here, not
        # trusted from the caller: a negative reading can only be clock
        # jitter (`CaptureClock.capture_time`'s own skew estimate can dip
        # below zero -- `pull/clock.py`), never a genuine "detection from
        # the future" to correct toward.
        self._frame_detection_lag_millis = max(0, int(detection_lag_millis))
        self._frame_lag_seconds = self._frame_detection_lag_millis / 1000.0

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
            boxes = self._run_associate(engine, detections or [], now, frame, detect)
        elif decision.run_detector:
            boxes = self._follow_verify(engine, detections or [], frame, now)
        else:
            boxes = self._follow_predict(engine, frame, now)
        tracker_millis = int(round((perf_counter() - started) * 1000.0))

        # TRACKING-V2-PLAN wave C5c: an ROI pass is a real, additional
        # detector cost -- summed into the SAME `inference_millis` total the
        # full-frame pass reports, the same "one number, whatever ran"
        # convention `detect_composite` already uses for multi-model
        # composite mode (`cv_service/inference/registry.py`).
        return FrameOutcome(
            boxes=boxes,
            inference_millis=inference_millis + self._roi_millis,
            detector_ran=decision.run_detector,
            detector_reason=decision.reason,
            tracker_millis=tracker_millis,
            engine_id=self._engine_id,
            locked_track_id=self._lock.bound_track_id,
            motion_millis=motion_millis,
            motion_engine_id=motion_engine_id,
            detector_roi=self._roi_ran,
            capability_level_served=self._capability_level_served,
            capability_level_reason=self._capability_level_reason,
            # TRACKING-V3-PLAN wave V3 -- read AFTER the mode dispatch above,
            # which is where `TrackBook.apply()` (the only thing that ever
            # advances these) is called, at most once, per `apply()`'s own
            # "ages every live track once per call" contract.
            reupdate_millis=self._book.last_reupdate_millis,
            reupdated_tracks=self._book.last_reupdated_tracks,
            # TRACKING-V3-PLAN wave V6 -- echoed straight from this call's
            # own `detection_lag_millis` argument, already clamped above.
            detection_lag_millis=self._frame_detection_lag_millis,
        )

    # -- late-detection back-correction (TRACKING-V3-PLAN wave V6, §4.5) ----

    def _late_corrected_box(self, track: Track, box: Box, now: float) -> "tuple[Box, float]":
        """`(box, now)`, or `reupdate.late_correction`'s re-propagated answer
        paired with `now` too, when this frame carries a measured capture
        lag worth correcting for -- `(box, now - self._frame_lag_seconds)`
        for the one case in between: a lag WAS measured but there was
        nothing honest to correct `box` with.

        Mechanically ORU (`reupdate.py`) applied to EVERY confirmed
        detection, not only after a miss: an offboard detector (§5.2, "L1
        RELAY") describes the frame it ran on, not the frame that is current
        by the time its answer lands, so booking `box` against `now` as-is
        is the same systematic lag bias `reupdate()` already deletes after
        an occlusion -- just arriving every frame instead of only some.

        **The second return value, added in the 2026-08-14 repair.** Every
        caller of this method immediately hands `box` to `TrackBook.apply()`
        for booking into `track.history` (`ObservationRing`, `history.py`)
        -- and that ring's own contract (`ObservationRing.record`'s own
        docstring) is that its timestamp must be the instant `box`'s content
        was actually true, never merely `now`. This method is the ONE place
        in the session that knows the answer for each of the three cases a
        caller cannot tell apart just by looking at `box`:

          * Correction disabled, or nothing measured this frame -- `box` is
            whatever the caller passed in, already true at `now` as far as
            this stream can tell (P5: no lag known, no correction to make).
          * `late_correction` SUCCEEDED -- `corrected` is deliberately
            re-propagated to represent position AT `now` (`late_correction`'s
            own docstring: "projects `box` forward by the SAME `lag_
            seconds`"), so `now` is correct for it too.
          * `late_correction` returned `None` -- `box` is still the RAW,
            never-corrected detection, and that content was true at its own
            capture instant, `now - self._frame_lag_seconds`, not `now`.
            Booking it under `now` anyway is the exact defect `history.py`'s
            `TimedObservation` docstring documents: this is precisely the
            case that used to poison the ring during the dead zone before
            any bracket existed to correct against.

        A genuine no-op (returns `(box, now)`, P5) whenever there is nothing
        to correct with: the deployment knob is off, this frame measured no
        lag (`_frame_lag_seconds <= 0.0` -- every push-mode frame today, and
        pull mode's own first frame before a skew estimate exists) -- so a
        stream that never measures a lag, or has this disabled, is untouched
        by construction on BOTH return values, which is P7's reversibility
        proof for this mechanism without a second code path.
        """
        if not self._params.detection_lag_correction_enabled:
            return box, now
        if self._frame_lag_seconds <= 0.0:
            return box, now
        corrected = reupdate_module.late_correction(
            track,
            track.history,
            box,
            now,
            self._frame_lag_seconds,
            max_gap_millis=self._params.reupdate_max_gap_millis,
            max_velocity_per_second=self._params.reupdate_max_velocity_per_second,
            # 2026-08-15 density gate -- `self._book.tracks` is reachable
            # here with no threading at all (unlike detections/frame, which
            # this method has no way to reach -- see `reupdate.py`'s own
            # module docstring for why that signal is a proxy, not the
            # measured quantity).
            max_track_count=self._params.reupdate_max_track_count,
            live_track_count=len(self._book.tracks),
        )
        if corrected is None:
            return box, now - self._frame_lag_seconds
        return corrected, now

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
        DECODED FRAME; after a reconnect the very next frame that arrives is
        not adjacent to whatever they last saw, so feeding it in would
        compute optical flow (or a "camera motion" transform) across a
        discontinuity -- a large, bogus estimate applied to every live track
        at exactly the moment the stream is most fragile. `_release_engine`/
        `_release_motion_compensator`/`_release_appearance_extractor` force
        `_resolve_engine`/`_resolve_motion_compensator`/`_resolve_appearance_
        extractor` to build fresh instances on the resumed stream's next
        active frame -- the SAME lazy build-once path a brand-new session's
        very first frame already takes, so a resumed stream's engines start
        exactly as cleanly as a new stream's would.

        **What this does NOT reset, and why that is the whole point of this
        wave.** `_book` (ids, lifecycle, ages), `_memory` (the dormant
        gallery) and `_lock`'s TARGET (what the operator asked FOLLOW to
        hold -- `_release_engine`'s own `lock.unbind()` clears only the
        BOUND track id, forcing a fresh re-anchor, never the target itself)
        are all left completely alone. Resuming the SAME `TrackBook`/
        `ObjectMemory`/`LockArbiter` OBJECTS (not rebuilding equivalent ones)
        is what makes wave C4's dormant gallery -- built to survive a
        NINE-SECOND occlusion -- actually able to survive a TWO-SECOND
        reconnect too, which is this wave's whole reason to exist. Also
        untouched: `_params`/`applied_wire_config` -- the wire restates
        `TrackingConfig` on the very next frame regardless
        (`_sync_tracking`), so there is nothing to gain by forgetting it a
        frame early, and `self._params.active` still governs whether the
        NEXT frame does any tracking work at all, exactly as it always has.
        """
        # The epoch exists to stop an ENGINE's restarted key numbering from
        # landing on tracks that are still live -- `bytetrack` mints its own
        # keys and restarts them at 1. On the `cost` path the BOOK mints the
        # keys, so there is nothing to protect against, and bumping would
        # orphan every track in the scene: the resumed stream's first pass
        # re-books each object under a fresh key and hands it a new id.
        #
        # Which is precisely what this wave exists to prevent. Measured
        # before this guard: ids 1,2 became 3,4 across a reconnect, so the
        # session was pooled and resumed and the operator still lost every
        # number -- the pooling working perfectly and buying nothing.
        self._release_engine(bump_epoch=not self._book_owns_keys())
        self._release_motion_compensator()
        self._release_appearance_extractor()

    # -- mode A: associate --------------------------------------------------

    def _run_associate(
        self,
        engine: Any,
        detections: Sequence[Any],
        now: float,
        frame: FrameFn,
        detect: DetectFn,
    ) -> list[TrackedBox]:
        if self._engine_id == CostAssociator.engine_id:
            return self._run_cost_associate(engine, detections, now, frame, detect)

        # TRACKING-V2-PLAN wave C5c: ROI re-detection is NOT wired into this
        # branch. `cost`'s candidates ARE `TrackBook`'s own tracks, already
        # known confirmed/unmatched before `TrackBook.apply()` runs -- the
        # seam `_roi_rescue` needs. `bytetrack`'s association state lives
        # inside a third-party engine (its own Kalman filters) with no such
        # seam -- the SAME reasoning wave C2's module docstring already gives
        # for why `bytetrack` never gets ego-motion compensation either. See
        # MODULE.md for this scope decision stated in full.
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
        self,
        engine: CostAssociator,
        detections: Sequence[Any],
        now: float,
        frame: FrameFn,
        detect: DetectFn,
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

        TRACKING-V2-PLAN wave C5c: it is ALSO the one associator for which
        `_roi_rescue` (below) has a seam -- `tracks_list`/`candidates` are
        already known confirmed/unmatched, by this SAME `assignment`, before
        `TrackBook.apply()` runs, so a rescue observation can be folded into
        the ONE `apply()` call this frame is only ever allowed to make
        (`TrackBook.apply()`'s own docstring, and `session.py`'s module
        docstring on the multi-target FOLLOW trap this mirrors).
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
        # Captured once: `candidates[i]` and `tracks_list[i]` are the SAME
        # track, by construction -- `_roi_rescue` needs the raw `Track` (for
        # `.misses`/`.track_id`, its own priority rule) alongside the
        # `Candidate` `engine.assign` already speaks, and reading
        # `self._book.tracks` a second time would only be correct by
        # coincidence (nothing mutates the book between here and there today,
        # but nothing should have to promise that to stay correct).
        tracks_list = self._book.tracks
        candidates = [
            Candidate(
                key=track.key,
                box=predict(track, now).box,
                label=track.label,
                descriptor=track.descriptor,
                confirmed=track.state != STATE_TENTATIVE,
            )
            for track in tracks_list
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
        # 2026-08-14 repair -- `TrackBook.apply()`'s own `captured_at` map
        # (its docstring), keyed the SAME way `recoveries` already is
        # (`observation.key`). Populated for every matched candidate below,
        # never for a ROI rescue or a brand-new birth (neither is a `_late_
        # corrected_box` candidate -- see `track.py`'s `_born`/`_adopt` for
        # why that is a stated scope boundary, not an oversight).
        captured_at: "dict[object, float]" = {}
        for candidate_index, target_index in assignment.matches:
            target = targets[target_index]
            # TRACKING-V3-PLAN wave V6 -- `tracks_list[candidate_index]` is
            # the SAME `Track` `candidates[candidate_index]` was built from
            # (this method's own comment on that list, above), so it is the
            # one with the real `.history`/`.history_transform` a late
            # correction needs to bracket against. A genuine no-op (returns
            # `target.box` unchanged) whenever this frame measured no lag or
            # the knob is off -- see `_late_corrected_box`'s own docstring.
            candidate_key = candidates[candidate_index].key
            corrected_box, box_captured_at = self._late_corrected_box(
                tracks_list[candidate_index], target.box, now
            )
            observations.append(
                Observation(
                    key=candidate_key,
                    box=corrected_box,
                    label=target.label,
                    confidence=target.confidence,
                    det_index=target.det_index,
                )
            )
            observation_descriptors.append(target.descriptor)
            captured_at[candidate_key] = box_captured_at

        # TRACKING-V2-PLAN wave C5c: at most one bounded, gated second look
        # at the highest-priority CONFIRMED candidate the match above just
        # left unmatched -- see `_roi_rescue`'s own docstring for the full
        # policy. Folded into the SAME `observations`/`observation_
        # descriptors` lists `TrackBook.apply()` books below, never a second
        # `apply()` call.
        rescue = self._roi_rescue(engine, tracks_list, candidates, assignment, now, frame, detect, extractor)
        rescue_index: Optional[int] = None
        if rescue is not None:
            observation, descriptor, _rescued_detection = rescue
            rescue_index = len(observations)
            observations.append(observation)
            observation_descriptors.append(descriptor)

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

        tracks = self._book.apply(
            observations, now, detector_ran=True, recoveries=recoveries, captured_at=captured_at
        )
        for track, descriptor in zip(tracks, observation_descriptors):
            observe_descriptor(track, descriptor)

        by_index = {
            observation.det_index: track
            for observation, track in zip(observations, tracks)
            if observation.det_index >= 0
        }
        boxes_out = [
            _box_for(
                detection,
                by_index.get(index),
                identity_confidence=(
                    recovery_by_index[index].confidence if index in recovery_by_index else 0.0
                ),
                dormant_millis=(
                    recovery_by_index[index].dormant_millis if index in recovery_by_index else 0
                ),
                # TRACKING-V3-PLAN wave V6 -- `track.box` is exactly
                # `observation.box` post-`_observe` (corrected by `_late_
                # corrected_box` above, or the raw detection unchanged when
                # nothing was), so passing it here is what makes a
                # correction reach the WIRE, not just this track's own
                # internal state. `None` (falls back to the raw detection,
                # byte-identical either way) when `index` matched nothing.
                box=(by_index[index].box if index in by_index else None),
            )
            for index, detection in enumerate(detections)
        ]
        # TRACKING-V2-PLAN wave C5c: the rescued detection has no FULL-FRAME
        # `det_index` (`Observation(det_index=-1)`, `_roi_rescue`'s own
        # docstring) -- it never entered `by_index` and never will, so it is
        # appended directly rather than folded into the comprehension above.
        # `rescue_index` is exactly where it landed in `observations`, so
        # `tracks[rescue_index]` is unambiguously ITS `Track`, regardless of
        # how many recovery-loop observations came after it.
        if rescue_index is not None:
            boxes_out.append(_box_for(rescue[2], tracks[rescue_index], box=tracks[rescue_index].box))
        return boxes_out

    def _roi_rescue(
        self,
        engine: CostAssociator,
        tracks: Sequence[Track],
        candidates: Sequence[Candidate],
        assignment: Assignment,
        now: float,
        frame: FrameFn,
        detect: DetectFn,
        extractor: Any,
    ) -> "Optional[tuple[Observation, Optional[Descriptor], Any]]":
        """At most one bounded, gated second look per frame -- TRACKING-V2-
        PLAN wave C5c, review §4.6's "detection recall" half: "the detector
        cannot detect the subject" is a different failure from bad
        association, dominant for a small/distant object that is a handful
        of pixels once downscaled to `imgsz`. Only reachable from `_run_cost_
        associate` -- see that method's own docstring for why `bytetrack`
        ASSOCIATE does not get this.

        **The gate that makes this a genuine no-op when disabled.** Checked
        FIRST, before touching `assignment` or anything else: `roi_enabled`
        is `False` by default (`config.py`'s `DEFAULT_TRACK_ROI_ENABLED`),
        and every existing scenario/mode that never opts in pays exactly
        zero of this method's cost.

        **Priority, stated explicitly.** Eligible candidates are the
        `assignment.unmatched_candidates` that are also CONFIRMED
        (`Candidate.confirmed`, i.e. `Track.state != TENTATIVE` -- a
        candidate that never earned an id is not "something the system
        believes in", so it is not worth a second detector pass). Among
        those, the one with the MOST consecutive misses -- closest to
        ageing out past `max_age_frames` into LOST -- gets the pass: that is
        the candidate a closer look benefits most, since recovering it now
        avoids the far more expensive failure (a retired id, a fresh birth,
        or a costly dormant-gallery round-trip) losing it altogether would
        cost. Ties broken by the lowest `track_id`, for a deterministic,
        greppable choice when two candidates are equally at risk.

        **The bound.** At most ONE `detect(roi)` call per frame, full stop
        -- the crop is built once, for the one chosen candidate, and this
        method returns after that single call succeeds or fails. An
        unbounded second pass per frame would double the very detector cost
        TRACKING-V2-PLAN exists to reduce.

        **The gate on the MATCH, not just the pass.** A degenerate crop (a
        collapsed box, or nothing left on-frame after clamping -- `_roi_box`)
        is a genuine no-op: no `detect()` call, no cost, logged nowhere
        because nothing went wrong. Otherwise `detect(roi)` runs through the
        SAME sole `InferenceGate` acquisition site a full-frame pass uses
        (the servicer's `_run_detector` -- P2), and anything it returns is
        judged by the SAME already-retuned `engine.assign(...)`, restricted
        to this ONE candidate against the crop's own targets: a match must
        clear the identical IoU/appearance/cost gates a full-frame match
        would, never a hand-attach bypassing them.
        """
        if not self._params.roi_enabled:
            return None
        eligible = [
            index for index in assignment.unmatched_candidates if candidates[index].confirmed
        ]
        if not eligible:
            return None
        chosen_index = max(eligible, key=lambda index: (tracks[index].misses, -tracks[index].track_id))
        candidate = candidates[chosen_index]

        roi = self._roi_box(candidate.box)
        if roi is None:
            return None

        self._roi_ran = True
        roi_detections, roi_millis = detect(roi)
        self._roi_millis += roi_millis
        if not roi_detections:
            return None

        roi_boxes = [Box(d.x, d.y, d.width, d.height) for d in roi_detections]
        roi_descriptors = self._describe(extractor, frame, roi_boxes)
        roi_targets = [
            Target(
                box=roi_boxes[index],
                label=detection.label,
                confidence=detection.confidence,
                descriptor=roi_descriptors[index],
                det_index=index,
            )
            for index, detection in enumerate(roi_detections)
        ]
        rescue_assignment = engine.assign([candidate], roi_targets)
        if not rescue_assignment.matches:
            return None
        # A rescue is held to a STRICTER overlap than an ordinary match, and
        # this is the one place the two deliberately differ. The crop exists
        # because the object was predicted here, so a genuine rescue sits on
        # the prediction; a neighbour that the deliberately-wide crop happens
        # to contain does not. Reusing the primary gate -- which C3 left
        # permissive on purpose, since a full-frame match has the whole scene
        # competing to explain each box -- let a crowd's neighbour win the
        # slot, and cost `clutter` six id swaps. Measured, not supposed.
        rescued_box = roi_targets[rescue_assignment.matches[0][1]].box
        if candidate.box.iou(rescued_box) < self._params.roi_min_iou:
            return None
        _, target_index = rescue_assignment.matches[0]
        target = roi_targets[target_index]
        observation = Observation(
            key=candidate.key,
            box=target.box,
            label=target.label,
            confidence=target.confidence,
            # -1: no FULL-FRAME detection index behind this observation
            # (`Observation.det_index`'s own docstring) -- `_run_cost_
            # associate` appends this box to the response directly instead
            # of through the `by_index`/`enumerate(detections)` mapping.
            det_index=-1,
        )
        return observation, target.descriptor, roi_detections[target_index]

    def _roi_box(self, box: Box) -> Optional[Box]:
        """A square crop centered on `box`, `roi_crop_factor` times its own
        larger dimension, clamped to the unit frame (TRACKING-V2-PLAN wave
        C5c). `None` -- a genuine no-op, P5 -- when `box` has already
        collapsed or the clamped crop has nothing of the frame left in it;
        the detector never sees a degenerate region.
        """
        side = self._params.roi_crop_factor * max(box.width, box.height)
        if side <= 0.0:
            return None
        cx, cy = box.center
        half = side / 2.0
        x0 = _clamp01(cx - half)
        x1 = _clamp01(cx + half)
        y0 = _clamp01(cy - half)
        y1 = _clamp01(cy + half)
        width = x1 - x0
        height = y1 - y0
        if width <= 0.0 or height <= 0.0:
            return None
        return Box(x0, y0, width, height)

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
        """A detector pass ran: re-anchor the held target, or start coasting.

        TRACKING-V2-PLAN wave C5b: when the locked target re-anchors, this is
        ALSO where up to `follow_top_k - 1` extra targets get their own
        chance to re-anchor or be freshly promoted
        (`_extras_verify_observations`) -- batched into the SAME `TrackBook.
        apply()` call as the lock's own observation, because `apply()` ages
        EVERY live track once per call and a second call in the same frame
        would double-count it. Skipped entirely when the lock itself fails to
        re-anchor (the coast-fallback branch below): a stream whose actual
        lock cannot be confirmed is not the moment to spend effort on
        situational-awareness boxes, and the next successful verify pass
        picks extras back up from a clean slate.
        """
        boxes = [Box(d.x, d.y, d.width, d.height) for d in detections]
        index = self._select_target(boxes, now)

        if index >= 0:
            try:
                anchored = engine.init(frame(), boxes[index])
            except Exception as exc:  # noqa: BLE001
                self._reset_engine(exc)
                return [_box_for(detection) for detection in detections]
            if anchored:
                locked_observation = observation_for(
                    detections[index],
                    self._follow_key(),
                    det_index=index,
                    # A re-anchor is CONFIRMED outright (TRACKING-PLAN §3.1):
                    # the operator chose this target and the detector just
                    # re-approved it, so `min_hits` -- an anti-flicker gate
                    # for ambient association -- does not apply.
                    authoritative=True,
                )
                # 2026-08-14 repair -- `TrackBook.apply()`'s own `captured_at`
                # map (its docstring). `None` (the default, meaning "every
                # observation's own capture instant is `now`") unless the
                # branch below actually resolves one for the locked
                # observation -- an extra never gets an entry, same stated
                # scope boundary as `_run_cost_associate`'s own map.
                captured_at: "Optional[dict[object, float]]" = None
                if self._followed is not None:
                    # TRACKING-V3-PLAN wave V6 -- `self._followed` is still
                    # the PREVIOUS frame's held `Track` here (reassigned only
                    # below), so its `.history`/`.history_transform` are real
                    # PRIOR evidence to bracket a late detection against. A
                    # genuine no-op on a FRESH acquisition (`self._followed
                    # is None`, nothing to bracket against yet) or when this
                    # frame measured no lag.
                    corrected_box, box_captured_at = self._late_corrected_box(
                        self._followed, locked_observation.box, now
                    )
                    if corrected_box is not locked_observation.box:
                        locked_observation = dataclasses.replace(locked_observation, box=corrected_box)
                    captured_at = {locked_observation.key: box_captured_at}
                claimed = {index}
                extra_observations, extra_candidates = self._extras_verify_observations(
                    boxes, detections, frame, now, claimed
                )
                tracks = self._book.apply(
                    [locked_observation, *extra_observations],
                    now,
                    detector_ran=True,
                    captured_at=captured_at,
                )
                locked_track = tracks[0]
                self._followed = locked_track
                self._lock.bind(locked_track.track_id)
                self._tracker_stalled = False
                self._extras = [
                    _ExtraFollow(key=candidate.key, engine=candidate.engine, track=track)
                    for candidate, track in zip(extra_candidates, tracks[1:])
                ]
                tracks_by_index = {index: locked_track}
                tracks_by_index.update(
                    {
                        candidate.det_index: track
                        for candidate, track in zip(extra_candidates, tracks[1:])
                    }
                )
                return [
                    _box_for(
                        detection,
                        tracks_by_index.get(position),
                        # TRACKING-V3-PLAN wave V6 -- same reasoning as
                        # `_run_cost_associate`'s own `boxes_out`: `track.box`
                        # is `observation.box` post-`_observe`, corrected for
                        # the LOCKED target when `_late_corrected_box` above
                        # actually ran, and byte-identical to the raw
                        # detection for every extra (never corrected, out of
                        # this wave's scope) or unmatched position.
                        box=(tracks_by_index[position].box if position in tracks_by_index else None),
                    )
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

    def _extras_verify_observations(
        self,
        boxes: Sequence[Box],
        detections: Sequence[Any],
        frame: FrameFn,
        now: float,
        claimed: "set[int]",
    ) -> "tuple[list[Observation], list[_ExtraCandidate]]":
        """This verify pass's extra-target observations, built but NOT yet
        booked (TRACKING-V2-PLAN wave C5b, review finding C6).

        Batched into the SAME `TrackBook.apply()` call the caller makes for
        the locked target's own re-anchor -- see this method's caller for
        why one call, not several. Mutates `claimed` with every detection
        index an extra takes, so the caller never lets one box serve two
        slots.

        **Policy, stated plainly per the plan's own instruction.** An
        EXISTING extra re-anchors on the best-IoU UNCLAIMED detection at or
        above `redetect_iou_threshold` -- the SAME test the locked target's
        own re-anchor already uses (`lock.best_iou_match`), reused rather
        than reinvented. One that fails to re-anchor is dropped outright
        (its engine released): an extra is opportunistic situational
        awareness, never a commitment, so it gets none of the locked
        target's coast/predict/LOST machinery. Whatever OPEN slots remain
        (a dropped extra, or `follow_top_k` freshly raised) are filled by
        the highest-CONFIDENCE unclaimed detections -- confidence, and only
        confidence, is the "worth a slot" policy. If this ever needs a
        second sort key, it has grown past what belongs in this file.
        """
        capacity = max(0, self._params.follow_top_k - 1)
        observations: "list[Observation]" = []
        candidates: "list[_ExtraCandidate]" = []
        if capacity <= 0:
            self._release_extras()
            return observations, candidates

        for extra in self._extras:
            available = [position for position in range(len(boxes)) if position not in claimed]
            held_box = predict(extra.track, now).box
            match = (
                lock_module.best_iou_match(
                    held_box, [boxes[position] for position in available], self._params.redetect_iou_threshold
                )
                if available
                else -1
            )
            if match < 0:
                self._drop_extra(extra)
                continue
            detection_index = available[match]
            try:
                anchored = extra.engine.init(frame(), boxes[detection_index])
            except Exception:  # noqa: BLE001 - one extra's failure costs that extra only
                self._drop_extra(extra)
                continue
            if not anchored:
                self._drop_extra(extra)
                continue
            claimed.add(detection_index)
            observations.append(
                observation_for(detections[detection_index], extra.key, det_index=detection_index)
            )
            candidates.append(
                _ExtraCandidate(key=extra.key, engine=extra.engine, det_index=detection_index)
            )

        open_slots = capacity - len(candidates)
        if open_slots > 0:
            registry = self._registry_provider()
            if registry is not None:
                ranked = sorted(
                    (position for position in range(len(detections)) if position not in claimed),
                    key=lambda position: detections[position].confidence,
                    reverse=True,
                )[:open_slots]
                for detection_index in ranked:
                    # TRACKING-V3-PLAN wave V1: an extra respects the SAME
                    # served-level ceiling as the locked target's own
                    # engine -- otherwise a level-1 stream could grow a
                    # `cv2`-backed extra through this side door even though
                    # `_create_engine` never allows one for the lock itself.
                    created = registry.follower(
                        self._params.engine_id,
                        max_age_frames=self._params.max_age_frames,
                        **self._level_kwargs(registry),
                    )
                    if created is None:
                        continue
                    _engine_id, engine = created
                    try:
                        anchored = engine.init(frame(), boxes[detection_index])
                    except Exception:  # noqa: BLE001 - a fresh extra's own failure, nothing else
                        continue
                    if not anchored:
                        continue
                    claimed.add(detection_index)
                    self._extra_key_seq += 1
                    key = f"follow-extra:{self._lock.generation}:{self._extra_key_seq}"
                    observations.append(
                        observation_for(detections[detection_index], key, det_index=detection_index)
                    )
                    candidates.append(
                        _ExtraCandidate(key=key, engine=engine, det_index=detection_index)
                    )

        return observations, candidates

    def _follow_predict(self, engine: Any, frame: FrameFn, now: float) -> list[TrackedBox]:
        """A tracker-only frame: move the locked target AND up to
        `follow_top_k - 1` extra targets, each by its own engine, in ONE
        batched `TrackBook.apply()` call (TRACKING-V2-PLAN wave C5b, review
        finding C6 -- FOLLOW used to be scene-blind between verify passes).

        The locked target is ALWAYS first in the returned list, built
        independently of the extras: if its OWN engine raises, this method
        reports the frame untracked exactly as it always has
        (`_build_coast_observation`'s exception path resets the locked
        engine and returns `None`), and extras are never even touched that
        frame -- a problem with the operator's actual lock is not the moment
        to spend effort on situational-awareness boxes.
        """
        if self._followed is None:  # pragma: no cover - the scheduler forbids it
            return []
        held = self._followed
        locked_observation = self._build_coast_observation(engine, held, frame, now, detector_ran=False)
        if locked_observation is None:
            return []

        extra_observations, surviving_extras = self._extras_observations(frame)

        tracks = self._book.apply([locked_observation, *extra_observations], now, detector_ran=False)
        boxes = [self._settle_followed(tracks[0])]
        self._extras = []
        for extra, track in zip(surviving_extras, tracks[1:]):
            # `track` is the SAME object `TrackBook` will keep handing back
            # for this key going forward -- re-wrapping it here (rather than
            # reusing `extra` as-is) is what keeps `_ExtraFollow.track`
            # honest as "the book's own object", the property `predict()`/
            # the next verify pass's re-anchor test both rely on.
            self._extras.append(_ExtraFollow(key=extra.key, engine=extra.engine, track=track))
            boxes.append(_from_track(track))
        return boxes

    def _extras_observations(
        self, frame: FrameFn
    ) -> "tuple[list[Observation], list[_ExtraFollow]]":
        """Advance every currently-held extra by its OWN engine's `update()`
        call (TRACKING-V2-PLAN wave C5b).

        An extra whose engine raises, reports lost, or returns an invalid
        box is simply DROPPED (its engine released, its slot freed for the
        next verify pass to fill) rather than predicted or coasted -- see
        `_extras_verify_observations`'s docstring for why an extra gets none
        of the locked target's stall/LOST machinery. Never touches
        `_tracker_failed`/`_box_invalid`/the `LockArbiter` -- the duty
        cycle's scheduler answers to the LOCKED target only, and an extra
        failing must never bring a verify pass forward on its account: that
        would let situational-awareness boxes dictate the cadence the
        operator's own lock is supposed to control.
        """
        observations: "list[Observation]" = []
        survivors: "list[_ExtraFollow]" = []
        for extra in self._extras:
            try:
                update = extra.engine.update(frame())
            except Exception:  # noqa: BLE001 - one extra's failure costs that extra only
                self._drop_extra(extra)
                continue
            if update is None or not update.box.valid:
                self._drop_extra(extra)
                continue
            observations.append(
                Observation(
                    key=extra.key,
                    box=update.box,
                    label=extra.track.label,
                    confidence=extra.track.confidence,
                    source=SOURCE_TRACKER,
                )
            )
            survivors.append(extra)
        return observations, survivors

    def _coast(
        self, engine: Any, frame: FrameFn, now: float, *, detector_ran: bool
    ) -> Optional[TrackedBox]:
        """Advance the LOCKED target by the tracker alone, and book it.

        Single-target contract, used by the FOLLOW verify pass's own
        coast-fallback branch (`detector_ran=True`, no extras touched -- see
        `_follow_verify`'s docstring for why). `_follow_predict` does NOT
        call this directly: it needs the locked observation batched together
        with every extra's into ONE `TrackBook.apply()` call, so it calls
        `_build_coast_observation`/`_settle_followed` itself instead. Kept as
        a thin wrapper over both rather than removed, so this file's other
        single-observation call site reads exactly as it did before this
        wave.
        """
        held = self._followed
        if held is None:  # pragma: no cover - guarded by both callers
            return None
        observation = self._build_coast_observation(engine, held, frame, now, detector_ran=detector_ran)
        if observation is None:
            return None
        track = self._book.apply([observation], now, detector_ran=detector_ran)[0]
        return self._settle_followed(track)

    def _build_coast_observation(
        self, engine: Any, held: Track, frame: FrameFn, now: float, *, detector_ran: bool
    ) -> Optional[Observation]:
        """Engine update / predict-on-stall for the LOCKED target, stopping
        short of booking it into the book.

        Split out of what used to be `_coast`'s own body (TRACKING-V2-PLAN
        wave C5b) so a tracker-only frame can batch the locked target's
        observation with every extra's into ONE `TrackBook.apply()` call --
        `apply()` ages EVERY live track once per call, so calling it more
        than once in the same frame would double-count `age_frames` for
        every track in the book, not just the ones this frame's engines
        touched. `None` means the engine raised: `_reset_engine` has already
        reset it and bumped the book epoch, and the caller reports this
        frame untracked, exactly `_coast`'s pre-existing contract.

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

        return Observation(
            key=self._follow_key(),
            box=box,
            label=held.label,
            confidence=held.confidence,
            source=SOURCE_TRACKER,
            # True exactly when `box` came from `predict()` rather than from
            # the engine -- see `Observation.predicted`.
            predicted=predicted,
        )

    def _settle_followed(self, track: Track) -> TrackedBox:
        """Post-`apply()` bookkeeping for the LOCKED target's own track,
        shared by `_coast` and `_follow_predict`'s own batched apply call."""
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

        TRACKING-V3-PLAN wave V3: when that PREDICTED test fails, one more
        attempt is made before giving up on this pass -- matching against
        the LAST REAL observation this track ever received, carried into
        THIS frame by the same accumulated ego-motion transform ORU itself
        reads (`Track.history_transform`, `reupdate.py`'s own module
        docstring), not the compounded, possibly-wrong-direction VELOCITY
        `predict()` is still trusting. This is the prospective half of the
        same evidence-vs-extrapolation swap ORU makes retrospectively at
        the moment of a successful re-anchor: `nonlinear`'s object reverses
        heading the instant it is hidden, so the constant-velocity
        prediction runs the wrong way for the whole gap and can never
        clear the re-anchor test on its own, while the last REAL box it
        was ever seen in barely differs from where a reversed-but-still-
        nearby object actually is.

        Not gated on `track.misses > 0` -- deliberately, and unlike
        `track.py`'s own ORU gate: `misses` only advances INSIDE `_observe`
        (this frame's, not yet run when `_select_target` is called), so the
        VERY FIRST verify pass after a real confirmation would otherwise
        never get this fallback, which is exactly the attempt where the
        drift is smallest and the fallback's odds are best. Gated instead
        by `reupdate_max_gap_millis` -- the SAME ceiling that bounds what
        ORU itself will reconstruct, deliberately: a gap too old to trust
        for one is too old to trust for the other, and `memory.py`'s
        dormant gallery is what serves it once genuinely LOST. A no-op, not
        a widened gate, when the PRIMARY test already succeeded: this is a
        SECOND candidate offered only on failure, never a looser threshold
        applied to the first one, so it cannot make an existing correct
        match worse.
        """
        held_box = predict(self._followed, now).box if self._followed is not None else None
        index = lock_module.select_target(
            boxes,
            held_box=held_box,
            target=self._lock.target,
            min_iou=self._params.redetect_iou_threshold,
            box_of_track=lambda track_id: self._box_of_track(track_id, now),
        )
        if index < 0 and held_box is not None:
            anchor = self._followed.history.latest()
            if anchor is not None:
                gap_millis = (now - anchor.timestamp) * 1000.0
                if 0.0 < gap_millis <= self._params.reupdate_max_gap_millis:
                    frozen_box = self._followed.history_transform.apply_box(anchor.observation.box)
                    index = lock_module.best_iou_match(
                        frozen_box, boxes, self._params.redetect_iou_threshold
                    )
        return index

    def _box_of_track(self, track_id: int, now: float) -> Optional[Box]:
        known = self._book.get(track_id)
        return predict(known, now).box if known is not None else None

    def _follow_key(self) -> str:
        """A fresh `TrackBook` key per applied lock, so re-acquiring after a
        release yields a NEW id instead of resurrecting the previous one."""
        return f"follow:{self._lock.generation}"

    # -- capability level (TRACKING-V3-PLAN wave V1, §5) ---------------------

    def _resolve_capability_level(self) -> int:
        """This stream's SERVED level, resolving (and probing the host)
        once per config change.

        `levels.probe()` reads live host state (importability + available
        memory) rather than anything cached at process start (decision E12:
        "what lets the workstation shed levels under load instead of
        dropping streams"), but this method itself still caches the OUTCOME
        until `apply_config` invalidates it (`_release_capability`) -- the
        same "hot knob, re-resolved on config change" shape `_resolve_
        motion_compensator`/`_resolve_appearance_extractor` already use, not
        a per-frame re-probe, which `process()`'s own comment above this
        call explains.

        Every degradation is logged EXACTLY ONCE per distinct reason
        (`_degraded_capability_reasons`, keyed by the reason string itself --
        two different requested levels capped to the same served level by
        the same host constraint share one log line, same dedup granularity
        `_warn_unknown` already uses for engine ids) and NEVER raises
        (invariant P5): `levels.resolve` is pure arithmetic with no failure
        mode to propagate.
        """
        if self._capability_resolved:
            return self._capability_level_served
        self._capability_resolved = True
        probed = levels_module.probe()
        served, reason = levels_module.resolve(self._params.capability_level, probed)
        self._capability_level_served = served
        self._capability_level_reason = reason
        if reason and reason not in self._degraded_capability_reasons:
            self._degraded_capability_reasons.add(reason)
            LOGGER.warning("tracking: capability level capped -- %s", reason)
        return served

    def _level_kwargs(self, registry: Any) -> "dict[str, int]":
        """`{"level": served}` for the real `TrackerRegistry`, `{}` for
        anything else offered through `registry_provider`.

        `registry_provider` is a plain callable contract (`Callable[[],
        Optional[TrackerRegistry]]`), not an enforced protocol -- tests
        across this codebase substitute their own duck-typed stand-ins for
        it, predating this wave, that implement `associator`/`follower`/
        `compensator`/`appearance` WITHOUT a `level` parameter. Passing
        `level=` unconditionally would break every one of them for a
        capability distinction they were never written to care about.
        `TrackerRegistry`'s own four accessors default `level=None` (no
        ceiling), so omitting this kwarg entirely is exactly equivalent to
        `level=None` for anything that DOES support it.
        """
        if isinstance(registry, TrackerRegistry):
            return {"level": self._capability_level_served}
        return {}

    def _release_capability(self) -> None:
        """Force the next active frame to re-probe/re-resolve the served
        level, and drop every engine the OLD level's roster filter gated.

        `_engine`/`_motion_engine`/`_appearance_engine` were each built
        through `TrackerRegistry`'s level-filtered roster (`registry.py`'s
        `_ASSOCIATOR_MIN_LEVEL` and siblings) using the level THEN in force
        -- a changed requested ceiling can widen or narrow which engines are
        even visible, so all three are invalidated exactly as a mode/
        engine_id change already invalidates `_engine` (`_release_engine`'s
        own docstring).
        """
        self._capability_resolved = False
        self._release_engine()
        self._release_motion_compensator()
        self._release_appearance_extractor()

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
        # TRACKING-V3-PLAN wave V1: the served level, already resolved by
        # `process()` before `_resolve_engine()` (this method's only caller)
        # ever runs -- see `_resolve_capability_level`'s own docstring. L1
        # offers no follower at all, so a level-1 FOLLOW request returns
        # `None` here and `_resolve_engine`'s existing `FOLLOW -> ASSOCIATE`
        # ladder takes over with no new code needed for it.
        level_kwargs = self._level_kwargs(registry)
        if self._params.mode == MODE_FOLLOW:
            return registry.follower(
                self._params.engine_id, max_age_frames=self._params.max_age_frames, **level_kwargs
            )
        return registry.associator(
            self._params.engine_id, max_age_frames=self._params.max_age_frames, **level_kwargs
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

        # TRACKING-V3-PLAN wave V1: the served level, already resolved by
        # `process()` before this method's only two callers ever run.
        level_kwargs = self._level_kwargs(registry)
        created = registry.compensator(requested, **level_kwargs)
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
            created = registry.compensator(MOTION_ENGINE_FLOW, **level_kwargs)
            if created is None or created[0] == MOTION_ENGINE_POSE:
                # `flow` needs L2 (`registry._COMPENSATOR_MIN_LEVEL`); at L1
                # the roster this level affords contains only `pose` -- the
                # SAME engine that just failed its availability check --
                # so re-offering it as a "fallback" would be a repeated
                # failure dressed up as one, not a genuine second option.
                # Uncompensated is the honest outcome (P5's "genuine no-op").
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
        # TRACKING-V3-PLAN wave V1: the served level, already resolved by
        # `process()` before this method's only caller (`_run_cost_
        # associate`) ever runs.
        created = registry.appearance(requested, **self._level_kwargs(registry))
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
        self._release_extras()
        self._followed = None
        self._tracker_stalled = False
        self._lock.unbind()

    def _book_owns_keys(self) -> bool:
        """Whether track keys are minted by the book rather than by an engine.

        True on the `cost` path, where a new candidate gets an `object()`
        sentinel from `_run_cost_associate` and a matched one reuses its
        track's existing key. False for `bytetrack`, which numbers tracks
        from its own counter and restarts it whenever it is rebuilt.
        """
        return (
            self._params.mode == MODE_ASSOCIATE
            and self._params.engine_id == CostAssociator.engine_id
        )

    def _release_engine(self, *, bump_epoch: bool = True) -> None:
        """Drop the current engine instance so the next frame rebuilds one.

        Called on a config change that invalidates the engine (mode/
        engine_id/max_age_frames) while tracking STAYS active -- review
        finding D2, the config-change twin of D1 above: an operator
        switching FOLLOW's engine from `lk` to `ncc` mid-stream keeps every
        track's id instead of losing the whole scene's numbering. `OFF`
        (`_reset_state`, below) is the one caller that wants a real wipe.
        Also the mechanism `reset_for_reconnect` (TRACKING-V2-PLAN wave C5b)
        reuses for a disconnect, for the identical reason.
        """
        self._engine = None
        self._engine_id = ""
        if bump_epoch:
            self._book.bump_epoch()
        self._release_extras()
        self._followed = None
        self._tracker_stalled = False
        self._lock.unbind()

    def _release_extras(self) -> None:
        """Drop every extra target's engine and forget the slot (TRACKING-V2-
        PLAN wave C5b) -- never the `Track` itself, which stays in the book,
        aging on the normal schedule, exactly like the locked target's own
        `Track` after `_release_engine` drops ITS engine.

        Called everywhere `_reset_engine`/`_release_engine` bump the book's
        epoch: an extra's booking key stops resolving to its existing
        `Track` the instant the epoch changes (`TrackBook._namespaced`), so
        holding onto a now-orphaned `_ExtraFollow` past that point would only
        let its NEXT re-anchor silently mint a fresh id under the same key
        string -- clearing the slot here makes that explicit instead of
        latent.
        """
        for extra in self._extras:
            self._drop_extra(extra)
        self._extras = []

    def _drop_extra(self, extra: "_ExtraFollow") -> None:
        """Release one extra's engine. Never raises -- a failed reset costs
        that engine, not the stream (P5), same posture every other engine
        teardown in this file takes."""
        try:
            extra.engine.reset()
        except Exception:  # noqa: BLE001
            pass

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
        # TRACKING-V3-PLAN wave V1: not a full `_release_capability()` --
        # the engines it would drop are already handled by the three calls
        # above -- just the cached served level itself, so the next active
        # frame re-probes fresh rather than trusting a value from before
        # this potentially-long OFF period.
        self._capability_resolved = False


def _box_for(
    detection: Any,
    track: Optional[Track] = None,
    *,
    identity_confidence: float = 0.0,
    dormant_millis: int = 0,
    box: Optional[Box] = None,
) -> TrackedBox:
    """One box on the response -- `box`, if given, else `Box(detection.x, ...)`.

    `box` (TRACKING-V3-PLAN wave V6) lets a caller that may have applied
    late-detection back-correction (`_late_corrected_box`) show what was
    actually BOOKED into the track rather than the raw, possibly-stale
    detection -- `_run_cost_associate` and `_follow_verify` are the only
    two callers that ever pass it (both build it from `track.box` AFTER
    `TrackBook.apply()`, which is exactly `observation.box`, corrected or
    not, by `_observe`'s own contract). Every other caller passes `None`
    and reproduces this function's pre-wave-V6 behavior exactly, which
    matters most for `_run_associate` (`bytetrack`): that engine's own
    `Observation.box` is ITS post-Kalman estimate, deliberately never shown
    here even absent this wave -- passing `track.box` there would be a
    genuine, unrelated behaviour change this wave does not intend to make.
    """
    return TrackedBox(
        label=detection.label,
        confidence=detection.confidence,
        box=box if box is not None else Box(detection.x, detection.y, detection.width, detection.height),
        track=track,
        identity_confidence=identity_confidence,
        dormant_millis=dormant_millis,
    )


def _from_track(track: Track) -> TrackedBox:
    return TrackedBox(label=track.label, confidence=track.confidence, box=track.box, track=track)


def _clamp01(value: float) -> float:
    """TRACKING-V2-PLAN wave C5c -- `_roi_box`'s own clamp-to-frame, pulled
    out to a plain function rather than importing `cv_service.inference.
    detector`'s equivalent (same name, same behaviour): that module is `cv2`
    /`numpy`-gated (P3), and this one call site does not need the rest of
    it.
    """
    return max(0.0, min(1.0, value))
