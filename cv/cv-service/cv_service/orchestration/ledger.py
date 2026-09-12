"""The per-frame ledger -- evidence as a first-class artifact (plan P3/§4.4).

Every contribution is recorded: who, ran/skipped/failed, why, what it cost,
what it claimed about each object. The ledger is the debug surface, the
replay fixture and the audit trail; nothing here is "visible only in logs",
which is what keeps the charter's "no per-frame INFO" true while still
answering every "why" question in the plan's §1.5 table.

**Tier: HOT.** A bounded ring per session (`CV_LEDGER_RING`, default 64),
read by the `Inspect` RPC. A few KB per stream, no I/O, and nothing is
serialised unless somebody actually asks -- `grpc/servicers.py` is the only
module that turns these dataclasses into proto, exactly as it is the only
module that turns `FrameOutcome` into `DetectionResponse`.

Pure stdlib; no `cv_pb2` import, by the same rule the rest of the tracking
code follows.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field
from typing import Iterable, Mapping, Optional


@dataclass(frozen=True)
class ObjectEvidence:
    """One contributor's algorithm-shaped claim about one object.

    Predicted box, per-term association cost, memory match distance, label
    vote tally. These live HERE and never on the object mirror (plan E7):
    the mirror describes the object, the ledger describes the algorithm.
    """

    contributor_id: str
    claim: "Mapping[str, str]"


@dataclass(frozen=True)
class LedgerEntry:
    """One contributor's turn at one frame."""

    contributor_id: str
    outcome: str
    reason: str = ""
    cost_ms: float = 0.0
    summary: "Mapping[str, str]" = field(default_factory=dict)


@dataclass
class FrameLedger:
    """Every contribution to one frame, plus the budget that shaped it.

    Mutable while the frame runs (the orchestrator appends, the aggregator
    finishes it), then parked in the ring and never touched again.
    """

    stream_id: str
    sequence: int
    captured_at_millis: float
    level_served: int = 0
    detector_reason: str = ""
    eligible: "tuple[str, ...]" = ()
    entries: "list[LedgerEntry]" = field(default_factory=list)
    objects: "dict[int, list[ObjectEvidence]]" = field(default_factory=dict)
    #: Frames the mailbox dropped since the previous ledger -- push mode's
    #: own `LatestOnlyMailbox.dropped` delta (plan §7 D5).
    drops_since_last: int = 0
    #: Milliseconds spent blocked on `InferenceGate.acquire()` this frame.
    gate_wait_ms: float = 0.0
    total_ms: float = 0.0
    #: True when a contributor halted the frame (no model resolved -> echo).
    halted: bool = False

    def record(self, entry: LedgerEntry) -> None:
        self.entries.append(entry)

    def claim(self, track_id: int, evidence: ObjectEvidence) -> None:
        self.objects.setdefault(track_id, []).append(evidence)

    def entry_for(self, contributor_id: str) -> Optional[LedgerEntry]:
        """The one entry for this contributor, or `None` -- tests and
        `Inspect` both ask this question and neither should scan by hand."""
        for entry in self.entries:
            if entry.contributor_id == contributor_id:
                return entry
        return None


class LedgerRing:
    """The last N frames' ledgers for one session, oldest evicted first.

    Bounded by construction (`deque(maxlen=...)`), the same discipline
    `history.ObservationRing` already uses: a debug surface that can grow
    without bound is a memory leak with a nice name.
    """

    __slots__ = ("_entries",)

    def __init__(self, capacity: int) -> None:
        self._entries: "deque[FrameLedger]" = deque(maxlen=max(1, int(capacity)))

    def append(self, ledger: FrameLedger) -> None:
        self._entries.append(ledger)

    def last(self, count: int) -> "list[FrameLedger]":
        """The most recent `count` ledgers, oldest first. All of them when
        `count <= 0` -- the wire's own "unset means everything" sentinel."""
        if count <= 0 or count >= len(self._entries):
            return list(self._entries)
        return list(self._entries)[-count:]

    def __len__(self) -> int:
        return len(self._entries)

    def __iter__(self) -> "Iterable[FrameLedger]":
        return iter(self._entries)
