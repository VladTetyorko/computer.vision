"""Tests for `cv_service.grpc.servicers.InferenceServicer`: real-detector
wiring, echo fallback, registry-routed detection.

Requires the generated stubs (`scripts/gen_proto.sh`) but NOT the `cv`
optional dependency group -- `cv_service.grpc.servicers` never imports
`cv_service.inference.detector`/`cv_service.inference.registry` at module
scope (both are only imported lazily, inside `_build_default_registry()`),
so these tests exercise the echo-fallback path independent of
opencv/ultralytics availability. Tests that inject a fake detector don't
need `cv` either, since the fake stands in for `YoloDetector` entirely.
"""

from __future__ import annotations

import time

import pytest

import cv_service.grpc.servicers as servicers_module
from cv_service.grpc.servicers import InferenceServicer, cv_pb2


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
    # `InferenceServicer()` with no `detector=` now builds a registry (see
    # `_build_default_registry`), not a lone detector directly -- patch that
    # instead so this still exercises the "nothing could load at all" path.
    monkeypatch.setattr(servicers_module, "_build_default_registry", lambda: None)
    servicer = InferenceServicer()
    assert servicer._detector is None
    assert servicer._registry is None

    request = _make_request()
    (response,) = list(servicer.DetectStream(iter([request]), context=None))

    assert response.stream_id == request.stream_id
    assert response.sequence == request.sequence
    assert response.timestamp_millis == request.timestamp_millis
    assert list(response.detections) == []
    assert response.inference_millis == 0


def test_detect_stream_maps_detector_output_to_wire_shape():
    from cv_service.inference.detector import Detection

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
    `cv_service.grpc.servicers._StreamReader`) into a 1-slot latest-wins
    mailbox -- a plain `iter([...])` delivers all of its items essentially
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
    with caplog.at_level("INFO", logger="cv_service.grpc.servicers"):
        list(servicer.DetectStream(iter(requests), context=None))

    warnings = [r for r in caplog.records if "model registry is Phase 3" in r.message]
    assert len(warnings) == 1


def test_matching_model_id_does_not_warn(caplog):
    detector = FakeDetector(model_name="yolo11n.pt")
    servicer = InferenceServicer(detector=detector)

    request = _make_request(model_id="yolo11n.pt")
    with caplog.at_level("INFO", logger="cv_service.grpc.servicers"):
        list(servicer.DetectStream(iter([request]), context=None))

    assert not any("model registry is Phase 3" in r.message for r in caplog.records)


# --- registry-routed path (InferenceServicer(registry=...)) -----------------
#
# `InferenceServicer._detect_via_registry` lazily imports
# `cv_service.inference.registry` -- that import needs the `cv` extra
# (cv2/numpy, transitively via `cv_service.inference.detector`) even though
# `FakeRegistry`/`FakeDetector` below never touch ultralytics/torch. Each
# test below skips cleanly rather than failing if the `cv` extra isn't
# installed, same pattern as `tests/inference/test_real_model.py`.


class FakeRegistry:
    """Minimal stand-in for `cv_service.inference.registry.ModelRegistry`:
    only the `.resolve(model_id) -> list[(id, detector)]` surface
    `InferenceServicer` actually calls."""

    def __init__(self, resolved_by_model_id: dict[str, list] | None = None, *, default: list | None = None):
        self._resolved_by_model_id = resolved_by_model_id or {}
        self._default = default if default is not None else []

    def resolve(self, model_id: str):
        return self._resolved_by_model_id.get(model_id, self._default)


def test_registry_path_routes_known_model_id_to_matching_detector():
    pytest.importorskip("cv_service.inference.registry")
    from cv_service.inference.detector import Detection

    detector = FakeDetector(
        model_name="orion12l.pt",
        detections=[Detection(label="tank", confidence=0.8, x=0.1, y=0.1, width=0.2, height=0.2)],
        inference_millis=42,
    )
    registry = FakeRegistry(resolved_by_model_id={"orion12l.pt": [("orion12l.pt", detector)]})
    servicer = InferenceServicer(registry=registry)

    request = _make_request(model_id="orion12l.pt")
    (response,) = list(servicer.DetectStream(iter([request]), context=None))

    assert response.inference_millis == 42
    assert len(response.detections) == 1
    assert response.detections[0].label == "tank"  # single member: no prefix
    # the response envelope always echoes what the request asked for, same
    # as the pre-registry behavior -- routing doesn't rewrite it.
    assert response.model_id == "orion12l.pt"


def test_registry_path_falls_back_to_default_for_unknown_model_id():
    pytest.importorskip("cv_service.inference.registry")
    from cv_service.inference.detector import Detection

    default_detector = FakeDetector(
        model_name="yolo11n.pt",
        detections=[Detection(label="person", confidence=0.5, x=0.0, y=0.0, width=0.1, height=0.1)],
        inference_millis=10,
    )
    # A real ModelRegistry.resolve() would fall back to the default for any
    # unrecognized id -- FakeRegistry's `default=` simulates exactly that.
    registry = FakeRegistry(default=[("yolo11n.pt", default_detector)])
    servicer = InferenceServicer(registry=registry)

    request = _make_request(model_id="totally-unknown-model")
    (response,) = list(servicer.DetectStream(iter([request]), context=None))

    assert response.inference_millis == 10
    assert response.detections[0].label == "person"


