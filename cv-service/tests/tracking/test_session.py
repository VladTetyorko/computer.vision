"""`cv_service.tracking.session` -- the per-frame sequence, composed.

Pure stdlib: fake engines, a fake clock, a fake "frame" that is just a
sentinel object. Nothing here imports cv2, numpy or gRPC -- which is the
point of keeping everything but `engines/` stdlib-only.
"""

from __future__ import annotations

import logging

import pytest

from cv_service.config import Settings
from cv_service.inference.detector import Detection
from cv_service.tracking.assign import AssignGates, AssignWeights, CostAssociator
from cv_service.tracking.engines.base import (
    METRIC_HELLINGER,
    Box,
    CameraPose,
    Descriptor,
    Observation,
    Transform,
    TrackerUpdate,
)
from cv_service.tracking.params import (
    MODE_ASSOCIATE,
    MODE_FOLLOW,
    MODE_OFF,
    LockRequest,
    TrackingRequest,
)
from cv_service.tracking.registry import MOTION_ENGINE_FLOW, MOTION_ENGINE_POSE
from cv_service.tracking.scheduler import REASON_ALWAYS, REASON_CADENCE, REASON_NO_LOCK
from cv_service.tracking.session import StreamTrackingSession
from cv_service.tracking.track import STATE_CONFIRMED

FRAME = object()  # the session never looks at a frame; only engines do.


def det(label="car", x=0.1, y=0.1, w=0.1, h=0.1, confidence=0.9) -> Detection:
    return Detection(label, confidence, x, y, w, h)


class FakeAssociator:
    """Keys every detection by its label, so identity is trivially assertable."""

    def __init__(self, engine_id="fake-assoc"):
        self.engine_id = engine_id
        self.resets = 0
        self.raise_on_next = False

    def associate(self, detections, now):
        if self.raise_on_next:
            self.raise_on_next = False
            raise RuntimeError("engine exploded mid-frame")
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
        self.resets += 1


class FakeFollower:
    def __init__(self, engine_id="fake-follow"):
        self.engine_id = engine_id
        self.inits = 0
        self.updates = 0
        self.resets = 0
        self.box = None
        self.init_returns = True
        self.update_returns = "ok"
        self.raise_on_next = False
        self.drift = 0.0
        self.confidence = 0.9

    def init(self, frame, box):
        self.inits += 1
        self.box = box
        return self.init_returns

    def update(self, frame):
        self.updates += 1
        if self.raise_on_next:
            self.raise_on_next = False
            raise RuntimeError("engine exploded mid-frame")
        if self.update_returns == "lost":
            return None
        if self.update_returns == "invalid":
            return TrackerUpdate(box=Box(0.1, 0.1, 0.0, 0.0), confidence=0.5)
        self.box = Box(self.box.x + self.drift, self.box.y, self.box.width, self.box.height)
        return TrackerUpdate(box=self.box, confidence=self.confidence)

    def reset(self):
        self.resets += 1


class FakeRegistry:
    def __init__(self, associator=None, follower=None, compensator=None, appearance=None):
        self._associator = associator
        self._follower = follower
        self._compensator = compensator
        self._appearance = appearance
        self.associator_calls = 0
        self.follower_calls = 0
        self.compensator_calls = 0
        self.appearance_calls = 0

    def associator(self, engine_id, *, max_age_frames):
        self.associator_calls += 1
        if self._associator is None:
            return None
        engine = self._associator() if callable(self._associator) else self._associator
        return engine.engine_id, engine

    def follower(self, engine_id, *, max_age_frames):
        self.follower_calls += 1
        if self._follower is None:
            return None
        engine = self._follower() if callable(self._follower) else self._follower
        return engine.engine_id, engine

    def compensator(self, engine_id):
        self.compensator_calls += 1
        if self._compensator is None:
            return None
        engine = self._compensator() if callable(self._compensator) else self._compensator
        return engine.engine_id, engine

    def appearance(self, engine_id):
        self.appearance_calls += 1
        if self._appearance is None:
            return None
        engine = self._appearance() if callable(self._appearance) else self._appearance
        return engine.engine_id, engine


class FakeMotionCompensator:
    """`MotionCompensator` test double -- a fixed transform, no pixels needed."""

    def __init__(self, engine_id="fake-motion", *, transform=None, is_available=True):
        self.engine_id = engine_id
        self.transform = transform if transform is not None else Transform(c=0.05)
        self.estimate_calls = 0
        self.raise_on_next = False
        self._is_available = is_available

    def available(self, pose):
        return self._is_available

    def estimate(self, frame, pose):
        self.estimate_calls += 1
        if self.raise_on_next:
            self.raise_on_next = False
            raise RuntimeError("motion compensator exploded")
        return self.transform

    def reset(self):
        pass


class FakeAppearanceExtractor:
    """`AppearanceExtractor` test double -- a fixed descriptor (or `None`),
    no pixels needed."""

    def __init__(self, engine_id="fake-appearance", *, descriptor=None):
        self.engine_id = engine_id
        self.descriptor = descriptor if descriptor is not None else Descriptor("fake", (1.0,), METRIC_HELLINGER)
        self.describe_calls = 0
        self.raise_on_next = False

    def describe(self, frame, boxes):
        self.describe_calls += 1
        if self.raise_on_next:
            self.raise_on_next = False
            raise RuntimeError("appearance extractor exploded")
        return [self.descriptor for _ in boxes]

    def reset(self):
        pass


