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
from cv_service.grpc.servicers import InferenceServicer, _camera_pose_from_wire, cv_pb2
from cv_service.inference.detector import Detection
from cv_service.tracking.engines.base import Box, CameraPose, Observation, Transform, TrackerUpdate

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


class StubCostAssociator:
    """`assign.CostAssociator`-shaped test double: no matches, every target
    born fresh -- enough to prove the wire plumbing without needing the
    real Hungarian solver (already brute-force-verified in
    `tests/tracking/test_assign.py`)."""

    engine_id = "cost"

    def __init__(self) -> None:
        self.retune_calls = 0

    def retune(self, *, weights, gates):
        self.retune_calls += 1

    def assign(self, candidates, targets):
        from cv_service.tracking.assign import Assignment

        return Assignment(
            unmatched_candidates=tuple(range(len(candidates))),
            unmatched_targets=tuple(range(len(targets))),
        )

    def reset(self):
        pass


class StubAppearanceExtractor:
    """`AppearanceExtractor` test double -- counts calls, describes nothing."""

    engine_id = "stub-appearance"

    def __init__(self) -> None:
        self.describe_calls = 0

    def describe(self, frame, boxes):
        assert isinstance(frame, np.ndarray), "the appearance path must receive decoded pixels"
        self.describe_calls += 1
        return [None for _ in boxes]

    def reset(self):
        pass


class StubCompensator:
    """`MotionCompensator` test double -- a fixed, distinguishable transform,
    no pixels touched (TRACKING-V2-PLAN wave C2)."""

    engine_id = "stub-motion"

    def estimate(self, frame, pose):
        assert isinstance(frame, np.ndarray), "FOLLOW's memoized frame loader must still be reused"
        return Transform(c=0.02)

    def available(self, pose):
        return True

    def reset(self):
        pass


class StubTrackerRegistry:
    def __init__(
        self,
        *,
        associator=StubAssociator,
        follower=StubFollower,
        compensator=None,
        appearance=None,
    ) -> None:
        self._associator = associator
        self._follower = follower
        self._compensator = compensator
        self._appearance = appearance
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

    def compensator(self, engine_id):
        # No motion compensator by default (TRACKING-V2-PLAN wave C2): most
        # of this file's tests predate ego-motion compensation and assert
        # exact byte/field-count equality, so the default keeps them
        # behaviourally unchanged. Tests that DO exercise motion pass their
        # own `compensator=`.
        if self._compensator is None:
            return None
        engine = self._compensator()
        self.created.append(engine)
        return engine.engine_id, engine

    def appearance(self, engine_id):
        # No appearance extractor by default (TRACKING-V2-PLAN wave C3),
        # same reasoning as `compensator` above -- only reached at all when
        # `_associator` builds a `cost` engine, which no test in this file
        # does by default (`StubAssociator`'s own `engine_id` is `"stub-
        # assoc"`).
        if self._appearance is None:
            return None
        engine = self._appearance()
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
    # A live motion compensator too (TRACKING-V2-PLAN wave C2, P2): the
    # ego-motion path must be exactly as gate-free as the SOT engine's own
    # update path, structurally, not just by the absence of a test for it.
    subject = servicer(gate=gate, tracker_registry=StubTrackerRegistry(compensator=StubCompensator))
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


def test_associate_with_cost_motion_and_appearance_never_acquires_the_inference_gate(clock):
    # TRACKING-V2-PLAN wave C3's own P2 proof: `cost` gives ASSOCIATE a live
    # motion compensator AND a live appearance extractor for the first time
    # -- neither the ego-motion estimate nor the per-object histogram
    # extraction may queue behind YOLO. ASSOCIATE has no duty cycle (every
    # frame IS a detector pass), so the gate acquisition count must equal
    # the frame count exactly -- one per detector pass, never one more from
    # either new evidence path.
    gate = RecordingGate()
    subject = servicer(
        gate=gate,
        tracker_registry=StubTrackerRegistry(
            associator=StubCostAssociator, compensator=StubCompensator, appearance=StubAppearanceExtractor
        ),
    )
    config = tracking(
        cv_pb2.TrackingMode.TRACKING_MODE_ASSOCIATE,
        engine_id="cost",
        appearance_engine_id="stub-appearance",
        min_hits=1,
    )
    session = servicers_module.StreamTrackingSession(
        settings=Settings(), registry_provider=subject._resolve_tracker_registry
    )

    frame_count = 20
    for frame in range(frame_count):
        clock.seconds = frame / 15.0
        response = subject._handle_request(frame_request(frame, config), session)
        assert response.detector_ran is True

    assert gate.acquisitions == frame_count


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


# --- ego-motion compensation (TRACKING-V2-PLAN wave C2) ------------------------


