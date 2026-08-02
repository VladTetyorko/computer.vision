"""Unit tests for cv_service.inference.detector: decode paths + box mapping.

These need the `cv` extra's opencv-python-headless/numpy installed (for
decode_frame) but NOT ultralytics/torch -- YoloDetector tests inject a fake
model object, and the "ultralytics missing" test forces an ImportError via
sys.modules regardless of whether it's actually installed. See
test_real_model.py for the real-weights integration test. `CV_IMGSZ`/`CV_DEVICE`
*parsing* is tested directly against `cv_service.config` in
`tests/test_config.py`; the tests below only cover how `YoloDetector` itself
resolves a value when one isn't explicitly constructor-supplied.
"""

from __future__ import annotations

import sys
import types

import numpy as np
import pytest

from cv_service.inference.detector import (
    DEFAULT_CONFIDENCE,
    DEFAULT_IMGSZ,
    ENCODING_BGR24,
    ENCODING_JPEG,
    ModelUnavailableError,
    YoloDetector,
    decode_frame,
    map_detections,
)


class FakeBoxes:
    def __init__(self, xyxy, conf, cls):
        self.xyxy = xyxy
        self.conf = conf
        self.cls = cls


class FakeResult:
    def __init__(self, boxes, names):
        self.boxes = boxes
        self.names = names


class FakeModel:
    """Stand-in for `ultralytics.YOLO`: records calls, returns a fixed result.

    `predict()` is called once at construction time too (warmup, see
    `YoloDetector._warmup`) -- `calls[0]` is always the warmup call, real
    `detect()` calls follow it.
    """

    def __init__(self, result):
        self._result = result
        self.calls: list[dict] = []

    def predict(self, frame, conf=None, imgsz=None, verbose=None, **kwargs):
        call = {"frame": frame, "conf": conf, "imgsz": imgsz, "verbose": verbose, **kwargs}
        self.calls.append(call)
        return [self._result]


def _empty_fake_model() -> FakeModel:
    return FakeModel(FakeResult(boxes=FakeBoxes(xyxy=[], conf=[], cls=[]), names={}))


# --- decode_frame ------------------------------------------------------


def test_decode_bgr24_reshapes_raw_bytes(bgr_frame):
    data = bgr_frame.tobytes()

    decoded = decode_frame(width=4, height=3, encoding=ENCODING_BGR24, data=data)

    assert decoded.shape == (3, 4, 3)
    assert decoded.dtype == np.uint8
    assert np.array_equal(decoded, bgr_frame)


def test_decode_bgr24_wrong_length_raises():
    with pytest.raises(ValueError, match="BGR24 frame data length"):
        decode_frame(width=4, height=3, encoding=ENCODING_BGR24, data=b"\x00" * 10)


def test_decode_jpeg_round_trips(jpeg_bytes):
    decoded = decode_frame(width=4, height=3, encoding=ENCODING_JPEG, data=jpeg_bytes)

    assert decoded.shape == (3, 4, 3)
    assert decoded.dtype == np.uint8


def test_decode_jpeg_invalid_bytes_raises():
    with pytest.raises(ValueError, match="failed to decode JPEG"):
        decode_frame(width=4, height=3, encoding=ENCODING_JPEG, data=b"not a jpeg")


def test_decode_unsupported_encoding_raises():
    with pytest.raises(ValueError, match="unsupported frame encoding"):
        decode_frame(width=4, height=3, encoding="IMAGE_ENCODING_UNSPECIFIED", data=b"")


# --- map_detections ------------------------------------------------------


def test_map_detections_normalizes_to_top_left_xywh():
    boxes = FakeBoxes(xyxy=[[10.0, 20.0, 50.0, 80.0]], conf=[0.9], cls=[0])
    result = FakeResult(boxes=boxes, names={0: "person"})

    detections = map_detections(result, frame_width=100, frame_height=200)

    assert len(detections) == 1
    detection = detections[0]
    assert detection.label == "person"
    assert detection.confidence == pytest.approx(0.9)
    assert detection.x == pytest.approx(0.10)
    assert detection.y == pytest.approx(0.10)
    assert detection.width == pytest.approx(0.40)
    assert detection.height == pytest.approx(0.30)


