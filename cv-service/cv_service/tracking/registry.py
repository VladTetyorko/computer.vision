"""`TrackerRegistry`: four rosters of engine **factories**, probed at startup.

`docs/plans/done/TRACKING-PLAN.md` §5.A, `docs/extracts/TRACKING-ORCHESTRATION.md` §2.1,
`docs/plans/active/TRACKING-V2-PLAN.md` §3 (wave C2 adds the motion roster;
wave C3 adds the appearance roster and `cost` to the associator one; wave C5b
adds no roster but leans harder on the follower roster's existing "a new
instance every call" contract -- see `follower()`'s own docstring).
Deliberately shaped like `cv_service/inference/registry.py`'s `ModelRegistry`
-- lazy construction, unknown ids logged once and served the default, the
roster logged once at INFO so an operator can see what is routable without
reading code -- with **one deliberate inversion**:

> `ModelRegistry` caches ONE detector per id, because a YOLO model is
> stateless across streams and expensive to load. `TrackerRegistry` hands out
> a NEW engine per call, because a tracker is the reverse: cheap to build and
> inherently stateful. Getting this backwards is the single easiest way to
> corrupt every stream at once, which is why it has its own test.

**Four rosters, because there are four protocols** (`Associator` for
ASSOCIATE, `SingleObjectTracker` for FOLLOW, `MotionCompensator` for
ego-motion, `AppearanceExtractor` for appearance evidence). An engine that
legitimately served more than one would appear in more than one roster; none
of the six shipped ones does, which is precisely the interface-segregation
point of §2.2 and why `GET /api/cv/trackers` returns a `modes: []` array per
engine.

**The probe is the answer to roster drift** (TRACKING-PLAN R3/R11): the Java
side advertises a static engine list from config, and cv-service may fail to
construct one of them -- a missing `lap`, an OpenCV build without the `video`
module, a future OpenCV that moves an API again. So the registry constructs
each engine once at startup, drops the ones that raise, and logs the roster
it actually got rather than the one it hoped for. Per frame,
`DetectionResponse.tracker_engine_id` then reports which engine really
served, so the UI can show the serving engine instead of the requested one.

Pure stdlib: the factories import `cv2`/`ultralytics` lazily, inside
themselves, so importing this module never requires the `cv` extra.
"""

from __future__ import annotations

import logging
from typing import Any, Callable, Optional

from cv_service.tracking import levels
from cv_service.tracking.params import MODE_ASSOCIATE, MODE_FOLLOW

LOGGER = logging.getLogger("cv_service.tracking.registry")

AssociatorFactory = Callable[..., Any]
FollowerFactory = Callable[..., Any]
MotionCompensatorFactory = Callable[..., Any]
AppearanceExtractorFactory = Callable[..., Any]

# Roster tags for the probe/log-line diagnostics (`roster()`), parallel to
# `MODE_ASSOCIATE`/`MODE_FOLLOW` -- neither a motion compensator nor an
# appearance extractor is tied to a `TrackingMode`, so each gets its own
# label rather than a borrowed one.
_ROLE_MOTION = "MOTION_COMPENSATOR"
_ROLE_APPEARANCE = "APPEARANCE_EXTRACTOR"

# Engine-id constants for the two shipped compensators. `session.py` needs
# these for its pose-unavailable-falls-back-to-flow policy (TRACKING-V2-PLAN
# §3, "Selection policy") but cannot import `engines/pose_gmc.py`/
# `engines/flow_gmc.py` directly -- the latter is `cv2` at module scope, and
# importing it from `session.py` would break "importable without the `cv`
# extra" for a module that today never needs one. Living here instead, next
# to the roster that names them, keeps both sides in sync by construction.
MOTION_ENGINE_FLOW = "flow"
MOTION_ENGINE_POSE = "pose"


def _bytetrack(*, max_age_frames: int) -> Any:
    from cv_service.tracking.engines.bytetrack import create

    return create(max_age_frames=max_age_frames)


def _lk(**_kwargs: Any) -> Any:
    from cv_service.tracking.engines.lk import create

    return create()


def _ncc(**_kwargs: Any) -> Any:
    from cv_service.tracking.engines.ncc import create

    return create()


def _flow(**_kwargs: Any) -> Any:
    from cv_service.tracking.engines.flow_gmc import create

    return create()


def _pose(**_kwargs: Any) -> Any:
    from cv_service.tracking.engines.pose_gmc import create

    return create()


