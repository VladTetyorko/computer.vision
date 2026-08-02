"""Integration test: real Ultralytics YOLO inference end-to-end.

Skipped unless `ultralytics` is importable AND the default model's weights
can actually be loaded (cached locally, or network access to download them
the first time). Per docs/MVP1-PLAN.md C7 bullet 1, this only asserts the
real model runs on a frame and returns a well-formed (possibly empty)
detection list -- a random/synthetic frame is not a reliable source of a
known COCO object, so we don't assert on *what* is detected.

The `yolo11n`/`orion12l` tests below load the two *local, checked-out*
weight files directly (`cv-service/yolo11n.pt`, `cv-service/orion12l.pt` --
see docs/CV-MODELS-PLAN.md's provenance note: ONLY these two local files,
never a network fetch of weights) and additionally exercise
`cv_service.registry` end-to-end -- real discovery, real lazy loading, real
composite merge -- rather than only the single hard-loaded default model.
Each skips gracefully (not fails) if its own weights file isn't present
locally or `ultralytics` can't load it, same pattern as `real_detector`
above.
"""

from __future__ import annotations

import importlib.util
import time
from pathlib import Path

import numpy as np
import pytest

ULTRALYTICS_AVAILABLE = importlib.util.find_spec("ultralytics") is not None

pytestmark = pytest.mark.skipif(
    not ULTRALYTICS_AVAILABLE,
    reason="ultralytics not installed ('cv' extra absent); skipping real-model integration test",
)

# cv-service/ checkout directory -- where yolo11n.pt/orion12l.pt actually
# live (see cv_service/config.py's `Settings.model_dir` default, same
# directory). This file sits one level deeper than before the layering
# refactor (tests/inference/ instead of tests/), hence three `.parent`s.
_CV_SERVICE_DIR = Path(__file__).resolve().parent.parent.parent


@pytest.fixture(scope="module")
def real_detector():
    from cv_service.inference.detector import ModelUnavailableError, YoloDetector

    try:
        return YoloDetector()
    except ModelUnavailableError as exc:
        pytest.skip(f"YOLO weights unavailable (offline, no cache?): {exc}")


def _tiny_frame(width: int = 64, height: int = 48) -> np.ndarray:
    rng = np.random.default_rng(seed=0)
    return rng.integers(0, 256, size=(height, width, 3), dtype=np.uint8)


def _assert_well_formed_detections(detections: list) -> None:
    for detection in detections:
        assert isinstance(detection.label, str) and detection.label
        assert 0.0 <= detection.confidence <= 1.0
        assert 0.0 <= detection.x <= 1.0
        assert 0.0 <= detection.y <= 1.0
        assert 0.0 <= detection.width <= 1.0
        assert 0.0 <= detection.height <= 1.0


def test_real_model_detects_on_random_frame(real_detector):
    from cv_service.inference.detector import ENCODING_BGR24

    width, height = 64, 48
    frame = _tiny_frame(width, height)

    detections, inference_millis = real_detector.detect(
        width=width, height=height, encoding=ENCODING_BGR24, data=frame.tobytes()
    )

    assert isinstance(detections, list)
    assert isinstance(inference_millis, int)
    assert inference_millis >= 0
    _assert_well_formed_detections(detections)


# --- per-local-model smoke tests (registry item 4 of docs/CV-MODELS-PLAN.md) -


def _load_local_detector(filename: str):
    """Load one of the two local checkpoints by explicit path, skipping
    (not failing) if the file isn't present or fails to load -- mirrors
    `real_detector`'s own skip contract, just per-file instead of per-`CV_MODEL`.
    """
    from cv_service.inference.detector import ModelUnavailableError, YoloDetector

    weights_path = _CV_SERVICE_DIR / filename
    if not weights_path.is_file():
        pytest.skip(f"{weights_path} not present locally; skipping smoke test for it")
    try:
        return YoloDetector(model_name=str(weights_path))
    except ModelUnavailableError as exc:
        pytest.skip(f"{filename} could not be loaded: {exc}")


@pytest.mark.parametrize("filename", ["yolo11n.pt", "orion12l.pt"])
def test_local_model_smoke_detects_on_tiny_frame(filename):
    """Each local checkpoint (docs/CV-MODELS-PLAN.md's two provenance-approved
    files) loads and runs one real inference call on a tiny synthetic frame.
    Also records the measured `inference_millis` for MODULE.md -- see the
    printed line when run with `-s`."""
    from cv_service.inference.detector import ENCODING_BGR24

    detector = _load_local_detector(filename)
    width, height = 64, 48
    frame = _tiny_frame(width, height)

    start = time.monotonic()
    detections, inference_millis = detector.detect(
        width=width, height=height, encoding=ENCODING_BGR24, data=frame.tobytes()
    )
    wall_millis = (time.monotonic() - start) * 1000

    assert isinstance(detections, list)
    assert inference_millis >= 0
    _assert_well_formed_detections(detections)
    print(
        f"\n[smoke] {filename}: inference_millis={inference_millis} "
        f"wall_millis={wall_millis:.1f} imgsz={detector.imgsz}"
    )


