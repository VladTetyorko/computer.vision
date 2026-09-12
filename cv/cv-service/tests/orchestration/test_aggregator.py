"""`Aggregator` -- the only writer of `Track`, at most once per frame.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.3 / O1 contradiction 7. The
invariant with teeth is the CALL COUNT: today's `session.process()` calls
`TrackBook.apply` at most once a frame, zero on the OFF/no-engine path and
zero on a FOLLOW re-anchor failure with nothing held. Anything else is a
behavioural delta, so it is asserted directly rather than inferred.
"""

from __future__ import annotations

from cv_service.orchestration.aggregator import Aggregator, Proposal
from cv_service.orchestration.contract import OUTCOME_RAN, FrameContext
from cv_service.orchestration.keys import Key
from cv_service.tracking.engines.base import Box, Observation
from cv_service.tracking.track import TrackBook

from tests.orchestration.params import params

from cv_service.tracking.params import MODE_ASSOCIATE


def context(now_millis: float = 1000.0) -> FrameContext:
    return FrameContext(
        stream_id="s",
        sequence=1,
        now_millis=now_millis,
        level_served=3,
        params=params(MODE_ASSOCIATE),
        lag_millis=0,
    )


def book() -> TrackBook:
    return TrackBook(params(MODE_ASSOCIATE))


def observation(key: str, *, label: str = "object") -> Observation:
    return Observation(
        key=key,
        box=Box(0.1, 0.1, 0.2, 0.2),
        label=label,
        confidence=0.9,
        det_index=0,
    )


class CountingBook(TrackBook):
    """A real book that also counts how often the fold ran."""

    def __init__(self) -> None:
        super().__init__(params(MODE_ASSOCIATE))
        self.applies = 0

    def apply(self, *args, **kwargs):
        self.applies += 1
        return super().apply(*args, **kwargs)


def aggregate(proposal, *, key=Key.OBSERVATIONS, detections=None, counting=None):
    tracked = counting if counting is not None else book()
    aggregator = Aggregator(tracked, raw_boxes=lambda detections: [("raw", d) for d in detections])
    ctx = context()
    if detections is not None:
        ctx.put(Key.DETECTIONS, detections, by="detect.full")
    if proposal is not None:
        ctx.put(key, proposal, by="test")
    contribution = aggregator.contribute(ctx, budget=None)
    return aggregator, contribution


def test_no_proposal_echoes_the_detectors_own_boxes_and_folds_nothing() -> None:
    counting = CountingBook()

    aggregator, contribution = aggregate(None, detections=["a", "b"], counting=counting)

    assert counting.applies == 0
    assert aggregator.applied is False
    assert aggregator.boxes == [("raw", "a"), ("raw", "b")]
    assert contribution.summary["path"] == "raw"
    assert contribution.outcome == OUTCOME_RAN


def test_no_proposal_and_no_detections_is_an_empty_response_not_a_crash() -> None:
    aggregator, _ = aggregate(None)

    assert aggregator.boxes == []
    assert aggregator.applied is False


def test_a_proposal_with_no_observations_folds_nothing_and_keeps_its_boxes() -> None:
    # FOLLOW's re-anchor-failure-with-nothing-held path: the response is
    # empty, the book is untouched, and no track ages on this frame.
    counting = CountingBook()

    aggregator, contribution = aggregate(
        Proposal(observations=(), boxes=(), summary={"branch": "reanchor-failed"}),
        counting=counting,
    )

    assert counting.applies == 0
    assert aggregator.applied is False
    assert aggregator.boxes == []
    assert contribution.summary["branch"] == "reanchor-failed"
    assert contribution.summary["folded"] == "0"


def test_a_proposal_with_observations_folds_exactly_once() -> None:
    counting = CountingBook()

    aggregator, contribution = aggregate(
        Proposal(
            observations=(observation("a"), observation("b")),
            settle=lambda tracks: [track.track_id for track in tracks],
        ),
        counting=counting,
    )

    assert counting.applies == 1
    assert aggregator.applied is True
    assert aggregator.boxes == [1, 2]
    assert contribution.summary["folded"] == "2"
    assert contribution.summary["live_tracks"] == "2"


def test_the_follow_proposal_wins_over_the_associate_one() -> None:
    # Only one of the two can ever be written in a given configuration (the
    # orchestrator refuses two writers of a key, and the modes are exclusive),
    # but the read order is stated so the precedence is not accidental.
    counting = CountingBook()
    aggregator = Aggregator(counting, raw_boxes=lambda d: list(d))
    ctx = context()
    ctx.put(Key.OBSERVATIONS, Proposal(observations=(), boxes=("associate",)), by="assoc.cost")
    ctx.put(Key.FOLLOW_OBS, Proposal(observations=(), boxes=("follow",)), by="follow.lk")

    aggregator.contribute(ctx, budget=None)

    assert aggregator.boxes == ["follow"]


def test_the_settle_callback_receives_the_tracks_the_book_handed_back() -> None:
    seen: "list[list]" = []

    aggregate(
        Proposal(
            observations=(observation("a"),),
            settle=lambda tracks: seen.append(list(tracks)) or [],
        )
    )

    (tracks,) = seen
    assert [track.track_id for track in tracks] == [1]


def test_the_frames_own_clock_is_what_the_book_ages_against() -> None:
    tracked = book()
    aggregator = Aggregator(tracked, raw_boxes=lambda d: list(d))
    ctx = context(now_millis=4500.0)
    ctx.put(Key.OBSERVATIONS, Proposal(observations=(observation("a"),), settle=lambda t: []), by="t")

    aggregator.contribute(ctx, budget=None)

    # `FrameContext.now` is seconds; the book stamps that, not milliseconds.
    assert tracked.tracks[0].last_seen == 4.5


def test_lifecycle_evidence_names_the_elected_label_and_the_raw_one() -> None:
    _, contribution = aggregate(
        Proposal(observations=(observation("a", label="person"),), settle=lambda t: [])
    )

    claim = contribution.evidence[1]
    assert claim["elected_label"] == "person"
    assert claim["raw_label"] == "person"
    assert claim["hits"] == "1"
    assert claim["misses"] == "0"


def test_descriptors_are_zipped_positionally_and_may_be_absent() -> None:
    # A path with no appearance evidence supplies no descriptors at all; the
    # zip must then be a no-op rather than an IndexError.
    aggregator, _ = aggregate(
        Proposal(observations=(observation("a"), observation("b")), settle=lambda t: [])
    )

    assert aggregator.applied is True
    assert all(track.descriptor is None for track in aggregator.book.tracks)


def test_the_aggregator_declares_no_writes_so_it_can_never_own_a_key() -> None:
    # It is the terminal fold: it reads the proposals and produces the
    # response, and the response is not a blackboard key.
    assert Aggregator.writes == frozenset()
    assert Key.OBSERVATIONS in Aggregator.reads
    assert Key.FOLLOW_OBS in Aggregator.reads
