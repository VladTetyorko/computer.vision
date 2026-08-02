"""Real YOLO inference backend for the ``Inference.DetectStream`` RPC.

This module is deliberately independent of the generated protobuf stubs
(``cv_service/gen``) so it can be imported and unit tested without running
``scripts/gen_proto.sh`` first. ``cv_service/grpc/servicers.py`` is the only
place that touches ``cv_pb2`` messages; it translates them to/from the plain
types used here (``ImageEncoding`` string constants, primitive
``width``/``height``/``data``, and the :class:`Detection` dataclass).

Everything in here (``ultralytics``, ``cv2``, ``numpy``) lives behind the
``cv`` optional dependency group in ``pyproject.toml``. Importing this module
without that extra installed raises ``ImportError`` at the ``cv2``/``numpy``
import below -- callers are expected to catch that and fall back to the echo
stub; see ``MODULE.md`` for the fallback contract.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Any

import cv2
import numpy as np

from cv_service.config import DEFAULT_IMGSZ, DEFAULT_MODEL, Settings

LOGGER = logging.getLogger("cv_service.inference.detector")

# Wire-format encoding names, matching `ImageEncoding` in
# proto/vision/v1/cv.proto (mirrored here as plain strings so this module
# never has to import the generated `cv_pb2` enum -- see module docstring).
ENCODING_JPEG = "IMAGE_ENCODING_JPEG"
ENCODING_BGR24 = "IMAGE_ENCODING_BGR24"

# DEFAULT_MODEL/DEFAULT_IMGSZ are re-exported here (same values as
# cv_service.config, which is the canonical home for CV_* defaults) so
# existing callers importing them from this module keep working.

# Ultralytics' own default confidence threshold. Used whenever a
# FrameRequest doesn't specify (or specifies 0, i.e. proto3's float default
# for "unset") a confidence_threshold. Not a CV_* env knob -- purely an
# inference-behavior default, out of cv_service.config's scope.
DEFAULT_CONFIDENCE = 0.25


class ModelUnavailableError(RuntimeError):
    """Raised when the YOLO backend could not be constructed.

    Covers both "ultralytics isn't installed" (the `cv` extra is absent) and
    "ultralytics is installed but the model couldn't be loaded" (e.g. no
    network access to download default weights). Callers should catch this
    broadly and fall back to echo behavior.
    """


@dataclass(frozen=True)
class Detection:
    """One detected object, already mapped to the wire's normalized box.

    Mirrors `Detection`/`BoundingBox` in proto/vision/v1/cv.proto: `x`/`y` is
    the TOP-LEFT corner of the box, `width`/`height` extend right/down from
    it, all four normalized to [0, 1] relative to the frame.
    """

    label: str
    confidence: float
    x: float
    y: float
    width: float
    height: float


def decode_frame(width: int, height: int, encoding: str, data: bytes) -> np.ndarray:
    """Decode a `FrameRequest`'s raw bytes into a BGR ``uint8`` ndarray.

    `encoding` must be one of `ENCODING_JPEG` / `ENCODING_BGR24` (the
    `ImageEncoding` enum names from the proto, e.g. via
    ``cv_pb2.ImageEncoding.Name(request.encoding)``). Returns an array shaped
    ``(height, width, 3)``.
    """
    if encoding == ENCODING_JPEG:
        buffer = np.frombuffer(data, dtype=np.uint8)
        frame = cv2.imdecode(buffer, cv2.IMREAD_COLOR)
        if frame is None:
            raise ValueError("failed to decode JPEG frame data")
        return frame

    if encoding == ENCODING_BGR24:
        expected = width * height * 3
        if len(data) != expected:
            raise ValueError(
                f"BGR24 frame data length {len(data)} != expected {expected} "
                f"for {width}x{height}"
            )
        return np.frombuffer(data, dtype=np.uint8).reshape((height, width, 3))

    raise ValueError(f"unsupported frame encoding: {encoding!r}")


def _clamp01(value: float) -> float:
    return max(0.0, min(1.0, value))


def map_detections(result: Any, frame_width: int, frame_height: int) -> list[Detection]:
    """Map one Ultralytics `Results` object to normalized `Detection`s.

    Ultralytics rescales `result.boxes.xyxy` back to the original frame's
    pixel coordinates (the array passed to `model.predict`), so normalizing
    by `frame_width`/`frame_height` here matches the proto's [0, 1]
    top-left-origin `BoundingBox` convention directly -- no letterbox
    un-padding needed.

    `result` only needs to duck-type Ultralytics' `Results`: a `.boxes` with
    iterable `.xyxy` / `.conf` / `.cls`, and a `.names` dict of
    class-index -> label. Tests pass a plain fake instead of a real model
    result. Mask-agnostic: a segmentation `Results` (e.g. a prompt-free
    YOLOE `*-seg-pf` checkpoint) also carries a non-None `.masks`, which this
    function never reads -- box mapping serves detection and segmentation
    checkpoints identically.
    """
    boxes = getattr(result, "boxes", None)
    if boxes is None:
        return []

    names = result.names
    detections: list[Detection] = []
    for xyxy, conf, cls in zip(boxes.xyxy, boxes.conf, boxes.cls):
        x1, y1, x2, y2 = (float(v) for v in xyxy)
        label = names[int(cls)]
        detections.append(
            Detection(
                label=label,
                confidence=float(conf),
                x=_clamp01(x1 / frame_width),
                y=_clamp01(y1 / frame_height),
                width=_clamp01((x2 - x1) / frame_width),
                height=_clamp01((y2 - y1) / frame_height),
            )
        )
    return detections


class YoloDetector:
    """Loads an Ultralytics YOLO model once and runs detection on frames.

    Construction is the only place that can raise `ModelUnavailableError`
    (missing `ultralytics`, the model/weights couldn't be loaded -- e.g.
    offline with no cached weights -- or the construction-time warmup
    inference failed). Once constructed, `detect()` runs on-device (CPU by
    default; Ultralytics picks CUDA automatically if available, but nothing
    here requires it).

    `model_name`/`device` fall back to :meth:`cv_service.config.Settings.from_env`
    when omitted -- resolved fresh at each construction (no caching), exactly
    mirroring this class's pre-``config.py`` behavior of reading
    ``CV_MODEL``/``CV_IMGSZ``/``CV_DEVICE`` itself. This is the only module
    outside ``cv_service/config.py`` that calls ``Settings.from_env()``
    directly rather than receiving an already-resolved value, because
    `YoloDetector` is constructed in more than one place (the model registry,
    tests, real-model smoke tests) that don't all have a `Settings` handy --
    the *parsing* still lives in exactly one place either way.
    """

    def __init__(
        self,
        model_name: str | None = None,
        *,
        model: Any = None,
        device: str | None = None,
    ) -> None:
        settings = Settings.from_env()
        self._model_name = model_name or settings.model
        self._imgsz = settings.imgsz
        # Constructor kwarg overrides the resolved default, same precedence
        # idiom as `model_name` above (falsy -- None or "" -- falls through
        # to the resolved default).
        self._device = device or settings.device

        if model is not None:
            # Test seam: inject a fake model, skip ultralytics entirely.
            self._model = model
        else:
            try:
                from ultralytics import YOLO
            except ImportError as exc:
                raise ModelUnavailableError(
                    "ultralytics is not installed; install the 'cv' extra "
                    "(pip install -e '.[cv]') to enable real inference"
                ) from exc

            try:
                self._model = YOLO(self._model_name)
            except Exception as exc:  # noqa: BLE001 - any load failure means "unavailable"
                raise ModelUnavailableError(
                    f"failed to load YOLO model {self._model_name!r}: {exc}"
                ) from exc

            LOGGER.info("loaded YOLO model %r", self._model_name)

        LOGGER.info(
            "cv-service inference device=%s (model=%r)",
            self._device if self._device is not None else "auto",
            self._model_name,
        )

        self._warmup()

    @property
    def model_name(self) -> str:
        return self._model_name

    @property
    def imgsz(self) -> int:
        return self._imgsz

    @property
    def device(self) -> str | None:
        """Resolved inference device, or `None` for "ultralytics auto"."""
        return self._device

    def _predict_kwargs(self, **kwargs: Any) -> dict[str, Any]:
        """Common `predict()` kwargs, adding `device=` only when resolved.

        Omitting the `device` kwarg entirely (rather than passing `None`)
        when `CV_DEVICE`/the constructor override is unset keeps behavior
        byte-identical to before this knob existed -- ultralytics' own
        `select_device('')` auto-detection still runs untouched.
        """
        if self._device is not None:
            kwargs["device"] = self._device
        return kwargs

    def _warmup(self) -> None:
        """Run one dummy `predict()` at construction time.

        Ultralytics pays a ~1s one-time warmup cost on its *first* predict
        call; without this, that cost lands on the first real frame instead,
        which risks tripping adapter-cv-grpc's 2s response timeout and
        forcing an outage-backoff teardown right at stream start (see
        docs/CYCLES-PLAN.md CP-a). Runs for injected `model=` doubles too --
        tests assert on this construction-time call. A failure here is
        treated the same as a load failure: `ModelUnavailableError`, which
        the caller degrades to the echo path instead of crashing. This is
        also where a bad/unavailable CV_DEVICE (e.g. "cuda:0" with no GPU)
        surfaces -- ultralytics raises during predict(), which this method
        already converts to ModelUnavailableError like any other warmup
        failure, so no special handling is needed for that case.
        """
        warmup_frame = np.zeros((self._imgsz, self._imgsz, 3), dtype=np.uint8)
        try:
            self._model.predict(
                warmup_frame, **self._predict_kwargs(imgsz=self._imgsz, verbose=False)
            )
        except Exception as exc:  # noqa: BLE001 - any warmup failure means "unavailable"
            raise ModelUnavailableError(
                f"warmup inference failed for model {self._model_name!r}: {exc}"
            ) from exc
        LOGGER.info("warmup inference complete for model %r (imgsz=%d)", self._model_name, self._imgsz)

    def detect(
        self,
        *,
        width: int,
        height: int,
        encoding: str,
        data: bytes,
        confidence_threshold: float | None = None,
    ) -> tuple[list[Detection], int]:
        """Decode `data` and run inference. Returns (detections, inference_millis)."""
        frame = decode_frame(width, height, encoding, data)
        conf = confidence_threshold if confidence_threshold else DEFAULT_CONFIDENCE

        start = time.monotonic()
        results = self._model.predict(
            frame, **self._predict_kwargs(conf=conf, imgsz=self._imgsz, verbose=False)
        )
        inference_millis = int(round((time.monotonic() - start) * 1000))

        frame_height, frame_width = frame.shape[0], frame.shape[1]
        detections = map_detections(results[0], frame_width, frame_height)
        return detections, inference_millis


# Re-exported for callers that only need the resolved-at-import-time default
# imgsz (e.g. tests asserting against DEFAULT_IMGSZ) without needing a
# Settings instance of their own.
__all__ = [
    "ENCODING_JPEG",
    "ENCODING_BGR24",
    "DEFAULT_MODEL",
    "DEFAULT_IMGSZ",
    "DEFAULT_CONFIDENCE",
    "ModelUnavailableError",
    "Detection",
    "decode_frame",
    "map_detections",
    "YoloDetector",
]