def test_yoloe_seg_pf_real_weights_smoke_boxes_work_masks_ignored():
    """Real-weights smoke test for the prompt-free open-vocabulary YOLOE
    checkpoint `yoloe-26s-seg-pf.pt` (docs/CV-MODELS-PLAN.md follow-up: an
    OPT-IN "everything incl. buildings" model, NOT the default).

    Skip-if-absent, same contract as `_load_local_detector` above: this file
    is gitignored (`*.pt`) and, unlike orion12l.pt, is NOT a user-provided
    checkpoint but an official Ultralytics asset that `YOLO(name)` auto-
    downloads to the cwd on first use. We do NOT trigger that download here --
    the test skips cleanly if the weights aren't already present locally (run
    the one-time acquisition command in MODULE.md to fetch them).

    Two things this proves that the fakes in test_inference.py/test_registry.py
    can't: (1) the seg-pf checkpoint loads via the plain `ultralytics.YOLO`
    path `YoloDetector` already uses -- no `YOLOE` class, no `set_classes()`;
    (2) its segmentation `Results` (which carry a non-None `.masks`) flow
    through `YoloDetector.detect()` -> `map_detections` and yield well-formed
    boxes with the masks silently ignored -- exactly like a detection model."""
    from cv_service.inference.detector import ENCODING_BGR24

    detector = _load_local_detector("yoloe-26s-seg-pf.pt")

    width, height = 64, 48
    frame = _tiny_frame(width, height)

    start = time.monotonic()
    detections, inference_millis = detector.detect(
        width=width, height=height, encoding=ENCODING_BGR24, data=frame.tobytes()
    )
    wall_millis = (time.monotonic() - start) * 1000

    assert isinstance(detections, list)
    assert inference_millis >= 0
    _assert_well_formed_detections(detections)
    print(
        f"\n[smoke] yoloe-26s-seg-pf.pt: inference_millis={inference_millis} "
        f"wall_millis={wall_millis:.1f} imgsz={detector.imgsz} "
        f"detections={len(detections)}"
    )


def test_registry_composite_real_weights_merges_and_prefixes_labels():
    """End-to-end: real `discover_roster` + `ModelRegistry` + `detect_composite`
    over the two local checkpoints together -- the actual composite mode a
    client would get by sending `model_id="yolo11n.pt,orion12l.pt"`, not just
    the pure-fake unit tests in `test_registry.py`."""
    from cv_service.inference.concurrency import InferenceGate
    from cv_service.inference.detector import ENCODING_BGR24, ModelUnavailableError
    from cv_service.inference.registry import ModelRegistry, detect_composite, discover_roster

    for filename in ("yolo11n.pt", "orion12l.pt"):
        if not (_CV_SERVICE_DIR / filename).is_file():
            pytest.skip(f"{filename} not present locally; skipping composite smoke test")

    roster = discover_roster(_CV_SERVICE_DIR, default_model="yolo11n.pt")
    registry = ModelRegistry(roster=roster, default_id="yolo11n.pt")

    try:
        resolved = registry.resolve("yolo11n.pt,orion12l.pt")
    except ModelUnavailableError as exc:  # pragma: no cover - defensive, see _detector_for
        pytest.skip(f"one of the two local models could not be loaded: {exc}")

    assert [model_id for model_id, _ in resolved] == ["yolo11n.pt", "orion12l.pt"]

    width, height = 64, 48
    frame = _tiny_frame(width, height)
    start = time.monotonic()
    detections, inference_millis = detect_composite(
        resolved,
        gate=InferenceGate(max_concurrent=2),
        width=width,
        height=height,
        encoding=ENCODING_BGR24,
        data=frame.tobytes(),
    )
    wall_millis = (time.monotonic() - start) * 1000

    _assert_well_formed_detections(detections)
    for detection in detections:
        assert ":" in detection.label, "composite mode (>1 member) must prefix every label"
        prefix = detection.label.split(":", 1)[0]
        assert prefix in {"yolo11n", "orion12l"}
    print(
        f"\n[smoke] composite yolo11n+orion12l: inference_millis={inference_millis} "
        f"wall_millis={wall_millis:.1f} detections={len(detections)}"
    )
