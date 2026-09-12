"""`Aggregator` -- the fold, and the only writer of `Track`.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.3 / P4. `TrackBook.apply`
already WAS the fold; this class gives it the name and the boundary.
Contributors propose; this decides. Nothing outside this module calls
`TrackBook.apply`, mints an id, ages, settles or retires a track, and a
grep-enforced test says so.

**Two precisions carried verbatim from O1 (plan §4.3).**

* `apply` is called **at most once per frame** -- zero on the OFF/no-engine
  path and zero on a FOLLOW re-anchor failure with nothing held. That is
  today's call-site behaviour exactly, and anything else is a behavioural
  delta (O1 contradiction 7). This class enforces it: one `Proposal` per
  frame, and a proposal that says `fold=False` books nothing. An EMPTY
  observation list is NOT that case: both ASSOCIATE paths call `apply([])`
  on a frame the detector found nothing on, and must keep doing so, or
  nothing in the scene would ever age.
* The frame's own clock (`ctx.now`) is the one clock age and death are
  measured against, whatever cadence a contributor ran at (O1 Q20).

**Why a proposal carries a `settle` callback.** A FOLLOW verify pass has
real post-fold work -- binding the lock to the booked id, re-wrapping the
extra slots around the tracks the book just handed back, remembering a
target that went LOST. That work owns per-stream FOLLOW state, not track
state, so moving it in here would give this class a second responsibility
and move the lock/extras state with it. Instead the proposer supplies the
closure and this class calls it with `apply()`'s own return value: the book
is still written in exactly one place, and the mode-specific bookkeeping
stays with the mode that owns it. Recorded as a deviation from the plan's
"Orchestrator -> Aggregator" arrow in `cv/cv-service/MODULE.md`.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable, Mapping, Optional, Sequence

from cv_service.orchestration.budget import AGGREGATE
from cv_service.orchestration.contract import (
    OUTCOME_RAN,
    PHASE_TRACK,
    Contribution,
    FrameContext,
)
from cv_service.orchestration.keys import Key
from cv_service.tracking.engines.base import Descriptor, Observation
from cv_service.tracking.track import RecoveredIdentity, Track, TrackBook, observe_descriptor



@dataclass(frozen=True)
class Proposal:
    """What a terminal contributor asks the aggregator to book.

    `fold=False` means "book nothing this frame" -- `boxes` is then the
    response as-is. That is the engine-raised path, FOLLOW's nothing-held
    path and its failed-re-anchor-with-no-target path, and it is the whole
    mechanism behind "at most once".

    An EMPTY `observations` with `fold=True` is a different thing entirely
    and both ASSOCIATE paths rely on it: `apply([])` still ages every live
    track once, which is how a frame the detector found nothing on advances
    the misses that eventually retire a track. Collapsing the two would
    freeze a whole scene the moment detections stopped arriving.
    """

    observations: "tuple[Observation, ...]" = ()
    #: Whether to call `TrackBook.apply` at all this frame.
    fold: bool = True
    #: Positional against `observations`; empty when the path has no
    #: appearance evidence to EMA (every path but `cost` ASSOCIATE).
    descriptors: "tuple[Optional[Descriptor], ...]" = ()
    recoveries: "Optional[dict[object, RecoveredIdentity]]" = None
    captured_at: "Optional[dict[object, float]]" = None
    detector_ran: bool = True
    #: `tracks -> response boxes`. Required whenever `fold` is True;
    #: ignored otherwise.
    settle: "Optional[Callable[[list[Track]], list[Any]]]" = None
    #: The response when `fold` is False.
    boxes: "tuple[Any, ...]" = ()
    #: Free-form ledger facts about the proposal itself (which branch of the
    #: mode ran, how many candidates, and so on).
    summary: "Mapping[str, str]" = field(default_factory=dict)


class Aggregator:
    """Folds one frame's proposal into the book and emits the response boxes.

    Registered as the last contributor in the orchestrator's order (it reads
    every terminal key and writes none), so its cost lands inside the same
    `PHASE_TRACK` window `tracker_millis` has always measured.
    """

    id = "aggregate"
    family = AGGREGATE
    reads = frozenset({Key.OBSERVATIONS, Key.FOLLOW_OBS, Key.DETECTIONS, Key.LOCK})
    writes: "frozenset[Key]" = frozenset()
    min_level = 0
    phase = PHASE_TRACK

    def __init__(self, book: TrackBook, *, raw_boxes: "Callable[[Sequence[Any]], list[Any]]") -> None:
        self._book = book
        self._raw_boxes = raw_boxes
        self._boxes: "list[Any]" = []
        self._applied = False

    @property
    def book(self) -> TrackBook:
        return self._book

    @property
    def boxes(self) -> "list[Any]":
        """This frame's response boxes -- read by the session after the run."""
        return self._boxes

    @property
    def applied(self) -> bool:
        """Whether `TrackBook.apply` ran this frame. The invariant, readable."""
        return self._applied

    def contribute(self, ctx: FrameContext, budget: Any) -> Contribution:
        self._boxes = []
        self._applied = False

        proposal: Optional[Proposal] = ctx.get(Key.FOLLOW_OBS) or ctx.get(Key.OBSERVATIONS)
        if proposal is None:
            # No terminal contributor ran: tracking is OFF, degraded to OFF,
            # or no engine was constructible. Echo the detector's own boxes,
            # exactly `session.process()`'s pre-orchestration first branch.
            detections = ctx.get(Key.DETECTIONS) or []
            self._boxes = self._raw_boxes(detections)
            return Contribution(
                outcome=OUTCOME_RAN,
                reason="untracked",
                summary={"folded": "0", "boxes": str(len(self._boxes)), "path": "raw"},
            )

        if not proposal.fold:
            self._boxes = list(proposal.boxes)
            return Contribution(
                outcome=OUTCOME_RAN,
                reason="nothing booked",
                summary={
                    "folded": "0",
                    "boxes": str(len(self._boxes)),
                    **dict(proposal.summary),
                },
            )

        tracks = self._book.apply(
            list(proposal.observations),
            ctx.now,
            detector_ran=proposal.detector_ran,
            recoveries=proposal.recoveries,
            captured_at=proposal.captured_at,
        )
        self._applied = True
        for track, descriptor in zip(tracks, proposal.descriptors):
            observe_descriptor(track, descriptor)

        settle = proposal.settle
        self._boxes = list(settle(tracks)) if settle is not None else []
        return Contribution(
            outcome=OUTCOME_RAN,
            reason="",
            summary={
                "folded": str(len(tracks)),
                "boxes": str(len(self._boxes)),
                "live_tracks": str(len(self._book.tracks)),
                **dict(proposal.summary),
            },
            evidence=_lifecycle_evidence(tracks),
        )


def _lifecycle_evidence(tracks: "Sequence[Track]") -> "dict[int, dict[str, str]]":
    """Per-object birth/death facts, the free "why did this id die" the plan
    asks for (E4, R5 Q4): the SORT-style counters the aggregator already
    decides from, recorded rather than thrown away.

    The ELECTED label and its current challenger are included because label
    identity is decided inside `TrackBook._observe` (see `MODULE.md`'s
    deviation note) -- this is where that decision becomes visible.
    """
    evidence: "dict[int, dict[str, str]]" = {}
    for track in tracks:
        evidence[track.track_id] = {
            "state": track.state,
            "source": track.source,
            "hits": str(track.hits),
            "misses": str(track.misses),
            "age_frames": str(track.age_frames),
            "elected_label": track.elected_label,
            "raw_label": track.label,
            "reupdated": str(int(track.reupdated)),
        }
    return evidence
