"""Integration test: real Ultralytics YOLO inference end-to-end.

Skipped unless `ultralytics` is importable AND the default model's weights
can actually be loaded (cached locally, or network access to download them
the first time). Per docs/MVP1-PLAN.md C7 bullet 1, this only asserts the
real model runs on a frame and returns a well-formed (possibly empty)
detection list -- a random/synthetic frame is not a reliable source of a
known COCO object, so we don't assert on *what* is detected.
"""

from __future__ import annotations

import importlib.util

import numpy as np
import pytest

ULTRALYTICS_AVAILABLE = importlib.util.find_spec("ultralytics") is not None

pytestmark = pytest.mark.skipif(
    not ULTRALYTICS_AVAILABLE,
    reason="ultralytics not installed ('cv' extra absent); skipping real-model integration test",
)


@pytest.fixture(scope="module")
def real_detector():
    from cv_service.inference import ModelUnavailableError, YoloDetector

    try:
        return YoloDetector()
    except ModelUnavailableError as exc:
        pytest.skip(f"YOLO weights unavailable (offline, no cache?): {exc}")


def test_real_model_detects_on_random_frame(real_detector):
    from cv_service.inference import ENCODING_BGR24

    rng = np.random.default_rng(seed=0)
    width, height = 64, 48
    frame = rng.integers(0, 256, size=(height, width, 3), dtype=np.uint8)

    detections, inference_millis = real_detector.detect(
        width=width, height=height, encoding=ENCODING_BGR24, data=frame.tobytes()
    )

    assert isinstance(detections, list)
    assert isinstance(inference_millis, int)
    assert inference_millis >= 0
    for detection in detections:
        assert isinstance(detection.label, str) and detection.label
        assert 0.0 <= detection.confidence <= 1.0
        assert 0.0 <= detection.x <= 1.0
        assert 0.0 <= detection.y <= 1.0
        assert 0.0 <= detection.width <= 1.0
        assert 0.0 <= detection.height <= 1.0
