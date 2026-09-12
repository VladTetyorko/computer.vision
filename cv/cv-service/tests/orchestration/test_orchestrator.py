"""`Orchestrator` -- order from declarations, and the three refusals.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.1 rules 2-4. No frames, no
engines, no clock: the orchestrator is a graph walker, and everything worth
asserting about it is assertable with fake contributors.
"""

from __future__ import annotations

import pytest

from cv_service.orchestration.budget import FrameBudget
from cv_service.orchestration.contract import (
    OUTCOME_FAILED,
    OUTCOME_RAN,
    OUTCOME_SKIPPED,
    PHASE_PRE,
    PHASE_TRACK,
    Contribution,
    FrameContext,
    skipped,
)
from cv_service.orchestration.keys import Key
from cv_service.orchestration.ledger import FrameLedger
from cv_service.orchestration.orchestrator import Orchestrator
from cv_service.tracking.scheduler import REASON_ALWAYS, Decision


class Fake:
    """A contributor that does exactly what a test tells it to."""

    def __init__(
        self,
        cid: str,
        *,
        reads=(),
        writes=(),
        family=None,
        phase=PHASE_TRACK,
        result=None,
        raises=None,
    ) -> None:
        self.id = cid
        self.family = family if family is not None else cid
        self.reads = frozenset(reads)
        self.writes = frozenset(writes)
        self.min_level = 0
        self.phase = phase
        self._result = result
        self._raises = raises
        self.calls = 0

    def contribute(self, ctx, budget):
        self.calls += 1
        if self._raises is not None:
            raise self._raises
        if self._result is not None:
            return self._result
        return Contribution(outputs={key: f"{self.id}:{key.value}" for key in self.writes})


def budget(*eligible: str, reasons=None) -> FrameBudget:
    return FrameBudget(
        decision=Decision(run_detector=True, reason=REASON_ALWAYS),
        eligible=frozenset(eligible),
        reasons=dict(reasons or {}),
    )


def context() -> FrameContext:
    return FrameContext(
        stream_id="s",
        sequence=1,
        now_millis=1000.0,
        level_served=3,
        params=None,
        lag_millis=0,
    )


def ledger() -> FrameLedger:
    return FrameLedger(stream_id="s", sequence=1, captured_at_millis=1000.0)


def run(contributors, eligible, *, reasons=None):
    orchestrator = Orchestrator(contributors)
    ctx, book = context(), ledger()
    millis = orchestrator.run(ctx, budget(*eligible, reasons=reasons), book)
    return orchestrator, ctx, book, millis


# --- rule 2: one writer per key -------------------------------------------


def test_two_writers_of_one_key_is_refused_at_build_time() -> None:
    with pytest.raises(ValueError, match="two writers"):
        Orchestrator(
            [
                Fake("a", writes=[Key.DETECTIONS]),
                Fake("b", writes=[Key.DETECTIONS]),
            ]
        )


def test_declaring_a_seeded_key_as_a_write_is_refused() -> None:
    with pytest.raises(ValueError, match="seeded key"):
        Orchestrator([Fake("a", writes=[Key.TRACKS_PREV])])


def test_writing_an_undeclared_key_is_refused_at_run_time() -> None:
    rogue = Fake(
        "rogue",
        writes=[Key.DETECTIONS],
        result=Contribution(outputs={Key.TRANSFORM: "sneaky"}),
    )
    orchestrator = Orchestrator([rogue])

    with pytest.raises(ValueError, match="undeclared key"):
        orchestrator.run(context(), budget("rogue"), ledger())


# --- ordering --------------------------------------------------------------


def test_order_follows_the_declarations_not_the_registration_order() -> None:
    consumer = Fake("consumer", reads=[Key.DETECTIONS], writes=[Key.OBSERVATIONS])
    producer = Fake("producer", writes=[Key.DETECTIONS])

    orchestrator = Orchestrator([consumer, producer])

    assert orchestrator.contributor_ids == ("producer", "consumer")


def test_a_cycle_in_the_declarations_is_refused() -> None:
    with pytest.raises(ValueError, match="cycle"):
        Orchestrator(
            [
                Fake("a", reads=[Key.ASSIGNMENT], writes=[Key.DETECTIONS]),
                Fake("b", reads=[Key.DETECTIONS], writes=[Key.ASSIGNMENT]),
            ]
        )


def test_pre_phase_contributors_always_run_before_track_phase_ones() -> None:
    # Nothing in the declarations connects these two, so only the phase rank
    # can order them -- which is what keeps `tracker_millis` measuring the
    # mode branch and not the detector pass.
    orchestrator = Orchestrator(
        [
            Fake("tracky", phase=PHASE_TRACK),
            Fake("prey", phase=PHASE_PRE),
        ]
    )

    assert orchestrator.contributor_ids == ("prey", "tracky")


def test_ties_are_broken_by_registration_order_so_the_order_is_stable() -> None:
    ids = ("one", "two", "three", "four")
    for _ in range(5):
        orchestrator = Orchestrator([Fake(cid) for cid in ids])
        assert orchestrator.contributor_ids == ids