def test_registry_path_echoes_when_nothing_resolves():
    pytest.importorskip("cv_service.inference.registry")
    registry = FakeRegistry()  # resolve() always returns [] (no default either)
    servicer = InferenceServicer(registry=registry)

    request = _make_request(model_id="anything")
    (response,) = list(servicer.DetectStream(iter([request]), context=None))

    assert list(response.detections) == []
    assert response.inference_millis == 0


def test_registry_path_composite_prefixes_labels_and_sums_millis():
    pytest.importorskip("cv_service.inference.registry")
    from cv_service.inference.detector import Detection

    detector_general = FakeDetector(
        model_name="yolo11n.pt",
        detections=[Detection(label="person", confidence=0.9, x=0.0, y=0.0, width=0.1, height=0.1)],
        inference_millis=15,
    )
    detector_military = FakeDetector(
        model_name="orion12l.pt",
        detections=[Detection(label="tank", confidence=0.7, x=0.2, y=0.2, width=0.2, height=0.2)],
        inference_millis=45,
    )
    registry = FakeRegistry(
        resolved_by_model_id={
            "yolo11n.pt,orion12l.pt": [
                ("yolo11n.pt", detector_general),
                ("orion12l.pt", detector_military),
            ]
        }
    )
    servicer = InferenceServicer(registry=registry)

    request = _make_request(model_id="yolo11n.pt,orion12l.pt")
    (response,) = list(servicer.DetectStream(iter([request]), context=None))

    assert response.inference_millis == 60  # sum of both members
    labels = sorted(d.label for d in response.detections)
    assert labels == ["orion12l:tank", "yolo11n:person"]


def test_registry_path_per_frame_failure_still_echoes():
    pytest.importorskip("cv_service.inference.registry")

    class BoomingRegistry:
        def resolve(self, model_id):
            raise RuntimeError("registry blew up")

    servicer = InferenceServicer(registry=BoomingRegistry())
    request = _make_request()

    (response,) = list(servicer.DetectStream(iter([request]), context=None))

    assert list(response.detections) == []
    assert response.inference_millis == 0


# --- ROI re-detection (TRACKING-V2-PLAN wave C5c) -----------------------------
#
# `_run_detector`'s full-frame branch (`roi=None`) is exercised by every test
# above, unchanged. These exercise `_run_roi_detector` directly -- the crop/
# decode/map round-trip is the part most likely to be silently wrong, per the
# wave's own instruction, so it is tested here in isolation from tracking/
# session/gRPC-stream plumbing entirely. Needs `numpy` (pixel cropping);
# skips rather than fails when the `cv` extra is absent, same contract this
# file's registry-path tests already use for `cv_service.inference.registry`.


class _RecordingGate:
    """Counts `acquire()` calls -- same minimal double
    `tests/grpc/test_detect_stream_tracking.py`'s own `RecordingGate` is,
    kept local here so this file's "importable without `cv`" claim needs no
    cross-file import."""

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


class _CropCapturingDetector:
    """Records the crop it was actually asked to detect on, and reports one
    detection back in the CROP's own normalized `[0, 1]` space."""

    model_name = "fake-crop-model"

    def __init__(self, detections, millis=5):
        self._detections = detections
        self._millis = millis
        self.calls: list[dict] = []

    def detect(self, **kwargs):
        self.calls.append(kwargs)
        return list(self._detections), self._millis


def test_run_detector_roi_crops_and_maps_coordinates_back_to_full_frame():
    # THE coordinate round-trip: a known ROI, a known box the fake detector
    # reports INSIDE it (crop-relative), and the exact full-frame
    # coordinates that must come back out.
    np = pytest.importorskip("numpy")
    from cv_service.inference.detector import ENCODING_BGR24, Detection
    from cv_service.tracking.engines.base import Box

    image = np.zeros((10, 20, 3), dtype=np.uint8)  # height=10, width=20
    detector = _CropCapturingDetector([Detection("car", 0.9, x=0.1, y=0.2, width=0.3, height=0.4)])
    servicer = InferenceServicer(detector=detector)
    request = _make_request(width=20, height=10)
    roi = Box(x=0.25, y=0.2, width=0.5, height=0.6)

    detections, millis = servicer._run_detector(request, roi, lambda: image)

    assert millis == 5
    call = detector.calls[0]
    # roi in PIXELS: x [5, 15) y [2, 8) -- a 10x6 crop of the 20x10 frame.
    assert call["width"] == 10
    assert call["height"] == 6
    assert call["encoding"] == ENCODING_BGR24
    assert len(call["data"]) == 10 * 6 * 3

    (detection,) = detections
    # full = roi.origin + crop_relative * roi.extent, applied per axis.
    assert detection.x == pytest.approx(0.25 + 0.1 * 0.5)
    assert detection.y == pytest.approx(0.2 + 0.2 * 0.6)
    assert detection.width == pytest.approx(0.3 * 0.5)
    assert detection.height == pytest.approx(0.4 * 0.6)
    assert detection.label == "car"
    assert detection.confidence == pytest.approx(0.9)