def _cost(**_kwargs: Any) -> Any:
    """Built with NEUTRAL `AssignWeights`/`AssignGates` (`assign.py`'s own
    dataclass defaults) -- unlike `_bytetrack`, this factory is never handed
    a stream's resolved cost configuration, because `TrackerRegistry`'s own
    construction contract (`max_age_frames` only, uniform across every
    associator) has no place for it. `session.py`'s `_run_cost_associate`
    retunes the real, resolved `TrackingParams.cost_weights`/`cost_gates`
    (with appearance further zeroed when no extractor is active) onto the
    instance this returns, once per call, via `CostAssociator.retune` -- see
    that method's own docstring for why "on config change" is cheap enough
    to just always do at the tens-of-boxes scale this operates at."""
    from cv_service.tracking.assign import AssignGates, AssignWeights, CostAssociator

    return CostAssociator(weights=AssignWeights(), gates=AssignGates())


def _histogram(**_kwargs: Any) -> Any:
    from cv_service.tracking.engines.histogram import create

    return create()


BUILTIN_ASSOCIATORS: dict[str, AssociatorFactory] = {"bytetrack": _bytetrack, "cost": _cost}
BUILTIN_FOLLOWERS: dict[str, FollowerFactory] = {"lk": _lk, "ncc": _ncc}
BUILTIN_COMPENSATORS: dict[str, MotionCompensatorFactory] = {
    MOTION_ENGINE_FLOW: _flow,
    MOTION_ENGINE_POSE: _pose,
}
BUILTIN_APPEARANCES: dict[str, AppearanceExtractorFactory] = {"histogram": _histogram}

# The capability ladder (TRACKING-V3-PLAN §5, wave V1): the lowest level (`levels.py`)
# at which each shipped engine may be SELECTED, independent of whether it happens to be
# technically CONSTRUCTIBLE on a more capable host -- a level is a CONTRACT (invariant
# P9), not "whatever this box can do", so a stream deliberately capped to L1 on a
# workstation that has `cv2` fully installed must still get exactly what an ARMv6 relay
# would get. `_create` below filters the candidate roster against these BEFORE calling
# any factory, so a level-1 stream never even ATTEMPTS `_lk`/`_ncc`/`_flow`/
# `_histogram`/`_bytetrack` -- which is what keeps L1 provably `cv2`/`numpy`-free
# (invariant P8) even when this same process also serves higher-level streams.
#
# `cost` (`assign.py`, pure stdlib) and `pose` (`engines/pose_gmc.py`, pure trigonometry)
# are affordable at L1. Everything needing `cv2` waits for L2. `bytetrack` specifically
# waits for L3 -- decision E11, measured (§5.1): below ~25-30 simultaneous detections
# `cost` is 5.5x cheaper (154us vs 840us @N=10) for +3.5 MiB against `bytetrack`'s
# +17 MiB, because `bytetrack` drags `numpy` in on its own; by L3 `numpy` is already
# resident for the detector, so that +17 MiB is already paid.
_ASSOCIATOR_MIN_LEVEL: dict[str, int] = {"cost": levels.LEVEL_L1, "bytetrack": levels.LEVEL_L3}
_FOLLOWER_MIN_LEVEL: dict[str, int] = {"lk": levels.LEVEL_L2, "ncc": levels.LEVEL_L2}
_COMPENSATOR_MIN_LEVEL: dict[str, int] = {
    MOTION_ENGINE_POSE: levels.LEVEL_L1,
    MOTION_ENGINE_FLOW: levels.LEVEL_L2,
}
_APPEARANCE_MIN_LEVEL: dict[str, int] = {"histogram": levels.LEVEL_L2}

# Probe-time construction arguments. Any positive value works -- the probe
# only asks "can this be built on this box at all", and the real per-stream
# construction passes the stream's own resolved `TrackingParams`. Ignored by
# every factory except `_bytetrack` (`**_kwargs`), including both
# compensator factories -- a motion engine has no `max_age_frames` concept at
# all, so the probe passes the same argument to all three rosters uniformly
# rather than special-casing the third.
_PROBE_MAX_AGE_FRAMES = 1


