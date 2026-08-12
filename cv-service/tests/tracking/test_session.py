"""`cv_service.tracking.session` -- the per-frame sequence, composed.

Pure stdlib: fake engines, a fake clock, a fake "frame" that is just a
sentinel object. Nothing here imports cv2, numpy or gRPC -- which is the
point of keeping everything but `engines/` stdlib-only.
"""

from __future__ import annotations

import dataclasses
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
    def detect(roi=None):
        # `roi=None` default (TRACKING-V2-PLAN wave C5c): every caller
        # before this wave, and every test in this file that never opts
        # into `roi_enabled`, calls this with no argument at all -- adding
        # the parameter here is what keeps this fake shaped like the real
        # `DetectFn` contract without changing a single existing call site.
        # A roi call (only ever made by a test that explicitly opted into
        # `roi_enabled`) answers EMPTY rather than reusing `detections`: a
        # crop around one candidate's predicted box would not, in a real
        # detector, also contain an unrelated object somewhere else in the
        # frame -- reusing the full-frame list here would let an
        # UNINTENTIONAL roi call (e.g. during an roi test's own setup
        # frames) silently contaminate a DIFFERENT track with the wrong
        # box. A test that wants a specific roi answer uses
        # `RecordingDetect` instead.
        if roi is None:
            return list(detections), millis
        return [], millis

    return detect


def run(subject, *, now_millis, detections=(), millis=7, detect=None):
    return subject.process(
        now_millis=now_millis,
        detect=detect if detect is not None else detect_returning(*detections, millis=millis),
        frame=lambda: FRAME,
    )


class RecordingDetect:
    """A `DetectFn` double for TRACKING-V2-PLAN wave C5c: `full` answers a
    `roi=None` (full-frame) call, `roi_response` answers every `roi=<Box>`
    call, and every roi call's OWN `Box` argument is recorded -- so a test
    can assert how many ROI passes happened this frame, and exactly which
    region each one asked for, without a servicer or a real detector.
    """

    def __init__(self, full=(), roi_response=(), millis=7, roi_millis=5):
        self.full = list(full)
        self.roi_response = list(roi_response)
        self.millis = millis
        self.roi_millis = roi_millis
        self.roi_calls: list[Box] = []

    def __call__(self, roi=None):
        if roi is None:
            return list(self.full), self.millis
        self.roi_calls.append(roi)
        return list(self.roi_response), self.roi_millis


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


# -- ROI re-detection (TRACKING-V2-PLAN wave C5c, review §4.6) ---------------


def roi_settings(**overrides) -> Settings:
    overrides.setdefault("track_roi_enabled", True)
    overrides.setdefault("track_roi_crop_factor", 4.0)
    return dataclasses.replace(Settings(), **overrides)


def test_roi_rescue_is_a_genuine_no_op_when_disabled():
    # Disabling must never call `detect` with a roi, even when an eligible
    # CONFIRMED-but-unmatched candidate exists -- a genuine no-op, not merely
    # an unlikely trigger. (The default is now ON; this asserts the OFF path
    # still costs exactly nothing, which is what an operator turning it off
    # for a dense scene is buying.)
    registry = FakeRegistry(associator=cost_engine)
    subject = session(registry, settings=dataclasses.replace(Settings(), track_roi_enabled=False))
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1))
    run(subject, now_millis=0.0, detections=[det("car", x=0.1)])

    detect = RecordingDetect(full=[])
    outcome = subject.process(now_millis=66.0, detect=detect, frame=lambda: FRAME)

    assert detect.roi_calls == []
    assert outcome.detector_roi is False
    assert outcome.boxes == []


def test_roi_rescue_recovers_an_unmatched_confirmed_track_under_its_own_id():
    registry = FakeRegistry(associator=cost_engine)
    subject = session(registry, settings=roi_settings())
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1))
    first = run(subject, now_millis=0.0, detections=[det("car", x=0.1, y=0.1, w=0.1, h=0.1)])
    track_id = first.boxes[0].track.track_id

    # The full-frame pass sees nothing; the ROI pass, over a crop around the
    # SAME predicted box, finds the object again.
    detect = RecordingDetect(full=[], roi_response=[det("car", x=0.1, y=0.1, w=0.1, h=0.1)])
    outcome = subject.process(now_millis=66.0, detect=detect, frame=lambda: FRAME)

    assert len(detect.roi_calls) == 1
    assert outcome.detector_roi is True
    assert len(outcome.boxes) == 1
    assert outcome.boxes[0].track.track_id == track_id
    assert outcome.boxes[0].track.misses == 0  # a genuine re-anchor, not a coast
    assert outcome.inference_millis == detect.millis + detect.roi_millis


