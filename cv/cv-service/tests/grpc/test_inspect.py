"""`Inspect`: the per-frame ledger and the session/process facts, at the wire.

CV-ORCHESTRATION wave W0, plan §4.4. Everything here goes through the real
servicer method and the real generated messages, because the whole point of
the RPC is that the dataclass tree under `cv_service/orchestration/` reaches
a caller correctly -- a test against the dataclasses alone would prove the
one half that was never in doubt.

Deterministic: a fake detector, the real `cost` associator, a real
`InferenceGate`. Nothing here depends on timing or on the `cv` extra's models.

CV-ORCHESTRATION wave W4 (decision E16): `orchestration.contributors.
roster()` registers an ASSOCIATE contributor chain only for the engine id
`CostAssociator.engine_id` ("cost") -- the old `bytetrack`-shaped stub that
minted its own `Observation.key` straight from `associate()` no longer gets
a contributor at all (`roster()`'s own comment: "nothing is registered"),
so it books no track and `Inspect` would have nothing to show. `StubTracker
Registry` below hands back the real, pure-stdlib `CostAssociator` instead,
the same substitution `tests/grpc/test_detect_stream_tracking.py`'s
`real_cost_associator()` makes for the same reason.
"""

from __future__ import annotations

import numpy as np

from cv_service.config import ROLE_INFERENCE, Settings
from cv_service.grpc.servicers import InferenceServicer, cv_pb2
from cv_service.inference.concurrency import InferenceGate
from cv_service.inference.detector import Detection
from cv_service.orchestration.detector import LOCAL_TARGET
from cv_service.tracking.assign import AssignGates, AssignWeights, CostAssociator
from cv_service.tracking.engines.base import TrackerUpdate

WIDTH, HEIGHT = 8, 6
FRAME_BYTES = bytes(np.zeros((HEIGHT, WIDTH, 3), dtype=np.uint8).tobytes())
GATE_PERMITS = 3


class FakeDetector:
    model_name = "fake.pt"

    def detect(self, **_kwargs):
        return [Detection("car", 0.9, 0.10, 0.10, 0.10, 0.10)], 11


def cost_associator() -> CostAssociator:
    """Neutral weights/gates (`assign.py`'s own dataclass defaults) -- the
    same construction `registry.py`'s production `_cost()` factory performs,
    kept here so `StubTrackerRegistry.associator()` returns a real, working
    engine rather than a hand-rolled double the roster no longer builds
    anything for."""
    return CostAssociator(weights=AssignWeights(), gates=AssignGates())


class StubFollower:
    """A single-object tracker that always holds the box it was given."""

    engine_id = "stub-follow"

    def __init__(self) -> None:
        self.box = None

    def init(self, frame, box):
        self.box = box
        return True

    def update(self, frame):
        return TrackerUpdate(box=self.box, confidence=0.9)

    def reset(self):
        pass


class StubTrackerRegistry:
    def associator(self, engine_id, *, max_age_frames):
        return CostAssociator.engine_id, cost_associator()

    def follower(self, engine_id, *, max_age_frames):
        return StubFollower.engine_id, StubFollower()

    def compensator(self, engine_id):
        return None

    def appearance(self, engine_id):
        return None


def frame_request(sequence: int, *, stream_id: str = "stream-1", mode=None):
    request = cv_pb2.FrameRequest(
        stream_id=stream_id,
        sequence=sequence,
        timestamp_millis=1000 + sequence,
        width=WIDTH,
        height=HEIGHT,
        encoding=cv_pb2.IMAGE_ENCODING_BGR24,
        data=FRAME_BYTES,
        model_id="fake.pt",
        model_version="v1",
        confidence_threshold=0.0,
    )
    request.tracking.mode = mode if mode is not None else cv_pb2.TRACKING_MODE_ASSOCIATE
    return request


def servicer(settings: Settings | None = None) -> InferenceServicer:
    return InferenceServicer(
        detector=FakeDetector(),
        inference_gate=InferenceGate(GATE_PERMITS),
        settings=settings if settings is not None else Settings(),
        tracker_registry=StubTrackerRegistry(),
    )


def run_stream(
    subject: InferenceServicer, frames: int, *, stream_id: str = "stream-1", mode=None
):
    """Drive `frames` frames through one POOLED session, the way
    `DetectStream` does, without its background reader thread (whose
    legitimate drops would make a frame-count assertion a race)."""
    session = subject._session_registry.acquire(stream_id)
    for sequence in range(frames):
        subject._handle_request(
            frame_request(sequence, stream_id=stream_id, mode=mode), session
        )
    return session


def inspect(subject: InferenceServicer, stream_id: str = "", frames: int = 0):
    return subject.Inspect(
        cv_pb2.InspectRequest(stream_id=stream_id, frames=frames), context=None
    )


# --- process facts --------------------------------------------------------


