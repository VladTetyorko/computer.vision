"""`DetectionResponse.objects` / `.ledger` at the wire (CV-ORCHESTRATION W1).

The mirror's whole point is that the wire can now report what the service
BELIEVES, not only what it drew -- so every test here asserts against the
serialized `DetectionResponse`, never against `FrameOutcome`. The unit-level
rules the builder itself obeys (absent group vs. zero field, read-only)
live in `tests/orchestration/test_mirror.py`.

Reuses `test_detect_stream_tracking.py`'s doubles rather than re-inventing
them: same fake clock, same scripted detector, same stub registry, so a
behaviour these tests assert is the behaviour that file's own tests see.
"""

from __future__ import annotations

import dataclasses

import pytest

from cv_service.config import Settings
from cv_service.grpc import servicers as servicers_module
from cv_service.grpc.servicers import cv_pb2
from cv_service.inference.detector import Detection
from cv_service.tracking.assign import AssignGates, AssignWeights, CostAssociator

from tests.grpc.test_detect_stream_tracking import (  # noqa: F401 - `clock` is a fixture
    ScriptedDetector,
    StubTrackerRegistry,
    _memory_config,
    clock,
    frame_request,
    servicer,
    tracking,
)


def _cost_associator() -> CostAssociator:
    """The REAL matcher, not `StubCostAssociator`.

    Two of the mirror's fields exist only when a genuine match happened:
    `kinematics.detector_box` (the raw target a track was paired with) and
    `provenance.assoc_cost` (which needs the engine's own `.cost`, a method
    the stub does not have). A test that used the stub would assert those two
    fields are empty and prove nothing about them.
    """
    return CostAssociator(weights=AssignWeights(), gates=AssignGates())


STREAM_ID = "stream-1"  # what `frame_request` puts on every request here.


def _session(subject, **settings):
    """A session stamped with its stream id, exactly as `SessionRegistry._mint`
    does in production.

    Hand-built sessions are this directory's convention (`drive()`), and they
    skip that stamping -- which matters here and nowhere else: `ObjectState.
    stream_id` and `FrameLedger.stream_id` both read the SESSION's id, while
    `DetectionResponse.stream_id` reads the REQUEST's. Leaving it blank would
    make these tests assert `""` and quietly stop checking that the two agree.
    """
    session = servicers_module.StreamTrackingSession(
        settings=dataclasses.replace(Settings(), track_roi_enabled=False, **settings),
        registry_provider=subject._resolve_tracker_registry,
    )
    session.stream_id = STREAM_ID
    return session


def _by_id(response) -> "dict[int, object]":
    return {state.id: state for state in response.objects}


# --- the mirror is additive, and detections do not move -----------------------


def test_objects_ride_alongside_detections_without_disturbing_them(clock):
    # The acceptance criterion, at the wire: turning the mirror on must not
    # change a single byte of `detections[]`. Asserted by serializing each
    # `Detection` submessage -- field-by-field equality would still pass if a
    # field were silently re-encoded, and bytes are what the Java codec reads.
    detector = ScriptedDetector(
        [[Detection("car", 0.9, 0.40, 0.50, 0.10, 0.10)] for _ in range(3)]
    )
    subject = servicer(
        detector=detector, tracker_registry=StubTrackerRegistry(associator=_cost_associator)
    )
    session = _session(subject)

    responses = []
    for frame in range(3):
        clock.seconds = float(frame)
        responses.append(subject._handle_request(frame_request(frame, _memory_config()), session))

    last = responses[-1]
    assert [d.SerializeToString() for d in last.detections] == [
        cv_pb2.Detection(
            label="car",
            confidence=0.9,
            box=cv_pb2.BoundingBox(x=0.40, y=0.50, width=0.10, height=0.10),
            track_id=1,
            track_state=cv_pb2.TrackState.TRACK_STATE_CONFIRMED,
            source=cv_pb2.DetectionSource.DETECTION_SOURCE_DETECTOR,
            track_age_frames=2,
        ).SerializeToString()
    ]
    assert len(last.objects) == 1
    assert last.objects[0].id == 1


def test_the_mirror_reports_an_object_the_detections_array_never_shows(clock):
    # Why this wave exists (plan §4.5, R4 Part B): a COASTING track is real
    # state the service is acting on, and before the mirror the wire could
    # only show it if it also happened to carry a box that frame.
    detector = ScriptedDetector(
        [
            [Detection("car", 0.9, 0.40, 0.50, 0.10, 0.10)],
            [],  # unseen: the track coasts, and emits no detection at all
        ]
    )
    subject = servicer(
        detector=detector, tracker_registry=StubTrackerRegistry(associator=_cost_associator)
    )
    session = _session(subject)

    for frame in range(2):
        clock.seconds = float(frame)
        response = subject._handle_request(frame_request(frame, _memory_config()), session)

    assert list(response.detections) == []
    assert [state.id for state in response.objects] == [1]
    assert response.objects[0].lifecycle == cv_pb2.OBJECT_LIFECYCLE_COASTING
    assert response.objects[0].provenance.source == cv_pb2.EVIDENCE_SOURCE_PREDICTED