class PoseOrFlowRegistry:
    """Distinguishes `pose` vs `flow` engine ids, for proving `session.py`'s
    pose-unavailable-falls-back-to-flow policy without wiring a real
    `TrackerRegistry`. `associator`/`follower` are the minimum needed to
    keep a FOLLOW session alive; only `compensator` is the point of this
    fake."""

    def __init__(self, *, pose_available):
        self.pose = FakeMotionCompensator(MOTION_ENGINE_POSE, is_available=pose_available)
        self.flow = FakeMotionCompensator(MOTION_ENGINE_FLOW)
        self.requested_ids = []

    def associator(self, engine_id, *, max_age_frames):
        return None

    def follower(self, engine_id, *, max_age_frames):
        return "fake-follow", FakeFollower()

    def compensator(self, engine_id):
        self.requested_ids.append(engine_id)
        if engine_id == MOTION_ENGINE_POSE:
            return self.pose.engine_id, self.pose
        if engine_id == MOTION_ENGINE_FLOW:
            return self.flow.engine_id, self.flow
        return None

    def appearance(self, engine_id):
        return None


def session(registry, settings=None) -> StreamTrackingSession:
    return StreamTrackingSession(
        settings=settings or Settings(), registry_provider=lambda: registry
    )


def detect_returning(*detections, millis=7):
    def detect():
        return list(detections), millis

    return detect


def run(subject, *, now_millis, detections=(), millis=7):
    return subject.process(
        now_millis=now_millis, detect=detect_returning(*detections, millis=millis), frame=lambda: FRAME
    )


# -- OFF --------------------------------------------------------------------


def test_a_fresh_session_is_off():
    subject = session(FakeRegistry())

    assert subject.active is False
    assert subject.params.mode == MODE_OFF


def test_off_stays_off_when_the_config_restates_nothing():
    subject = session(FakeRegistry())

    subject.apply_config(TrackingRequest())

    assert subject.active is False


# -- ASSOCIATE --------------------------------------------------------------


def test_associate_detects_on_every_frame_and_books_stable_ids():
    engine = FakeAssociator()
    subject = session(FakeRegistry(associator=engine))
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, min_hits=1))

    ids = []
    for frame in range(4):
        outcome = run(subject, now_millis=frame * 66.0, detections=[det(), det("person", x=0.6)])
        assert outcome.detector_ran is True
        assert outcome.detector_reason == REASON_ALWAYS
        ids.append([box.track.track_id for box in outcome.boxes])

    assert ids == [[1, 2]] * 4
    assert all(box.track.state == STATE_CONFIRMED for box in outcome.boxes)


def test_the_serving_engine_id_is_reported_on_every_response():
    subject = session(FakeRegistry(associator=FakeAssociator("bytetrack")))
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE))

    assert run(subject, now_millis=0.0, detections=[det()]).engine_id == "bytetrack"


def test_two_sessions_never_share_engines_or_ids():
    left = session(FakeRegistry(associator=FakeAssociator))
    right = session(FakeRegistry(associator=FakeAssociator))
    for subject in (left, right):
        subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, min_hits=1))

    left_outcome = run(left, now_millis=0.0, detections=[det("car")])
    right_outcome = run(right, now_millis=0.0, detections=[det("van"), det("bus", x=0.5)])

    assert [b.track.track_id for b in left_outcome.boxes] == [1]
    assert [b.track.track_id for b in right_outcome.boxes] == [1, 2]
    assert [t.label for t in left.tracks] == ["car"]
    assert [t.label for t in right.tracks] == ["van", "bus"]


def test_a_detection_the_engine_did_not_identify_is_reported_untracked():
    class Partial(FakeAssociator):
        def associate(self, detections, now):
            return [
                Observation(
                    key="only-the-first",
                    box=Box(detections[0].x, detections[0].y, detections[0].width, detections[0].height),
                    label=detections[0].label,
                    confidence=detections[0].confidence,
                    det_index=0,
                )
            ]

    subject = session(FakeRegistry(associator=Partial()))
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, min_hits=1))

    outcome = run(subject, now_millis=0.0, detections=[det("car"), det("person", x=0.6)])

    assert outcome.boxes[0].track is not None
    assert outcome.boxes[1].track is None


# -- ASSOCIATE via `cost` (TRACKING-V2-PLAN wave C3) -------------------------


def cost_engine(**overrides) -> CostAssociator:
    weights = overrides.pop("weights", AssignWeights())
    gates = overrides.pop("gates", AssignGates())
    return CostAssociator(weights=weights, gates=gates)


def test_bytetrack_is_still_selectable_and_never_touches_motion_or_appearance():
    # Acceptance: `bytetrack` remains fully working and selectable, and
    # stays the no-appearance, no-compensation baseline -- neither the
    # motion nor the appearance registry call is ever made for it.
    registry = FakeRegistry(
        associator=FakeAssociator("bytetrack"),
        compensator=FakeMotionCompensator,
        appearance=FakeAppearanceExtractor,
    )
    subject = session(registry)
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="bytetrack", min_hits=1))

    outcome = run(subject, now_millis=0.0, detections=[det()])

    assert outcome.engine_id == "bytetrack"
    assert outcome.boxes[0].track is not None
    assert registry.compensator_calls == 0
    assert registry.appearance_calls == 0
    assert outcome.motion_engine_id == ""


