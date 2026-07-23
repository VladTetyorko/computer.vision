"""Tests for cv_service.server: real-detector wiring, echo fallback.

Requires the generated stubs (`scripts/gen_proto.sh`) but NOT the `cv`
optional dependency group -- `cv_service.server` never imports
`cv_service.inference` at module scope (see `_build_default_detector`), so
these tests exercise the echo-fallback path independent of opencv/ultralytics
availability. Tests that inject a fake detector don't need `cv` either, since
the fake stands in for `YoloDetector` entirely.
"""

from __future__ import annotations

import time

import pytest

import cv_service.server as server_module
from cv_service.server import InferenceServicer, cv_pb2


class FakeDetector:
    def __init__(self, model_name="fake-model", detections=None, inference_millis=7):
        self.model_name = model_name
        self._detections = detections if detections is not None else []
        self._inference_millis = inference_millis
        self.calls: list[dict] = []

    def detect(self, **kwargs):
        self.calls.append(kwargs)
        return self._detections, self._inference_millis


def _make_request(**overrides):
    fields = dict(
        stream_id="stream-1",
        sequence=42,
        timestamp_millis=1234,
        width=4,
        height=3,
        encoding=cv_pb2.IMAGE_ENCODING_BGR24,
        data=b"\x00" * (4 * 3 * 3),
        model_id="",
        model_version="",
        confidence_threshold=0.0,
    )
    fields.update(overrides)
    return cv_pb2.FrameRequest(**fields)


def test_detect_stream_echoes_when_detector_unavailable(monkeypatch):
    monkeypatch.setattr(server_module, "_build_default_detector", lambda: None)
    servicer = InferenceServicer()
    assert servicer._detector is None

    request = _make_request()
    (response,) = list(servicer.DetectStream(iter([request]), context=None))

    assert response.stream_id == request.stream_id
    assert response.sequence == request.sequence
    assert response.timestamp_millis == request.timestamp_millis
    assert list(response.detections) == []
    assert response.inference_millis == 0


def test_detect_stream_maps_detector_output_to_wire_shape():
    from cv_service.inference import Detection

    detector = FakeDetector(
        detections=[Detection(label="person", confidence=0.87, x=0.1, y=0.2, width=0.3, height=0.4)],
        inference_millis=12,
    )
    servicer = InferenceServicer(detector=detector)

    request = _make_request()
    (response,) = list(servicer.DetectStream(iter([request]), context=None))

    assert response.inference_millis == 12
    assert len(response.detections) == 1
    detection = response.detections[0]
    assert detection.label == "person"
    assert detection.confidence == pytest.approx(0.87)
    assert detection.box.x == pytest.approx(0.1)
    assert detection.box.y == pytest.approx(0.2)
    assert detection.box.width == pytest.approx(0.3)
    assert detection.box.height == pytest.approx(0.4)
    # stream_id/sequence/timestamp echo exactly as the Phase 0 stub did.
    assert response.stream_id == request.stream_id
    assert response.sequence == request.sequence
    assert response.timestamp_millis == request.timestamp_millis

    call = detector.calls[0]
    assert call["encoding"] == "IMAGE_ENCODING_BGR24"
    assert call["width"] == 4
    assert call["height"] == 3
    assert call["confidence_threshold"] is None


def test_detect_stream_passes_nonzero_confidence_threshold():
    detector = FakeDetector()
    servicer = InferenceServicer(detector=detector)

    request = _make_request(confidence_threshold=0.42)
    list(servicer.DetectStream(iter([request]), context=None))

    assert detector.calls[0]["confidence_threshold"] == pytest.approx(0.42)


def test_detect_stream_falls_back_to_echo_on_per_frame_failure():
    class BoomingDetector:
        model_name = "boom-model"

        def detect(self, **_kwargs):
            raise RuntimeError("decode blew up")

    servicer = InferenceServicer(detector=BoomingDetector())
    request = _make_request()

    (response,) = list(servicer.DetectStream(iter([request]), context=None))

    assert list(response.detections) == []
    assert response.inference_millis == 0
    assert response.stream_id == request.stream_id
    assert response.sequence == request.sequence


class _PacedIterator:
    """Yields `items` one at a time with a small delay before each.

    V-d's `DetectStream` now reads frames on a background thread (see
    `cv_service.server._StreamReader`) into a 1-slot latest-wins mailbox --
    a plain `iter([...])` delivers all of its items essentially
    instantaneously, which races that background thread against however
    fast this test happens to get scheduled onto the mailbox, and can
    legitimately (by design -- see `LatestOnlyMailbox`) drop items that a
    slower, more realistic producer (a real network) would never have
    bunched up in the first place. This iterator's small per-item delay
    keeps the consumer always caught up before the next item exists, making
    "every item is delivered, in order" deterministic again without
    changing what's being tested.
    """

    def __init__(self, items, delay: float = 0.02) -> None:
        self._items = iter(items)
        self._delay = delay

    def __iter__(self):
        return self

    def __next__(self):
        time.sleep(self._delay)
        return next(self._items)


def test_multiple_requests_get_independent_responses():
    detector = FakeDetector()
    servicer = InferenceServicer(detector=detector)

    requests = [_make_request(sequence=i) for i in range(3)]
    responses = list(servicer.DetectStream(_PacedIterator(requests), context=None))

    assert [r.sequence for r in responses] == [0, 1, 2]


def test_unknown_model_id_warns_once_and_serves_default(caplog):
    detector = FakeDetector(model_name="yolo11n.pt")
    servicer = InferenceServicer(detector=detector)

    requests = [_make_request(model_id="some-other-model") for _ in range(3)]
    with caplog.at_level("INFO", logger="cv_service.server"):
        list(servicer.DetectStream(iter(requests), context=None))

    warnings = [r for r in caplog.records if "model registry is Phase 3" in r.message]
    assert len(warnings) == 1


def test_matching_model_id_does_not_warn(caplog):
    detector = FakeDetector(model_name="yolo11n.pt")
    servicer = InferenceServicer(detector=detector)

    request = _make_request(model_id="yolo11n.pt")
    with caplog.at_level("INFO", logger="cv_service.server"):
        list(servicer.DetectStream(iter([request]), context=None))

    assert not any("model registry is Phase 3" in r.message for r in caplog.records)