def test_camera_pose_from_wire_maps_every_field():
    wire = cv_pb2.CameraPose(
        yaw_degrees=12.0,
        pitch_degrees=-3.5,
        roll_degrees=1.5,
        hfov_degrees=62.0,
        vfov_degrees=36.0,
        pose_timestamp_millis=987,
    )

    pose = _camera_pose_from_wire(wire)

    assert pose == CameraPose(
        yaw_degrees=12.0,
        pitch_degrees=-3.5,
        roll_degrees=1.5,
        hfov_degrees=62.0,
        vfov_degrees=36.0,
        timestamp_millis=987,
    )


def test_an_absent_camera_pose_maps_to_unknown():
    absent = _camera_pose_from_wire(cv_pb2.FrameRequest().camera_pose)

    assert absent == CameraPose()
    assert absent.known is False


def test_follow_reports_the_serving_motion_engine_and_its_cost_on_the_wire(clock):
    subject = servicer(tracker_registry=StubTrackerRegistry(compensator=StubCompensator))
    config = follow_config(verify_every_millis=100_000)

    responses = drive(subject, [frame_request(0, config)])

    assert responses[0].motion_engine_id == "stub-motion"
    assert responses[0].motion_millis >= 0


def test_no_motion_compensator_reports_no_compensation_on_the_wire(clock):
    subject = servicer()  # StubTrackerRegistry() default: no compensator
    config = follow_config(verify_every_millis=100_000)

    responses = drive(subject, [frame_request(0, config)])

    assert responses[0].motion_engine_id == ""
    assert responses[0].motion_millis == 0


def test_associate_never_populates_motion_fields_on_the_wire(clock):
    # `bytetrack` (or any non-`cost` associator) is deliberately NOT
    # compensated (TRACKING-V2-PLAN wave C2's own reasoning, unchanged by
    # wave C3): even with a compensator available, it must never be asked
    # for one -- `StubAssociator`'s `engine_id` is `"stub-assoc"`, not
    # `"cost"`. See `test_associate_with_cost_populates_motion_fields_on_
    # the_wire` below for the wave C3 counterpart.
    subject = servicer(tracker_registry=StubTrackerRegistry(compensator=StubCompensator))
    config = tracking(cv_pb2.TrackingMode.TRACKING_MODE_ASSOCIATE, min_hits=1)

    responses = drive(subject, [frame_request(0, config)])

    assert responses[0].motion_engine_id == ""
    assert responses[0].motion_millis == 0


def test_associate_with_cost_populates_motion_fields_on_the_wire(clock):
    # TRACKING-V2-PLAN wave C3's keystone, at the wire: `cost`'s candidates
    # ARE `TrackBook`'s own tracks, so ASSOCIATE now HAS a seam to warp --
    # unlike `bytetrack` above.
    subject = servicer(
        tracker_registry=StubTrackerRegistry(associator=StubCostAssociator, compensator=StubCompensator)
    )
    config = tracking(cv_pb2.TrackingMode.TRACKING_MODE_ASSOCIATE, engine_id="cost", min_hits=1)

    responses = drive(subject, [frame_request(0, config)])

    assert responses[0].motion_engine_id == "stub-motion"


def test_appearance_engine_id_on_the_wire_reaches_the_session(clock):
    # An end-to-end plumbing check for the wire field TRACKING-V2-PLAN §2
    # froze at C0 and this wave finally reads: `appearance_engine_id`
    # (field 9) genuinely crosses from `FrameRequest.tracking` into
    # `StreamTrackingSession`'s `cost` path and asks the registry for it --
    # there is no dedicated wire field to assert on (appearance has no wire
    # counterpart to `motion_millis`/`motion_engine_id` this wave), so the
    # observable proof is the stub extractor actually being built and used.
    built: list["StubAppearanceExtractor"] = []

    def make_extractor():
        extractor = StubAppearanceExtractor()
        built.append(extractor)
        return extractor

    subject = servicer(
        tracker_registry=StubTrackerRegistry(associator=StubCostAssociator, appearance=make_extractor)
    )
    config = tracking(
        cv_pb2.TrackingMode.TRACKING_MODE_ASSOCIATE,
        engine_id="cost",
        appearance_engine_id="stub-appearance",
        min_hits=1,
    )

    drive(subject, [frame_request(0, config)])

    assert len(built) == 1
    assert built[0].describe_calls == 1


def test_a_camera_pose_on_the_wire_reaches_the_session(clock):
    # An end-to-end plumbing check: a `pose`-requesting stream with a real
    # FOV on the wire gets `pose` compensation credited on the response,
    # never falling back to `flow` -- proving `camera_pose` genuinely
    # crosses from `FrameRequest` into `StreamTrackingSession.process`.
    class StubPoseCompensator(StubCompensator):
        engine_id = "pose"

        def estimate(self, frame, pose):
            assert pose.known, "a wire camera_pose with a real hfov must reach the compensator"
            return Transform()

    subject = servicer(tracker_registry=StubTrackerRegistry(compensator=StubPoseCompensator))
    config = follow_config(verify_every_millis=100_000, motion_engine_id="pose")

    responses = drive(
        subject,
        [frame_request(0, config, camera_pose=cv_pb2.CameraPose(hfov_degrees=60.0))],
    )

    assert responses[0].motion_engine_id == "pose"