def test_cost_is_selectable_and_books_stable_ids_across_frames():
    registry = FakeRegistry(associator=cost_engine)
    subject = session(registry)
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1))

    ids = []
    for frame in range(4):
        outcome = run(subject, now_millis=frame * 66.0, detections=[det(), det("person", x=0.6)])
        assert outcome.engine_id == "cost"
        ids.append(sorted(box.track.track_id for box in outcome.boxes if box.track is not None))

    assert ids == [[1, 2]] * 4


def test_cost_births_a_new_track_for_an_unmatched_target():
    registry = FakeRegistry(associator=cost_engine)
    subject = session(registry)
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1))

    first = run(subject, now_millis=0.0, detections=[det("car", x=0.1)])
    second = run(subject, now_millis=66.0, detections=[det("car", x=0.1), det("bus", x=0.8, y=0.8)])

    assert first.boxes[0].track.track_id == 1
    assert sorted(box.track.track_id for box in second.boxes) == [1, 2]


def test_an_unmatched_candidate_ages_toward_lost_instead_of_vanishing():
    registry = FakeRegistry(associator=cost_engine)
    subject = session(registry)
    subject.apply_config(
        TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1, max_age_frames=2)
    )
    run(subject, now_millis=0.0, detections=[det("car", x=0.1)])

    # The detector now sees nothing near the held track at all -- it should
    # age (miss), not disappear outright or silently swap onto nothing.
    for frame in range(1, 4):
        run(subject, now_millis=frame * 66.0, detections=[])

    assert subject.tracks[0].track_id == 1
    assert subject.tracks[0].misses > 0


def test_associate_with_cost_asks_for_a_motion_compensator():
    # THE keystone this wave adds: `cost`'s candidates ARE the book's own
    # tracks, so -- unlike `bytetrack` -- ego-motion compensation now has a
    # seam to plug into for ASSOCIATE.
    compensator = FakeMotionCompensator()
    registry = FakeRegistry(associator=cost_engine, compensator=compensator)
    subject = session(registry)
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1))

    outcome = run(subject, now_millis=0.0, detections=[det()])

    assert compensator.estimate_calls == 1
    assert outcome.motion_engine_id == "fake-motion"


def test_associate_with_cost_warps_the_book_before_matching():
    # The end-to-end proof that C2's `TrackBook.warp()` now changes the
    # ASSOCIATE DECISION for `cost`, not merely a coasting box's display: a
    # static detection is only re-matched to its existing track because the
    # candidate's PREDICTED+WARPED box, not its stale stored one, is what
    # the cost function compares against.
    compensator = FakeMotionCompensator(transform=Transform(c=0.2))
    registry = FakeRegistry(associator=cost_engine, compensator=compensator)
    subject = session(registry)
    subject.apply_config(
        TrackingRequest(
            mode=MODE_ASSOCIATE,
            engine_id="cost",
            min_hits=1,
            # A tight IoU gate: only a WARPED candidate box can still
            # satisfy it against a detection that shifted a whole 0.2 of
            # frame width between frames.
            redetect_iou_threshold=0.9,
        )
    )
    born = run(subject, now_millis=0.0, detections=[det("car", x=0.1, y=0.1, w=0.3, h=0.3)])
    assert born.boxes[0].track.track_id == 1

    # The SAME physical object, shifted by exactly the compensator's own
    # transform -- i.e. what the camera moving by that much would produce.
    shifted = run(subject, now_millis=66.0, detections=[det("car", x=0.3, y=0.1, w=0.3, h=0.3)])

    assert shifted.boxes[0].track.track_id == 1


def test_associate_with_cost_asks_for_an_appearance_extractor():
    extractor = FakeAppearanceExtractor()
    registry = FakeRegistry(associator=cost_engine, appearance=extractor)
    subject = session(registry)
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1))

    run(subject, now_millis=0.0, detections=[det()])

    assert extractor.describe_calls == 1


def test_a_cost_track_accumulates_a_descriptor_from_detector_evidence():
    extractor = FakeAppearanceExtractor(descriptor=Descriptor("fake", (1.0, 0.0), METRIC_HELLINGER))
    registry = FakeRegistry(
        associator=lambda: cost_engine(weights=AssignWeights(iou=1.0, appearance=0.5)),
        appearance=extractor,
    )
    subject = session(registry)
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1))

    run(subject, now_millis=0.0, detections=[det()])

    assert subject.tracks[0].descriptor == extractor.descriptor


def test_no_appearance_extractor_active_never_decodes_a_frame():
    calls = []
    subject = session(FakeRegistry(associator=cost_engine))
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1))

    subject.process(
        now_millis=0.0,
        detect=detect_returning(det()),
        frame=lambda: calls.append(1) or FRAME,
    )

    assert calls == []


def test_a_raising_appearance_extractor_degrades_this_frame_without_killing_the_stream():
    extractor = FakeAppearanceExtractor()
    extractor.raise_on_next = True
    registry = FakeRegistry(associator=cost_engine, appearance=extractor)
    subject = session(registry)
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1))

    degraded = run(subject, now_millis=0.0, detections=[det()])
    assert degraded.boxes[0].track is not None  # association itself is unaffected

    recovered = run(subject, now_millis=66.0, detections=[det("bus", x=0.8, y=0.8)])
    assert extractor.describe_calls == 2
    assert recovered.boxes[-1].track is not None


