"""Affinity: a stream's identities belong to its TRACKER, never to a detector.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.9 (wave W4), decisions E11/E17.

§4.9 splits the per-frame work in two and gives each half a different
scaling rule:

    detector   stateless      N instances, any of them may serve any frame
    tracker    session sticky ONE instance per stream, for the stream's life

The whole design rests on the claim that those two rules do not interact --
that which detector answered a frame is invisible to the ids the tracker
mints. This file is that claim under test, because nothing else proves it:
`tests/orchestration/test_detector_pool.py` proves the pool FAILS OVER, and
`tests/tracking/test_sessions.py` proves a session SURVIVES a reconnect, but
neither one runs the two at the same time, which is the only arrangement an
operator ever actually deploys.

Three things are asserted here and nowhere else:

1. a detector swap mid-stream is invisible to identities (`cost` mints keys
   in the BOOK, so a new detector instance cannot restart a key sequence);
2. a reconnect inside the grace window keeps BOTH the ids and the pool
   placement (`detect_through` must survive `reset_for_reconnect`, which
   invalidates the roster the contributors were built from);
3. an exhausted fleet degrades per frame and RECOVERS -- ids from before the
   outage are still the same ids after it.

Pure stdlib. The "remote detectors" are objects with a `.target` and a
`.detect()`; nothing here opens a socket, because affinity is a property of
the composition, not of the transport.
"""

from __future__ import annotations

import pytest

from cv_service.config import Settings
from cv_service.inference.detector import Detection
from cv_service.orchestration.contract import OUTCOME_FAILED
from cv_service.orchestration.detector import (
    DetectorBusy,
    DetectorPass,
    DetectorUnavailable,
    FramePayload,
    LocalDetectorClient,
    NoDetectorAvailable,
    PoolDetectorClient,
)
from cv_service.tracking.params import MODE_ASSOCIATE, TrackingRequest
from cv_service.tracking.session import StreamTrackingSession
from cv_service.tracking.sessions import SessionRegistry

FRAME = object()

TARGET_A = "cv-detector-1:50051"
TARGET_B = "cv-detector-2:50051"


def det(label="car", x=0.1, y=0.1, w=0.1, h=0.1, confidence=0.9) -> Detection:
    return Detection(label, confidence, x, y, w, h)


class CostOnlyRegistry:
    """The real `cost` associator and nothing else.

    Deliberately NOT a fake associator: `cost` is the only associator the
    roster can build since E16, and it is the one whose keys are minted by
    the `TrackBook` rather than by the engine -- which is precisely the
    property that makes a detector swap invisible. A fake would prove the
    fake.
    """

    def __init__(self) -> None:
        from cv_service.tracking.assign import AssignGates, AssignWeights, CostAssociator

        self.engine = CostAssociator(weights=AssignWeights(), gates=AssignGates())

    def associator(self, engine_id, *, max_age_frames, level=None):
        return self.engine.engine_id, self.engine

    def follower(self, engine_id, *, max_age_frames, level=None):
        return None

    def compensator(self, engine_id, *, level=None):
        return None

    def appearance(self, engine_id, *, level=None):
        return None


class FakeRemote:
    """A `RemoteDetector`: answers with the detections it was handed, or
    refuses exactly the way a real instance at its queue bound does."""

    def __init__(self, target: str, detections) -> None:
        self.target = target
        self.detections = list(detections)
        self.busy = False
        self.down = False
        self.calls = 0
        self.payloads: "list[FramePayload]" = []

    def detect(self, payload: FramePayload, roi=None) -> DetectorPass:
        self.calls += 1
        self.payloads.append(payload)
        if self.busy:
            raise DetectorBusy(f"{self.target}: queue full")
        if self.down:
            raise DetectorUnavailable(f"{self.target}: unreachable")
        return DetectorPass(
            detections=list(self.detections),
            inference_millis=11,
            wait_ms=0.0,
            roi=roi is not None,
        )


