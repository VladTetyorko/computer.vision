"""`memory.gallery` -- ask the dormant gallery about everything still unexplained.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.2, TRACKING-V2-PLAN wave C4.
Lifted out of `_run_cost_associate`'s unmatched-target loop with one
deliberate change of shape and none of behaviour: the ANSWER is now a value
(`Key.RECOVERIES`), computed before anything is booked, and `propose.cost`
files it against the key the observation is booked under.

**Why asking is a node of its own.** The gallery is the only contributor that
can hand an operator back a number they already recognise, and until now the
fact that it ran at all was invisible -- it left no trace unless it happened
to hit. A row that says "asked 3, recovered 0, gallery empty" is the
difference between "recovery is broken" and "there was nothing to recover",
and that question has been asked of this code more than once.

**The claim order is the behaviour, not an implementation detail.** This runs
AFTER `detect.roi` because `ObjectMemory.claim()` pops an identity exactly
once and the rescue spends a real detector call to earn its answer -- the
declarations (`ASSIGNMENT` -> `DETECTIONS_ROI` -> here) encode that, so the
order survives a re-registration.
"""

from __future__ import annotations

from typing import Any, Optional

from cv_service.orchestration.budget import MEMORY_GALLERY
from cv_service.orchestration.contract import PHASE_TRACK, Contribution, FrameContext, skipped
from cv_service.orchestration.corrections import recovered_identity
from cv_service.orchestration.contributors.associate import CostAssignment
from cv_service.orchestration.engines import EngineSet
from cv_service.orchestration.keys import Key
from cv_service.tracking.assign import Target
from cv_service.tracking.levels import LEVEL_L1
from cv_service.tracking.memory import Recovery
from cv_service.tracking.track import RecoveredIdentity


class MemoryGallery:
    """One read-only offer per unmatched target, claimed on a hit."""

    id = MEMORY_GALLERY
    family = MEMORY_GALLERY
    reads = frozenset({Key.ASSIGNMENT})
    writes = frozenset({Key.RECOVERIES})
    min_level = LEVEL_L1
    phase = PHASE_TRACK

    def __init__(self, engines: EngineSet) -> None:
        self._engines = engines

    def contribute(self, ctx: FrameContext, budget: Any) -> Contribution:
        memory = self._engines.memory
        if memory is None:
            return skipped("no gallery resolved")
        found: Optional[CostAssignment] = ctx.get(Key.ASSIGNMENT)
        if found is None:
            return skipped("no assignment to recover against")

        unmatched = found.assignment.unmatched_targets
        if not unmatched:
            return skipped("everything was matched")

        recoveries: "dict[int, tuple[RecoveredIdentity, Recovery]]" = {}
        evidence: "dict[int, dict[str, str]]" = {}
        for target_index in unmatched:
            recovered = self._offer(found.targets[target_index], ctx.now)
            if recovered is None:
                continue
            recoveries[target_index] = recovered
            identity, recovery = recovered
            evidence[recovery.track_id] = {
                "recovered": "1",
                "identity_confidence": repr(recovery.confidence),
                "dormant_millis": str(recovery.dormant_millis),
                # The identity's own ELECTED label at the moment it was
                # retired, which is the name an operator was already looking
                # at -- `None` for a pre-L1 entry, never a raw label.
                "remembered_label": str(identity.elected_label),
                "first_seen": repr(identity.first_seen),
            }

        return Contribution(
            outputs={Key.RECOVERIES: recoveries} if recoveries else {},
            summary={
                "asked": str(len(unmatched)),
                "recovered": str(len(recoveries)),
                "dormant": str(memory.size()),
            },
            evidence=evidence,
        )

    def _offer(
        self, target: Target, now: float
    ) -> "Optional[tuple[RecoveredIdentity, Recovery]]":
        """Offer one unmatched target to the dormant gallery, and claim it on
        a hit (TRACKING-V2-PLAN wave C4).

        `ObjectMemory.match()` is deliberately read-only (a candidate may
        still lose to a better-scoring one this same frame, or simply not be
        worth taking), so only a caller that has decided to TAKE the
        recovery calls `.claim()`. `claim()` returning `None` here means a
        same-frame race, not an error: TWO unmatched targets can each score
        above threshold against the same dormant entry (a crowd is exactly
        where this matters -- see `MODULE.md`'s clutter finding), and
        `ObjectMemory` only pops an identity once. Whichever target's turn
        comes first in `assignment.unmatched_targets` wins it; every other
        target that would have matched the SAME identity falls back to a
        fresh id on this call, exactly as if nothing had matched -- never a
        forced double-claim of one operator-recognised number onto two
        different objects.
        """
        memory = self._engines.memory
        if memory is None:
            return None
        recovery = memory.match(
            box=target.box,
            label=target.label,
            descriptor=target.descriptor,
            now_millis=now * 1000.0,
        )
        if recovery is None:
            return None
        identity = memory.claim(recovery.track_id)
        if identity is None:
            return None
        return recovered_identity(identity, recovery), recovery