def test_changing_the_appearance_engine_id_releases_the_extractor():
    first = FakeAppearanceExtractor("fake-appearance-a")
    second = FakeAppearanceExtractor("fake-appearance-b")
    engines = iter([first, second])
    registry = FakeRegistry(associator=cost_engine, appearance=lambda: next(engines))
    subject = session(registry)
    subject.apply_config(
        TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1, appearance_engine_id="a")
    )
    run(subject, now_millis=0.0, detections=[det()])
    assert first.describe_calls == 1

    subject.apply_config(
        TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1, appearance_engine_id="b")
    )
    run(subject, now_millis=66.0, detections=[det()])

    assert second.describe_calls == 1
    assert first.describe_calls == 1  # the old extractor was never touched again


def test_appearance_off_never_asks_the_registry():
    registry = FakeRegistry(associator=cost_engine, appearance=FakeAppearanceExtractor)
    subject = session(registry)
    subject.apply_config(
        TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1, appearance_engine_id="off")
    )

    run(subject, now_millis=0.0, detections=[det()])

    assert registry.appearance_calls == 0


# -- FOLLOW -----------------------------------------------------------------


def follow_session(
    follower=None, *, verify_every_millis=2000, lock_seq=1, compensator=None, motion_engine_id="", **lock_kwargs
):
    engine = follower or FakeFollower()
    subject = session(FakeRegistry(follower=engine, compensator=compensator))
    subject.apply_config(
        TrackingRequest(
            mode=MODE_FOLLOW,
            verify_every_millis=verify_every_millis,
            min_hits=1,
            motion_engine_id=motion_engine_id,
            lock=LockRequest(lock_seq=lock_seq, **(lock_kwargs or {"point_x": 0.15, "point_y": 0.15})),
        )
    )
    return subject, engine


def test_follow_without_a_lock_keeps_re_acquiring():
    subject = session(FakeRegistry(follower=FakeFollower()))
    subject.apply_config(TrackingRequest(mode=MODE_FOLLOW))

    for frame in range(5):
        outcome = run(subject, now_millis=frame * 66.0, detections=[det()])
        assert outcome.detector_ran is True
        assert outcome.detector_reason == REASON_NO_LOCK
        assert outcome.locked_track_id == 0


def test_a_click_lock_binds_the_target_and_confirms_it_at_once():
    subject, engine = follow_session()

    outcome = run(subject, now_millis=0.0, detections=[det("car"), det("person", x=0.7, y=0.7)])

    assert engine.inits == 1
    assert outcome.locked_track_id == 1
    assert outcome.boxes[0].track.state == STATE_CONFIRMED
    assert outcome.boxes[1].track is None  # the unlocked box stays untracked


def test_follow_runs_the_detector_at_the_configured_cadence_and_no_more():
    subject, engine = follow_session(verify_every_millis=2000)

    passes = 0
    frames = 150  # 10 seconds at 15 fps
    for frame in range(frames):
        outcome = run(subject, now_millis=frame * (1000.0 / 15.0), detections=[det()])
        if outcome.detector_ran:
            passes += 1

    # Frame 0 acquires; then one verify pass per 2000 ms across 9933 ms.
    assert passes == 5
    assert engine.updates == frames - passes
    assert passes / frames == pytest.approx(1 / 30, abs=0.005)


def test_an_unhappy_tracker_never_collapses_the_duty_cycle_into_every_frame():
    # Regression: raising trigger (b)/(d) again on the verify frame that had
    # ALREADY been brought forward by it made the detector run on every
    # single frame for as long as the tracker stayed unhappy.
    subject, engine = follow_session(verify_every_millis=2000)
    run(subject, now_millis=0.0, detections=[det("car", x=0.1)])
    engine.update_returns = "invalid"

    passes = 0
    for frame in range(1, 150):
        # The detector only ever offers a box the held target cannot match.
        outcome = run(subject, now_millis=frame * (1000.0 / 15.0), detections=[det("bus", x=0.8, y=0.8)])
        passes += int(outcome.detector_ran)

    assert passes <= 10


def test_a_tracker_only_frame_emits_the_held_box_alone_from_the_tracker():
    subject, engine = follow_session(verify_every_millis=100_000)
    run(subject, now_millis=0.0, detections=[det("car"), det("person", x=0.7, y=0.7)])

    outcome = run(subject, now_millis=66.0, detections=[det()])

    assert outcome.detector_ran is False
    assert outcome.detector_reason == "DETECTOR_REASON_UNSPECIFIED"
    assert len(outcome.boxes) == 1
    assert outcome.boxes[0].track.source == "DETECTION_SOURCE_TRACKER"
    assert outcome.boxes[0].track.track_id == 1


def test_a_lost_tracker_forces_the_next_frame_to_verify():
    subject, engine = follow_session(verify_every_millis=100_000)
    run(subject, now_millis=0.0, detections=[det()])
    engine.update_returns = "lost"

    coasted = run(subject, now_millis=66.0, detections=[det()])
    assert coasted.detector_ran is False

    recovered = run(subject, now_millis=132.0, detections=[det()])
    assert recovered.detector_ran is True
    assert recovered.detector_reason == "DETECTOR_REASON_TRACKER_FAILED"