def test_map_detections_multiple_boxes_preserve_order_and_labels():
    boxes = FakeBoxes(
        xyxy=[[0.0, 0.0, 10.0, 10.0], [5.0, 5.0, 15.0, 25.0]],
        conf=[0.5, 0.75],
        cls=[2, 0],
    )
    result = FakeResult(boxes=boxes, names={0: "person", 2: "car"})

    detections = map_detections(result, frame_width=50, frame_height=50)

    assert [d.label for d in detections] == ["car", "person"]
    assert detections[0].confidence == pytest.approx(0.5)
    assert detections[1].confidence == pytest.approx(0.75)


def test_map_detections_empty_boxes_returns_empty_list():
    boxes = FakeBoxes(xyxy=[], conf=[], cls=[])
    result = FakeResult(boxes=boxes, names={})

    assert map_detections(result, frame_width=100, frame_height=100) == []


def test_map_detections_no_boxes_attribute_returns_empty_list():
    result = types.SimpleNamespace(boxes=None, names={})

    assert map_detections(result, frame_width=100, frame_height=100) == []


def test_map_detections_ignores_masks_on_segmentation_results():
    """A prompt-free YOLOE (`yoloe-*-seg-pf.pt`) is an instance-segmentation
    model, so its Ultralytics `Results` carry a non-None `.masks` attribute in
    addition to the usual `.boxes`. `map_detections` must remain
    mask-agnostic: it reads only `.boxes.xyxy`/`.conf`/`.cls` + `.names` and
    never touches `.masks`, so the same box-mapping path serves detection and
    segmentation checkpoints identically. Verified end-to-end against a real
    `yoloe-26s-seg-pf.pt` result in `test_real_model.py`; this fake double
    proves it without needing ultralytics/torch."""
    boxes = FakeBoxes(xyxy=[[10.0, 20.0, 50.0, 80.0]], conf=[0.9], cls=[0])
    # A seg-style Results also exposes a (here non-None) `.masks` attribute --
    # an object map_detections must never require or read.
    seg_result = types.SimpleNamespace(
        boxes=boxes,
        names={0: "building"},
        masks=object(),  # non-None; would blow up if map_detections touched it wrongly
    )

    detections = map_detections(seg_result, frame_width=100, frame_height=200)

    assert len(detections) == 1
    assert detections[0].label == "building"
    assert detections[0].x == pytest.approx(0.10)
    assert detections[0].width == pytest.approx(0.40)


def test_map_detections_clamps_out_of_frame_boxes():
    # A box that overshoots the frame on every edge (rounding, edge object).
    boxes = FakeBoxes(xyxy=[[-5.0, -5.0, 150.0, 250.0]], conf=[0.3], cls=[0])
    result = FakeResult(boxes=boxes, names={0: "person"})

    detections = map_detections(result, frame_width=100, frame_height=100)

    detection = detections[0]
    assert 0.0 <= detection.x <= 1.0
    assert 0.0 <= detection.y <= 1.0
    assert 0.0 <= detection.width <= 1.0
    assert 0.0 <= detection.height <= 1.0
    assert detection.x == pytest.approx(0.0)
    assert detection.y == pytest.approx(0.0)
    assert detection.width == pytest.approx(1.0)
    assert detection.height == pytest.approx(1.0)


# --- YoloDetector (fake model injected, no real ultralytics needed) -----


def test_yolo_detector_detect_with_injected_model(bgr_frame):
    boxes = FakeBoxes(xyxy=[[0.0, 0.0, 4.0, 3.0]], conf=[0.6], cls=[1])
    result = FakeResult(boxes=boxes, names={1: "car"})
    fake_model = FakeModel(result)
    detector = YoloDetector(model_name="fake-model", model=fake_model)

    detections, inference_millis = detector.detect(
        width=4, height=3, encoding=ENCODING_BGR24, data=bgr_frame.tobytes()
    )

    assert detector.model_name == "fake-model"
    assert len(detections) == 1
    assert detections[0].label == "car"
    assert isinstance(inference_millis, int)
    assert inference_millis >= 0
    # calls[0] is the construction-time warmup call; calls[-1] is this detect().
    assert fake_model.calls[-1]["conf"] == DEFAULT_CONFIDENCE


