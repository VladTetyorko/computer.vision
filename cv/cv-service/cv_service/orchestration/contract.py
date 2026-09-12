"""The contributor contract: `Contributor`, `Contribution`, `FrameContext`.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.1. A contributor is the
adapter between the blackboard and an evidence engine. The four engine
Protocols in `cv_service/tracking/engines/base.py` stay untouched as the
pixel seam -- contributors WRAP them, they never replace them.

**`contribute` never raises.** Every implementation here is written that
way, and `Orchestrator` wraps the call anyway: a raise becomes a
`FAILED:<reason>` ledger entry and the frame continues with the key absent.
That is what makes degradation per-part instead of per-stream.

Pure stdlib, like everything else under `cv_service/tracking/` -- this
package must stay importable with no `cv` extra at all.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Mapping, Optional, Protocol, runtime_checkable

from cv_service.orchestration.keys import Key

#: `Contribution.outcome` / `LedgerEntry.outcome`. The value strings match
#: the proto `LedgerOutcome` enum's own value names, the same
#: name-string-across-the-boundary convention `scheduler.REASON_*` and
#: `track.STATE_*` already use (`grpc/servicers.py` is the only translator).
OUTCOME_RAN = "LEDGER_OUTCOME_RAN"
OUTCOME_SKIPPED = "LEDGER_OUTCOME_SKIPPED"
OUTCOME_FAILED = "LEDGER_OUTCOME_FAILED"


#: Which perf_counter span a contributor's cost falls inside. `TRACK` is
#: exactly today's `tracker_millis` window -- the mode branch, i.e. frame
#: decode for appearance, association, the ROI pass and the fold. `PRE` is
#: everything before that timer starts: the detector pass (its own
#: `inference_millis`) and ego-motion (its own `motion_millis`). Keeping the
#: split explicit is what lets the wire's three cost numbers keep meaning
#: exactly what they meant before this package existed.
PHASE_PRE = "pre"
PHASE_TRACK = "track"


@dataclass(frozen=True)
class Contribution:
    """What one contributor did to one frame.

    `outputs` are written to the blackboard by the orchestrator, never by
    the contributor itself -- which is how "a key has exactly one writer"
    stays checkable: a contributor can only ever write the keys it declared.

    `cost_ms` is normally `None`, meaning "the orchestrator's own timer is
    the honest number". A contributor sets it only when the value that
    reaches the WIRE is its own measurement (`egomotion`'s `motion_millis`,
    `detect.*`'s `inference_millis`) and must not be re-derived.

    `evidence` is `{track_id: {claim -> value}}`: algorithm-shaped facts
    (predicted box, per-term association cost, memory distance, label votes)
    that belong in the ledger and NEVER on the object mirror (plan E7).

    `halt` stops the frame: the one user today is `detect.full` resolving no
    model at all, which the servicer degrades to an echo response.
    """

    outputs: "Mapping[Key, Any]" = field(default_factory=dict)
    outcome: str = OUTCOME_RAN
    reason: str = ""
    summary: "Mapping[str, str]" = field(default_factory=dict)
    evidence: "Mapping[int, Mapping[str, str]]" = field(default_factory=dict)
    cost_ms: Optional[float] = None
    halt: bool = False


def skipped(reason: str) -> Contribution:
    """`SKIPPED:<reason>` with nothing written -- the common early return."""
    return Contribution(outcome=OUTCOME_SKIPPED, reason=reason)


@runtime_checkable
class Contributor(Protocol):
    """One owner of one responsibility, declaring what it reads and writes.

    `min_level` is the capability ladder floor (`tracking/levels.py`) this
    contributor needs; `0` means "any level". The orchestrator does not
    enforce it -- `TrackerRegistry`'s own level-filtered roster already
    refuses to build an engine below its floor, and duplicating that check
    here would give two owners to one rule. It is declared so the ledger and
    `Inspect` can say WHY a contributor is absent from a low-level session.
    """

    id: str
    #: The budget's eligibility key (`budget.DETECT_FULL` and siblings). Several
    #: contributors share one family when only one of them can ever be
    #: registered -- `assoc.cost`/`assoc.bytetrack`, `follow.lk`/`follow.ncc`,
    #: `egomotion.flow`/`egomotion.pose` -- so the eligibility table stays one
    #: row per RESPONSIBILITY rather than one per engine.
    family: str
    reads: "frozenset[Key]"
    writes: "frozenset[Key]"
    min_level: int
    phase: str

    def contribute(self, ctx: "FrameContext", budget: Any) -> Contribution:
        """Run this frame. Must not raise (the orchestrator wraps it anyway)."""
        ...


class FrameContext:
    """The per-frame blackboard, plus the frame's given facts.

    One instance per `process()` call. `get`/`put` are the only access;
    `put` records WHO wrote each key so a ledger reader can answer "where
    did this value come from" without consulting the registration table.
    """

    __slots__ = (
        "stream_id",
        "sequence",
        "now",
        "now_millis",
        "level_served",
        "params",
        "lag_millis",
        "lag_seconds",
        "_values",
        "_writers",
    )

    def __init__(
        self,
        *,
        stream_id: str,
        sequence: int,
        now_millis: float,
        level_served: int,
        params: Any,
        lag_millis: int,
    ) -> None:
        self.stream_id = stream_id
        self.sequence = sequence
        self.now_millis = now_millis
        #: Seconds -- the one clock the aggregator ages and retires against
        #: (plan §4.3 / O1 Q20), whatever cadence a contributor ran at.
        self.now = now_millis / 1000.0
        self.level_served = level_served
        self.params = params
        self.lag_millis = lag_millis
        self.lag_seconds = lag_millis / 1000.0
        self._values: "dict[Key, Any]" = {}
        self._writers: "dict[Key, str]" = {}

    def seed(self, key: Key, value: Any) -> None:
        """Put a given fact of the frame on the board (see `keys.SEEDED`)."""
        self._values[key] = value
        self._writers[key] = "session"

    def put(self, key: Key, value: Any, *, by: str) -> None:
        self._values[key] = value
        self._writers[key] = by

    def get(self, key: Key, default: Any = None) -> Any:
        return self._values.get(key, default)

    def has(self, key: Key) -> bool:
        return key in self._values

    def writer_of(self, key: Key) -> Optional[str]:
        return self._writers.get(key)