def test_a_collapsed_box_forces_the_next_frame_to_verify():
    subject, engine = follow_session(verify_every_millis=100_000)
    run(subject, now_millis=0.0, detections=[det()])
    engine.update_returns = "invalid"

    run(subject, now_millis=66.0, detections=[det()])
    forced = run(subject, now_millis=132.0, detections=[det()])

    assert forced.detector_ran is True
    assert forced.detector_reason == "DETECTOR_REASON_BOX_INVALID"


def test_a_verify_pass_that_cannot_re_anchor_coasts_beside_the_detections():
    subject, engine = follow_session(verify_every_millis=1)
    run(subject, now_millis=0.0, detections=[det("car", x=0.1)])

    # The detector now only sees something far away from the held box.
    outcome = run(subject, now_millis=10.0, detections=[det("bus", x=0.8, y=0.8)])

    assert outcome.detector_ran is True
    assert [box.track is None for box in outcome.boxes] == [True, False]
    assert outcome.boxes[-1].track.state == "TRACK_STATE_COASTING"
    assert outcome.boxes[-1].track.misses == 1


def test_a_target_lost_past_max_age_drops_the_lock():
    subject = session(FakeRegistry(follower=FakeFollower()))
    subject.apply_config(
        TrackingRequest(
            mode=MODE_FOLLOW,
            verify_every_millis=1,
            min_hits=1,
            max_age_frames=2,
            lock=LockRequest(lock_seq=1, point_x=0.15, point_y=0.15),
        )
    )
    run(subject, now_millis=0.0, detections=[det("car", x=0.1)])

    # Verify passes that see nothing at all: the target is gone, not merely
    # unmatched, so there is nothing to re-acquire onto either.
    states = []
    for frame in range(1, 5):
        outcome = run(subject, now_millis=frame * 10.0, detections=[])
        states.append(outcome.boxes[0].track.state if outcome.boxes else None)

    assert states == [
        "TRACK_STATE_COASTING",
        "TRACK_STATE_COASTING",
        "TRACK_STATE_LOST",
        None,
    ]
    assert outcome.locked_track_id == 0
    assert outcome.detector_reason == REASON_NO_LOCK


def test_a_re_acquired_target_recovers_the_id_it_had_before_it_was_lost():
    subject = session(FakeRegistry(follower=FakeFollower()))
    subject.apply_config(
        TrackingRequest(
            mode=MODE_FOLLOW,
            verify_every_millis=1,
            min_hits=1,
            max_age_frames=2,
            lock=LockRequest(lock_seq=1, point_x=0.15, point_y=0.15),
        )
    )
    born = run(subject, now_millis=0.0, detections=[det("car", x=0.1)])

    for frame in range(1, 4):  # occluded: the detector sees nothing
        run(subject, now_millis=frame * 10.0, detections=[])

    recovered = run(subject, now_millis=40.0, detections=[det("car", x=0.1)])

    assert recovered.locked_track_id == born.locked_track_id
    assert recovered.boxes[0].track.state == STATE_CONFIRMED


def test_releasing_a_lock_then_re_acquiring_yields_a_new_id():
    subject, _engine = follow_session()
    first = run(subject, now_millis=0.0, detections=[det()])

    subject.apply_config(
        TrackingRequest(mode=MODE_FOLLOW, min_hits=1, lock=LockRequest(lock_seq=2, release=True))
    )
    subject.apply_config(
        TrackingRequest(
            mode=MODE_FOLLOW,
            min_hits=1,
            lock=LockRequest(lock_seq=3, point_x=0.15, point_y=0.15),
        )
    )
    second = run(subject, now_millis=1000.0, detections=[det()])

    assert second.locked_track_id != first.locked_track_id


def test_a_target_the_engine_cannot_anchor_reports_no_lock():
    engine = FakeFollower()
    engine.init_returns = False
    subject, _engine = follow_session(engine)

    outcome = run(subject, now_millis=0.0, detections=[det()])

    assert outcome.locked_track_id == 0
    assert all(box.track is None for box in outcome.boxes)


def test_a_coasting_box_moves_instead_of_freezing_where_it_was_last_seen():
    # THE fix for review finding C1: a tracker that reports lost must not
    # leave the box sitting exactly where it was last confirmed -- it has to
    # keep extrapolating from the track's own velocity.
    subject, engine = follow_session(verify_every_millis=100_000)
    born = run(subject, now_millis=0.0, detections=[det("car", x=0.1, y=0.1)])
    born_box = born.boxes[0].track.box

    # Establish a non-zero velocity: the held target visibly moves right.
    engine.drift = 0.01
    run(subject, now_millis=66.0, detections=[det()])
    engine.update_returns = "lost"

    coasted = run(subject, now_millis=132.0, detections=[det()])

    assert coasted.boxes[0].track.box.x > born_box.x + 0.01


# -- trigger (b): tracker confidence (review finding C2) --------------------