class WireDetect:
    """The servicer's per-frame callable, in its W4 shape: still callable
    (the local client's only contract) and now also carrying this frame's
    wire payload (the pool's only extra requirement)."""

    def __init__(self, payload: FramePayload, detections) -> None:
        self.payload = payload
        self.detections = list(detections)
        self.calls = 0

    def __call__(self, roi=None):
        self.calls += 1
        if roi is not None:
            return [], 5
        return list(self.detections), 5


def payload(sequence: int) -> FramePayload:
    return FramePayload(
        stream_id="affinity",
        sequence=sequence,
        width=640,
        height=480,
        encoding="IMAGE_ENCODING_JPEG",
        data=b"\xff\xd8jpeg",
        model_id="yolo26n",
    )


def associate_session(client=None) -> StreamTrackingSession:
    subject = StreamTrackingSession(
        settings=Settings(), registry_provider=lambda: CostOnlyRegistry()
    )
    subject.stream_id = "affinity"
    if client is not None:
        subject.detect_through(client)
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, min_hits=1))
    return subject


def pool(*remotes) -> PoolDetectorClient:
    return PoolDetectorClient(remotes, local=LocalDetectorClient())


def run(subject, sequence, detections):
    return subject.process(
        now_millis=sequence * 100.0,
        detect=WireDetect(payload(sequence), detections),
        frame=lambda: FRAME,
    )


SCENE = [det("car"), det("person", x=0.6)]


# -- 1. a detector swap mid-stream is invisible to identities ---------------


def test_ids_survive_a_detector_swap_mid_stream():
    first, second = FakeRemote(TARGET_A, SCENE), FakeRemote(TARGET_B, SCENE)
    client = pool(first, second)
    subject = associate_session(client)

    ids, served = [], []
    for sequence in range(8):
        # Halfway through the stream, target A hits its queue bound and
        # stays there -- a real detector being drained, scaled down, or
        # simply losing the admission race to another tracker.
        first.busy = sequence >= 4
        outcome = run(subject, sequence, SCENE)
        ids.append([box.track.track_id for box in outcome.boxes])
        served.append(client.served_by)

    assert served == [TARGET_A] * 4 + [TARGET_B] * 4
    assert ids == [[1, 2]] * 8, "a detector swap must never renumber a track"
    assert [track.label for track in subject.tracks] == ["car", "person"]

    health = {entry.target: entry for entry in client.health}
    assert (health[TARGET_A].served, health[TARGET_A].refused) == (4, 4)
    assert (health[TARGET_B].served, health[TARGET_B].refused) == (4, 0)
    # A refusal is the fleet budget working, not a fault (§4.9, E10).
    assert health[TARGET_A].failed == 0


def test_the_swap_is_recorded_per_frame_in_the_ledger():
    first, second = FakeRemote(TARGET_A, SCENE), FakeRemote(TARGET_B, SCENE)
    client = pool(first, second)
    subject = associate_session(client)

    run(subject, 0, SCENE)
    first.busy = True
    run(subject, 1, SCENE)

    rows = [ledger.entry_for("detect.full").summary for ledger in subject.ledgers.last(0)]
    assert rows[0]["served_by"] == TARGET_A
    assert rows[0]["hops"] == "0"
    assert rows[1]["served_by"] == TARGET_B
    assert rows[1]["hops"] == "1", "the cost of the failover is on the frame that paid it"


def test_every_target_sees_the_same_stream_id_and_a_moving_sequence():
    first, second = FakeRemote(TARGET_A, SCENE), FakeRemote(TARGET_B, SCENE)
    client = pool(first, second)
    subject = associate_session(client)

    for sequence in range(4):
        first.busy = sequence >= 2
        run(subject, sequence, SCENE)

    # stream_id travels for ACCOUNTING only -- both instances see it, and
    # neither is allowed to key anything on it (§4.9: detectors are stateless).
    assert [p.stream_id for p in first.payloads] == ["affinity"] * 4
    assert [p.stream_id for p in second.payloads] == ["affinity"] * 2
    assert [p.sequence for p in second.payloads] == [2, 3]


# -- 2. a reconnect keeps both the ids and the placement --------------------


