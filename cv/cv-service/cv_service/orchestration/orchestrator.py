"""`Orchestrator` -- order from declarations, one ledger per frame.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.2. The whole "engine" is a
topological sort over what each contributor declares it reads and writes,
computed ONCE at `apply_config` and never per frame (rule 3: resolve-once).
The DAG has under a dozen nodes and is fixed per configuration, which is
exactly why a workflow library was rejected (plan §2, rejected shapes).

Three invariants this class makes structural rather than aspirational:

* **A key has exactly one writer** (rule 2) -- two writers is a build-time
  `ValueError`, not a silent last-write-wins.
* **A contributor cannot write a key it did not declare** -- outputs are
  applied by this class, from the declaration, never by the contributor.
* **A raise is `FAILED:<reason>` and the frame continues** (rule 4) -- the
  key is simply absent and every downstream reader already handles absence,
  because absence is also what `SKIPPED` produces.

`tracker_millis` semantics are preserved by the `phase` split: one
`perf_counter` span around the `PHASE_TRACK` contributors and the fold,
which is exactly the window `session.process()` measured before this package
existed (the mode branch -- NOT ego-motion, which has always had its own
`motion_millis`).
"""

from __future__ import annotations

import logging
from time import perf_counter
from typing import Iterable, Optional, Sequence

from cv_service.orchestration.budget import FrameBudget
from cv_service.orchestration.contract import (
    OUTCOME_FAILED,
    OUTCOME_RAN,
    OUTCOME_SKIPPED,
    PHASE_PRE,
    PHASE_TRACK,
    Contribution,
    Contributor,
    FrameContext,
)
from cv_service.orchestration.keys import SEEDED, Key
from cv_service.orchestration.ledger import FrameLedger, LedgerEntry, ObjectEvidence

LOGGER = logging.getLogger("cv_service.orchestration")

_PHASE_RANK = {PHASE_PRE: 0, PHASE_TRACK: 1}


class Orchestrator:
    """Holds the ordered contributor list and runs one frame through it."""

    __slots__ = ("_order", "_writers")

    def __init__(self, contributors: "Sequence[Contributor]") -> None:
        self._writers = _writers_of(contributors)
        self._order = _topological_order(contributors, self._writers)

    @property
    def order(self) -> "tuple[Contributor, ...]":
        return self._order

    @property
    def contributor_ids(self) -> "tuple[str, ...]":
        return tuple(contributor.id for contributor in self._order)

    def declarations(self) -> "list[tuple[str, tuple[str, ...], tuple[str, ...]]]":
        """`(id, reads, writes)` per contributor, in run order -- the roster
        `Inspect` reports and `MODULE.md` documents."""
        return [
            (
                contributor.id,
                tuple(sorted(key.value for key in contributor.reads)),
                tuple(sorted(key.value for key in contributor.writes)),
            )
            for contributor in self._order
        ]

    def run(self, ctx: FrameContext, budget: FrameBudget, ledger: FrameLedger) -> float:
        """Run every contributor in order; returns the `PHASE_TRACK` span in
        milliseconds (today's `tracker_millis`).

        Stops early only on `Contribution.halt` (no model resolved), which
        the ledger records so "the frame stopped here" is a readable fact
        rather than an inference from a short entry list.
        """
        track_started: Optional[float] = None
        track_millis = 0.0
        for contributor in self._order:
            if contributor.phase == PHASE_TRACK and track_started is None:
                track_started = perf_counter()

            if not budget.allows(contributor.family):
                ledger.record(
                    LedgerEntry(
                        contributor_id=contributor.id,
                        outcome=OUTCOME_SKIPPED,
                        reason=budget.reason(contributor.family),
                    )
                )
                continue

            started = perf_counter()
            try:
                contribution = contributor.contribute(ctx, budget)
            except Exception as exc:  # noqa: BLE001 - one part fails, the frame continues
                LOGGER.warning(
                    "contributor %r raised (%s); this frame runs without its output",
                    contributor.id,
                    exc,
                )
                ledger.record(
                    LedgerEntry(
                        contributor_id=contributor.id,
                        outcome=OUTCOME_FAILED,
                        reason=f"{type(exc).__name__}: {exc}",
                        cost_ms=_elapsed_ms(started),
                    )
                )
                continue

            cost_ms = contribution.cost_ms
            if cost_ms is None:
                cost_ms = _elapsed_ms(started)
            self._apply(ctx, contributor, contribution, ledger, cost_ms)
            if contribution.halt:
                ledger.halted = True
                break

        if track_started is not None:
            track_millis = _elapsed_ms(track_started)
        return track_millis

    def _apply(
        self,
        ctx: FrameContext,
        contributor: "Contributor",
        contribution: Contribution,
        ledger: FrameLedger,
        cost_ms: float,
    ) -> None:
        for key, value in contribution.outputs.items():
            if key not in contributor.writes:
                # A declaration bug, not a data bug -- loud, and the value is
                # dropped rather than silently widening the DAG.
                raise ValueError(
                    f"contributor {contributor.id!r} wrote undeclared key {key.value!r}"
                )
            ctx.put(key, value, by=contributor.id)
        ledger.record(
            LedgerEntry(
                contributor_id=contributor.id,
                outcome=contribution.outcome,
                reason=contribution.reason,
                cost_ms=cost_ms,
                summary=dict(contribution.summary),
            )
        )
        for track_id, claim in contribution.evidence.items():
            ledger.claim(track_id, ObjectEvidence(contributor.id, dict(claim)))


