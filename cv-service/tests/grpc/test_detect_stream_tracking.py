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
from cv_service.tracking.assign import AssignGates, AssignWeights, CostAssociator
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


def test_multi_target_follow_updates_never_acquire_the_inference_gate(clock):
    """Extends the single-target proof above to the multi-target case
    (TRACKING-V2-PLAN wave C5b, review finding C6, P2's own instruction):
    K engine `update()` calls per frame -- one for the locked target, one
    per extra -- must be exactly as gate-free as one."""
    gate = RecordingGate()
    subject = servicer(gate=gate, tracker_registry=StubTrackerRegistry(follower=StubFollower))
    config = follow_config(verify_every_millis=100_000)  # exactly one pass, ever

    session = servicers_module.StreamTrackingSession(
        settings=dataclasses.replace(
            Settings(),
            track_follow_top_k=3,
            track_max_age_millis=1_000_000,  # see the sibling test above for why
        ),
        registry_provider=subject._resolve_tracker_registry,
    )
    first = subject._handle_request(frame_request(0, config), session)
    assert first.detector_ran is True
    assert gate.acquisitions == 1
    # FakeDetector's two detections (car + person): the locked target plus
    # ONE extra, both carrying track ids on this same verify-pass frame.
    assert len(first.detections) == 2
    assert all(detection.track_id != 0 for detection in first.detections)

    for frame in range(1, 60):
        clock.seconds = frame / 15.0
        response = subject._handle_request(frame_request(frame, config), session)
        assert response.detector_ran is False
        # The locked target AND the extra keep moving every tracker-only
        # frame, each on its OWN engine -- neither ever touches the gate.
        assert len(response.detections) == 2

    assert gate.acquisitions == 1


def test_associate_with_cost_motion_and_appearance_never_acquires_the_inference_gate(clock):
    # ROI re-detection turned off for this test, deliberately. Its detector
    # double is indexed by CALL COUNT, and a rescue is an extra call, so an
    # enabled rescue desynchronises the script and the test stops measuring
    # what it is named for. The rescue's own gating has its own tests.
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
        settings=dataclasses.replace(Settings(), track_roi_enabled=False), registry_provider=subject._resolve_tracker_registry
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
    # ROI re-detection turned off for this test, deliberately. Its detector
    # double is indexed by CALL COUNT, and a rescue is an extra call, so an
    # enabled rescue desynchronises the script and the test stops measuring
    # what it is named for. The rescue's own gating has its own tests.
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
        settings=dataclasses.replace(Settings(), track_roi_enabled=False), registry_provider=subject._resolve_tracker_registry
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
    # ROI re-detection turned off for this test, deliberately. Its detector
    # double is indexed by CALL COUNT, and a rescue is an extra call, so an
    # enabled rescue desynchronises the script and the test stops measuring
    # what it is named for. The rescue's own gating has its own tests.
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
        settings=dataclasses.replace(Settings(), track_roi_enabled=False), registry_provider=subject._resolve_tracker_registry
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


# --- the session is pooled by stream_id (TRACKING-V2-PLAN wave C5b, review finding B5) --


def test_a_reconnect_within_the_grace_window_keeps_the_same_session(clock):
    """The direct fix for finding B5: an RF blip must not cost the operator
    the numbers they are watching.

    Note what this asserts and what it deliberately does not. An earlier
    version of this test accepted ids 3 and 4 -- "the counter did not restart
    at 1" -- which the pooling satisfied while the operator's #1 still came
    back as #3. That is not the fix; it is the defect with a different
    number on it. The same physical objects must come back under the SAME
    ids, which they only do once the reconnect stops bumping the book's key
    epoch (`StreamTrackingSession._book_owns_keys`)."""
    subject = servicer()
    config = tracking(cv_pb2.TrackingMode.TRACKING_MODE_ASSOCIATE, min_hits=1)

    first = list(subject.DetectStream(iter([frame_request(0, config)]), None))
    clock.seconds = 0.5  # a brief reconnect gap, nowhere near the default grace window
    second = list(subject.DetectStream(iter([frame_request(1, config)]), None))

    assert [d.track_id for d in first[0].detections] == [1, 2]
    assert [d.track_id for d in second[0].detections] == [1, 2]
    assert subject._session_registry.size() == 1


