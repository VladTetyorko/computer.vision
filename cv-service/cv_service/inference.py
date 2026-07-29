"""Real YOLO inference backend for the ``Inference.DetectStream`` RPC.

This module is deliberately independent of the generated protobuf stubs
(``cv_service/gen``) so it can be imported and unit tested without running
``scripts/gen_proto.sh`` first. ``server.py`` is the only place that touches
``cv_pb2`` messages; it translates them to/from the plain types used here
(``ImageEncoding`` string constants, primitive ``width``/``height``/``data``,
and the :class:`Detection` dataclass).

Everything in here (``ultralytics``, ``cv2``, ``numpy``) lives behind the
``cv`` optional dependency group in ``pyproject.toml``. Importing this module
without that extra installed raises ``ImportError`` at the ``cv2``/``numpy``
import below -- callers (``server.py``) are expected to catch that and fall
back to the echo stub; see ``MODULE.md`` for the fallback contract.
"""

from __future__ import annotations

import logging
import os
import time
from dataclasses import dataclass
from typing import Any

import cv2
import numpy as np

LOGGER = logging.getLogger("cv_service.inference")

# Wire-format encoding names, matching `ImageEncoding` in
# proto/vision/v1/cv.proto (mirrored here as plain strings so this module
# never has to import the generated `cv_pb2` enum -- see module docstring).
ENCODING_JPEG = "IMAGE_ENCODING_JPEG"
ENCODING_BGR24 = "IMAGE_ENCODING_BGR24"

# Default Ultralytics model, overridable via the CV_MODEL env var. yolo11n is
# the smallest/fastest Ultralytics YOLO11 checkpoint -- a reasonable CPU-first
# default for a live demo.
DEFAULT_MODEL = "yolo26n.pt" # orion12l

# Ultralytics' own default confidence threshold. Used whenever a
# FrameRequest doesn't specify (or specifies 0, i.e. proto3's float default
# for "unset") a confidence_threshold.
DEFAULT_CONFIDENCE = 0.25

# Inference input size (pixels, square), overridable via the CV_IMGSZ env
# var. Ultralytics' own default is 640; 416 trades a little accuracy for
# meaningfully faster CPU inference (see docs/CYCLES-PLAN.md CP-a). Must be a
# multiple of 32 by Ultralytics convention (320 is a valid smaller choice) --
# not enforced here, an odd value just gets passed through to `predict()`.
# The Docker image exports an OpenVINO model with a FIXED input size baked in
# at export time (see Dockerfile) -- that export imgsz MUST equal the
# runtime CV_IMGSZ, or inference silently runs against the wrong size.
DEFAULT_IMGSZ = 416


def _parse_device(raw: str | None) -> str | None:
    """Parse the `CV_DEVICE` env var: unset/blank -> None ("ultralytics auto").

    Any non-blank value (`"cpu"`, `"cuda"`, `"cuda:0"`, `"0"`, ...) is passed
    through as-is -- ultralytics/torch own validating device strings, this
    function doesn't enumerate or second-guess them. Whitespace is stripped
    so a stray env-file trailing space doesn't turn into a bogus device
    string. Mirrors `_parse_imgsz`'s forgiving-parse idiom, but there's no
    "garbage" case here (any non-empty string is a legal device spec).
    """
    if raw is None:
        return None
    stripped = raw.strip()
    return stripped or None


# Default inference device, resolved once at import time from the CV_DEVICE
# env var (same forgiving-parse idiom as CV_IMGSZ). `None` means "don't pass
# device= to predict() at all" -- ultralytics then auto-selects CUDA if
# available, CPU otherwise, exactly today's behavior. Set explicitly (e.g.
# "cuda:0") to make the inference device observable/deterministic rather
# than relying on ultralytics' own auto-detection, primarily for GPU-box
# deployments (see cv-service/DEPLOY-GPU.md).
DEFAULT_DEVICE = _parse_device(os.environ.get("CV_DEVICE"))


class ModelUnavailableError(RuntimeError):
    """Raised when the YOLO backend could not be constructed.

    Covers both "ultralytics isn't installed" (the `cv` extra is absent) and
    "ultralytics is installed but the model couldn't be loaded" (e.g. no
    network access to download default weights). Callers should catch this
    broadly and fall back to echo behavior -- see server.py.
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


def _parse_imgsz(raw: str | None) -> int:
    """Parse the `CV_IMGSZ` env var into a positive int, default on garbage.

    Inference size is a performance knob, not something a missing/malformed
    env var should be able to crash the service over -- unset, non-numeric,
    or non-positive values all silently fall back to `DEFAULT_IMGSZ`.
    """
    if not raw:
        return DEFAULT_IMGSZ
    try:
        value = int(raw)
    except ValueError:
        LOGGER.warning("CV_IMGSZ=%r is not a valid integer; using default %d", raw, DEFAULT_IMGSZ)
        return DEFAULT_IMGSZ
    if value <= 0:
        LOGGER.warning("CV_IMGSZ=%r must be positive; using default %d", raw, DEFAULT_IMGSZ)
        return DEFAULT_IMGSZ
    return value


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
    result.
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
    """

    def __init__(
        self,
        model_name: str | None = None,
        *,
        model: Any = None,
        device: str | None = None,
    ) -> None:
        self._model_name = model_name or os.environ.get("CV_MODEL", DEFAULT_MODEL)
        self._imgsz = _parse_imgsz(os.environ.get("CV_IMGSZ"))
        # Constructor kwarg overrides the env-resolved default, same
        # precedence idiom as `model_name` above (falsy -- None or "" --
        # falls through to the env-resolved default).
        self._device = device or DEFAULT_DEVICE

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
        server.py's caller degrades to the echo path instead of crashing.
        This is also where a bad/unavailable CV_DEVICE (e.g. "cuda:0" with
        no GPU) surfaces -- ultralytics raises during predict(), which this
        method already converts to ModelUnavailableError like any other
        warmup failure, so no special handling is needed for that case.
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