def _writers_of(contributors: "Iterable[Contributor]") -> "dict[Key, str]":
    """`{key: contributor id}`, refusing two writers of one key (rule 2)."""
    writers: "dict[Key, str]" = {}
    for contributor in contributors:
        for key in contributor.writes:
            if key in SEEDED:
                raise ValueError(
                    f"contributor {contributor.id!r} declares seeded key {key.value!r} as a write; "
                    "the session owns the frame's given facts"
                )
            existing = writers.get(key)
            if existing is not None:
                raise ValueError(
                    f"key {key.value!r} has two writers ({existing!r} and {contributor.id!r}); "
                    "one responsibility, one owner (CV-ORCHESTRATION §4.1 rule 2)"
                )
            writers[key] = contributor.id
    return writers


def _topological_order(
    contributors: "Sequence[Contributor]", writers: "dict[Key, str]"
) -> "tuple[Contributor, ...]":
    """Kahn's algorithm over the declarations, tie-broken by `(phase,
    registration index)`.

    The tie-break is what makes the order DETERMINISTIC and phase-monotonic:
    everything in `PHASE_PRE` runs before anything in `PHASE_TRACK`, which
    keeps the `tracker_millis` window exactly where it has always been.
    """
    by_id = {contributor.id: contributor for contributor in contributors}
    rank = {
        contributor.id: (_PHASE_RANK.get(contributor.phase, 99), index)
        for index, contributor in enumerate(contributors)
    }
    dependencies: "dict[str, set[str]]" = {contributor.id: set() for contributor in contributors}
    dependents: "dict[str, set[str]]" = {contributor.id: set() for contributor in contributors}
    for contributor in contributors:
        for key in contributor.reads:
            producer = writers.get(key)
            if producer is None or producer == contributor.id:
                continue
            dependencies[contributor.id].add(producer)
            dependents[producer].add(contributor.id)

    ready = sorted(
        (cid for cid, deps in dependencies.items() if not deps), key=lambda cid: rank[cid]
    )
    ordered: "list[Contributor]" = []
    while ready:
        current = ready.pop(0)
        ordered.append(by_id[current])
        for dependent in sorted(dependents[current], key=lambda cid: rank[cid]):
            dependencies[dependent].discard(current)
            if not dependencies[dependent]:
                ready.append(dependent)
                ready.sort(key=lambda cid: rank[cid])

    if len(ordered) != len(contributors):
        stuck = sorted(set(by_id) - {contributor.id for contributor in ordered})
        raise ValueError(
            f"contributor declarations form a cycle; unresolvable: {stuck}"
        )
    return tuple(ordered)


def _elapsed_ms(started: float) -> float:
    return (perf_counter() - started) * 1000.0