class TrackerRegistry:
    """`{engine_id -> factory}` per role, with a startup constructibility probe."""

    def __init__(
        self,
        *,
        associators: dict[str, AssociatorFactory],
        followers: dict[str, FollowerFactory],
        compensators: dict[str, MotionCompensatorFactory],
        appearances: dict[str, AppearanceExtractorFactory],
        default_associate_id: str,
        default_follow_id: str,
        default_motion_id: str,
        default_appearance_id: str,
    ) -> None:
        self._associators = dict(associators)
        self._followers = dict(followers)
        self._compensators = dict(compensators)
        self._appearances = dict(appearances)
        self._default_associate_id = default_associate_id
        self._default_follow_id = default_follow_id
        self._default_motion_id = default_motion_id
        self._default_appearance_id = default_appearance_id
        self._warned_unknown_ids: set[str] = set()
        self._probed = False

    # -- roster -------------------------------------------------------------

    @property
    def default_associate_id(self) -> str:
        return self._default_associate_id

    @property
    def default_follow_id(self) -> str:
        return self._default_follow_id

    @property
    def default_motion_id(self) -> str:
        return self._default_motion_id

    @property
    def default_appearance_id(self) -> str:
        return self._default_appearance_id

    def roster(self) -> dict[str, list[str]]:
        """`{engine_id -> [roles it serves]}`, sorted -- diagnostics and the log line."""
        roles: dict[str, list[str]] = {}
        for engine_id in self._associators:
            roles.setdefault(engine_id, []).append(MODE_ASSOCIATE)
        for engine_id in self._followers:
            roles.setdefault(engine_id, []).append(MODE_FOLLOW)
        for engine_id in self._compensators:
            roles.setdefault(engine_id, []).append(_ROLE_MOTION)
        for engine_id in self._appearances:
            roles.setdefault(engine_id, []).append(_ROLE_APPEARANCE)
        return {engine_id: roles[engine_id] for engine_id in sorted(roles)}

    def probe(self) -> dict[str, list[str]]:
        """Construct every engine once; drop what raises; log the real roster.

        Idempotent -- runs at most once per registry. Never raises: a box
        with no constructible engine at all is a degraded box, not a broken
        one, and the session falls back FOLLOW -> ASSOCIATE -> OFF (and, for
        motion/appearance, to no compensation/no appearance evidence at all).
        """
        if self._probed:
            return self.roster()
        self._probed = True
        for roster, role in (
            (self._associators, MODE_ASSOCIATE),
            (self._followers, MODE_FOLLOW),
            (self._compensators, _ROLE_MOTION),
            (self._appearances, _ROLE_APPEARANCE),
        ):
            for engine_id in list(roster):
                try:
                    roster[engine_id](max_age_frames=_PROBE_MAX_AGE_FRAMES)
                except Exception as exc:  # noqa: BLE001 - probing is the point
                    del roster[engine_id]
                    LOGGER.warning(
                        "tracker engine %r (%s) is not constructible on this host (%s); "
                        "removing it from the roster",
                        engine_id,
                        role,
                        exc,
                    )
        roster = self.roster()
        LOGGER.info(
            "cv-service tracker registry roster: %s (associate default=%r, follow default=%r, "
            "motion default=%r, appearance default=%r)",
            roster,
            self._default_associate_id,
            self._default_follow_id,
            self._default_motion_id,
            self._default_appearance_id,
        )
        return roster

    # -- construction -------------------------------------------------------

    def associator(
        self, engine_id: str, *, max_age_frames: int, level: Optional[int] = None
    ) -> Optional[tuple[str, Any]]:
        """A NEW `Associator` for this stream, or `None` if none can be built.

        `level` (TRACKING-V3-PLAN wave V1), when given, is a CEILING on the
        roster itself (`_ASSOCIATOR_MIN_LEVEL`), applied BEFORE `engine_id`/
        the default/the fallback ladder are even considered -- see
        `_create`'s own docstring for why filtering the roster first, rather
        than filtering the outcome, is what keeps a capped stream from ever
        attempting a factory it is not allowed to use. `None` (the default)
        means no ceiling -- every pre-V1 call site, and every test that
        never heard of levels, keeps working unchanged.
        """
        return self._create(
            self._associators,
            engine_id,
            self._default_associate_id,
            min_level=_ASSOCIATOR_MIN_LEVEL,
            level=level,
            max_age_frames=max_age_frames,
        )

    def follower(
        self, engine_id: str, *, max_age_frames: int, level: Optional[int] = None
    ) -> Optional[tuple[str, Any]]:
        """A NEW `SingleObjectTracker` for this stream, or `None`.

        Multi-target FOLLOW (TRACKING-V2-PLAN wave C5b) calls this more than
        once per stream -- once for the locked target, once more per "extra"
        target up to `TrackingParams.follow_top_k - 1` -- which is exactly
        what this method's own "a new instance every call, never shared"
        contract already promises; nothing here changed for it. See
        `session.py`'s `_extras_verify_observations`/`_ExtraFollow`.

        `level`: see `associator()`'s own docstring -- same ceiling shape,
        `_FOLLOWER_MIN_LEVEL` roster. L1 offers no follower at all (§5.2:
        FOLLOW needs local pixels, which is exactly what L1 does not have),
        so a level-1 stream always gets `None` here, which is precisely what
        drives `session.py`'s existing `FOLLOW -> ASSOCIATE` degrade ladder.
        """
        return self._create(
            self._followers,
            engine_id,
            self._default_follow_id,
            min_level=_FOLLOWER_MIN_LEVEL,
            level=level,
            max_age_frames=max_age_frames,
        )

    def compensator(self, engine_id: str, *, level: Optional[int] = None) -> Optional[tuple[str, Any]]:
        """A NEW `MotionCompensator` for this stream, or `None`.

        No `max_age_frames` -- that concept belongs to the association
        engines' lost-track bookkeeping and means nothing to a motion
        compensator, so this call, unlike its two siblings, passes no
        construction argument at all. `level`: see `associator()`'s own
        docstring, `_COMPENSATOR_MIN_LEVEL` roster.
        """
        return self._create(
            self._compensators,
            engine_id,
            self._default_motion_id,
            min_level=_COMPENSATOR_MIN_LEVEL,
            level=level,
        )

    def appearance(self, engine_id: str, *, level: Optional[int] = None) -> Optional[tuple[str, Any]]:
        """A NEW `AppearanceExtractor` for this stream, or `None`.

        Same no-extra-argument shape as `compensator()` -- an appearance
        extractor needs nothing from `TrackingParams` to construct; the cost
        WEIGHTS/GATES that decide how much its output counts parametrize
        `assign.CostAssociator` downstream (`session.py`'s `_run_cost_
        associate`), not this factory. `level`: see `associator()`'s own
        docstring, `_APPEARANCE_MIN_LEVEL` roster.
        """
        return self._create(
            self._appearances,
            engine_id,
            self._default_appearance_id,
            min_level=_APPEARANCE_MIN_LEVEL,
            level=level,
        )

    def _create(
        self,
        roster: dict[str, Callable[..., Any]],
        engine_id: str,
        default_id: str,
        *,
        min_level: Optional[dict[str, int]] = None,
        level: Optional[int] = None,
        **construct_kwargs: Any,
    ) -> Optional[tuple[str, Any]]:
        if level is not None and min_level is not None:
            # Filter FIRST, before `engine_id`/the default/the fallback
            # ladder are even looked at -- a candidate this level cannot
            # afford is not merely deprioritized, it is INVISIBLE, which is
            # what stops a capped stream from ever calling a factory it is
            # not allowed to use (TRACKING-V3-PLAN invariant P8/P9).
            roster = {
                candidate: factory
                for candidate, factory in roster.items()
                if min_level.get(candidate, levels.MIN_LEVEL) <= level
            }
        candidates = []
        if engine_id and engine_id in roster:
            candidates.append(engine_id)
        else:
            if engine_id:
                self._warn_unknown(engine_id, sorted(roster), default_id)
            if default_id in roster:
                candidates.append(default_id)
        # A registry whose default is itself absent still serves: any
        # surviving engine for this role beats degrading the stream.
        candidates.extend(sorted(set(roster) - set(candidates)))

        for candidate in candidates:
            try:
                return candidate, roster[candidate](**construct_kwargs)
            except Exception as exc:  # noqa: BLE001 - never a dead stream
                LOGGER.warning("tracker engine %r failed to construct (%s)", candidate, exc)
        return None

    def _warn_unknown(self, engine_id: str, known: list[str], default_id: str) -> None:
        if engine_id in self._warned_unknown_ids:
            return
        self._warned_unknown_ids.add(engine_id)
        LOGGER.info(
            "tracker engine_id=%r requested but not in this host's roster %s; using %r instead",
            engine_id,
            known,
            default_id,
        )


def build_default_registry(settings: Any, *, probe: bool = True) -> TrackerRegistry:
    """The production registry: the seven shipped engines (§5.B plus wave
    C3's `cost`/`histogram`), probed.

    Unlike `inference.registry.build_default_registry` this never returns
    `None`: a registry with an empty roster is still a usable object that
    degrades every stream to OFF, and returning `None` would just move the
    same branch into every caller.
    """
    registry = TrackerRegistry(
        associators=BUILTIN_ASSOCIATORS,
        followers=BUILTIN_FOLLOWERS,
        compensators=BUILTIN_COMPENSATORS,
        appearances=BUILTIN_APPEARANCES,
        default_associate_id=settings.track_associate_engine,
        default_follow_id=settings.track_follow_engine,
        default_motion_id=settings.track_motion_engine,
        default_appearance_id=settings.track_appearance_engine,
    )
    if probe:
        registry.probe()
    return registry
