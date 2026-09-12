"""The roster: which contributors this stream's configuration actually has.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.1. The orchestrator orders a
roster; this decides what is IN one. The two are separate on purpose --
ordering is a property of the declarations and nothing else, while
membership is a property of mode, engine and the level that resolved.

**Registered, not merely eligible.** A contributor is registered when this
configuration COULD use it, and the budget then refuses it per frame with a
reason (`detect.full` on an off-duty frame, `appearance` when the engine id
is `off`, `detect.roi` when the rescue is disabled). A contributor the
configuration can never use is not registered at all: a FOLLOW stream does
not grow a `detect.roi` row saying "cost associator only" on every frame of
its life, because that is noise, not evidence.

**Exclusivity is structural.** Only one contributor may ever write
`Key.OBSERVATIONS` in a build -- CV-ORCHESTRATION wave W4 (decision E16)
retired `assoc.bytetrack`, the second one that used to exist, leaving
`propose.cost` the sole writer; a future associator that also wrote it would
be refused at build time by §4.1 rule 2, the one-writer rule catching a
roster mistake rather than a reviewer having to. Likewise FOLLOW writes
`Key.FOLLOW_OBS` and never `OBSERVATIONS`, so the aggregator's precedence
between them is stated rather than relied on.

**When the roster is rebuilt.** `signature()` is the tuple that decides
membership and the ids inside it. Engines resolve LAZILY -- the first active
frame is what turns `engine_id` from `""` into `cost` -- so the roster is
rebuilt on a superset of `apply_config` moments. Recorded in
`cv/cv-service/MODULE.md` as a deviation from the plan, which assumed
registration could be a config-time event.
"""

from __future__ import annotations

from typing import Any, Callable, Optional

from cv_service.orchestration.aggregator import Aggregator
from cv_service.orchestration.contributors.appearance import Appearance
from cv_service.orchestration.contributors.associate import AssociateCost, ProposeCost
from cv_service.orchestration.contributors.detect import DetectFull
from cv_service.orchestration.contributors.egomotion import EgoMotion
from cv_service.orchestration.contributors.follow import Follow
from cv_service.orchestration.contributors.memory import MemoryGallery
from cv_service.orchestration.contributors.predict import PredictConstantVelocity
from cv_service.orchestration.contributors.roi import RoiRescue
from cv_service.orchestration.detector import DetectorClient
from cv_service.orchestration.engines import EngineSet
from cv_service.orchestration.state import StreamState
from cv_service.tracking.assign import CostAssociator
from cv_service.tracking.lock import LockArbiter
from cv_service.tracking.params import MODE_ASSOCIATE, MODE_FOLLOW
from cv_service.tracking.track import TrackBook

__all__ = ["signature", "roster"]


def signature(engines: EngineSet) -> "tuple[str, ...]":
    """What the roster's membership and contributor ids depend on.

    The engine id is read as `""` until one has actually been built, so a
    session that has not yet resolved anything gets the untracked roster and
    the frame that resolves one gets its own, correct set.
    """
    params = engines.params
    return (
        params.mode,
        engines.engine_id if engines.engine is not None else "",
        params.motion_engine_id,
        params.appearance_engine_id,
    )


def roster(
    *,
    engines: EngineSet,
    book: TrackBook,
    lock: LockArbiter,
    state: StreamState,
    client: DetectorClient,
    registry_provider: "Callable[[], Optional[Any]]",
    aggregator: Aggregator,
) -> list:
    """This configuration's contributors, in registration order.

    Registration order is only a TIE-BREAK: the declarations decide the rest
    (`Orchestrator`), and the two phases decide which side of the
    `tracker_millis` window each lands on. It is written in run order anyway,
    so the file reads the way the frame runs.
    """
    params = engines.params
    # Always: the detector's decision is the scheduler's alone, and it is
    # taken on the OFF path too -- `process()` has always called `detect()`
    # before ever looking at the mode.
    built: list = [DetectFull(client, state)]

    engine = engines.engine
    if engine is not None and params.mode == MODE_ASSOCIATE:
        if engines.engine_id == CostAssociator.engine_id:
            built.extend(
                [
                    EgoMotion(engines, book, state),
                    PredictConstantVelocity(book),
                    Appearance(engines),
                    AssociateCost(engines, book, state),
                    RoiRescue(engines, client, state),
                    MemoryGallery(engines),
                    ProposeCost(),
                ]
            )
        # Else: nothing is registered. `cost` is the ONLY associator
        # `registry.py`'s roster can build since CV-ORCHESTRATION W4
        # (decision E16) retired `bytetrack` -- `params.py#resolve()` even
        # aliases a wire/env `"bytetrack"` to `"cost"` before it gets here --
        # so `engines.engine_id` reaching this branch is dead code against
        # the real registry. It stays reachable against a `registry_provider`
        # a test (or a future host) substitutes with a roster of its own,
        # and registering nothing for it is still CORRECT, not a gap: no
        # contributor then writes `Key.OBSERVATIONS`/`Key.FOLLOW_OBS`, and
        # `Aggregator.contribute`'s own "no terminal contributor ran" branch
        # already echoes the detector's raw boxes and books nothing -- the
        # same honest outcome the removed `AssociateByteTrack` crash path
        # produced on a raise, with no special case needed here to get it.
    elif engine is not None and params.mode == MODE_FOLLOW:
        built.extend(
            [
                EgoMotion(engines, book, state),
                Follow(engines, book, lock, state, registry_provider),
            ]
        )

    # Always last: it reads every terminal key and writes none, so its cost
    # lands inside the same window `tracker_millis` has always measured.
    built.append(aggregator)
    return built