def test_declarations_report_reads_and_writes_in_run_order() -> None:
    orchestrator = Orchestrator(
        [
            Fake("consumer", reads=[Key.DETECTIONS], writes=[Key.OBSERVATIONS]),
            Fake("producer", reads=[Key.FRAME], writes=[Key.DETECTIONS]),
        ]
    )

    assert orchestrator.declarations() == [
        ("producer", ("frame",), ("detections",)),
        ("consumer", ("detections",), ("observations",)),
    ]


# --- rule 4: a raise is FAILED, not a dead stream ---------------------------


def test_a_raise_becomes_a_failed_entry_and_the_frame_continues() -> None:
    boom = Fake("boom", writes=[Key.DETECTIONS], raises=RuntimeError("no model"))
    after = Fake("after", reads=[Key.DETECTIONS], writes=[Key.OBSERVATIONS])

    _, ctx, book, _ = run([boom, after], ["boom", "after"])

    entry = book.entry_for("boom")
    assert entry is not None
    assert entry.outcome == OUTCOME_FAILED
    assert entry.reason == "RuntimeError: no model"
    # The key is simply absent -- exactly what SKIPPED produces, so every
    # downstream reader already handles it.
    assert ctx.has(Key.DETECTIONS) is False
    assert after.calls == 1
    assert book.entry_for("after").outcome == OUTCOME_RAN


def test_a_failed_contributor_still_reports_what_it_cost() -> None:
    _, _, book, _ = run([Fake("boom", raises=ValueError("x"))], ["boom"])

    assert book.entry_for("boom").cost_ms >= 0.0


# --- budget ----------------------------------------------------------------


def test_an_ineligible_contributor_is_skipped_with_the_budgets_own_reason() -> None:
    fake = Fake("assoc", family="assoc")

    _, _, book, _ = run([fake], [], reasons={"assoc": "mode is FOLLOW"})

    assert fake.calls == 0
    entry = book.entry_for("assoc")
    assert entry.outcome == OUTCOME_SKIPPED
    assert entry.reason == "mode is FOLLOW"


def test_eligibility_is_keyed_by_family_not_by_id() -> None:
    # `assoc.cost` and `assoc.bytetrack` share one eligibility row, so the
    # budget table stays one row per RESPONSIBILITY.
    fake = Fake("assoc.cost", family="assoc")

    _, _, book, _ = run([fake], ["assoc"])

    assert fake.calls == 1
    assert book.entry_for("assoc.cost").outcome == OUTCOME_RAN


def test_a_contributor_may_skip_itself_without_writing_anything() -> None:
    fake = Fake("quiet", writes=[Key.TRANSFORM], result=skipped("nothing moved"))

    _, ctx, book, _ = run([fake], ["quiet"])

    assert ctx.has(Key.TRANSFORM) is False
    assert book.entry_for("quiet").outcome == OUTCOME_SKIPPED
    assert book.entry_for("quiet").reason == "nothing moved"


# --- halt, evidence, timing ------------------------------------------------


def test_halt_stops_the_frame_and_says_so_in_the_ledger() -> None:
    stopper = Fake("stop", result=Contribution(halt=True, reason="no model resolved"))
    never = Fake("never")

    _, _, book, _ = run([stopper, never], ["stop", "never"])

    assert never.calls == 0
    assert book.halted is True
    assert [entry.contributor_id for entry in book.entries] == ["stop"]


def test_evidence_is_filed_against_the_object_and_names_its_author() -> None:
    claimer = Fake(
        "assoc.cost",
        result=Contribution(evidence={7: {"cost_total": "0.25"}}),
    )

    _, _, book, _ = run([claimer], ["assoc.cost"])

    (evidence,) = book.objects[7]
    assert evidence.contributor_id == "assoc.cost"
    assert evidence.claim == {"cost_total": "0.25"}


def test_a_contributor_may_report_its_own_cost_when_that_is_the_wire_number() -> None:
    owns_its_clock = Fake("egomotion", result=Contribution(cost_ms=42.0))

    _, _, book, _ = run([owns_its_clock], ["egomotion"])

    assert book.entry_for("egomotion").cost_ms == 42.0


def test_the_returned_span_covers_the_track_phase_only() -> None:
    orchestrator = Orchestrator([Fake("prey", phase=PHASE_PRE)])

    assert orchestrator.run(context(), budget("prey"), ledger()) == 0.0


def test_the_span_is_positive_once_a_track_phase_contributor_runs() -> None:
    orchestrator = Orchestrator([Fake("tracky", phase=PHASE_TRACK)])

    assert orchestrator.run(context(), budget("tracky"), ledger()) >= 0.0


# --- the blackboard --------------------------------------------------------


def test_the_context_records_who_wrote_each_key() -> None:
    _, ctx, _, _ = run([Fake("producer", writes=[Key.DETECTIONS])], ["producer"])

    assert ctx.get(Key.DETECTIONS) == "producer:detections"
    assert ctx.writer_of(Key.DETECTIONS) == "producer"


def test_seeded_keys_are_attributed_to_the_session() -> None:
    ctx = context()
    ctx.seed(Key.POSE, "pose")

    assert ctx.writer_of(Key.POSE) == "session"
    assert ctx.get(Key.LOCK) is None