def test_roi_rescue_never_fires_for_a_tentative_candidate():
    # `min_hits=2`: one hit is not enough to leave TENTATIVE, so a
    # candidate that has never earned an id is not "something the system
    # believes in" -- not eligible for a rescue pass.
    registry = FakeRegistry(associator=cost_engine)
    subject = session(registry, settings=roi_settings())
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=2))
    run(subject, now_millis=0.0, detections=[det("car", x=0.1)])

    detect = RecordingDetect(full=[], roi_response=[det("car", x=0.1)])
    subject.process(now_millis=66.0, detect=detect, frame=lambda: FRAME)

    assert detect.roi_calls == []


def test_roi_rescue_never_hand_attaches_a_detection_that_fails_the_cost_gates():
    # A strict `min_iou` gate: the roi "find" is a completely different box
    # (zero overlap with the candidate's predicted position), so the SAME
    # gates a full-frame match would apply must reject it -- never a
    # hand-attach.
    registry = FakeRegistry(associator=cost_engine)
    subject = session(registry, settings=roi_settings(track_cost_gate_min_iou=0.5))
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1))
    run(subject, now_millis=0.0, detections=[det("car", x=0.1, y=0.1, w=0.1, h=0.1)])

    detect = RecordingDetect(full=[], roi_response=[det("car", x=0.8, y=0.8, w=0.1, h=0.1)])
    outcome = subject.process(now_millis=66.0, detect=detect, frame=lambda: FRAME)

    assert len(detect.roi_calls) == 1  # the pass DID run --
    assert outcome.boxes == []  # -- it just found nothing worth merging
    assert subject.tracks[0].misses == 1  # ages normally, exactly as before this wave


def test_at_most_one_roi_pass_happens_per_frame_even_with_two_eligible_candidates():
    registry = FakeRegistry(associator=cost_engine)
    subject = session(registry, settings=roi_settings())
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1))
    run(subject, now_millis=0.0, detections=[det("car", x=0.1), det("bus", x=0.6, y=0.6)])
    # Both miss together on the next frame -- two eligible candidates.
    run(subject, now_millis=66.0, detections=[])

    detect = RecordingDetect(full=[])
    subject.process(now_millis=132.0, detect=detect, frame=lambda: FRAME)

    assert len(detect.roi_calls) == 1


def test_roi_rescue_prioritizes_the_candidate_with_the_most_misses():
    registry = FakeRegistry(associator=cost_engine)
    subject = session(registry, settings=roi_settings())
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1))
    run(subject, now_millis=0.0, detections=[det("car", x=0.1, y=0.1, w=0.1, h=0.1), det("bus", x=0.6, y=0.6, w=0.1, h=0.1)])
    # `car` keeps matching (misses stays 0); `bus` starts missing a frame
    # earlier than the shared frame below, so by the shared frame `bus` has
    # STRICTLY more misses than `car` -- the priority rule this test pins.
    run(subject, now_millis=66.0, detections=[det("car", x=0.1, y=0.1, w=0.1, h=0.1)])

    detect = RecordingDetect(full=[])
    subject.process(now_millis=132.0, detect=detect, frame=lambda: FRAME)

    assert len(detect.roi_calls) == 1
    roi = detect.roi_calls[0]
    # `bus`'s predicted box is centered near x=0.65/y=0.65; `car`'s near
    # x=0.15/y=0.15 -- asserting on the CENTER is robust to the exact crop
    # math (`_roi_box`'s own concern, tested separately below).
    center_x, center_y = roi.center
    assert center_x > 0.4
    assert center_y > 0.4


