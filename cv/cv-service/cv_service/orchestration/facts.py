"""What `Inspect` asks, expressed as plain values (plan §4.4).

The ledger answers "what happened on frame N"; these two answer the questions
that are not about any one frame -- "what is this session configured as right
now" and "what is this process doing at all". Both are read from LIVE state by
a gRPC thread that is not the one processing frames, which is why they are
frozen snapshots: the caller gets a consistent set of numbers, not a handle it
could keep reading while the tracker mutates underneath it.

**Tier: COLD.** Built only when somebody calls `Inspect`. Nothing here is
touched on the per-frame path.

Pure stdlib, no `cv_pb2` -- `grpc/servicers.py` is the sole translator to the
wire, the same rule `FrameOutcome` and `FrameLedger` follow.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Optional


@dataclass(frozen=True)
class SessionFacts:
    """One live tracking session's configuration and current scene size.

    `engine_id` is what is actually SERVING, which is not always what was
    requested: the degradation ladder (`EngineSet.degrade_to`) rewrites the
    mode and the engine underneath a request that could not be honoured, and
    this is the field that tells an operator it happened.

    `contributors` is empty until the session has run its first frame --
    engines resolve lazily, so a session that has never been asked for a
    frame genuinely has no roster yet, and inventing one here would be a
    prettier answer than the true one.
    """

    stream_id: str = ""
    mode: str = ""
    engine_id: str = ""
    motion_engine_id: str = ""
    appearance_engine_id: str = ""
    level_served: int = 0
    level_reason: str = ""
    live_tracks: int = 0
    dormant_identities: int = 0
    locked_track_id: int = 0
    frames_processed: int = 0
    contributors: "tuple[str, ...]" = ()


@dataclass(frozen=True)
class ProcessFacts:
    """What the whole worker is doing -- the answer to "is it even me?".

    `detector_client` names the seam `orchestration/detector.py` currently
    provides (`local` in W0; the detector-role pool is W4), so the first
    question a distributed deployment raises is answerable before the
    feature that raises it ships.
    """

    detector_client: str = ""
    sessions: int = 0
    gate_permits: int = 0
    ledger_ring: int = 0
    stream_ids: "tuple[str, ...]" = field(default_factory=tuple)


def session_facts(
    *,
    stream_id: str,
    params: Any,
    engines: Any,
    book: Any,
    lock: Any,
    sequence: int,
    built: Optional[Any],
) -> SessionFacts:
    """Read one session's facts without disturbing it.

    Lives here rather than on `StreamTrackingSession` so the shape and the
    reading of it stay in one place -- and so the rule that makes it SAFE is
    stated once: `built` is the roster the session ALREADY has, never one
    this call would build, and every engine field is read, never resolved.
    An `Inspect` call arrives on a different gRPC thread than the one running
    frames; a debug read that resolved an engine would be mutating a session
    in order to describe it.
    """
    memory = engines.memory
    return SessionFacts(
        stream_id=stream_id,
        mode=params.mode,
        engine_id=engines.engine_id,
        motion_engine_id=engines.motion_engine_id,
        appearance_engine_id=engines.appearance_engine_id,
        level_served=engines.capability_level_served,
        level_reason=engines.capability_level_reason,
        live_tracks=len(book.tracks),
        dormant_identities=memory.size() if memory is not None else 0,
        locked_track_id=lock.bound_track_id or 0,
        frames_processed=sequence,
        contributors=tuple(row[0] for row in built.declarations()) if built else (),
    )


__all__ = ["ProcessFacts", "SessionFacts", "session_facts"]