# --- object memory (TRACKING-V2-PLAN wave C4) ----------------------------------


class ScriptedDetector:
    """Returns one pre-scripted list of `Detection`s per call, in order --
    empty once the script runs out. Unlike `FakeDetector`'s fixed two boxes,
    this lets a test control exactly what each frame sees, frame by frame."""

    model_name = "scripted"

    def __init__(self, script) -> None:
        self._script = list(script)
        self.calls = 0

    def detect(self, **_kwargs):
        detections = self._script[self.calls] if self.calls < len(self._script) else []
        self.calls += 1
        return detections, 0


def _memory_config(**fields):
    return tracking(
        cv_pb2.TrackingMode.TRACKING_MODE_ASSOCIATE,
        engine_id="cost",
        min_hits=1,
        max_age_frames=2,
        **fields,
    )


def test_a_recovered_track_keeps_its_id_and_reports_the_recovery_honestly(clock):
    # The wire-level counterpart to `long_occlusion`'s harness row
    # (`MODULE.md` "Wave C4"): a track that genuinely EXPIRES from
    # `TrackBook` -- not merely coasts -- and later reappears must come back
    # as the SAME `track_id`, with `identity_confidence`/`dormant_millis`
    # (`Detection` wire fields 10/11) reporting the recovery honestly on
    # that one frame and staying at their zero value on every other one.
    # `max_age_frames=2` -> `_expire()`'s own limit is 4 consecutive misses,
    # so 5 unseen frames (1-5) genuinely retire track #1 before frame 6
    # brings it back.
    detector = ScriptedDetector(
        [
            [Detection("car", 0.9, 0.40, 0.50, 0.10, 0.10)],  # frame 0: born as #1
            [],
            [],
            [],
            [],
            [],  # frames 1-5: unseen -- misses climbs past the expiry limit
            [Detection("car", 0.9, 0.41, 0.50, 0.10, 0.10)],  # frame 6: reappears
        ]
    )
    subject = servicer(
        detector=detector, tracker_registry=StubTrackerRegistry(associator=StubCostAssociator)
    )
    config = _memory_config()
    session = servicers_module.StreamTrackingSession(
        settings=Settings(), registry_provider=subject._resolve_tracker_registry
    )

    responses = []
    for frame in range(7):
        clock.seconds = float(frame)
        responses.append(subject._handle_request(frame_request(frame, config), session))

    born = responses[0].detections[0]
    assert born.track_id == 1
    assert born.identity_confidence == 0.0
    assert born.dormant_millis == 0

    recovered = responses[6].detections
    assert len(recovered) == 1
    assert recovered[0].track_id == 1  # the SAME id, not a fresh one
    assert recovered[0].track_state == cv_pb2.TrackState.TRACK_STATE_CONFIRMED
    assert recovered[0].identity_confidence > 0.0
    # Retired during frame 5 (clock.seconds == 5.0), recovered at frame 6
    # (clock.seconds == 6.0): a 1-second gap.
    assert recovered[0].dormant_millis == pytest.approx(1000, abs=5)


def test_a_genuinely_different_object_in_the_gap_does_not_get_the_remembered_id(clock):
    # The refusal case the plan calls out explicitly: a false recovery is
    # worse than a missed one, so a different LABEL arriving during the gap
    # must mint its own id, never borrow the dormant one.
    detector = ScriptedDetector(
        [
            [Detection("car", 0.9, 0.40, 0.50, 0.10, 0.10)],  # frame 0: born as #1
            [],
            [],
            [],
            [],
            [],  # frames 1-5: unseen
            [Detection("person", 0.9, 0.41, 0.50, 0.10, 0.10)],  # frame 6: NOT the car
        ]
    )
    subject = servicer(
        detector=detector, tracker_registry=StubTrackerRegistry(associator=StubCostAssociator)
    )
    config = _memory_config()
    session = servicers_module.StreamTrackingSession(
        settings=Settings(), registry_provider=subject._resolve_tracker_registry
    )

    responses = []
    for frame in range(7):
        clock.seconds = float(frame)
        responses.append(subject._handle_request(frame_request(frame, config), session))

    stranger = responses[6].detections
    assert len(stranger) == 1
    assert stranger[0].track_id == 2  # a FRESH id -- #1 is not handed out
    assert stranger[0].identity_confidence == 0.0
    assert stranger[0].dormant_millis == 0


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