def test_yolo_detector_uses_explicit_confidence_threshold(bgr_frame):
    boxes = FakeBoxes(xyxy=[], conf=[], cls=[])
    result = FakeResult(boxes=boxes, names={})
    fake_model = FakeModel(result)
    detector = YoloDetector(model_name="fake-model", model=fake_model)

    detector.detect(
        width=4,
        height=3,
        encoding=ENCODING_BGR24,
        data=bgr_frame.tobytes(),
        confidence_threshold=0.7,
    )

    assert fake_model.calls[-1]["conf"] == pytest.approx(0.7)


def test_yolo_detector_zero_confidence_threshold_uses_default(bgr_frame):
    boxes = FakeBoxes(xyxy=[], conf=[], cls=[])
    result = FakeResult(boxes=boxes, names={})
    fake_model = FakeModel(result)
    detector = YoloDetector(model_name="fake-model", model=fake_model)

    detector.detect(
        width=4,
        height=3,
        encoding=ENCODING_BGR24,
        data=bgr_frame.tobytes(),
        confidence_threshold=0.0,
    )

    assert fake_model.calls[-1]["conf"] == DEFAULT_CONFIDENCE


def test_yolo_detector_missing_ultralytics_raises_model_unavailable(monkeypatch):
    monkeypatch.setitem(sys.modules, "ultralytics", None)

    with pytest.raises(ModelUnavailableError, match="ultralytics is not installed"):
        YoloDetector()


def test_yolo_detector_model_load_failure_raises_model_unavailable(monkeypatch):
    fake_ultralytics = types.ModuleType("ultralytics")

    def _boom(*_args, **_kwargs):
        raise RuntimeError("no network access to download weights")

    fake_ultralytics.YOLO = _boom
    monkeypatch.setitem(sys.modules, "ultralytics", fake_ultralytics)

    with pytest.raises(ModelUnavailableError, match="failed to load YOLO model"):
        YoloDetector(model_name="yolo11n.pt")


# --- imgsz / CV_IMGSZ -----------------------------------------------------


def test_detect_passes_imgsz_kwarg_to_predict(bgr_frame, monkeypatch):
    monkeypatch.delenv("CV_IMGSZ", raising=False)
    fake_model = _empty_fake_model()
    detector = YoloDetector(model_name="fake-model", model=fake_model)

    detector.detect(width=4, height=3, encoding=ENCODING_BGR24, data=bgr_frame.tobytes())

    assert fake_model.calls[-1]["imgsz"] == DEFAULT_IMGSZ


def test_cv_imgsz_defaults_when_unset(monkeypatch):
    monkeypatch.delenv("CV_IMGSZ", raising=False)
    detector = YoloDetector(model_name="fake-model", model=_empty_fake_model())

    assert detector.imgsz == DEFAULT_IMGSZ


def test_cv_imgsz_honors_override(monkeypatch):
    monkeypatch.setenv("CV_IMGSZ", "320")
    detector = YoloDetector(model_name="fake-model", model=_empty_fake_model())

    assert detector.imgsz == 320


@pytest.mark.parametrize("garbage", ["not-a-number", "0", "-32", "  "])
def test_cv_imgsz_garbage_falls_back_to_default(monkeypatch, garbage):
    monkeypatch.setenv("CV_IMGSZ", garbage)
    detector = YoloDetector(model_name="fake-model", model=_empty_fake_model())

    assert detector.imgsz == DEFAULT_IMGSZ


# --- warmup ----------------------------------------------------------------