def test_run_detector_roi_is_gated_by_the_inference_gate():
    # P2: a ROI pass is still a detector pass, gated exactly like a
    # full-frame one -- and through the SAME site (`_run_detector`).
    np = pytest.importorskip("numpy")
    from cv_service.tracking.engines.base import Box

    image = np.zeros((10, 20, 3), dtype=np.uint8)
    gate = _RecordingGate()
    detector = _CropCapturingDetector([])
    servicer = InferenceServicer(detector=detector, inference_gate=gate)
    request = _make_request(width=20, height=10)

    servicer._run_detector(request, Box(0.25, 0.2, 0.5, 0.6), lambda: image)

    assert gate.acquisitions == 1


def test_run_detector_roi_reuses_the_given_frame_loader_not_a_second_decode():
    # "Reuse the servicer's existing memoized frame loader. Do not add a
    # second decode." -- proven directly: the loader is called exactly
    # once for one ROI pass.
    np = pytest.importorskip("numpy")
    from cv_service.tracking.engines.base import Box

    image = np.zeros((10, 20, 3), dtype=np.uint8)
    calls = []

    def loader():
        calls.append(1)
        return image

    servicer = InferenceServicer(detector=_CropCapturingDetector([]))
    request = _make_request(width=20, height=10)

    servicer._run_detector(request, Box(0.25, 0.2, 0.5, 0.6), loader)

    assert len(calls) == 1


def test_run_detector_roi_with_no_frame_loader_is_a_safe_no_op():
    from cv_service.tracking.engines.base import Box

    gate = _RecordingGate()
    detector = _CropCapturingDetector([])
    servicer = InferenceServicer(detector=detector, inference_gate=gate)
    request = _make_request()

    detections, millis = servicer._run_detector(request, Box(0.25, 0.2, 0.5, 0.6), None)

    assert detections == []
    assert millis == 0
    assert detector.calls == []  # never even reached the detector
    assert gate.acquisitions == 0  # -- so never the gate either


def test_run_detector_roi_with_a_degenerate_crop_is_a_safe_no_op():
    # A ROI that rounds down to nothing usable (`_ROI_MIN_PIXELS`) on a tiny
    # frame -- costs this one pass's detections (P5), never raises, never
    # touches the detector or the gate.
    np = pytest.importorskip("numpy")
    from cv_service.tracking.engines.base import Box

    image = np.zeros((10, 20, 3), dtype=np.uint8)
    gate = _RecordingGate()
    detector = _CropCapturingDetector([])
    servicer = InferenceServicer(detector=detector, inference_gate=gate)
    request = _make_request(width=20, height=10)

    # A sliver 0.5% of the frame wide -- rounds to < 2px at this resolution.
    detections, millis = servicer._run_detector(request, Box(0.5, 0.5, 0.005, 0.005), lambda: image)

    assert detections == []
    assert millis == 0
    assert detector.calls == []
    assert gate.acquisitions == 0


def test_run_detector_roi_none_is_byte_identical_to_the_pre_wave_full_frame_call():
    # P1, restated for `_run_detector` itself: the `roi=None` branch must be
    # untouched by this wave -- same detector call, same result, whether or
    # not a `frame_loader`/`roi` argument is even passed.
    detector = FakeDetector(detections=[], inference_millis=11)
    servicer = InferenceServicer(detector=detector)
    request = _make_request()

    without_args = servicer._run_detector(request)
    detector.calls.clear()
    with_none_roi = servicer._run_detector(request, None, None)

    assert without_args == with_none_roi == ([], 11)
    assert len(detector.calls) == 1


def test_registry_path_serves_a_roi_crop_through_detect_composite():
    # The registry-routed branch (composite mode's own gate-per-member
    # acquisition, `detect_composite`) gets the SAME crop treatment as the
    # explicit-detector branch above -- `_detect_via_registry`'s
    # width/height/encoding/data overrides are what make this work without
    # a second code path.
    pytest.importorskip("cv_service.inference.registry")
    np = pytest.importorskip("numpy")
    from cv_service.inference.detector import Detection
    from cv_service.tracking.engines.base import Box

    class OneModelRegistry:
        def resolve(self, model_id):
            return [("fake.pt", _CropCapturingDetector([Detection("car", 0.9, 0.0, 0.0, 1.0, 1.0)]))]

    servicer = InferenceServicer(registry=OneModelRegistry())
    request = _make_request(width=20, height=10)
    roi = Box(0.25, 0.2, 0.5, 0.6)
    image = np.zeros((10, 20, 3), dtype=np.uint8)

    detections, _millis = servicer._run_detector(request, roi, lambda: image)

    (detection,) = detections
    assert detection.x == pytest.approx(0.25)
    assert detection.y == pytest.approx(0.2)
    assert detection.width == pytest.approx(0.5)
    assert detection.height == pytest.approx(0.6)