def test_inspect_without_a_stream_id_describes_the_process():
    subject = servicer()
    run_stream(subject, 2, stream_id="stream-a")
    run_stream(subject, 1, stream_id="stream-b")

    response = inspect(subject)

    assert response.found
    assert response.process.detector_client == "local"
    assert response.process.sessions == 2
    assert response.process.gate_permits == GATE_PERMITS
    assert response.process.ledger_ring == Settings().ledger_ring
    assert sorted(response.process.stream_ids) == ["stream-a", "stream-b"]
    # CV-ORCHESTRATION wave W4: process-wide facts a `local` deployment still
    # answers -- `role` names configured intent, the gate numbers are live,
    # `detector_targets` is empty (the wire's own doc: "Set only on a `pool`
    # client; empty on `local`").
    assert response.process.role == "all"
    assert response.process.gate_occupancy == 0
    assert response.process.gate_queue_depth == 0
    assert not response.process.detector_targets
    # No stream was named, so there is nothing session-shaped to answer with.
    assert not response.ledgers
    assert response.session.stream_id == ""


def test_inspect_reports_gate_max_queue_only_on_a_process_that_also_serves_detector():
    """`gate_max_queue` (§4.9's own wire comment) is 0 on a process that
    serves no `Detector` -- true for `inference`/`tracker`, since those
    roles never register `DetectorServicer` alongside `InferenceServicer`
    (`grpc/server.py`'s own role wiring). Only `role=all` combines both on
    one gate, so only `role=all` has a bound worth reporting here."""
    all_role = servicer(Settings(role="all", detector_max_queue=9))
    run_stream(all_role, 1)
    assert inspect(all_role).process.gate_max_queue == 9

    inference_only = servicer(Settings(role=ROLE_INFERENCE, detector_max_queue=9))
    run_stream(inference_only, 1)
    assert inspect(inference_only).process.gate_max_queue == 0


def test_inspect_shows_pool_facts_when_detector_targets_are_configured():
    """The other half of `detector_client`/`detector_targets`: once an
    operator sets `CV_DETECTOR_TARGETS`, every session's `PoolDetectorClient`
    reports into the SAME process-wide health dict (`InferenceServicer.
    __init__`'s `self._detector_health`), so `Inspect` sees it even though
    each session holds its own private `PoolDetectorClient` instance."""
    subject = servicer(Settings(detector_targets=(LOCAL_TARGET,)))
    run_stream(subject, 3, stream_id="stream-a")

    response = inspect(subject)

    assert response.process.detector_client == "pool"
    assert [t.target for t in response.process.detector_targets] == [LOCAL_TARGET]
    target = response.process.detector_targets[0]
    assert target.served == 3
    assert target.refused == 0
    assert target.failed == 0
    assert target.last_error == ""


def test_inspect_reports_an_unknown_stream_as_not_found_but_still_answers():
    subject = servicer()
    run_stream(subject, 1, stream_id="stream-a")

    response = inspect(subject, "nobody-is-serving-this")

    assert not response.found
    # The process half is still filled in: learning WHICH streams exist is
    # the whole reason a caller who guessed wrong asked.
    assert list(response.process.stream_ids) == ["stream-a"]
    assert not response.ledgers


# --- session facts --------------------------------------------------------


def test_inspect_names_the_engine_that_is_actually_serving():
    subject = servicer()
    run_stream(subject, 3)

    facts = inspect(subject, "stream-1").session

    assert facts.stream_id == "stream-1"
    assert facts.mode == "TRACKING_MODE_ASSOCIATE"
    assert facts.engine_id == "cost"
    assert facts.frames_processed == 3
    assert facts.live_tracks == 1
    assert facts.level_served > 0


def test_inspect_lists_the_contributors_in_run_order():
    subject = servicer()
    run_stream(subject, 1)

    contributors = list(inspect(subject, "stream-1").session.contributors)

    # CV-ORCHESTRATION wave W4 (decision E16): `cost` is the only ASSOCIATE
    # associator left, so its full contributor chain (`orchestration.
    # contributors.roster()`) is what every ASSOCIATE stream now registers --
    # not the three-contributor `detect.full` / `assoc.bytetrack` / `aggregate`
    # chain the retired engine used to get.
    assert contributors == [
        "detect.full",
        "egomotion.flow",
        "predict.cv",
        "appearance.histogram",
        "assoc.cost",
        "detect.roi",
        "memory.gallery",
        "propose.cost",
        "aggregate",
    ]


def test_a_session_that_never_ran_a_frame_reports_no_roster():
    subject = servicer()
    subject._session_registry.acquire("stream-1")

    facts = inspect(subject, "stream-1").session

    # Engines resolve lazily, so there genuinely is no roster yet -- naming
    # one here would be a prettier answer than the true one.
    assert list(facts.contributors) == []
    assert facts.frames_processed == 0


# --- the per-frame ledger -------------------------------------------------