def test_roi_box_is_a_square_crop_centered_on_the_candidates_predicted_box():
    registry = FakeRegistry(associator=cost_engine)
    subject = session(registry, settings=roi_settings(track_roi_crop_factor=2.0))
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1))
    run(subject, now_millis=0.0, detections=[det("car", x=0.40, y=0.40, w=0.10, h=0.10)])

    detect = RecordingDetect(full=[])
    subject.process(now_millis=66.0, detect=detect, frame=lambda: FRAME)

    (roi,) = detect.roi_calls
    # box center is (0.45, 0.45); crop_factor=2.0 * larger dimension (0.10)
    # = 0.20 side, so [0.35, 0.55] on both axes -- comfortably inside the
    # unit frame, so no clamping to complicate the assertion.
    assert roi.x == pytest.approx(0.35)
    assert roi.y == pytest.approx(0.35)
    assert roi.width == pytest.approx(0.20)
    assert roi.height == pytest.approx(0.20)


def test_a_non_positive_crop_factor_is_a_genuine_no_op():
    registry = FakeRegistry(associator=cost_engine)
    subject = session(registry, settings=roi_settings(track_roi_crop_factor=0.0))
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1))
    run(subject, now_millis=0.0, detections=[det("car", x=0.1)])

    detect = RecordingDetect(full=[])
    subject.process(now_millis=66.0, detect=detect, frame=lambda: FRAME)

    assert detect.roi_calls == []


def test_bytetrack_associate_never_triggers_a_roi_pass():
    # TRACKING-V2-PLAN wave C5c's own scope decision: `bytetrack` has no
    # Candidate/Assignment seam (its association state lives inside a
    # third-party engine), the SAME reasoning wave C2 already gives for why
    # `bytetrack` never gets ego-motion compensation either.
    registry = FakeRegistry(associator=FakeAssociator("bytetrack"))
    subject = session(registry, settings=roi_settings())
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="bytetrack", min_hits=1))
    run(subject, now_millis=0.0, detections=[det("car", x=0.1)])

    detect = RecordingDetect(full=[])
    subject.process(now_millis=66.0, detect=detect, frame=lambda: FRAME)

    assert detect.roi_calls == []


def test_follow_mode_never_triggers_a_roi_pass():
    registry = FakeRegistry(follower=FakeFollower)
    subject = session(registry, settings=roi_settings())
    subject.apply_config(
        TrackingRequest(
            mode=MODE_FOLLOW, engine_id="fake-follow", min_hits=1, lock=LockRequest(point_x=0.15, point_y=0.15)
        )
    )
    run(subject, now_millis=0.0, detections=[det("car", x=0.1, y=0.1, w=0.1, h=0.1)])

    detect = RecordingDetect(full=[det("car", x=0.1, y=0.1, w=0.1, h=0.1)])
    subject.process(now_millis=2000.0, detect=detect, frame=lambda: FRAME)

    assert detect.roi_calls == []


def test_a_roi_rescue_does_not_double_age_the_rest_of_the_book():
    # `TrackBook.apply()`'s own trap (this file's module docstring, and the
    # multi-target FOLLOW wave that first found it): a second `apply()` call
    # in the same frame would double-count `age_frames`/`misses` for EVERY
    # live track, not just the rescued one. `bystander` here is a live,
    # ALWAYS-matched track that never touches the rescue at all -- its
    # `age_frames` must advance by exactly one on the rescue frame.
    registry = FakeRegistry(associator=cost_engine)
    subject = session(registry, settings=roi_settings())
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1))
    run(
        subject,
        now_millis=0.0,
        detections=[det("car", x=0.1, y=0.1, w=0.1, h=0.1), det("bystander", x=0.6, y=0.6, w=0.1, h=0.1)],
    )
    bystander_age_before = next(t for t in subject.tracks if t.label == "bystander").age_frames

    detect = RecordingDetect(
        full=[det("bystander", x=0.6, y=0.6, w=0.1, h=0.1)],
        roi_response=[det("car", x=0.1, y=0.1, w=0.1, h=0.1)],
    )
    subject.process(now_millis=66.0, detect=detect, frame=lambda: FRAME)

    bystander_age_after = next(t for t in subject.tracks if t.label == "bystander").age_frames
    assert bystander_age_after == bystander_age_before + 1


# -- object memory (TRACKING-V2-PLAN wave C4) --------------------------------