# --- every group, on one real frame ------------------------------------------


def test_every_group_carries_this_frames_own_facts(clock):
    # Plan §4.5's table, end to end: each of the seven groups filled from the
    # internal fact it names, on a frame where the real matcher actually
    # matched. Frame 1 (not 0) because `predicted_box` needs a PREVIOUS track
    # to extrapolate from.
    detector = ScriptedDetector(
        [
            [Detection("car", 0.90, 0.40, 0.50, 0.10, 0.10)],
            [Detection("car", 0.70, 0.42, 0.50, 0.10, 0.10)],
        ]
    )
    subject = servicer(
        detector=detector, tracker_registry=StubTrackerRegistry(associator=_cost_associator)
    )
    session = _session(subject)

    for frame in range(2):
        clock.seconds = float(frame)
        response = subject._handle_request(frame_request(frame, _memory_config()), session)

    state = response.objects[0]
    assert state.id == 1
    assert state.stream_id == response.stream_id == STREAM_ID
    assert state.lifecycle == cv_pb2.OBJECT_LIFECYCLE_CONFIRMED

    assert state.identity.label == "car"
    assert state.identity.label_raw == "car"
    assert [c.label for c in state.identity.candidates] == ["car"]
    assert state.identity.stability >= 1

    assert state.kinematics.box.x == pytest.approx(0.42)
    # The RAW detector target, kept separate from the booked box precisely
    # because late/ORU correction can make the two differ.
    assert state.kinematics.detector_box.x == pytest.approx(0.42)
    # Extrapolated from frame 0's box by `predict.cv`, so it is the OLD
    # position plus one frame of (still zero) velocity -- not the new one.
    assert state.kinematics.predicted_box.x == pytest.approx(0.40)
    assert state.kinematics.horizon_ms == 1000
    assert state.kinematics.displacement_x == pytest.approx(0.02)
    assert state.kinematics.motion_compensated is False

    assert state.belief.confidence_raw == pytest.approx(0.70)
    # The EMA, seeded at birth from 0.90 and pulled toward 0.70: strictly
    # between the two, which is the whole difference from `confidence_raw`.
    assert 0.70 < state.belief.confidence_smoothed < 0.90
    assert state.belief.existence == pytest.approx(1.0)  # two hits, no misses
    assert state.belief.since_confirmed_ms == 0

    assert state.provenance.source == cv_pb2.EVIDENCE_SOURCE_DETECTOR
    assert "assoc.cost" in state.provenance.contributors
    assert state.provenance.assoc_cost > 0.0
    assert state.provenance.reupdated is False

    # A gallery IS resolved for this stream, and it did not recover this
    # object -- an absent group would have said "nobody could be asked".
    assert state.HasField("memory")
    assert state.memory.recovered is False
    assert state.memory.match_distance == pytest.approx(1.0)

    assert state.timing.hits == 2
    assert state.timing.misses == 0
    assert state.timing.age_frames == 1


def test_the_lock_group_is_absent_until_a_lock_has_ever_been_applied(clock):
    # Absent group vs. zero field: `Lock(locked=False)` would claim this
    # object lost a contest that never took place.
    detector = ScriptedDetector([[Detection("car", 0.9, 0.40, 0.50, 0.10, 0.10)]])
    subject = servicer(
        detector=detector, tracker_registry=StubTrackerRegistry(associator=_cost_associator)
    )
    session = _session(subject)

    clock.seconds = 0.0
    response = subject._handle_request(frame_request(0, _memory_config()), session)

    assert response.objects[0].HasField("lock") is False


# --- DORMANT reaches the wire ------------------------------------------------


