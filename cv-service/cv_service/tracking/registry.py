"""`TrackerRegistry`: three rosters of engine **factories**, probed at startup.

`docs/plans/done/TRACKING-PLAN.md` §5.A, `docs/extracts/TRACKING-ORCHESTRATION.md` §2.1,
`docs/plans/active/TRACKING-V2-PLAN.md` §3 (wave C2 adds the third roster).
Deliberately shaped like `cv_service/inference/registry.py`'s `ModelRegistry`
-- lazy construction, unknown ids logged once and served the default, the
roster logged once at INFO so an operator can see what is routable without
reading code -- with **one deliberate inversion**:

> `ModelRegistry` caches ONE detector per id, because a YOLO model is
> stateless across streams and expensive to load. `TrackerRegistry` hands out
> a NEW engine per call, because a tracker is the reverse: cheap to build and
> inherently stateful. Getting this backwards is the single easiest way to
> corrupt every stream at once, which is why it has its own test.

**Three rosters, because there are three protocols** (`Associator` for
ASSOCIATE, `SingleObjectTracker` for FOLLOW, `MotionCompensator` for
ego-motion). An engine that legitimately served more than one would appear
in more than one roster; none of the five shipped ones does, which is
precisely the interface-segregation point of §2.2 and why
`GET /api/cv/trackers` returns a `modes: []` array per engine.

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

from cv_service.tracking.params import MODE_ASSOCIATE, MODE_FOLLOW

LOGGER = logging.getLogger("cv_service.tracking.registry")

AssociatorFactory = Callable[..., Any]
FollowerFactory = Callable[..., Any]
MotionCompensatorFactory = Callable[..., Any]

# Roster tag for the probe/log-line diagnostics (`roster()`), parallel to
# `MODE_ASSOCIATE`/`MODE_FOLLOW` -- a motion compensator is not tied to a
# `TrackingMode`, so it gets its own label rather than a borrowed one.
_ROLE_MOTION = "MOTION_COMPENSATOR"

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


BUILTIN_ASSOCIATORS: dict[str, AssociatorFactory] = {"bytetrack": _bytetrack}
BUILTIN_FOLLOWERS: dict[str, FollowerFactory] = {"lk": _lk, "ncc": _ncc}
BUILTIN_COMPENSATORS: dict[str, MotionCompensatorFactory] = {
    MOTION_ENGINE_FLOW: _flow,
    MOTION_ENGINE_POSE: _pose,
}

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
        default_associate_id: str,
        default_follow_id: str,
        default_motion_id: str,
    ) -> None:
        self._associators = dict(associators)
        self._followers = dict(followers)
        self._compensators = dict(compensators)
        self._default_associate_id = default_associate_id
        self._default_follow_id = default_follow_id
        self._default_motion_id = default_motion_id
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

    def roster(self) -> dict[str, list[str]]:
        """`{engine_id -> [roles it serves]}`, sorted -- diagnostics and the log line."""
        roles: dict[str, list[str]] = {}
        for engine_id in self._associators:
            roles.setdefault(engine_id, []).append(MODE_ASSOCIATE)
        for engine_id in self._followers:
            roles.setdefault(engine_id, []).append(MODE_FOLLOW)
        for engine_id in self._compensators:
            roles.setdefault(engine_id, []).append(_ROLE_MOTION)
        return {engine_id: roles[engine_id] for engine_id in sorted(roles)}

    def probe(self) -> dict[str, list[str]]:
        """Construct every engine once; drop what raises; log the real roster.

        Idempotent -- runs at most once per registry. Never raises: a box
        with no constructible engine at all is a degraded box, not a broken
        one, and the session falls back FOLLOW -> ASSOCIATE -> OFF (and, for
        motion, to no compensation at all).
        """
        if self._probed:
            return self.roster()
        self._probed = True
        for roster, role in (
            (self._associators, MODE_ASSOCIATE),
            (self._followers, MODE_FOLLOW),
            (self._compensators, _ROLE_MOTION),
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
            "motion default=%r)",
            roster,
            self._default_associate_id,
            self._default_follow_id,
            self._default_motion_id,
        )
        return roster

    # -- construction -------------------------------------------------------

    def associator(self, engine_id: str, *, max_age_frames: int) -> Optional[tuple[str, Any]]:
        """A NEW `Associator` for this stream, or `None` if none can be built."""
        return self._create(
            self._associators, engine_id, self._default_associate_id, max_age_frames=max_age_frames
        )

    def follower(self, engine_id: str, *, max_age_frames: int) -> Optional[tuple[str, Any]]:
        """A NEW `SingleObjectTracker` for this stream, or `None`."""
        return self._create(
            self._followers, engine_id, self._default_follow_id, max_age_frames=max_age_frames
        )

    def compensator(self, engine_id: str) -> Optional[tuple[str, Any]]:
        """A NEW `MotionCompensator` for this stream, or `None`.

        No `max_age_frames` -- that concept belongs to the association
        engines' lost-track bookkeeping and means nothing to a motion
        compensator, so this call, unlike its two siblings, passes no
        construction argument at all.
        """
        return self._create(self._compensators, engine_id, self._default_motion_id)

    def _create(
        self,
        roster: dict[str, Callable[..., Any]],
        engine_id: str,
        default_id: str,
        **construct_kwargs: Any,
    ) -> Optional[tuple[str, Any]]:
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
    """The production registry: the five shipped engines of §5.B, probed.

    Unlike `inference.registry.build_default_registry` this never returns
    `None`: a registry with an empty roster is still a usable object that
    degrades every stream to OFF, and returning `None` would just move the
    same branch into every caller.
    """
    registry = TrackerRegistry(
        associators=BUILTIN_ASSOCIATORS,
        followers=BUILTIN_FOLLOWERS,
        compensators=BUILTIN_COMPENSATORS,
        default_associate_id=settings.track_associate_engine,
        default_follow_id=settings.track_follow_engine,
        default_motion_id=settings.track_motion_engine,
    )
    if probe:
        registry.probe()
    return registry
