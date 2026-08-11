"""Wave T1 at the wire: `DetectStream` with `FrameRequest.tracking`.

Everything here is deterministic -- fake detectors, fake engines, a fake
clock, a recording gate -- so nothing depends on timing or on the `cv`
extra's real models. Requires only the generated stubs
(`scripts/gen_proto.sh`) and `cv2`/`numpy` (via `decode_frame` on the FOLLOW
path), same as this directory's other servicer tests.
"""

from __future__ import annotations

import dataclasses

import numpy as np
import pytest

from cv_service.config import Settings
from cv_service.grpc import servicers as servicers_module
from cv_service.grpc.servicers import InferenceServicer, cv_pb2
from cv_service.inference.detector import Detection
from cv_service.tracking.engines.base import Box, Observation, TrackerUpdate

WIDTH, HEIGHT = 8, 6
FRAME_BYTES = bytes(np.zeros((HEIGHT, WIDTH, 3), dtype=np.uint8).tobytes())


def frame_request(sequence: int, tracking=None, **overrides):
    fields = dict(
        stream_id="stream-1",
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
    fields.update(overrides)
    request = cv_pb2.FrameRequest(**fields)
    if tracking is not None:
        request.tracking.CopyFrom(tracking)
    return request


class FakeDetector:
    """One steady detection plus a distractor; counts its own calls."""

    model_name = "fake.pt"

    def __init__(self) -> None:
        self.calls = 0

    def detect(self, **_kwargs):
        self.calls += 1
        return [
            Detection("car", 0.9, 0.10, 0.10, 0.10, 0.10),
            Detection("person", 0.7, 0.70, 0.70, 0.06, 0.12),
        ], 11


class RecordingGate:
    """An `InferenceGate` that records every acquisition.

    Deterministic on purpose: TRACKING-PLAN §3.1's "the tracker update never
    acquires `InferenceGate`" is a structural claim, and a timing-based test
    of it would be a flake generator.
    """

    def __init__(self) -> None:
        self.acquisitions = 0

    def acquire(self):
        gate = self

        class _Scope:
            def __enter__(self):
                gate.acquisitions += 1
                return None

            def __exit__(self, *_exc):
                return False

        return _Scope()


class StubAssociator:
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
    engine_id = "stub-follow"

    def __init__(self) -> None:
        self.box = None
        self.raise_on_update = False

    def init(self, frame, box):
        assert isinstance(frame, np.ndarray), "the FOLLOW path must receive decoded pixels"
        self.box = box
        return True

    def update(self, frame):
        assert isinstance(frame, np.ndarray)
        if self.raise_on_update:
            raise RuntimeError("engine exploded mid-frame")
        return TrackerUpdate(box=self.box, confidence=0.9)

    def reset(self):
        pass


class StubTrackerRegistry:
    def __init__(self, *, associator=StubAssociator, follower=StubFollower) -> None:
        self._associator = associator
        self._follower = follower
        self.created = []

    def associator(self, engine_id, *, max_age_frames):
        if self._associator is None:
            return None
        engine = self._associator()
        self.created.append(engine)
        return engine.engine_id, engine

    def follower(self, engine_id, *, max_age_frames):
        if self._follower is None:
            return None
        engine = self._follower()
        self.created.append(engine)
        return engine.engine_id, engine


class FakeClock:
    """Drives `time.monotonic` so the duty cycle is exact, not wall-clock."""

    def __init__(self) -> None:
        self.seconds = 0.0

    def __call__(self) -> float:
        return self.seconds


@pytest.fixture
def clock(monkeypatch):
    fake = FakeClock()
    monkeypatch.setattr(servicers_module.time, "monotonic", fake)
    return fake


_DEFAULT = object()


def servicer(detector=None, gate=None, tracker_registry=_DEFAULT) -> InferenceServicer:
    return InferenceServicer(
        detector=detector if detector is not None else FakeDetector(),
        inference_gate=gate if gate is not None else RecordingGate(),
        settings=Settings(),
        tracker_registry=StubTrackerRegistry() if tracker_registry is _DEFAULT else tracker_registry,
    )


def tracking(mode, *, lock=None, **fields):
    config = cv_pb2.TrackingConfig(mode=mode, **fields)
    if lock is not None:
        config.lock.CopyFrom(lock)
    return config


def drive(subject, requests):
    """Run a whole stream through `_handle_request` with one session.

    Deliberately not through `DetectStream`: its `_StreamReader` is a real
    background thread against a zero-latency in-memory iterator, which
    legitimately drops frames (cv-service/MODULE.md, `LatestOnlyMailbox`) and
    would make a frame-count assertion a race. `DetectStream`'s own session
    wiring is covered separately below.
    """
    session = servicers_module.StreamTrackingSession(
        settings=Settings(), registry_provider=subject._resolve_tracker_registry
    )
    return [subject._handle_request(request, session) for request in requests]


# --- OFF: byte-identical to the pre-tracking service --------------------------


def test_a_request_with_no_tracking_field_returns_a_byte_identical_response(clock):
    detector = FakeDetector()
    subject = servicer(detector=detector)

    with_session = drive(subject, [frame_request(1)])[0]
    without_session = subject._handle_request(frame_request(1))

    assert with_session.SerializeToString() == without_session.SerializeToString()
    # ...and not one tracking field is set, so an old Java client parses
    # exactly the bytes it parsed before this wave existed.
    assert {field.name for field, _ in with_session.ListFields()} == {
        "stream_id",
        "sequence",
        "timestamp_millis",
        "model_id",
        "model_version",
        "detections",
        "inference_millis",
    }
    assert all(
        {f.name for f, _ in detection.ListFields()} == {"label", "confidence", "box"}
        for detection in with_session.detections
    )


def test_an_explicit_off_mode_is_byte_identical_to_an_absent_one(clock):
    subject = servicer()

    absent = subject._handle_request(frame_request(1))
    explicit = drive(
        subject, [frame_request(1, tracking(cv_pb2.TrackingMode.TRACKING_MODE_OFF))]
    )[0]

    assert explicit.SerializeToString() == absent.SerializeToString()


def test_a_mode_this_build_does_not_know_is_treated_as_off(clock):
    subject = servicer()
    request = frame_request(1)
    request.tracking.mode = 99  # a newer client

    assert subject._handle_request(request).SerializeToString() == (
        subject._handle_request(frame_request(1)).SerializeToString()
    )


# --- ASSOCIATE ----------------------------------------------------------------


def test_associate_puts_stable_ids_on_the_wire(clock):
    subject = servicer()
    config = tracking(cv_pb2.TrackingMode.TRACKING_MODE_ASSOCIATE, min_hits=1)

    responses = drive(subject, [frame_request(i, config) for i in range(3)])

    for response in responses:
        assert response.detector_ran is True
        assert response.detector_reason == cv_pb2.DetectorReason.DETECTOR_REASON_ALWAYS
        assert response.tracker_engine_id == "stub-assoc"
        assert [detection.track_id for detection in response.detections] == [1, 2]
        assert all(
            detection.track_state == cv_pb2.TrackState.TRACK_STATE_CONFIRMED
            for detection in response.detections
        )
        assert all(
            detection.source == cv_pb2.DetectionSource.DETECTION_SOURCE_DETECTOR
            for detection in response.detections
        )
    assert responses[-1].detections[0].track_age_frames == 2


def test_two_streams_get_independent_id_spaces(clock):
    subject = servicer()
    config = tracking(cv_pb2.TrackingMode.TRACKING_MODE_ASSOCIATE, min_hits=1)

    left = drive(subject, [frame_request(0, config)])
    right = drive(subject, [frame_request(0, config)])

    assert [d.track_id for d in left[0].detections] == [1, 2]
    assert [d.track_id for d in right[0].detections] == [1, 2]


# --- FOLLOW: the duty cycle ---------------------------------------------------


def follow_config(**fields):
    lock = cv_pb2.TargetLock(lock_seq=1, point_x=0.15, point_y=0.15)
    return tracking(
        cv_pb2.TrackingMode.TRACKING_MODE_FOLLOW, lock=lock, min_hits=1, **fields
    )


def test_follow_runs_the_detector_at_the_cadence_and_no_more(clock):
    detector = FakeDetector()
    subject = servicer(detector=detector)
    config = follow_config(verify_every_millis=2000)

    session = servicers_module.StreamTrackingSession(
        settings=Settings(), registry_provider=subject._resolve_tracker_registry
    )
    responses = []
    for frame in range(150):  # 10 s at 15 fps
        clock.seconds = frame / 15.0
        responses.append(subject._handle_request(frame_request(frame, config), session))

    passes = sum(1 for response in responses if response.detector_ran)

    assert passes == 5
    assert detector.calls == 5
    assert all(response.locked_track_id == 1 for response in responses)
    tracker_only = [r for r in responses if not r.detector_ran]
    assert len(tracker_only) == 145
    assert all(
        r.detections[0].source == cv_pb2.DetectionSource.DETECTION_SOURCE_TRACKER
        for r in tracker_only
    )
    assert all(
        r.detector_reason == cv_pb2.DetectorReason.DETECTOR_REASON_UNSPECIFIED
        for r in tracker_only
    )


def test_the_tracker_update_never_acquires_the_inference_gate(clock):
    gate = RecordingGate()
    subject = servicer(gate=gate)
    config = follow_config(verify_every_millis=100_000)  # exactly one pass, ever

    session = servicers_module.StreamTrackingSession(
        # `track_max_age_millis` overridden well past this test's ~4s window:
        # this test's target is deliberately never re-confirmed by the
        # detector after frame 0 (that is the whole point -- it is proving
        # 59 STRAIGHT tracker-only frames never touch the gate), which is
        # exactly the wall-clock LOST rule's own trigger condition
        # (TRACKING-V2-PLAN wave C1, review finding B7). Left at the
        # production default, this test's target would go LOST partway
        # through and force a second, gate-acquiring detector pass -- a real
        # consequence of that fix, but an orthogonal one to what THIS test
        # checks, so it is decoupled here rather than left to collide.
        settings=dataclasses.replace(Settings(), track_max_age_millis=1_000_000),
        registry_provider=subject._resolve_tracker_registry,
    )
    first = subject._handle_request(frame_request(0, config), session)
    assert first.detector_ran is True
    assert gate.acquisitions == 1

    for frame in range(1, 60):
        clock.seconds = frame / 15.0
        response = subject._handle_request(frame_request(frame, config), session)
        assert response.detector_ran is False

    # 59 tracker-only frames later, the gate has still been taken exactly
    # once -- by the one detector pass, never by a tracker update.
    assert gate.acquisitions == 1


def test_a_lock_applies_only_on_a_strictly_greater_seq(clock):
    subject = servicer()
    session = servicers_module.StreamTrackingSession(
        settings=Settings(), registry_provider=subject._resolve_tracker_registry
    )
    first = tracking(
        cv_pb2.TrackingMode.TRACKING_MODE_FOLLOW,
        lock=cv_pb2.TargetLock(lock_seq=2, point_x=0.15, point_y=0.15),
        min_hits=1,
        verify_every_millis=1,
    )
    subject._handle_request(frame_request(0, first), session)
    held = session._lock.generation

    stale = tracking(
        cv_pb2.TrackingMode.TRACKING_MODE_FOLLOW,
        lock=cv_pb2.TargetLock(lock_seq=1, point_x=0.75, point_y=0.75),
        min_hits=1,
        verify_every_millis=1,
    )
    clock.seconds = 1.0
    subject._handle_request(frame_request(1, stale), session)

    assert session._lock.generation == held
    assert session._lock.target.point == pytest.approx((0.15, 0.15))


def test_restating_the_identical_config_never_re_resolves_it(clock):
    subject = servicer()
    config = tracking(cv_pb2.TrackingMode.TRACKING_MODE_ASSOCIATE, min_hits=1)
    session = servicers_module.StreamTrackingSession(
        settings=Settings(), registry_provider=subject._resolve_tracker_registry
    )

    applications = []
    original = session.apply_config
    session.apply_config = lambda *a, **k: (applications.append(1), original(*a, **k))[1]

    for frame in range(20):
        clock.seconds = frame / 15.0
        subject._handle_request(frame_request(frame, config), session)

    assert len(applications) == 1


# --- degradation --------------------------------------------------------------


def test_a_raising_engine_degrades_one_frame_and_the_stream_survives(clock):
    subject = servicer()
    config = follow_config(verify_every_millis=100_000)
    session = servicers_module.StreamTrackingSession(
        settings=Settings(), registry_provider=subject._resolve_tracker_registry
    )

    subject._handle_request(frame_request(0, config), session)
    session._engine.raise_on_update = True

    clock.seconds = 1.0
    degraded = subject._handle_request(frame_request(1, config), session)
    assert degraded.detections == []  # no track facts invented for this frame

    clock.seconds = 2.0
    survived = subject._handle_request(frame_request(2, config), session)
    assert survived.detector_ran is True  # re-acquires, stream still alive
    # A reset engine re-numbers from its own beginning, so the book retires
    # the old id rather than letting a new object inherit it.
    assert survived.locked_track_id == 2


def test_no_tracker_registry_serves_detections_without_track_ids(clock):
    subject = servicer(tracker_registry=None)
    config = tracking(cv_pb2.TrackingMode.TRACKING_MODE_ASSOCIATE)

    responses = drive(subject, [frame_request(i, config) for i in range(2)])

    assert [d.track_id for d in responses[0].detections] == [0, 0]
    assert responses[0].tracker_engine_id == ""


# --- the session lives where `_StreamReader` does -----------------------------


def test_detect_stream_creates_one_session_per_call(clock):
    subject = servicer()
    config = tracking(cv_pb2.TrackingMode.TRACKING_MODE_ASSOCIATE, min_hits=1)

    first = list(subject.DetectStream(iter([frame_request(0, config)]), None))
    second = list(subject.DetectStream(iter([frame_request(0, config)]), None))

    # Two calls, two sessions: ids restart at 1 rather than continuing.
    assert [d.track_id for d in first[0].detections] == [1, 2]
    assert [d.track_id for d in second[0].detections] == [1, 2]


def test_the_tracker_registry_is_built_lazily_and_only_once():
    built = []

    class CountingServicer(InferenceServicer):
        def _resolve_tracker_registry(self):
            built.append(1)
            return super()._resolve_tracker_registry()

    subject = CountingServicer(
        detector=FakeDetector(),
        inference_gate=RecordingGate(),
        settings=Settings(),
        tracker_registry=StubTrackerRegistry(),
    )

    subject._handle_request(frame_request(0))  # OFF: never asks for a registry
    assert built == []