def test_a_track_that_expires_is_recovered_under_its_own_id_when_it_reappears():
    # The session-level counterpart to `long_occlusion`'s harness row
    # (`MODULE.md` "Wave C4"): `max_age_frames=1` retires a track past 2
    # consecutive misses (`TrackBook`'s own retention multiplier), which is
    # short enough to drive deterministically here without 90 frames.
    registry = FakeRegistry(associator=cost_engine)
    subject = session(registry)
    subject.apply_config(
        TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1, max_age_frames=1)
    )
    born = run(subject, now_millis=0.0, detections=[det("car", x=0.1)])
    assert born.boxes[0].track.track_id == 1

    for frame in range(1, 4):
        run(subject, now_millis=frame * 1000.0, detections=[])
    assert subject.tracks == []  # genuinely gone from the live book

    recovered = run(subject, now_millis=4000.0, detections=[det("car", x=0.1)])

    box = recovered.boxes[0]
    assert box.track.track_id == 1  # the SAME id, not a fresh one
    assert box.track.state == STATE_CONFIRMED  # min_hits was not re-earned
    assert box.identity_confidence > 0.0
    assert box.dormant_millis == pytest.approx(1000, abs=5)

    # The NEXT frame is an ordinary re-match, not a recovery -- both fields
    # must fall back to their zero value rather than staying "sticky".
    again = run(subject, now_millis=4066.0, detections=[det("car", x=0.1)])
    assert again.boxes[0].track.track_id == 1
    assert again.boxes[0].identity_confidence == 0.0
    assert again.boxes[0].dormant_millis == 0


def test_a_track_expiring_under_one_associator_is_recovered_after_switching_to_cost():
    # `TrackBook._retire` remembers regardless of which associator produced
    # the track -- `bytetrack` (via `FakeAssociator`) never itself queries
    # the gallery back, but the SAME book, and the SAME `ObjectMemory`
    # instance, keep serving this stream across an `apply_config` engine
    # switch. Proven end to end through public API only (`subject.tracks`/
    # `outcome.boxes`), not by inspecting session internals.
    registry = FakeRegistry(associator=FakeAssociator("bytetrack"))
    subject = session(registry)
    subject.apply_config(
        TrackingRequest(mode=MODE_ASSOCIATE, engine_id="bytetrack", min_hits=1, max_age_frames=1)
    )
    born = run(subject, now_millis=0.0, detections=[det("car", x=0.1)])
    assert born.boxes[0].track.track_id == 1

    for frame in range(1, 4):
        run(subject, now_millis=frame * 1000.0, detections=[])
    assert subject.tracks == []

    registry._associator = cost_engine  # the registry now serves `cost`
    subject.apply_config(
        TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1, max_age_frames=1)
    )
    recovered = run(subject, now_millis=4000.0, detections=[det("car", x=0.1)])

    assert recovered.boxes[0].track.track_id == 1


def test_a_different_object_arriving_in_the_gap_does_not_inherit_the_id():
    registry = FakeRegistry(associator=cost_engine)
    subject = session(registry)
    subject.apply_config(
        TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1, max_age_frames=1)
    )
    run(subject, now_millis=0.0, detections=[det("car", x=0.1)])

    for frame in range(1, 4):
        run(subject, now_millis=frame * 1000.0, detections=[])

    stranger = run(subject, now_millis=4000.0, detections=[det("person", x=0.1)])

    box = stranger.boxes[0]
    assert box.track.track_id == 2  # a fresh id -- #1 is not handed out
    assert box.identity_confidence == 0.0
    assert box.dormant_millis == 0


def test_memory_disabled_deployment_wide_is_a_genuine_no_op():
    # `CV_TRACK_MEMORY_TTL_MILLIS<=0` (here, `Settings` directly) is memory
    # OFF fleet-wide -- a track that expires is gone for good, exactly the
    # pre-wave-C4 behaviour, and a reappearance mints a fresh id.
    settings = Settings(track_memory_ttl_millis=0)
    registry = FakeRegistry(associator=cost_engine)
    subject = session(registry, settings)
    subject.apply_config(
        TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1, max_age_frames=1)
    )
    run(subject, now_millis=0.0, detections=[det("car", x=0.1)])

    for frame in range(1, 4):
        run(subject, now_millis=frame * 1000.0, detections=[])
    assert subject.tracks == []

    reborn = run(subject, now_millis=4000.0, detections=[det("car", x=0.1)])

    box = reborn.boxes[0]
    assert box.track.track_id == 2  # nothing was ever remembered
    assert box.identity_confidence == 0.0


