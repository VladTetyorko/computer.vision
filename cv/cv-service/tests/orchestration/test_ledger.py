"""`FrameLedger` and `LedgerRing` -- the debug surface, and its bound.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.4. The one thing that can go
structurally wrong with a per-frame record is that it grows without bound, so
that is what most of this file is about.
"""

from __future__ import annotations

from cv_service.config import DEFAULT_LEDGER_RING, Settings
from cv_service.orchestration.contract import OUTCOME_RAN, OUTCOME_SKIPPED
from cv_service.orchestration.ledger import (
    FrameLedger,
    LedgerEntry,
    LedgerRing,
    ObjectEvidence,
)


def ledger(sequence: int = 1) -> FrameLedger:
    return FrameLedger(stream_id="s", sequence=sequence, captured_at_millis=float(sequence))


def test_entries_keep_the_order_they_were_recorded_in() -> None:
    book = ledger()
    book.record(LedgerEntry("detect.full", OUTCOME_RAN))
    book.record(LedgerEntry("assoc.cost", OUTCOME_RAN))
    book.record(LedgerEntry("detect.roi", OUTCOME_SKIPPED, "roi rescue disabled"))

    assert [entry.contributor_id for entry in book.entries] == [
        "detect.full",
        "assoc.cost",
        "detect.roi",
    ]


def test_entry_for_finds_a_contributor_and_reports_absence_as_none() -> None:
    book = ledger()
    book.record(LedgerEntry("assoc.cost", OUTCOME_RAN, cost_ms=1.5))

    assert book.entry_for("assoc.cost").cost_ms == 1.5
    assert book.entry_for("follow.lk") is None


def test_several_contributors_may_claim_the_same_object() -> None:
    book = ledger()
    book.claim(4, ObjectEvidence("predict.cv", {"predicted": "0.1,0.2"}))
    book.claim(4, ObjectEvidence("assoc.cost", {"cost_total": "0.3"}))
    book.claim(9, ObjectEvidence("memory.gallery", {"distance": "0.8"}))

    assert [evidence.contributor_id for evidence in book.objects[4]] == [
        "predict.cv",
        "assoc.cost",
    ]
    assert list(book.objects) == [4, 9]


def test_the_ring_evicts_the_oldest_frame_first() -> None:
    ring = LedgerRing(3)
    for sequence in range(1, 6):
        ring.append(ledger(sequence))

    assert len(ring) == 3
    assert [book.sequence for book in ring] == [3, 4, 5]


def test_last_returns_the_newest_n_oldest_first() -> None:
    ring = LedgerRing(10)
    for sequence in range(1, 6):
        ring.append(ledger(sequence))

    assert [book.sequence for book in ring.last(2)] == [4, 5]


def test_last_of_everything_is_the_wires_unset_sentinel() -> None:
    ring = LedgerRing(10)
    for sequence in range(1, 4):
        ring.append(ledger(sequence))

    assert [book.sequence for book in ring.last(0)] == [1, 2, 3]
    assert [book.sequence for book in ring.last(-1)] == [1, 2, 3]
    assert [book.sequence for book in ring.last(99)] == [1, 2, 3]


def test_a_zero_capacity_ring_still_holds_the_current_frame() -> None:
    # A ring that holds nothing would make `Inspect` silently useless on a
    # misconfigured box; one frame is the honest floor.
    ring = LedgerRing(0)
    ring.append(ledger(7))

    assert [book.sequence for book in ring] == [7]


def test_the_capacity_is_configuration_not_a_literal(monkeypatch) -> None:
    # CLAUDE.md rule 1. The knob exists, has a default, and is readable from
    # the environment like every other cv-service setting.
    monkeypatch.delenv("CV_LEDGER_RING", raising=False)
    assert Settings.from_env().ledger_ring == DEFAULT_LEDGER_RING

    monkeypatch.setenv("CV_LEDGER_RING", "8")
    assert Settings.from_env().ledger_ring == 8

    monkeypatch.setenv("CV_LEDGER_RING", "-3")
    assert Settings.from_env().ledger_ring == DEFAULT_LEDGER_RING