def test_warmup_runs_at_construction_with_configured_imgsz(monkeypatch):
    monkeypatch.setenv("CV_IMGSZ", "320")
    fake_model = _empty_fake_model()

    YoloDetector(model_name="fake-model", model=fake_model)

    assert len(fake_model.calls) == 1
    warmup_call = fake_model.calls[0]
    assert warmup_call["imgsz"] == 320
    assert warmup_call["frame"].shape == (320, 320, 3)
    assert warmup_call["frame"].dtype == np.uint8
    assert not warmup_call["frame"].any()  # black (all-zero) frame


def test_warmup_uses_default_imgsz_when_env_unset(monkeypatch):
    monkeypatch.delenv("CV_IMGSZ", raising=False)
    fake_model = _empty_fake_model()

    YoloDetector(model_name="fake-model", model=fake_model)

    assert fake_model.calls[0]["frame"].shape == (DEFAULT_IMGSZ, DEFAULT_IMGSZ, 3)


def test_warmup_failure_raises_model_unavailable():
    class BoomingModel:
        def predict(self, *_args, **_kwargs):
            raise RuntimeError("warmup exploded")

    with pytest.raises(ModelUnavailableError, match="warmup inference failed"):
        YoloDetector(model_name="fake-model", model=BoomingModel())


# --- device / CV_DEVICE -----------------------------------------------------
#
# `CV_DEVICE` *parsing* (`_parse_device`) now lives in `cv_service.config`
# and is tested directly there (`tests/test_config.py`). The tests below only
# cover `YoloDetector`'s own device *resolution* (constructor kwarg vs. the
# `cv_service.config.Settings.from_env()` value it falls back to) --
# `YoloDetector` re-resolves `Settings.from_env()` fresh at each construction
# (no import-time caching, unlike this class's pre-`config.py` incarnation),
# so `monkeypatch.setenv("CV_DEVICE", ...)` immediately before constructing a
# detector is what simulates each scenario.


def test_yolo_detector_device_defaults_to_none_when_env_unset(monkeypatch):
    monkeypatch.delenv("CV_DEVICE", raising=False)
    detector = YoloDetector(model_name="fake-model", model=_empty_fake_model())

    assert detector.device is None


def test_yolo_detector_device_honors_env_default(monkeypatch):
    monkeypatch.setenv("CV_DEVICE", "cuda:0")
    detector = YoloDetector(model_name="fake-model", model=_empty_fake_model())

    assert detector.device == "cuda:0"


def test_yolo_detector_device_constructor_kwarg_overrides_env(monkeypatch):
    monkeypatch.setenv("CV_DEVICE", "cuda:1")
    detector = YoloDetector(model_name="fake-model", model=_empty_fake_model(), device="cpu")

    assert detector.device == "cpu"


def test_detect_passes_device_kwarg_to_predict_when_set(bgr_frame, monkeypatch):
    monkeypatch.delenv("CV_DEVICE", raising=False)
    fake_model = _empty_fake_model()
    detector = YoloDetector(model_name="fake-model", model=fake_model, device="cuda:0")

    detector.detect(width=4, height=3, encoding=ENCODING_BGR24, data=bgr_frame.tobytes())

    assert fake_model.calls[-1]["device"] == "cuda:0"


def test_warmup_passes_device_kwarg_to_predict_when_set(monkeypatch):
    monkeypatch.delenv("CV_DEVICE", raising=False)
    fake_model = _empty_fake_model()

    YoloDetector(model_name="fake-model", model=fake_model, device="cuda:0")

    assert fake_model.calls[0]["device"] == "cuda:0"


def test_detect_omits_device_kwarg_entirely_when_unset(bgr_frame, monkeypatch):
    monkeypatch.delenv("CV_DEVICE", raising=False)
    fake_model = _empty_fake_model()
    detector = YoloDetector(model_name="fake-model", model=fake_model)

    detector.detect(width=4, height=3, encoding=ENCODING_BGR24, data=bgr_frame.tobytes())

    assert detector.device is None
    assert "device" not in fake_model.calls[-1]


def test_warmup_omits_device_kwarg_entirely_when_unset(monkeypatch):
    monkeypatch.delenv("CV_DEVICE", raising=False)
    fake_model = _empty_fake_model()

    YoloDetector(model_name="fake-model", model=fake_model)

    assert "device" not in fake_model.calls[0]