def test_a_weakening_tracker_confidence_brings_the_verify_pass_forward():
    engine = FakeFollower()
    engine.confidence = 0.1  # well under the default min_tracker_confidence
    subject, _engine = follow_session(engine, verify_every_millis=2000)
    run(subject, now_millis=0.0, detections=[det("car", x=0.1)])

    # Tracker-only frame: the update itself is a valid, successful box, but
    # its confidence is weak -- this is trigger (b)'s early-warning half,
    # not a hard failure.
    weak = run(subject, now_millis=66.0, detections=[det("bus", x=0.8, y=0.8)])
    assert weak.detector_ran is False

    # The NEXT frame brings the verify pass forward well before the 2000ms
    # cadence would have, because the previous frame's weak confidence
    # raised trigger (b).
    brought_forward = run(subject, now_millis=132.0, detections=[det("bus", x=0.8, y=0.8)])
    assert brought_forward.detector_ran is True
    assert brought_forward.detector_reason == "DETECTOR_REASON_TRACKER_FAILED"


def test_a_persistently_weak_confidence_never_forces_two_verify_passes_in_a_row():
    # Same anti-collapse rule finding C2's fix must obey: even if the
    # tracker's own confidence never recovers, trigger (b) can only ever be
    # raised on a tracker-only frame -- so two consecutive frames can never
    # both be verify passes on its account.
    engine = FakeFollower()
    engine.confidence = 0.0
    subject, _engine = follow_session(engine, verify_every_millis=2000)
    run(subject, now_millis=0.0, detections=[det("car", x=0.1)])

    # Bounded well short of either LOST threshold (the wall-clock rule and
    # the frame-based one, fix 5) so this stays a clean test of trigger (b)
    # alone -- once genuinely LOST, continuous NO_LOCK-driven detection is
    # separately correct behaviour, not a collapse, and is covered by
    # `test_a_target_lost_past_max_age_drops_the_lock`.
    consecutive = 0
    for frame in range(1, 30):
        outcome = run(subject, now_millis=frame * 66.0, detections=[det("bus", x=0.8, y=0.8)])
        consecutive = consecutive + 1 if outcome.detector_ran else 0
        assert consecutive <= 1


# -- ego-motion compensation (TRACKING-V2-PLAN wave C2) ---------------------


def test_no_compensator_reports_no_compensation_on_the_outcome():
    # Default `FakeRegistry()` offers no compensator -- every other FOLLOW
    # test in this file runs through this path, so it stays behaviourally
    # identical to before wave C2 (motion fields at their proto3 zero).
    subject, _engine = follow_session()

    outcome = run(subject, now_millis=0.0, detections=[det()])

    assert outcome.motion_millis == 0
    assert outcome.motion_engine_id == ""


def test_associate_never_asks_for_a_motion_compensator():
    # TRACKING-V2-PLAN C2's design decision: ASSOCIATE is deliberately NOT
    # compensated this wave (association still lives inside ByteTrack's own
    # state, with no seam to warp) -- so the registry is never even asked.
    registry = FakeRegistry(associator=FakeAssociator(), compensator=FakeMotionCompensator)
    subject = session(registry)
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, min_hits=1))

    outcome = run(subject, now_millis=0.0, detections=[det()])

    assert registry.compensator_calls == 0
    assert outcome.motion_millis == 0
    assert outcome.motion_engine_id == ""


def test_a_follow_frame_estimates_motion_once_and_reports_it():
    compensator = FakeMotionCompensator("fake-motion")
    subject, _engine = follow_session(compensator=compensator)

    outcome = run(subject, now_millis=0.0, detections=[det()])

    assert compensator.estimate_calls == 1
    assert outcome.motion_engine_id == "fake-motion"
    assert outcome.motion_millis >= 0


def test_the_coasted_box_is_warped_by_the_compensators_transform():
    # A track with zero velocity would otherwise coast in place exactly
    # where it was last confirmed, so any movement here can only be
    # `TrackBook.warp()` carrying the stored box through the compensator's
    # transform (called once per frame in `process()`, before `_coast`
    # reads the track).
    engine = FakeFollower()
    compensator = FakeMotionCompensator(transform=Transform(c=0.1))
    subject = session(FakeRegistry(follower=engine, compensator=compensator))
    subject.apply_config(
        TrackingRequest(
            mode=MODE_FOLLOW,
            verify_every_millis=100_000,
            min_hits=1,
            lock=LockRequest(lock_seq=1, point_x=0.15, point_y=0.15),
        )
    )
    born = run(subject, now_millis=0.0, detections=[det("car", x=0.1, y=0.1)])
    born_box = born.boxes[0].track.box

    engine.update_returns = "lost"
    coasted = run(subject, now_millis=66.0, detections=[det()])

    assert coasted.boxes[0].track.box.x == pytest.approx(born_box.x + 0.1)
    assert coasted.boxes[0].track.box.y == pytest.approx(born_box.y)


