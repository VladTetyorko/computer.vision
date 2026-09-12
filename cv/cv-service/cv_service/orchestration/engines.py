"""`EngineSet` -- every lazily-resolved collaborator one stream can hold.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` wave W0. Moved verbatim out of
`StreamTrackingSession`, which owned all five resolution ladders plus the
degradation policy and was 2 362 lines partly because of it.

Five things resolve lazily here, each build-once-until-invalidated, each
with its own reason for existing at all:

    capability level   probes the host; every roster below is filtered by it
    engine             the associator or follower serving this stream
    motion compensator ego-motion; the one ladder with a RUNTIME fallback
    appearance         descriptors for `cost`; constructibility IS availability
    object memory      the dormant gallery, resolved regardless of mode

**No behaviour changed in the move.** Same order, same caching, same
one-log-line-per-distinct-reason dedup, same degradation ladder (`FOLLOW ->
ASSOCIATE -> OFF`), same "never raise, never a dead stream" posture (P5).
The only structural change is that `degrade_to` no longer retunes the
scheduler and the book itself: it hands the new `TrackingParams` to the
session through `on_params`, because after W0 the scheduler lives behind
`BudgetPolicy` and having two owners write it was how that would drift.

The logger name is still `cv_service.tracking.session` on purpose -- these
warnings are the ones an operator greps for, and a file move is not a reason
to rename an operational signal.

Pure stdlib. The engines it builds are not -- but building one is the
registry's job, and the registry is handed in.
"""

from __future__ import annotations

import logging
from typing import Any, Callable, Optional

from cv_service.orchestration.state import ExtraFollow, StreamState
from cv_service.tracking import levels as levels_module
from cv_service.tracking.assign import CostAssociator
from cv_service.tracking.engines.base import CameraPose
from cv_service.tracking.lock import LockArbiter
from cv_service.tracking.memory import ObjectMemory
from cv_service.tracking.params import MODE_ASSOCIATE, MODE_FOLLOW, MODE_OFF, TrackingParams
from cv_service.tracking.registry import MOTION_ENGINE_FLOW, MOTION_ENGINE_POSE, TrackerRegistry
from cv_service.tracking.track import TrackBook

LOGGER = logging.getLogger("cv_service.tracking.session")

# `TrackingParams.motion_engine_id`'s one non-roster value (TRACKING-V2-PLAN
# §3, wire docstring "off disables") -- checked here, not in `params.py`,
# because it is this module that decides what to DO with the resolved value,
# the same division of labour `resolve()`'s own docstring draws between
# "turn a sentinel into a value" and "decide what serves it".
_MOTION_ENGINE_OFF = "off"

# `TrackingParams.appearance_engine_id`'s equivalent (TRACKING-V2-PLAN wave
# C3) -- same division of labour as `_MOTION_ENGINE_OFF` above.
_APPEARANCE_ENGINE_OFF = "off"