def test_every_contributor_gets_a_row_with_an_outcome():
    subject = servicer()
    run_stream(subject, 1)

    ledger = inspect(subject, "stream-1").ledgers[0]

    assert ledger.stream_id == "stream-1"
    assert ledger.sequence == 0
    outcomes = {entry.contributor_id: entry.outcome for entry in ledger.entries}
    # `detect.roi` is registered (`cost`'s chain always is) but SKIPPED on
    # this frame: the single detection matched outright, so the rescue --
    # "one bounded second look at what [the match] left unmatched" (§4.1) --
    # found nothing to do. Every other contributor genuinely ran. A row
    # existing at all, whatever its outcome, is what this test's name is
    # about -- see `test_a_skipped_contributor_says_why` below for a SKIPPED
    # row's own reason string.
    assert outcomes == {
        "detect.full": cv_pb2.LEDGER_OUTCOME_RAN,
        "egomotion.flow": cv_pb2.LEDGER_OUTCOME_RAN,
        "predict.cv": cv_pb2.LEDGER_OUTCOME_RAN,
        "appearance.histogram": cv_pb2.LEDGER_OUTCOME_RAN,
        "assoc.cost": cv_pb2.LEDGER_OUTCOME_RAN,
        "detect.roi": cv_pb2.LEDGER_OUTCOME_SKIPPED,
        "memory.gallery": cv_pb2.LEDGER_OUTCOME_RAN,
        "propose.cost": cv_pb2.LEDGER_OUTCOME_RAN,
        "aggregate": cv_pb2.LEDGER_OUTCOME_RAN,
    }, [(entry.contributor_id, entry.outcome, entry.reason) for entry in ledger.entries]
    assert not ledger.halted
    # The response's own `DetectorReason`, carried as its value NAME: a
    # debug surface a human reads should not render `1` where the
    # response renders `DETECTOR_REASON_ALWAYS`.
    assert ledger.detector_reason == "DETECTOR_REASON_ALWAYS"
    assert "detect.full" in set(ledger.eligible)


def test_a_skipped_contributor_says_why():
    subject = servicer()
    # FOLLOW holding no lock re-acquires on a rate limit, so the second
    # frame's detector pass is refused -- the case that must show up as a
    # SKIPPED row with the scheduler's own reason, not as a missing row.
    run_stream(subject, 2, mode=cv_pb2.TRACKING_MODE_FOLLOW)

    ledgers = inspect(subject, "stream-1").ledgers
    detect_rows = [
        entry
        for ledger in ledgers
        for entry in ledger.entries
        if entry.contributor_id == "detect.full"
    ]

    assert len(detect_rows) == 2
    assert detect_rows[0].outcome == cv_pb2.LEDGER_OUTCOME_RAN
    assert detect_rows[0].reason == "DETECTOR_REASON_NO_LOCK"
    assert detect_rows[1].outcome == cv_pb2.LEDGER_OUTCOME_SKIPPED
    assert detect_rows[1].reason


def test_the_ledger_attributes_each_claim_to_the_contributor_that_made_it():
    subject = servicer()
    run_stream(subject, 1)

    ledger = inspect(subject, "stream-1").ledgers[0]

    assert ledger.objects, "the aggregator books one track, so one object is claimed"
    track_id, claims = next(iter(ledger.objects.items()))
    assert track_id > 0
    assert [claim.contributor_id for claim in claims.claims] == ["aggregate"]
    assert claims.claims[0].claim, "an evidence row with no claim is not evidence"


def test_frames_bounds_the_window_and_zero_means_everything():
    subject = servicer()
    run_stream(subject, 4)

    assert [ledger.sequence for ledger in inspect(subject, "stream-1").ledgers] == [0, 1, 2, 3]
    assert [
        ledger.sequence for ledger in inspect(subject, "stream-1", frames=2).ledgers
    ] == [2, 3]


def test_the_ring_is_bounded_by_cv_ledger_ring():
    subject = servicer(Settings(ledger_ring=2))
    run_stream(subject, 5)

    ledgers = inspect(subject, "stream-1").ledgers

    assert [ledger.sequence for ledger in ledgers] == [3, 4]


def test_inspect_never_resolves_an_engine_to_answer():
    """A debug read must not mutate the session it is describing -- an
    `Inspect` call arrives on a different gRPC thread than the one running
    frames."""
    subject = servicer()
    session = subject._session_registry.acquire("stream-1")

    inspect(subject, "stream-1")

    assert session._engines.engine is None
    assert session._built is None


def test_the_ledger_records_what_the_transport_dropped_before_this_frame():
    """A drop is the transport's fact, never the tracker's decision: the
    ledger records it so a frame that looks like a jump has an explanation,
    and nothing reads it back."""
    subject = servicer()
    session = subject._session_registry.acquire("stream-1")
    subject._handle_request(frame_request(0), session)
    subject._handle_request(frame_request(3), session, dropped_frames=2)

    ledgers = inspect(subject, "stream-1").ledgers

    assert [ledger.drops_since_last for ledger in ledgers] == [0, 2]