def test_a_reconnect_after_the_grace_window_starts_a_fresh_session(clock):
    """The other half of finding B5's fix: the grace window is a window, not
    a promise -- a stream gone long enough is presumed gone for good, and
    its book is discarded so a same-named stream_id reappearing much later
    does not silently inherit a dead session's state."""
    subject = servicer()
    config = tracking(cv_pb2.TrackingMode.TRACKING_MODE_ASSOCIATE, min_hits=1)

    list(subject.DetectStream(iter([frame_request(0, config)]), None))
    clock.seconds = 9999.0  # far past CV_TRACK_SESSION_GRACE_MILLIS's default
    second = list(subject.DetectStream(iter([frame_request(1, config)]), None))

    assert [d.track_id for d in second[0].detections] == [1, 2]


def test_a_reconnect_does_not_resume_the_locked_engines_own_state(clock):
    """The wave's one deliberate subtlety: `lk`/`flow` each hold a previous
    decoded frame, so a resumed session must rebuild its ENGINES from
    scratch even though it resumes its book/lock. Proven directly on the
    resumed `StreamTrackingSession` object, not just inferred from track
    ids: the engine reference is gone, forcing `_resolve_engine` to build a
    brand-new instance on the next active frame, while the lock's own
    target (not merely its bound track id) survives untouched."""
    subject = servicer()
    config = follow_config(verify_every_millis=100_000)

    list(subject.DetectStream(iter([frame_request(0, config)]), None))
    resumed = subject._session_registry.acquire("stream-1")

    assert resumed._engine is None
    assert resumed._lock.has_target  # the operator's FOLLOW target survives
    assert resumed.tracks  # the book itself was not wiped
    subject._session_registry.release("stream-1", resumed)


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


# --- ROI re-detection (TRACKING-V2-PLAN wave C5c, review §4.6) ---------------


class PartialThenRoiDetector:
    """A `YoloDetector`-shaped double whose answer depends on WHETHER it was
    asked about the full frame (`width`/`height` == `WIDTH`/`HEIGHT`) or a
    crop (anything smaller) -- letting a test drive `StreamTrackingSession.
    _roi_rescue` deterministically through the REAL gRPC/session/servicer
    wiring, not a stubbed-out associator.

    Full-frame: both objects on the FIRST call (so both get born and
    CONFIRMED at `min_hits=1`); `person` silently drops out of every
    full-frame call after that, exactly the "the detector cannot detect the
    subject" failure this wave exists to recover from.

    A crop call answers with `person` alone, in **crop-relative** normalized
    coordinates -- which is what a real detector handed a crop returns, and
    what `_map_roi_detection` exists to undo. This double originally returned
    the same FULL-FRAME numbers for both kinds of call, so the box it
    reported mapped back to somewhere the object was not, and the test still
    passed because the rescue's IoU gate was permissive enough to adopt it.
    The crop is `roi_crop_factor` (4x) around the predicted box, so a
    correctly-reported object sits at a quarter of the crop's extent, centred
    -- hence 0.375/0.25.
    """

    model_name = "fake-roi"

    def __init__(self) -> None:
        self.calls: list[dict] = []
        self._full_frame_passes = 0

    def detect(self, *, width, height, **_kwargs):
        self.calls.append({"width": width, "height": height})
        if width == WIDTH and height == HEIGHT:
            self._full_frame_passes += 1
            if self._full_frame_passes == 1:
                return [
                    Detection("car", 0.9, 0.10, 0.10, 0.10, 0.10),
                    Detection("person", 0.7, 0.70, 0.70, 0.06, 0.12),
                ], 11
            return [Detection("car", 0.9, 0.10, 0.10, 0.10, 0.10)], 11
        return [Detection("person", 0.7, 0.375, 0.375, 0.25, 0.25)], 3


def _cost_engine() -> CostAssociator:
    return CostAssociator(weights=AssignWeights(), gates=AssignGates())