def test_n_stalled_frames_accumulate_n_warps_not_one():
    # THE end-to-end accumulation proof, at the session level: a track that
    # nobody re-anchors for N consecutive tracker-only frames must drift by
    # N times one frame's ego-motion, not by one frame's worth applied once
    # regardless of how long the stall has run -- the coordinator's fix.
    # Warping only the box a `predict()` call happened to READ (using that
    # call's own single frame's transform) would leave this asserting a
    # `+1 x shift`, not `+N x shift`.
    engine = FakeFollower()
    step = Transform(c=0.01)
    compensator = FakeMotionCompensator(transform=step)
    subject = session(FakeRegistry(follower=engine, compensator=compensator))
    subject.apply_config(
        TrackingRequest(
            mode=MODE_FOLLOW,
            verify_every_millis=100_000,  # no verify pass interrupts the stall
            min_hits=1,
            lock=LockRequest(lock_seq=1, point_x=0.15, point_y=0.15),
        )
    )
    born = run(subject, now_millis=0.0, detections=[det("car", x=0.1, y=0.1)])
    born_box = born.boxes[0].track.box

    # The tracker itself never recovers: every remaining frame coasts on
    # prediction alone -- exactly when compensation has to do its job (the
    # "tracker stalled, nothing but prediction left" case). Empty
    # detections throughout -- a static detection here would let a
    # trigger-(b)-forced verify pass re-anchor onto it and reset the box,
    # which is a confound this test does not want; a real occluder offers
    # nothing to re-anchor onto either.
    engine.update_returns = "lost"
    outcome = None
    frame_count = 20
    for frame in range(1, frame_count + 1):
        outcome = run(subject, now_millis=frame * 66.0, detections=[])

    assert outcome.boxes[0].track.box.x == pytest.approx(born_box.x + frame_count * 0.01)


def test_pose_is_selected_when_it_has_a_usable_camera_pose():
    registry = PoseOrFlowRegistry(pose_available=True)
    subject = session(registry)
    subject.apply_config(
        TrackingRequest(
            mode=MODE_FOLLOW,
            min_hits=1,
            motion_engine_id=MOTION_ENGINE_POSE,
            lock=LockRequest(lock_seq=1, point_x=0.15, point_y=0.15),
        )
    )

    outcome = subject.process(
        now_millis=0.0, detect=detect_returning(det()), frame=lambda: FRAME, pose=CameraPose(hfov_degrees=60.0)
    )

    assert outcome.motion_engine_id == MOTION_ENGINE_POSE
    assert registry.pose.estimate_calls == 1
    assert registry.flow.estimate_calls == 0


def test_flow_serves_when_pose_is_requested_but_has_no_usable_camera_pose():
    # The documented normal state today: Java does not populate `camera_
    # pose` yet, so an operator (or a deployment default) asking for `pose`
    # gets `flow` instead of silent non-compensation -- logged once.
    registry = PoseOrFlowRegistry(pose_available=False)
    subject = session(registry)
    subject.apply_config(
        TrackingRequest(
            mode=MODE_FOLLOW,
            min_hits=1,
            motion_engine_id=MOTION_ENGINE_POSE,
            lock=LockRequest(lock_seq=1, point_x=0.15, point_y=0.15),
        )
    )

    outcome = run(subject, now_millis=0.0, detections=[det()])

    assert outcome.motion_engine_id == MOTION_ENGINE_FLOW
    assert registry.pose.estimate_calls == 0
    assert registry.flow.estimate_calls == 1


def test_motion_off_never_asks_the_registry_for_a_compensator():
    registry = FakeRegistry(follower=FakeFollower(), compensator=FakeMotionCompensator)
    subject = session(registry)
    subject.apply_config(
        TrackingRequest(
            mode=MODE_FOLLOW,
            min_hits=1,
            motion_engine_id="off",
            lock=LockRequest(lock_seq=1, point_x=0.15, point_y=0.15),
        )
    )

    outcome = run(subject, now_millis=0.0, detections=[det()])

    assert registry.compensator_calls == 0
    assert outcome.motion_engine_id == ""
    assert outcome.motion_millis == 0


def test_a_raising_motion_compensator_degrades_this_frame_without_killing_the_stream():
    compensator = FakeMotionCompensator()
    compensator.raise_on_next = True
    subject, _engine = follow_session(compensator=compensator)

    degraded = run(subject, now_millis=0.0, detections=[det()])
    assert degraded.motion_engine_id == ""
    assert degraded.boxes[0].track is not None  # tracking itself is unaffected

    recovered = run(subject, now_millis=66.0, detections=[det("bus", x=0.8, y=0.8)])
    assert recovered.motion_engine_id == compensator.engine_id


def test_changing_the_motion_engine_id_rebuilds_it_without_touching_the_tracker():
    first = FakeMotionCompensator("fake-motion-a")
    second = FakeMotionCompensator("fake-motion-b")
    engines = iter([first, second])
    tracker = FakeFollower()
    subject = session(FakeRegistry(follower=tracker, compensator=lambda: next(engines)))
    subject.apply_config(
        TrackingRequest(
            mode=MODE_FOLLOW,
            min_hits=1,
            motion_engine_id="a",
            lock=LockRequest(lock_seq=1, point_x=0.15, point_y=0.15),
        )
    )
    before = run(subject, now_millis=0.0, detections=[det()])
    assert before.motion_engine_id == "fake-motion-a"
    assert tracker.inits == 1

    subject.apply_config(
        TrackingRequest(
            mode=MODE_FOLLOW,
            min_hits=1,
            motion_engine_id="b",
            lock=LockRequest(lock_seq=1, point_x=0.15, point_y=0.15),
        )
    )
    after = run(subject, now_millis=66.0, detections=[det()])

    assert after.motion_engine_id == "fake-motion-b"
    # The SOT engine and the track's id survive untouched -- only the
    # independent motion field changed.
    assert tracker.inits == 1
    assert after.locked_track_id == before.locked_track_id


# -- configuration ----------------------------------------------------------