def test_a_positive_request_ttl_overrides_the_deployment_default():
    # A tiny per-request TTL means the identity has already expired from the
    # GALLERY (not merely `TrackBook`) by the time the object reappears.
    registry = FakeRegistry(associator=cost_engine)
    subject = session(registry)
    subject.apply_config(
        TrackingRequest(
            mode=MODE_ASSOCIATE,
            engine_id="cost",
            min_hits=1,
            max_age_frames=1,
            memory_ttl_millis=500,
        )
    )
    run(subject, now_millis=0.0, detections=[det("car", x=0.1)])

    for frame in range(1, 4):
        run(subject, now_millis=frame * 1000.0, detections=[])  # retired at now=3000

    # 4000 - 3000 = 1000ms dormant, already past the 500ms TTL.
    stranger = run(subject, now_millis=4000.0, detections=[det("car", x=0.1)])

    assert stranger.boxes[0].track.track_id == 2


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
    """Re-acquisition keeps happening -- but on a cadence, not every frame.

    This test used to assert a pass on EVERY frame, which is what the code
    did and what made trigger (c) a trap: a FOLLOW stream whose target is
    simply gone spent more detector passes than ASSOCIATE would, for as long
    as the stream lasted, silently ending the duty cycle FOLLOW exists for.
    Rewritten deliberately -- the old assertion pinned the defect.
    """
    subject = session(FakeRegistry(follower=FakeFollower()))
    subject.apply_config(TrackingRequest(mode=MODE_FOLLOW))

    reasons = []
    for frame in range(20):
        outcome = run(subject, now_millis=frame * 66.0, detections=[det()])
        assert outcome.locked_track_id == 0
        if outcome.detector_ran:
            reasons.append(outcome.detector_reason)

    # It keeps trying -- an operator's target may be back at any moment...
    assert reasons
    assert set(reasons) == {REASON_NO_LOCK}
    # ...but it does not spend a pass on all twenty frames.
    assert len(reasons) < 20


def test_follow_without_a_lock_does_not_run_the_detector_every_frame():
    """The bound, stated as the cost it exists to prevent."""
    subject = session(FakeRegistry(follower=FakeFollower()))
    subject.apply_config(TrackingRequest(mode=MODE_FOLLOW))

    frames = 30
    passes = sum(
        1
        for frame in range(frames)
        if run(subject, now_millis=frame * 66.0, detections=[det()]).detector_ran
    )
    # 30 frames at ~15 fps is ~2 s of stream. At the default 250 ms
    # re-acquire cadence that is a handful of passes, not thirty.
    assert passes <= frames // 3


def test_re_acquisition_is_never_slower_than_the_verify_cadence():
    """An operator asking for a very short cadence is asking the detector to
    look often, and a target they have LOST is more urgent than one they
    still hold -- so the rate limit must not overtake the cadence."""
    subject = session(FakeRegistry(follower=FakeFollower()))
    subject.apply_config(TrackingRequest(mode=MODE_FOLLOW, verify_every_millis=1))

    for frame in range(5):
        outcome = run(subject, now_millis=frame * 10.0, detections=[det()])
        assert outcome.detector_ran is True
        assert outcome.detector_reason == REASON_NO_LOCK


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


# -- multi-target FOLLOW (TRACKING-V2-PLAN wave C5b, review finding C6) -----


def multi_follow_session(follow_top_k, *, verify_every_millis=2000, **lock_kwargs):
    """A FOLLOW session whose follower engine is a FRESH `FakeFollower`
    PER CALL (unlike `follow_session`'s single shared instance, which would
    corrupt multi-target state: the locked target and every extra each need
    their OWN engine object). `created` lists every engine built, in
    construction order -- index 0 is always the locked target's own.
    """
    created: "list[FakeFollower]" = []

    def factory():
        engine = FakeFollower()
        created.append(engine)
        return engine

    settings = dataclasses.replace(Settings(), track_follow_top_k=follow_top_k)
    subject = session(FakeRegistry(follower=factory), settings)
    subject.apply_config(
        TrackingRequest(
            mode=MODE_FOLLOW,
            verify_every_millis=verify_every_millis,
            min_hits=1,
            lock=LockRequest(lock_seq=1, **(lock_kwargs or {"point_x": 0.15, "point_y": 0.15})),
        )
    )
    return subject, created