def test_a_roi_pass_acquires_the_inference_gate_exactly_like_a_full_frame_pass(clock):
    # P2 at the wire: a ROI pass is STILL a detector pass, gated exactly
    # like a full-frame one, through the SAME sole acquisition site
    # (`_run_detector`).
    gate = RecordingGate()
    detector = PartialThenRoiDetector()
    settings = dataclasses.replace(Settings(), track_roi_enabled=True)
    subject = InferenceServicer(
        detector=detector,
        inference_gate=gate,
        settings=settings,
        tracker_registry=StubTrackerRegistry(associator=_cost_engine),
    )
    config = tracking(cv_pb2.TrackingMode.TRACKING_MODE_ASSOCIATE, engine_id="cost", min_hits=1)
    session = servicers_module.StreamTrackingSession(
        settings=settings, registry_provider=subject._resolve_tracker_registry
    )

    clock.seconds = 0.0
    first = subject._handle_request(frame_request(0, config), session)
    assert sorted(d.label for d in first.detections) == ["car", "person"]
    assert gate.acquisitions == 1  # one full-frame pass, both objects found

    clock.seconds = 0.066
    second = subject._handle_request(frame_request(1, config), session)

    # +1 full-frame (misses `person`) +1 roi (recovers `person`) == 3 total.
    assert gate.acquisitions == 3
    assert second.detector_roi is True
    assert sorted(d.label for d in second.detections) == ["car", "person"]


def test_a_roi_recovery_keeps_the_same_track_id_and_reports_it_on_the_wire(clock):
    detector = PartialThenRoiDetector()
    settings = dataclasses.replace(Settings(), track_roi_enabled=True)
    subject = InferenceServicer(
        detector=detector,
        inference_gate=RecordingGate(),
        settings=settings,
        tracker_registry=StubTrackerRegistry(associator=_cost_engine),
    )
    config = tracking(cv_pb2.TrackingMode.TRACKING_MODE_ASSOCIATE, engine_id="cost", min_hits=1)
    session = servicers_module.StreamTrackingSession(
        settings=settings, registry_provider=subject._resolve_tracker_registry
    )

    clock.seconds = 0.0
    first = subject._handle_request(frame_request(0, config), session)
    person_id_before = next(d.track_id for d in first.detections if d.label == "person")

    clock.seconds = 0.066
    second = subject._handle_request(frame_request(1, config), session)
    person_id_after = next(d.track_id for d in second.detections if d.label == "person")

    assert person_id_after == person_id_before


def test_detector_roi_is_false_when_the_full_frame_pass_finds_everything(clock):
    # The common case, restated on the wire: nothing to rescue this frame,
    # so `detector_roi` reports it honestly.
    class AlwaysBothDetector:
        model_name = "fake-both"

        def detect(self, **_kwargs):
            return [
                Detection("car", 0.9, 0.10, 0.10, 0.10, 0.10),
                Detection("person", 0.7, 0.70, 0.70, 0.06, 0.12),
            ], 11

    settings = dataclasses.replace(Settings(), track_roi_enabled=True)
    subject = InferenceServicer(
        detector=AlwaysBothDetector(),
        inference_gate=RecordingGate(),
        settings=settings,
        tracker_registry=StubTrackerRegistry(associator=_cost_engine),
    )
    config = tracking(cv_pb2.TrackingMode.TRACKING_MODE_ASSOCIATE, engine_id="cost", min_hits=1)
    session = servicers_module.StreamTrackingSession(
        settings=settings, registry_provider=subject._resolve_tracker_registry
    )

    clock.seconds = 0.0
    subject._handle_request(frame_request(0, config), session)
    clock.seconds = 0.066
    second = subject._handle_request(frame_request(1, config), session)

    assert second.detector_roi is False


def test_detector_roi_defaults_to_false_when_roi_is_disabled(clock):
    # With ROI turned OFF, even an eligible unmatched confirmed candidate on
    # every frame never makes the wire report a ROI pass, because none ever
    # runs. (ROI is ON by default since C5c; this pins that turning it off is
    # a real no-op rather than a quieter version of the same work.)
    detector = PartialThenRoiDetector()
    roi_off = dataclasses.replace(Settings(), track_roi_enabled=False)
    subject = InferenceServicer(
        detector=detector,
        inference_gate=RecordingGate(),
        settings=roi_off,
        tracker_registry=StubTrackerRegistry(associator=_cost_engine),
    )
    config = tracking(cv_pb2.TrackingMode.TRACKING_MODE_ASSOCIATE, engine_id="cost", min_hits=1)
    session = servicers_module.StreamTrackingSession(
        settings=dataclasses.replace(Settings(), track_roi_enabled=False), registry_provider=subject._resolve_tracker_registry
    )

    clock.seconds = 0.0
    subject._handle_request(frame_request(0, config), session)
    clock.seconds = 0.066
    second = subject._handle_request(frame_request(1, config), session)

    assert second.detector_roi is False
    assert sorted(d.label for d in second.detections) == ["car"]  # person genuinely lost
    assert all(call["width"] == WIDTH and call["height"] == HEIGHT for call in detector.calls)
