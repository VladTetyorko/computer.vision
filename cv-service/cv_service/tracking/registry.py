"""`TrackerRegistry`: two rosters of engine **factories**, probed at startup.

`docs/TRACKING-PLAN.md` §5.A, `docs/TRACKING-ORCHESTRATION.md` §2.1.
Deliberately shaped like `cv_service/inference/registry.py`'s `ModelRegistry`
-- lazy construction, unknown ids logged once and served the default, the
roster logged once at INFO so an operator can see what is routable without
reading code -- with **one deliberate inversion**:

> `ModelRegistry` caches ONE detector per id, because a YOLO model is
> stateless across streams and expensive to load. `TrackerRegistry` hands out
> a NEW engine per call, because a tracker is the reverse: cheap to build and
> inherently stateful. Getting this backwards is the single easiest way to
> corrupt every stream at once, which is why it has its own test.

**Two rosters, because there are two protocols** (`Associator` for
ASSOCIATE, `SingleObjectTracker` for FOLLOW). An engine that legitimately
served both modes would appear in both; none of the three shipped ones does,
which is precisely the interface-segregation point of §2.2 and why
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


def _bytetrack(*, max_age_frames: int) -> Any:
    from cv_service.tracking.engines.bytetrack import create

    return create(max_age_frames=max_age_frames)


def _lk(**_kwargs: Any) -> Any:
    from cv_service.tracking.engines.lk import create

    return create()


def _ncc(**_kwargs: Any) -> Any:
    from cv_service.tracking.engines.ncc import create

    return create()


BUILTIN_ASSOCIATORS: dict[str, AssociatorFactory] = {"bytetrack": _bytetrack}
BUILTIN_FOLLOWERS: dict[str, FollowerFactory] = {"lk": _lk, "ncc": _ncc}

# Probe-time construction arguments. Any positive value works -- the probe
# only asks "can this be built on this box at all", and the real per-stream
# construction passes the stream's own resolved `TrackingParams`.
_PROBE_MAX_AGE_FRAMES = 1


class TrackerRegistry:
    """`{engine_id -> factory}` per mode, with a startup constructibility probe."""

    def __init__(
        self,
        *,
        associators: dict[str, AssociatorFactory],
        followers: dict[str, FollowerFactory],
        default_associate_id: str,
        default_follow_id: str,
    ) -> None:
        self._associators = dict(associators)
        self._followers = dict(followers)
        self._default_associate_id = default_associate_id
        self._default_follow_id = default_follow_id
        self._warned_unknown_ids: set[str] = set()
        self._probed = False

    # -- roster -------------------------------------------------------------

    @property
    def default_associate_id(self) -> str:
        return self._default_associate_id

    @property
    def default_follow_id(self) -> str:
        return self._default_follow_id

    def roster(self) -> dict[str, list[str]]:
        """`{engine_id -> [modes it serves]}`, sorted -- diagnostics and the log line."""
        modes: dict[str, list[str]] = {}
        for engine_id in self._associators:
            modes.setdefault(engine_id, []).append(MODE_ASSOCIATE)
        for engine_id in self._followers:
            modes.setdefault(engine_id, []).append(MODE_FOLLOW)
        return {engine_id: modes[engine_id] for engine_id in sorted(modes)}

    def probe(self) -> dict[str, list[str]]:
        """Construct every engine once; drop what raises; log the real roster.

        Idempotent -- runs at most once per registry. Never raises: a box
        with no constructible engine at all is a degraded box, not a broken
        one, and the session falls back FOLLOW -> ASSOCIATE -> OFF.
        """
        if self._probed:
            return self.roster()
        self._probed = True
        for roster, mode in ((self._associators, MODE_ASSOCIATE), (self._followers, MODE_FOLLOW)):
            for engine_id in list(roster):
                try:
                    roster[engine_id](max_age_frames=_PROBE_MAX_AGE_FRAMES)
                except Exception as exc:  # noqa: BLE001 - probing is the point
                    del roster[engine_id]
                    LOGGER.warning(
                        "tracker engine %r (%s) is not constructible on this host (%s); "
                        "removing it from the roster",
                        engine_id,
                        mode,
                        exc,
                    )
        roster = self.roster()
        LOGGER.info(
            "cv-service tracker registry roster: %s (associate default=%r, follow default=%r)",
            roster,
            self._default_associate_id,
            self._default_follow_id,
        )
        return roster

    # -- construction -------------------------------------------------------

    def associator(self, engine_id: str, *, max_age_frames: int) -> Optional[tuple[str, Any]]:
        """A NEW `Associator` for this stream, or `None` if none can be built."""
        return self._create(
            self._associators, engine_id, self._default_associate_id, max_age_frames
        )

    def follower(self, engine_id: str, *, max_age_frames: int) -> Optional[tuple[str, Any]]:
        """A NEW `SingleObjectTracker` for this stream, or `None`."""
        return self._create(self._followers, engine_id, self._default_follow_id, max_age_frames)

    def _create(
        self,
        roster: dict[str, Callable[..., Any]],
        engine_id: str,
        default_id: str,
        max_age_frames: int,
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
        # surviving engine for this mode beats degrading the stream.
        candidates.extend(sorted(set(roster) - set(candidates)))

        for candidate in candidates:
            try:
                return candidate, roster[candidate](max_age_frames=max_age_frames)
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
    """The production registry: the three shipped engines of §5.B, probed.

    Unlike `inference.registry.build_default_registry` this never returns
    `None`: a registry with an empty roster is still a usable object that
    degrades every stream to OFF, and returning `None` would just move the
    same branch into every caller.
    """
    registry = TrackerRegistry(
        associators=BUILTIN_ASSOCIATORS,
        followers=BUILTIN_FOLLOWERS,
        default_associate_id=settings.track_associate_engine,
        default_follow_id=settings.track_follow_engine,
    )
    if probe:
        registry.probe()
    return registry
