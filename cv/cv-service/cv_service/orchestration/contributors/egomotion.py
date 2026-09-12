"""`egomotion.<engine>` -- this frame of camera motion, and the warp it drives.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.1. The estimate moved here
verbatim from `StreamTrackingSession._estimate_motion`; the `TrackBook.warp`
call that consumes it moved with it, because the two are one responsibility
and splitting them is how the original defect happened (warping only the box
a `predict()` call happened to READ, once, with whatever transform that frame
had -- `TrackBook.warp`'s own docstring carries the measurement).

**This contributor is `PHASE_PRE`, and that is load-bearing.** R3's
"surprise 4" says `tracker_millis` spans ego-motion; it does not.
`session.process()` started its timer AFTER `_estimate_motion` and AFTER
`book.warp`, and ego-motion has always reported its own `motion_millis` wire
field. Putting this in `PHASE_TRACK` would silently start charging the
tracker for it.

**The id is the REQUESTED engine, the summary is the SERVED one.** `pose`
falls back to `flow` at runtime when a stream carries no usable
`CameraPose`, and that fallback can flip frame to frame -- a contributor id
that changed with it would make the ledger's roster unstable, so the
resolved id is reported as a ledger fact instead.
"""

from __future__ import annotations

import logging
from time import perf_counter
from typing import Any

from cv_service.orchestration.budget import EGOMOTION
from cv_service.orchestration.contract import PHASE_PRE, Contribution, FrameContext
from cv_service.orchestration.engines import EngineSet
from cv_service.orchestration.keys import Key
from cv_service.orchestration.state import StreamState
from cv_service.tracking.engines.base import IDENTITY, CameraPose, Transform
from cv_service.tracking.track import TrackBook

LOGGER = logging.getLogger("cv_service.tracking.session")


class EgoMotion:
    """Estimate the camera's own motion, then carry every live track through it."""

    family = EGOMOTION
    reads = frozenset({Key.FRAME, Key.POSE})
    writes = frozenset({Key.TRANSFORM})
    min_level = 0
    phase = PHASE_PRE

    def __init__(self, engines: EngineSet, book: TrackBook, state: StreamState) -> None:
        self._engines = engines
        self._book = book
        self._state = state
        self.id = f"egomotion.{engines.params.motion_engine_id}"

    def contribute(self, ctx: FrameContext, budget: Any) -> Contribution:
        pose: CameraPose = ctx.get(Key.POSE) or CameraPose()
        frame = ctx.get(Key.FRAME)
        transform, motion_millis, motion_engine_id = self._estimate_motion(pose, frame)
        self._state.motion_millis = motion_millis
        self._state.motion_engine_id = motion_engine_id

        # Warp every LIVE track's stored state through this frame's transform,
        # BEFORE anything reads a track box this frame -- prediction, the
        # re-anchor test, a lock-by-id lookup, coasting. A no-op for IDENTITY,
        # which is what makes an unresolvable compensator bit-for-bit
        # indistinguishable from this contributor never running at all.
        self._book.warp(transform)

        return Contribution(
            outputs={Key.TRANSFORM: transform},
            reason="" if motion_engine_id else "nothing constructible",
            summary={
                "served": motion_engine_id,
                "identity": str(int(transform.identity)),
            },
            # Its own measurement: `motion_millis` is a wire field.
            cost_ms=float(motion_millis),
        )

    def _estimate_motion(self, pose: CameraPose, frame: Any) -> "tuple[Transform, int, str]":
        """This frame's camera-motion transform, timed, plus who served it.

        Resolving a compensator is cheap (no pixels touched yet), but actually
        running `flow` needs a decoded frame. For FOLLOW that frame decode is
        free: `frame()` is the servicer's MEMOIZED loader and the SOT engine
        already needs pixels this frame. For ASSOCIATE with `cost` it is a
        genuine per-frame cost, paid deliberately, only for the one engine
        that can use the result, and reused (not re-decoded) by the appearance
        contributor's own `frame()` call in the same frame. `pose` engines
        ignore `frame` entirely but are handed it anyway -- harmless, since it
        is the same memoized call either way.
        """
        engine = self._engines.resolve_motion_compensator(pose)
        if engine is None:
            return IDENTITY, 0, ""
        started = perf_counter()
        try:
            transform = engine.estimate(frame(), pose)
        except Exception as exc:  # noqa: BLE001 - a bad estimate costs accuracy, never the stream
            LOGGER.warning(
                "motion compensator %r raised (%s); this frame runs uncompensated",
                self._engines.motion_engine_id,
                exc,
            )
            self._engines.forget_motion_engine()
            return IDENTITY, int(round((perf_counter() - started) * 1000.0)), ""
        motion_millis = int(round((perf_counter() - started) * 1000.0))
        return transform, motion_millis, self._engines.motion_engine_id