class EngineSet:
    """Every engine one stream may resolve, plus the degradation ladder.

    `on_params` is called whenever a degradation rewrites `TrackingParams`;
    the session re-tunes the budget and the book from it. A callback rather
    than a back-reference so this class can be built and driven in a test
    with no session at all.
    """

    def __init__(
        self,
        *,
        settings: Any,
        registry_provider: "Callable[[], Optional[TrackerRegistry]]",
        params: TrackingParams,
        book: TrackBook,
        lock: LockArbiter,
        state: StreamState,
        on_params: "Callable[[TrackingParams], None]",
    ) -> None:
        self._settings = settings
        self._registry_provider = registry_provider
        self._params = params
        self._book = book
        self._lock = lock
        self._state = state
        self._on_params = on_params
        self._engine: Any = None
        self._engine_id = ""
        self._degraded_engine_ids: "set[str]" = set()
        self._motion_engine: Any = None
        self._motion_engine_id = ""
        self._motion_resolved = False
        self._degraded_motion_ids: "set[str]" = set()
        self._appearance_engine: Any = None
        self._appearance_engine_id = ""
        self._appearance_resolved = False
        self._degraded_appearance_ids: "set[str]" = set()
        self._memory: Optional[ObjectMemory] = None
        self._memory_resolved = False
        self._capability_resolved = False
        self._capability_level_served = 0
        self._capability_level_reason = ""
        self._degraded_capability_reasons: "set[str]" = set()

    # -- what the session and the contributors read -------------------------

    @property
    def params(self) -> TrackingParams:
        return self._params

    @property
    def engine(self) -> Any:
        """The resolved engine, or `None` -- never resolves one itself."""
        return self._engine

    @property
    def engine_id(self) -> str:
        return self._engine_id

    @property
    def motion_engine_id(self) -> str:
        return self._motion_engine_id

    @property
    def appearance_engine_id(self) -> str:
        return self._appearance_engine_id

    @property
    def appearance_extractor(self) -> Any:
        """The extractor already resolved for this stream, without resolving one.

        `cost` needs to know WHETHER one resolved (it zeroes the appearance
        cost weight when none did), and asking that question must not itself
        build one on a frame the budget refused appearance.
        """
        return self._appearance_engine

    @property
    def memory(self) -> Optional[ObjectMemory]:
        return self._memory

    @property
    def capability_level_served(self) -> int:
        return self._capability_level_served

    @property
    def capability_level_reason(self) -> str:
        return self._capability_level_reason

    def forget_motion_engine(self) -> None:
        """A compensator raised: drop the instance, KEEP the id.

        Deliberately not `release_motion_compensator`, which also clears the
        id: the id is what the next warning line names, and the next frame's
        `resolve_motion_compensator` overwrites it anyway on success.
        """
        self._motion_engine = None
        self._motion_resolved = False

    def forget_appearance_extractor(self) -> None:
        """An extractor raised. Same id-preserving shape as motion above."""
        self._appearance_engine = None
        self._appearance_resolved = False

    @property
    def degraded_motion_ids(self) -> "set[str]":
        """The dedup set for per-engine warnings, shared with its contributor."""
        return self._degraded_motion_ids

    @property
    def degraded_appearance_ids(self) -> "set[str]":
        return self._degraded_appearance_ids

    # -- capability level (TRACKING-V3-PLAN wave V1, §5) ---------------------

    def resolve_capability_level(self) -> int:
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

    def level_kwargs(self, registry: Any) -> "dict[str, int]":
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

    def reconfigure(self, previous: TrackingParams, params: TrackingParams) -> None:
        """Adopt a changed wire config, releasing whatever it invalidated.

        Moved off the session (CV-ORCHESTRATION W0): every branch here
        ends in a release this class owns, so keeping the decision next to
        the state it invalidates leaves exactly one place to get the
        invalidation rules wrong instead of two.
        """
        self._params = params
        if params.capability_level != previous.capability_level:
            # TRACKING-V3-PLAN wave V1: the served level gates every engine
            # roster below (`_create_engine`/`_resolve_motion_compensator`/
            # `_resolve_appearance_extractor`), so a changed REQUESTED
            # ceiling invalidates whatever was already built through the OLD
            # one -- exactly like a mode/engine_id change (`_release_engine`
            # below), just triggered by a different field.
            self.release_capability()
        if (
            params.mode != previous.mode
            or params.engine_id != previous.engine_id
            or params.max_age_frames != previous.max_age_frames
        ):
            # Mode, engine and `max_age_frames` are all baked into the engine
            # instance (the last is ByteTrack's own lost-track buffer), so
            # changing any of them rebuilds it. Cadence/IoU/min-hits changes
            # do not: those are read per frame from `TrackingParams` and must
            # not cost the operator their track ids.
            self.release_engine()
        if params.motion_engine_id != previous.motion_engine_id:
            # A separate check from the block above: the motion compensator
            # is independent of the SOT engine (and of `mode` -- an operator
            # may switch `lk` <-> `ncc` without ever touching this), so it
            # is only rebuilt when ITS OWN wire field actually changes.
            self.release_motion_compensator()
        if params.appearance_engine_id != previous.appearance_engine_id:
            # Same independence as motion above -- appearance is orthogonal
            # to which associator or SOT engine is active.
            self.release_appearance_extractor()
        if params.memory_params != previous.memory_params:
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
            if self.memory is not None and params.memory_params.ttl_millis > 0:
                self.memory.retune(params.memory_params)
            else:
                self.release_memory()

    def release_capability(self) -> None:
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
        self.release_engine()
        self.release_motion_compensator()
        self.release_appearance_extractor()

    # -- engines and degradation -------------------------------------------

    def resolve_engine(self) -> Any:
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
            self.degrade_to(MODE_OFF, "no tracker registry on this host")
            return None

        created = self.create_engine(registry)
        if created is None and self._params.mode == MODE_FOLLOW:
            self.degrade_to(MODE_ASSOCIATE, "no FOLLOW engine constructible")
            created = self.create_engine(registry)
        if created is None:
            self.degrade_to(MODE_OFF, "no tracking engine constructible")
            return None

        self._engine_id, self._engine = created
        return self._engine

    def create_engine(self, registry: TrackerRegistry) -> Optional[tuple[str, Any]]:
        # TRACKING-V3-PLAN wave V1: the served level, already resolved by
        # `process()` before `_resolve_engine()` (this method's only caller)
        # ever runs -- see `_resolve_capability_level`'s own docstring. L1
        # offers no follower at all, so a level-1 FOLLOW request returns
        # `None` here and `_resolve_engine`'s existing `FOLLOW -> ASSOCIATE`
        # ladder takes over with no new code needed for it.
        level_kwargs = self.level_kwargs(registry)
        if self._params.mode == MODE_FOLLOW:
            return registry.follower(
                self._params.engine_id, max_age_frames=self._params.max_age_frames, **level_kwargs
            )
        return registry.associator(
            self._params.engine_id, max_age_frames=self._params.max_age_frames, **level_kwargs
        )

    def degrade_to(self, mode: str, why: str) -> None:
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
        self._on_params(self._params)


    def resolve_motion_compensator(self, pose: CameraPose) -> Any:
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
        level_kwargs = self.level_kwargs(registry)
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

    def release_motion_compensator(self) -> None:
        """Drop the current compensator so the next active FOLLOW frame
        rebuilds (and re-checks pose availability) from scratch."""
        self._motion_engine = None
        self._motion_engine_id = ""
        self._motion_resolved = False

    # -- appearance (TRACKING-V2-PLAN wave C3) -------------------------------

    def resolve_appearance_extractor(self) -> Any:
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
        created = registry.appearance(requested, **self.level_kwargs(registry))
        if created is None:
            return None
        self._appearance_engine_id, self._appearance_engine = created
        return self._appearance_engine

    def release_appearance_extractor(self) -> None:
        """Drop the current extractor so the next active `cost` frame
        rebuilds from scratch."""
        self._appearance_engine = None
        self._appearance_engine_id = ""
        self._appearance_resolved = False

    # -- object memory (TRACKING-V2-PLAN wave C4) ----------------------------

    def resolve_memory(self) -> Optional[ObjectMemory]:
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

    def release_memory(self) -> None:
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

    def reset_engine(self, exc: BaseException) -> None:
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
        self.release_extras()
        self._state.followed = None
        self._state.tracker_stalled = False
        self._lock.unbind()

    def book_owns_keys(self) -> bool:
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

    def release_engine(self, *, bump_epoch: bool = True) -> None:
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
        self.release_extras()
        self._state.followed = None
        self._state.tracker_stalled = False
        self._lock.unbind()

    def release_extras(self) -> None:
        """Drop every extra target's engine and forget the slot (TRACKING-V2-
        PLAN wave C5b) -- never the `Track` itself, which stays in the book,
        aging on the normal schedule, exactly like the locked target's own
        `Track` after `_release_engine` drops ITS engine.

        Called everywhere `_reset_engine`/`_release_engine` bump the book's
        epoch: an extra's booking key stops resolving to its existing
        `Track` the instant the epoch changes (`TrackBook._namespaced`), so
        holding onto a now-orphaned `ExtraFollow` past that point would only
        let its NEXT re-anchor silently mint a fresh id under the same key
        string -- clearing the slot here makes that explicit instead of
        latent.
        """
        for extra in self._state.extras:
            self.drop_extra(extra)
        self._state.extras = []

    def drop_extra(self, extra: "ExtraFollow") -> None:
        """Release one extra's engine. Never raises -- a failed reset costs
        that engine, not the stream (P5), same posture every other engine
        teardown in this file takes."""
        try:
            extra.engine.reset()
        except Exception:  # noqa: BLE001
            pass

    def reset_state(self) -> None:
        """Tracking just went OFF (or degraded all the way down to it).

        Unlike `_release_engine`'s config-change case, this is a genuine
        stop -- there is no "still active, still coasting" state for a track
        to survive as, so the book is actually wiped (`forget_keys`), not
        just epoch-namespaced.
        """
        self.release_engine()
        self._book.forget_keys()
        self._state.last_detector_millis = None
        self._state.tracker_failed = False
        self._state.box_invalid = False
        self._state.tracker_stalled = False
        self.release_motion_compensator()
        self.release_appearance_extractor()
        self.release_memory()
        # TRACKING-V3-PLAN wave V1: not a full `_release_capability()` --
        # the engines it would drop are already handled by the three calls
        # above -- just the cached served level itself, so the next active
        # frame re-probes fresh rather than trusting a value from before
        # this potentially-long OFF period.
        self._capability_resolved = False