def test_a_cadence_change_keeps_the_engine_and_the_track_ids():
    subject, engine = follow_session(verify_every_millis=2000)
    first = run(subject, now_millis=0.0, detections=[det()])

    subject.apply_config(
        TrackingRequest(
            mode=MODE_FOLLOW,
            verify_every_millis=500,
            min_hits=1,
            lock=LockRequest(lock_seq=1, point_x=0.15, point_y=0.15),
        )
    )
    later = run(subject, now_millis=600.0, detections=[det()])

    assert later.locked_track_id == first.locked_track_id
    assert later.detector_ran is True
    assert later.detector_reason == REASON_CADENCE


def test_an_engine_change_rebuilds_and_retires_the_ids():
    subject = session(FakeRegistry(associator=FakeAssociator))
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="a", min_hits=1))
    first = run(subject, now_millis=0.0, detections=[det()])

    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="b", min_hits=1))
    second = run(subject, now_millis=66.0, detections=[det()])

    assert second.boxes[0].track.track_id > first.boxes[0].track.track_id


def test_switching_to_off_clears_the_state():
    subject, _engine = follow_session()
    run(subject, now_millis=0.0, detections=[det()])

    subject.apply_config(TrackingRequest(mode=MODE_OFF))

    assert subject.active is False
    assert subject.tracks == []


# -- degradation ------------------------------------------------------------


def test_a_raising_engine_degrades_that_frame_to_untracked_without_killing_the_stream():
    engine = FakeAssociator()
    subject = session(FakeRegistry(associator=engine))
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, min_hits=1))
    run(subject, now_millis=0.0, detections=[det()])

    engine.raise_on_next = True
    degraded = run(subject, now_millis=66.0, detections=[det("car"), det("person", x=0.6)])

    assert [box.track for box in degraded.boxes] == [None, None]
    assert [box.label for box in degraded.boxes] == ["car", "person"]
    assert engine.resets == 1

    survived = run(subject, now_millis=132.0, detections=[det()])
    assert survived.boxes[0].track is not None


def test_an_engine_exception_does_not_retire_or_renumber_other_tracks():
    # Review finding D1: the old fix wiped the WHOLE book (`forget_keys()`)
    # on any engine exception, so a transient error involving one target
    # renumbered the entire scene. Both tracks here now survive untouched.
    engine = FakeAssociator()
    subject = session(FakeRegistry(associator=engine))
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, min_hits=1))
    run(subject, now_millis=0.0, detections=[det("car"), det("person", x=0.6)])
    ids_before = {track.label: track.track_id for track in subject.tracks}
    assert set(ids_before) == {"car", "person"}

    engine.raise_on_next = True
    run(subject, now_millis=66.0, detections=[det("car"), det("person", x=0.6)])

    ids_after = {track.label: track.track_id for track in subject.tracks}
    assert ids_after == ids_before


def test_no_follow_engine_degrades_to_associate(caplog):
    subject = session(FakeRegistry(associator=FakeAssociator("bytetrack"), follower=None))
    subject.apply_config(TrackingRequest(mode=MODE_FOLLOW, min_hits=1))

    with caplog.at_level(logging.WARNING, logger="cv_service.tracking.session"):
        outcome = run(subject, now_millis=0.0, detections=[det()])

    assert subject.params.mode == MODE_ASSOCIATE
    assert outcome.engine_id == "bytetrack"
    assert outcome.boxes[0].track is not None
    assert sum("degrading" in r.getMessage() for r in caplog.records) == 1


def test_no_engine_at_all_degrades_to_off_and_still_emits_the_detections():
    subject = session(FakeRegistry(associator=None, follower=None))
    subject.apply_config(TrackingRequest(mode=MODE_FOLLOW))

    outcome = run(subject, now_millis=0.0, detections=[det("car")])

    assert subject.params.mode == MODE_OFF
    assert outcome.engine_id == ""
    assert [box.label for box in outcome.boxes] == ["car"]
    assert outcome.boxes[0].track is None


def test_no_registry_at_all_degrades_to_off():
    subject = StreamTrackingSession(settings=Settings(), registry_provider=lambda: None)
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE))

    outcome = run(subject, now_millis=0.0, detections=[det()])

    assert subject.params.mode == MODE_OFF
    assert outcome.boxes[0].track is None


def test_degradation_is_logged_once_per_engine_id(caplog):
    subject = session(FakeRegistry(associator=None, follower=None))
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="ghost"))

    with caplog.at_level(logging.WARNING, logger="cv_service.tracking.session"):
        for frame in range(10):
            run(subject, now_millis=frame * 66.0, detections=[det()])

    assert sum("ghost" in r.getMessage() for r in caplog.records) == 1


def test_a_frame_with_no_model_resolved_asks_for_an_echo():
    subject = session(FakeRegistry(associator=FakeAssociator()))
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE))

    outcome = subject.process(
        now_millis=0.0, detect=lambda: (None, 0), frame=lambda: FRAME
    )

    assert outcome.boxes is None


def test_the_session_holds_no_reference_to_an_inference_gate():
    # Structural guard for TRACKING-PLAN §3.1's hard rule: the tracker path
    # cannot acquire the gate even by accident, because the session has no
    # way to reach one. The behavioural counterpart lives in
    # tests/grpc/test_detect_stream_tracking.py.
    subject = session(FakeRegistry(associator=FakeAssociator()))

    assert not any("gate" in name for name in vars(subject))