def test_a_dormant_identity_appears_on_the_wire_after_its_track_retires(clock):
    # The acceptance criterion for step 2, and the one state the wire could
    # not express at all before this wave: an id the book has RETIRED but the
    # gallery still remembers. Same script as
    # `test_a_recovered_track_keeps_its_id_and_reports_the_recovery_honestly`
    # -- `max_age_frames=2` makes `_expire()`'s limit 4 consecutive misses,
    # so by frame 5 track #1 is genuinely gone from `TrackBook`.
    detector = ScriptedDetector(
        [
            [Detection("car", 0.9, 0.40, 0.50, 0.10, 0.10)],
            [],
            [],
            [],
            [],
            [],
            [Detection("car", 0.9, 0.41, 0.50, 0.10, 0.10)],
        ]
    )
    subject = servicer(
        detector=detector, tracker_registry=StubTrackerRegistry(associator=_cost_associator)
    )
    session = _session(subject)

    responses = []
    for frame in range(7):
        clock.seconds = float(frame)
        responses.append(subject._handle_request(frame_request(frame, _memory_config()), session))

    dormant_frames = [
        index
        for index, response in enumerate(responses)
        if any(
            state.lifecycle == cv_pb2.OBJECT_LIFECYCLE_DORMANT for state in response.objects
        )
    ]
    assert dormant_frames, "no frame reported a DORMANT object"

    response = responses[dormant_frames[0]]
    assert list(response.detections) == [], "a dormant identity must not fabricate a detection"
    dormant = next(
        state for state in response.objects if state.lifecycle == cv_pb2.OBJECT_LIFECYCLE_DORMANT
    )
    assert dormant.id == 1
    assert dormant.identity.label == "car"
    assert dormant.provenance.source == cv_pb2.EVIDENCE_SOURCE_MEMORY
    # `0` on the frame it was retired on -- that is the truthful elapsed
    # time, not a missing value -- and it must then GROW, which is the fact
    # an operator reads to know whether it is still worth waiting for.
    assert dormant.memory.dormant_ms == 0
    later = responses[dormant_frames[-1]]
    assert next(
        state.memory.dormant_ms
        for state in later.objects
        if state.lifecycle == cv_pb2.OBJECT_LIFECYCLE_DORMANT
    ) == 1000 * (dormant_frames[-1] - dormant_frames[0])
    # Its last sighting IS the moment it was lost -- never `0`, which on this
    # wire reads as "never seen".
    assert dormant.timing.last_seen_ms == dormant.timing.last_confirmed_ms
    assert dormant.kinematics.box.x == pytest.approx(0.40)

    # And once it is recovered it is LIVE again, not reported twice.
    recovered = responses[6]
    assert [state.lifecycle for state in recovered.objects] == [
        cv_pb2.OBJECT_LIFECYCLE_CONFIRMED
    ]
    assert recovered.objects[0].memory.recovered is True


# --- the clock rebase --------------------------------------------------------


def test_timing_is_rebased_onto_this_responses_own_timestamp(clock):
    # cv-service ages tracks on `time.monotonic()`; `Timing`'s instants are
    # useless to a consumer on that clock. A track touched THIS frame has
    # `last_seen == now`, so after the single rebase its `last_seen_ms` must
    # be exactly the response's own `timestamp_millis` -- which is the only
    # clock the consumer already holds.
    detector = ScriptedDetector([[Detection("car", 0.9, 0.40, 0.50, 0.10, 0.10)]] * 2)
    subject = servicer(
        detector=detector, tracker_registry=StubTrackerRegistry(associator=_cost_associator)
    )
    session = _session(subject)

    for frame in range(2):
        clock.seconds = 1000.0 + frame  # far from the request timestamps, on purpose
        response = subject._handle_request(frame_request(frame, _memory_config()), session)

    state = response.objects[0]
    assert state.timing.last_seen_ms == response.timestamp_millis
    assert state.timing.last_confirmed_ms == response.timestamp_millis
    # Born one second earlier, on the same rebased timebase.
    assert state.timing.first_seen_ms == response.timestamp_millis - 1000


# --- the ledger rides only when asked ----------------------------------------


def test_the_ledger_is_attached_only_when_the_request_asks_to_trace(clock):
    detector = ScriptedDetector([[Detection("car", 0.9, 0.40, 0.50, 0.10, 0.10)]] * 2)
    subject = servicer(
        detector=detector, tracker_registry=StubTrackerRegistry(associator=_cost_associator)
    )
    session = _session(subject)

    clock.seconds = 0.0
    quiet = subject._handle_request(frame_request(0, _memory_config()), session)
    clock.seconds = 1.0
    traced = subject._handle_request(
        frame_request(1, _memory_config(), trace=True), session
    )

    assert quiet.HasField("ledger") is False
    assert traced.HasField("ledger") is True
    assert traced.ledger.stream_id == STREAM_ID
    assert traced.ledger.sequence == 1
    assert [entry.contributor_id for entry in traced.ledger.entries], "an empty ledger is not one"


def test_tracing_changes_the_ledger_and_nothing_else(clock):
    # `trace` is a debug demand, so it must be provably free of side effects
    # on the response an operator's UI reads. Two identical streams, one
    # traced, compared byte-for-byte on every other field.
    def run(trace: bool):
        detector = ScriptedDetector([[Detection("car", 0.9, 0.40, 0.50, 0.10, 0.10)]] * 3)
        subject = servicer(
            detector=detector, tracker_registry=StubTrackerRegistry(associator=_cost_associator)
        )
        session = _session(subject)
        responses = []
        for frame in range(3):
            clock.seconds = float(frame)
            responses.append(
                subject._handle_request(
                    frame_request(frame, _memory_config(), trace=trace), session
                )
            )
        return responses

    for quiet, traced in zip(run(False), run(True)):
        stripped = cv_pb2.DetectionResponse()
        stripped.CopyFrom(traced)
        stripped.ClearField("ledger")
        assert stripped.SerializeToString() == quiet.SerializeToString()