def test_a_reconnect_keeps_the_ids_and_the_pool_placement():
    first, second = FakeRemote(TARGET_A, SCENE), FakeRemote(TARGET_B, SCENE)
    client = pool(first, second)
    built: "list[StreamTrackingSession]" = []

    def factory() -> StreamTrackingSession:
        subject = StreamTrackingSession(
            settings=Settings(), registry_provider=lambda: CostOnlyRegistry()
        )
        subject.detect_through(client)
        built.append(subject)
        return subject

    registry = SessionRegistry(grace_millis=5_000, capacity=8, session_factory=factory)

    subject = registry.acquire("affinity")
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, min_hits=1))
    before = [box.track.track_id for box in run(subject, 0, SCENE).boxes]

    # The transport dropped: `release` calls `reset_for_reconnect`, which
    # invalidates the roster -- the exact path that would silently revert a
    # session to in-process detection if `detect_through` were forgotten.
    registry.release("affinity", subject)
    resumed = registry.acquire("affinity")
    assert resumed is subject
    assert len(built) == 1

    # The detector the stream had is gone by the time it comes back.
    first.busy = True
    after = [box.track.track_id for box in run(resumed, 1, SCENE).boxes]

    assert before == [1, 2]
    assert after == before, "a reconnect must not renumber a track either"
    assert client.served_by == TARGET_B
    assert second.calls == 1, "the resumed session still detects through the POOL"


def test_reset_for_reconnect_does_not_revert_a_session_to_local_detection():
    first = FakeRemote(TARGET_A, SCENE)
    client = pool(first)
    subject = associate_session(client)
    run(subject, 0, SCENE)

    subject.reset_for_reconnect()
    run(subject, 1, SCENE)

    # The roster `reset_for_reconnect` invalidated rebuilt around the POOL,
    # not around the in-process client `__init__` left behind.
    assert first.calls == 2


# -- 3. an exhausted fleet is a per-frame degradation, and it recovers ------


def test_an_exhausted_fleet_degrades_one_frame_and_keeps_the_ids():
    first, second = FakeRemote(TARGET_A, SCENE), FakeRemote(TARGET_B, SCENE)
    client = pool(first, second)
    subject = associate_session(client)

    before = [box.track.track_id for box in run(subject, 0, SCENE).boxes]

    first.busy = second.busy = True
    outcome = run(subject, 1, SCENE)
    # §4.1 rule 4: the contributor raised and the frame CONTINUED -- the
    # tracker coasted on what it already knows instead of the whole frame
    # failing. The refusal is named per target, which is §4.9's "reported
    # degradation, never a silent queue".
    failed = subject.ledgers.last(1)[0].entry_for("detect.full")
    assert failed.outcome == OUTCOME_FAILED
    assert TARGET_A in failed.reason and TARGET_B in failed.reason
    assert outcome.boxes == [], "nothing was detected, so nothing is claimed"
    # KNOWN DIVERGENCE, asserted so it cannot drift silently:
    # `DetectionResponse.detector_ran` is the SCHEDULER'S DECISION (`budget.
    # run_detector`), not "a detector answered" -- it has meant that since
    # TRACKING-V2 and W4 deliberately did not redefine a wire field the Java
    # side already reads. On an exhausted fleet the two diverge for the first
    # time: `True` here means "this frame was due a detector pass", and the
    # LEDGER above is the only surface that says whether one happened.
    assert outcome.detector_ran is True

    first.busy = second.busy = False
    after = [box.track.track_id for box in run(subject, 2, SCENE).boxes]

    assert before == [1, 2]
    assert after == before, "an outage the fleet recovered from costs no identities"


def test_the_pool_raises_rather_than_queueing_when_every_target_refuses():
    first, second = FakeRemote(TARGET_A, SCENE), FakeRemote(TARGET_B, SCENE)
    first.busy = second.busy = True
    client = pool(first, second)
    client.bind(WireDetect(payload(0), SCENE))

    with pytest.raises(NoDetectorAvailable):
        client.detect()

    # Never silently served locally: a tracker with `CV_DETECTOR_TARGETS`
    # set has no in-process model loaded to fall back to.
    assert client.served_by == ""
