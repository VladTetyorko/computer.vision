"""`Inspect`: the per-frame ledger and the session/process facts, at the wire.

CV-ORCHESTRATION wave W0, plan §4.4. Everything here goes through the real
servicer method and the real generated messages, because the whole point of
the RPC is that the dataclass tree under `cv_service/orchestration/` reaches
a caller correctly -- a test against the dataclasses alone would prove the
one half that was never in doubt.

Deterministic: a fake detector, a stub associator, a real `InferenceGate`.
Nothing here depends on timing or on the `cv` extra's models.
"""

from __future__ import annotations

import numpy as np

from cv_service.config import Settings
from cv_service.grpc.servicers import InferenceServicer, cv_pb2
from cv_service.inference.concurrency import InferenceGate
from cv_service.inference.detector import Detection
from cv_service.tracking.engines.base import Box, Observation, TrackerUpdate

WIDTH, HEIGHT = 8, 6
FRAME_BYTES = bytes(np.zeros((HEIGHT, WIDTH, 3), dtype=np.uint8).tobytes())
GATE_PERMITS = 3


class FakeDetector:
    model_name = "fake.pt"

    def detect(self, **_kwargs):
        return [Detection("car", 0.9, 0.10, 0.10, 0.10, 0.10)], 11


class StubAssociator:
    """Every detection becomes its own observation -- enough to give the
    aggregator something to book without a real engine."""

    engine_id = "stub-assoc"

    def associate(self, detections, now):
        return [
            Observation(
                key=detection.label,
                box=Box(detection.x, detection.y, detection.width, detection.height),
                label=detection.label,
                confidence=detection.confidence,
                det_index=index,
            )
            for index, detection in enumerate(detections)
        ]

    def reset(self):
        pass


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
        return StubAssociator.engine_id, StubAssociator()

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
    # No stream was named, so there is nothing session-shaped to answer with.
    assert not response.ledgers
    assert response.session.stream_id == ""


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
    assert facts.engine_id == "stub-assoc"
    assert facts.frames_processed == 3
    assert facts.live_tracks == 1
    assert facts.level_served > 0


def test_inspect_lists_the_contributors_in_run_order():
    subject = servicer()
    run_stream(subject, 1)

    contributors = list(inspect(subject, "stream-1").session.contributors)

    assert contributors == ["detect.full", "assoc.bytetrack", "aggregate"]


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
    assert [entry.contributor_id for entry in ledger.entries] == [
        "detect.full",
        "assoc.bytetrack",
        "aggregate",
    ]
    assert all(
        entry.outcome == cv_pb2.LEDGER_OUTCOME_RAN for entry in ledger.entries
    ), [(entry.contributor_id, entry.outcome, entry.reason) for entry in ledger.entries]
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
