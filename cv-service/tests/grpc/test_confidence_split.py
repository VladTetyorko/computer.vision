"""The acquisition/continuation confidence split (docs/conclusions/CV-RATE-BUDGET.md §4).

The detector runs at a low floor while tracking is active so the associators'
low-confidence stage has boxes to work with; the operator's own threshold moves
to the response. Same deterministic idiom as `test_detect_stream_tracking.py`.
"""

from __future__ import annotations

import pytest

from cv_service.config import DEFAULT_CONFIDENCE, DEFAULT_DETECT_FLOOR, Settings
from cv_service.grpc.servicers import InferenceServicer, cv_pb2
from cv_service.inference.detector import Detection

from tests.grpc.test_detect_stream_tracking import (  # noqa: F401 - `clock` is a fixture
    RecordingGate,
    StubTrackerRegistry,
    clock,
    drive,
    frame_request,
    tracking,
)

ASSOCIATE = cv_pb2.TRACKING_MODE_ASSOCIATE


class ThresholdRecordingDetector:
    """Records every `confidence_threshold` it was handed, and emits boxes at
    caller-chosen confidences so the report filter can be exercised."""

    model_name = "fake.pt"

    def __init__(self, detections=None) -> None:
        self.thresholds: list = []
        self._detections = detections if detections is not None else [
            Detection("car", 0.9, 0.10, 0.10, 0.10, 0.10)
        ]

    def detect(self, *, confidence_threshold=None, **_kwargs):
        self.thresholds.append(confidence_threshold)
        return list(self._detections), 11


def servicer_with(detector, settings=None) -> InferenceServicer:
    return InferenceServicer(
        detector=detector,
        inference_gate=RecordingGate(),
        settings=settings if settings is not None else Settings(),
        tracker_registry=StubTrackerRegistry(),
    )


# --- which threshold reaches the detector -------------------------------------


def test_tracking_active_detects_at_the_floor_not_the_operator_threshold(clock):
    detector = ThresholdRecordingDetector()
    subject = servicer_with(detector)

    drive(subject, [frame_request(1, tracking(ASSOCIATE), confidence_threshold=0.4)])

    # 0.4 is what the operator wants to SEE; 0.15 is what the detector runs at,
    # which is the whole point -- below `high_confidence` (0.25), so the
    # associators' low stage is finally non-empty.
    assert detector.thresholds == [DEFAULT_DETECT_FLOOR]
    assert DEFAULT_DETECT_FLOOR < 0.25


def test_tracking_off_still_detects_at_the_operator_threshold(clock):
    detector = ThresholdRecordingDetector()
    subject = servicer_with(detector)

    subject._handle_request(frame_request(1, confidence_threshold=0.4))

    # The OFF path must stay byte-identical to the pre-split service (P1).
    # approx because `confidence_threshold` is a proto `float` (float32).
    assert detector.thresholds == [pytest.approx(0.4)]


def test_an_operator_threshold_below_the_floor_wins(clock):
    detector = ThresholdRecordingDetector()
    subject = servicer_with(detector)

    drive(subject, [frame_request(1, tracking(ASSOCIATE), confidence_threshold=0.05)])

    # The floor may only ever WIDEN recall. Someone who deliberately asked for
    # 0.05 must not silently be given 0.15.
    assert detector.thresholds == [pytest.approx(0.05)]


def test_an_unset_threshold_falls_back_to_the_detector_default(clock):
    detector = ThresholdRecordingDetector()
    subject = servicer_with(detector)

    drive(subject, [frame_request(1, tracking(ASSOCIATE), confidence_threshold=0.0)])

    assert detector.thresholds == [min(DEFAULT_DETECT_FLOOR, DEFAULT_CONFIDENCE)]


def test_the_floor_is_configurable(clock):
    detector = ThresholdRecordingDetector()
    subject = servicer_with(detector, settings=Settings(detect_floor=0.05))

    drive(subject, [frame_request(1, tracking(ASSOCIATE), confidence_threshold=0.4)])

    assert detector.thresholds == [pytest.approx(0.05)]


# --- what reaches the operator ------------------------------------------------


def test_a_weak_box_is_withheld_until_its_track_is_confirmed(clock):
    weak = [Detection("car", 0.18, 0.10, 0.10, 0.10, 0.10)]
    detector = ThresholdRecordingDetector(detections=weak)
    subject = servicer_with(detector)

    responses = drive(
        subject,
        [frame_request(i, tracking(ASSOCIATE), confidence_threshold=0.4) for i in range(1, 5)],
    )

    # min_hits=3: TENTATIVE on frames 1-2 (withheld -- this is the flicker a
    # lower floor would otherwise put on screen), CONFIRMED from frame 3, where
    # the earned identity outweighs the weak per-frame score.
    reported = [len(response.detections) for response in responses]
    assert reported == [0, 0, 1, 1]
    assert responses[-1].detections[0].confidence == pytest.approx(0.18)


def test_a_strong_box_is_reported_immediately(clock):
    strong = [Detection("car", 0.95, 0.10, 0.10, 0.10, 0.10)]
    detector = ThresholdRecordingDetector(detections=strong)
    subject = servicer_with(detector)

    responses = drive(
        subject,
        [frame_request(i, tracking(ASSOCIATE), confidence_threshold=0.4) for i in range(1, 4)],
    )

    assert [len(response.detections) for response in responses] == [1, 1, 1]


def test_a_weak_box_never_reaches_the_operator_when_it_never_confirms(clock):
    # A different label every frame -> a fresh TENTATIVE track each time, which
    # is exactly what detector noise at a 0.15 floor looks like.
    class NoisyDetector(ThresholdRecordingDetector):
        def __init__(self) -> None:
            super().__init__()
            self._n = 0

        def detect(self, *, confidence_threshold=None, **_kwargs):
            self.thresholds.append(confidence_threshold)
            self._n += 1
            return [Detection(f"noise-{self._n}", 0.18, 0.10, 0.10, 0.10, 0.10)], 11

    subject = servicer_with(NoisyDetector())

    responses = drive(
        subject,
        [frame_request(i, tracking(ASSOCIATE), confidence_threshold=0.4) for i in range(1, 6)],
    )

    assert [len(response.detections) for response in responses] == [0, 0, 0, 0, 0]