def test_k_equal_one_reproduces_todays_single_target_behaviour_exactly():
    """The wave's own safety net: `follow_top_k=1` (the shipped default)
    must never build an extra, never touch the registry an extra would need,
    and emit exactly one box on a tracker-only frame -- byte-identical to
    FOLLOW before this wave existed."""
    subject, engines = multi_follow_session(1, verify_every_millis=100_000)
    run(
        subject,
        now_millis=0.0,
        detections=[det("car", x=0.1, y=0.1), det("person", x=0.4, y=0.4, confidence=0.8)],
    )

    tracker_only = run(subject, now_millis=66.0, detections=[det()])

    assert len(tracker_only.boxes) == 1
    assert len(engines) == 1  # no extra engine was ever built


def test_follow_emits_more_than_one_box_between_verify_passes_when_k_exceeds_one():
    subject, _engines = multi_follow_session(3, verify_every_millis=100_000)
    born = run(
        subject,
        now_millis=0.0,
        detections=[
            det("car", x=0.1, y=0.1),
            det("person", x=0.4, y=0.4, confidence=0.8),
            det("bus", x=0.7, y=0.7, confidence=0.6),
        ],
    )
    assert born.locked_track_id != 0

    tracker_only = run(subject, now_millis=66.0, detections=[det()])

    # The locked target plus both extras -- K=3 means a ceiling of 3, and
    # all three candidates were available to fill it.
    assert len(tracker_only.boxes) == 3
    assert tracker_only.boxes[0].track.track_id == born.locked_track_id


def test_the_locked_target_is_always_first_and_is_never_displaced():
    subject, engines = multi_follow_session(3, verify_every_millis=100_000)
    born = run(
        subject,
        now_millis=0.0,
        detections=[
            det("car", x=0.1, y=0.1),
            det("person", x=0.4, y=0.4, confidence=0.8),
            det("bus", x=0.7, y=0.7, confidence=0.6),
        ],
    )
    locked_id = born.locked_track_id

    # One extra's own engine fails; the locked target's engine is untouched.
    engines[1].update_returns = "lost"
    outcome = run(subject, now_millis=66.0, detections=[det()])

    assert outcome.boxes[0].track.track_id == locked_id
    assert len(outcome.boxes) == 2  # locked + the one surviving extra


def test_a_dropped_extras_engine_is_released_and_its_slot_reopens():
    subject, engines = multi_follow_session(2, verify_every_millis=1)
    run(
        subject,
        now_millis=0.0,
        detections=[det("car", x=0.1, y=0.1), det("person", x=0.4, y=0.4)],
    )
    assert len(engines) == 2
    engines[1].update_returns = "lost"
    dropped = run(subject, now_millis=10.0, detections=[det()])
    assert len(dropped.boxes) == 1  # only the locked target survives this frame
    assert engines[1].resets == 1  # the failed extra's engine was released

    # The next verify pass has an unclaimed detection again -- a fresh extra
    # is promoted into the now-open slot, with its OWN new engine.
    refilled = run(
        subject,
        now_millis=20.0,
        detections=[det("car", x=0.1, y=0.1), det("bus", x=0.7, y=0.7)],
    )

    assert len(refilled.boxes) == 2
    assert len(engines) == 3  # a THIRD, brand-new engine for the new extra


def test_an_existing_extra_re_anchors_on_the_best_iou_match_next_verify_pass():
    subject, engines = multi_follow_session(2, verify_every_millis=1)
    run(
        subject,
        now_millis=0.0,
        detections=[det("car", x=0.1, y=0.1), det("person", x=0.4, y=0.4)],
    )
    extra_id = None
    for track in subject.tracks:
        if track.label == "person":
            extra_id = track.track_id

    # The SAME physical target, slightly moved -- still the best IoU match.
    reanchored = run(
        subject,
        now_millis=10.0,
        detections=[det("car", x=0.1, y=0.1), det("person", x=0.41, y=0.4)],
    )

    assert len(engines) == 2  # no third engine -- the same extra re-anchored
    reanchored_extra = next(box for box in reanchored.boxes if box.track and box.track.label == "person")
    assert reanchored_extra.track.track_id == extra_id


def test_follow_top_k_never_touches_extras_when_the_lock_itself_fails_to_reanchor():
    subject, engines = multi_follow_session(3, verify_every_millis=1)
    run(
        subject,
        now_millis=0.0,
        detections=[det("car", x=0.1, y=0.1), det("person", x=0.4, y=0.4)],
    )
    assert len(engines) == 2  # locked + one extra, promoted on the first pass

    # The verify pass now sees nothing near the locked target at all.
    coasting = run(subject, now_millis=10.0, detections=[det("bus", x=0.8, y=0.8)])

    assert coasting.boxes[-1].track.state == "TRACK_STATE_COASTING"
    # No third engine was built attempting to promote "bus" as an extra.
    assert len(engines) == 2


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


# -- reconnect (TRACKING-V2-PLAN wave C5b, review finding B5) ----------------
#
# `reset_for_reconnect` is called by `cv_service.tracking.sessions.
# SessionRegistry` when a stream disconnects (see that module's own tests
# for the pool mechanics); these pin exactly what it does and does not touch
# on the session itself.


def test_reconnect_drops_the_engine_but_keeps_the_book_and_the_locks_target():
    subject, _engine = follow_session()
    run(subject, now_millis=0.0, detections=[det()])
    assert subject._engine is not None
    assert subject.tracks
    assert subject._lock.bound_track_id != 0

    subject.reset_for_reconnect()

    # Engine state: NOT resumed -- forces a clean rebuild on the next frame.
    assert subject._engine is None
    assert subject._engine_id == ""
    # Identity core: resumed untouched -- the book was not wiped, and the
    # operator's own FOLLOW target survives (only the momentary BOUND track
    # id resets, which correctly asks the next frame to re-verify it).
    assert subject.tracks
    assert subject._lock.has_target
    assert subject._lock.bound_track_id == 0


def test_reconnect_drops_the_motion_compensator():
    compensator = FakeMotionCompensator()
    subject, _engine = follow_session(compensator=compensator)
    run(subject, now_millis=0.0, detections=[det()])
    assert subject._motion_engine is not None

    subject.reset_for_reconnect()

    assert subject._motion_engine is None
    assert subject._motion_resolved is False


def test_reconnect_drops_the_appearance_extractor():
    extractor = FakeAppearanceExtractor()
    subject = session(FakeRegistry(associator=cost_engine(), appearance=extractor))
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1))
    run(subject, now_millis=0.0, detections=[det()])
    assert subject._appearance_engine is not None

    subject.reset_for_reconnect()

    assert subject._appearance_engine is None
    assert subject._appearance_resolved is False


def test_reconnect_does_not_rebuild_the_dormant_gallery():
    subject = session(FakeRegistry(associator=cost_engine()))
    subject.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, engine_id="cost", min_hits=1))
    run(subject, now_millis=0.0, detections=[det()])
    memory_before = subject._memory
    assert memory_before is not None

    subject.reset_for_reconnect()

    assert subject._memory is memory_before  # the SAME gallery object


def test_a_reconnected_stream_re_verifies_and_continues_the_id_counter():
    """End to end at the session level: after a reconnect, the very next
    active frame still finds and locks the target again, via a freshly
    built engine. Reconnect IS an engine reset (`reset_for_reconnect` calls
    the same `_release_engine`/`bump_epoch` machinery review findings D1/D2
    already built), so it inherits that mechanism's existing, deliberate
    rule verbatim: a live target's OWN id is not preserved across an engine
    reset (`test_an_engine_change_rebuilds_and_retires_the_ids` pins the
    identical rule for an ordinary `engine_id` change) -- only the BOOK's
    overall numbering survives, continuing from where it left off rather
    than restarting at 1. Recovering the SAME id for the reconnecting
    object specifically is what wave C4's `ObjectMemory` is for, and it is
    already reachable here exactly as it is for any other engine reset --
    this wave adds no new mechanism for it, it only makes the book survive
    long enough to be asked."""
    subject, engine = follow_session(verify_every_millis=100_000)
    first = run(subject, now_millis=0.0, detections=[det("car", x=0.1)])

    subject.reset_for_reconnect()
    assert engine.inits == 1  # the OLD engine was touched exactly once, pre-reconnect

    resumed = run(subject, now_millis=1000.0, detections=[det("car", x=0.1)])

    assert resumed.locked_track_id > first.locked_track_id


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
