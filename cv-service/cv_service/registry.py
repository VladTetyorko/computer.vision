"""Model registry for ``cv_service.inference.YoloDetector``: lazy
``{model_id -> YoloDetector}``, plus composite (multi-model) detection.

Independent of the generated protobuf stubs (``cv_pb2``), same convention as
``inference.py`` -- ``server.py`` is the only place that translates wire
types (``FrameRequest.model_id``, the ``encoding`` enum) to/from the plain
types used here. Importing this module requires the ``cv`` extra (it imports
``cv_service.inference``, which imports ``cv2``/``numpy`` at module scope) --
``server.py`` only imports it lazily inside ``_build_default_registry()``,
mirroring ``_build_default_detector()``'s existing lazy import of
``cv_service.inference``.
"""

from __future__ import annotations

import dataclasses
import logging
import threading
from pathlib import Path
from typing import Callable, Optional

from cv_service.concurrency import InferenceGate
from cv_service.inference import Detection, ModelUnavailableError, YoloDetector

LOGGER = logging.getLogger("cv_service.registry")

# Suffix `yolo export ... format=openvino` gives its output directory (see
# Dockerfile) -- e.g. `yolo11n.pt` exports to `yolo11n_openvino_model/`.
_OPENVINO_SUFFIX = "_openvino_model"

# `DetectionRequest.model_id` (FrameRequest.model_id on the wire) accepts a
# comma-separated list to run more than one model on the same frame
# ("composite mode" -- see `detect_composite`).
_MODEL_ID_SEPARATOR = ","

# Type of the callable used to construct a detector for one roster entry --
# `YoloDetector` by default; tests inject a fake to avoid touching
# ultralytics/torch entirely.
DetectorFactory = Callable[..., "YoloDetector"]


def discover_roster(base_dir: Path, default_model: str) -> dict[str, str]:
    """Scan `base_dir` for locally-present model files/exports.

    Returns ``{model_id -> loadable name}`` where `model_id` is the bare
    file/directory name (what a client would send as ``model_id`` on the
    wire) and the value is what gets passed to ``YoloDetector(model_name=...)``
    -- currently identical (both are paths under `base_dir`), kept as two
    separate things in case that ever needs to diverge.

    Discovers:
    - every ``*.pt`` file directly under `base_dir` (e.g. ``yolo11n.pt``,
      ``orion12l.pt``);
    - every ``*_openvino_model`` directory directly under `base_dir` (an
      exported OpenVINO IR, see Dockerfile).

    `default_model` (resolved from ``CV_MODEL``/``DEFAULT_MODEL`` by the
    caller) is always included even if discovery didn't find it under
    `base_dir` -- it may be an Ultralytics well-known alias not yet
    downloaded locally (e.g. a fresh checkout before the first run), or an
    absolute/relative path elsewhere entirely (a custom ``CV_MODEL``) --
    either way it must always be loadable as the fallback, registry or not.
    """
    roster: dict[str, str] = {}
    if base_dir.is_dir():
        for pt_file in sorted(base_dir.glob("*.pt")):
            if pt_file.is_file():
                roster[pt_file.name] = str(pt_file)
        for exported_dir in sorted(base_dir.glob(f"*{_OPENVINO_SUFFIX}")):
            if exported_dir.is_dir():
                roster[exported_dir.name] = str(exported_dir)
    if default_model not in roster:
        roster[default_model] = default_model
    return roster


def short_name(model_id: str) -> str:
    """Shorten a roster id for use as a composite-mode label prefix.

    Strips the ``.pt`` extension or the ``_openvino_model`` export suffix so
    ``yolo11n.pt`` and ``yolo11n_openvino_model`` (the same underlying model,
    exported or not) both shorten to ``yolo11n``. Anything else (a bare
    alias, a custom path) passes through unchanged.
    """
    if model_id.endswith(_OPENVINO_SUFFIX):
        return model_id[: -len(_OPENVINO_SUFFIX)]
    if model_id.endswith(".pt"):
        return model_id[: -len(".pt")]
    return model_id


class ModelRegistry:
    """Lazy ``{model_id -> YoloDetector}`` keyed by a discovered roster.

    Construction is cheap -- it just records the roster; no model is loaded
    until first requested via `resolve()`/`default_detector()` (each model
    pays its own warmup cost exactly once, on that first use, then is cached
    for the process's lifetime). A model that fails to load (corrupt
    weights, unsupported format, etc.) is logged once and remembered as
    unavailable -- `resolve()` treats it exactly like an unknown id, dropping
    it from a composite run rather than failing the whole request.
    """

    def __init__(
        self,
        roster: dict[str, str],
        default_id: str,
        *,
        detector_factory: DetectorFactory = YoloDetector,
    ) -> None:
        if default_id not in roster:
            raise ValueError(f"default_id {default_id!r} must be a key in roster {sorted(roster)}")
        self._roster = dict(roster)
        self._default_id = default_id
        self._detector_factory = detector_factory
        self._lock = threading.Lock()
        self._detectors: dict[str, YoloDetector] = {}
        self._unavailable_ids: set[str] = set()
        self._warned_unknown_ids: set[str] = set()

    @property
    def roster(self) -> dict[str, str]:
        return dict(self._roster)

    @property
    def default_id(self) -> str:
        return self._default_id

    def promote(self, model_id: str) -> bool:
        """Re-point the registry default at `model_id` (the "promote" action).

        The roster already routes any request to any known id per-frame;
        "promotion" only changes which id is the *default* -- the one served
        when a request names no model (or an unknown one), and the one
        `ListModels` reports as ``"active"``. Returns ``True`` on success;
        ``False`` (a no-op) if `model_id` is not a known roster id -- an
        unknown id is a normal, reported outcome (`Training.PromoteModel`
        turns it into ``Ack{ok:false}``), not an error. Does NOT force the
        model to load: like `default_id` at construction, the detector is
        still built lazily on first use.
        """
        with self._lock:
            if model_id not in self._roster:
                return False
            self._default_id = model_id
        LOGGER.info("registry default promoted to model_id=%r", model_id)
        return True

    def loaded_ids(self) -> list[str]:
        """Ids actually loaded (lazily) so far -- for tests/diagnostics."""
        with self._lock:
            return list(self._detectors)

    def default_detector(self) -> Optional[YoloDetector]:
        """Lazily construct (and cache) the detector for `default_id`."""
        return self._detector_for(self._default_id)

    def resolve(self, requested_model_id: str) -> list[tuple[str, YoloDetector]]:
        """Split `requested_model_id` (comma-separated for composite mode)
        into ``(id, detector)`` pairs for every *known, loadable* member, in
        request order.

        - An id not in the roster is logged at most once per distinct id
          (matches the pre-registry "model registry is Phase 3" log-once
          contract) and dropped.
        - An id that *is* in the roster but fails to load (see
          `_detector_for`) is also dropped, having already logged once at
          load time.
        - If every requested id was empty/unknown/unloadable (including a
          blank `requested_model_id`, e.g. an old client that never set it),
          falls back to ``[(default_id, default_detector())]`` -- unless the
          default itself is unavailable, in which case returns ``[]`` and the
          caller (`server.py`) echoes that frame. This is the "unknown/absent
          id keeps today's behavior" contract from the plan: a partially-
          unknown composite list degrades gracefully to its known members
          rather than discarding the whole request.
        """
        requested_ids = (
            [part.strip() for part in requested_model_id.split(_MODEL_ID_SEPARATOR)]
            if requested_model_id
            else []
        )
        requested_ids = [model_id for model_id in requested_ids if model_id]

        resolved: list[tuple[str, YoloDetector]] = []
        for model_id in requested_ids:
            if model_id not in self._roster:
                self._warn_unknown(model_id)
                continue
            detector = self._detector_for(model_id)
            if detector is not None:
                resolved.append((model_id, detector))

        if resolved:
            return resolved

        default_detector = self.default_detector()
        if default_detector is None:
            return []
        return [(self._default_id, default_detector)]

    def _detector_for(self, model_id: str) -> Optional[YoloDetector]:
        with self._lock:
            cached = self._detectors.get(model_id)
            if cached is not None:
                return cached
            if model_id in self._unavailable_ids:
                return None
            path = self._roster[model_id]
            try:
                detector = self._detector_factory(model_name=path)
            except ModelUnavailableError as exc:
                self._unavailable_ids.add(model_id)
                LOGGER.warning(
                    "model_id=%r (%s) could not be loaded (%s); excluding it "
                    "from detection until the service restarts",
                    model_id,
                    path,
                    exc,
                )
                return None
            self._detectors[model_id] = detector
            LOGGER.info("registry loaded model_id=%r (%s)", model_id, path)
            return detector

    def _warn_unknown(self, model_id: str) -> None:
        with self._lock:
            if model_id in self._warned_unknown_ids:
                return
            self._warned_unknown_ids.add(model_id)
        LOGGER.info(
            "model_id=%r requested but not in the local registry roster (%s); "
            "serving the default model %r instead",
            model_id,
            sorted(self._roster),
            self._default_id,
        )


def detect_composite(
    resolved: list[tuple[str, YoloDetector]],
    *,
    gate: InferenceGate,
    width: int,
    height: int,
    encoding: str,
    data: bytes,
    confidence_threshold: Optional[float] = None,
) -> tuple[list[Detection], int]:
    """Run every resolved ``(id, detector)`` pair on the same frame and
    concatenate detections.

    - Each member's own `detect()` call is individually gated by `gate` --
      the same `InferenceGate` a lone model's call would go through (V-d);
      composite mode doesn't get its own separate bound, it's just N calls
      through the existing one, same as N single-model requests would be.
    - `inference_millis` returned is the SUM of every member's own
      `inference_millis` -- composite work is strictly more compute, run
      sequentially, not overlapped (see MODULE.md "Composite mode" for the
      measured per-model numbers this adds up from).
    - When more than one model actually ran, every detection's `label` is
      prefixed ``"{short_name}:{label}"`` (e.g. ``"orion12l:tank"``) so two
      models' class spaces can't collide once merged -- proto's `Detection`
      message has no dedicated model-tag field to carry this instead (see
      MODULE.md "Composite mode: no free proto field"). A single-model
      request (the overwhelmingly common case, and every request before
      composite mode existed) is completely unaffected: labels pass through
      exactly as the model named them.
    - Confidence filtering already happened per-model inside each
      `YoloDetector.detect()` call (each model applies its own threshold to
      its own outputs before returning) -- composite mode doesn't add a
      second filtering pass on top.
    """
    all_detections: list[Detection] = []
    total_millis = 0
    tag_labels = len(resolved) > 1
    for model_id, detector in resolved:
        with gate.acquire():
            detections, millis = detector.detect(
                width=width,
                height=height,
                encoding=encoding,
                data=data,
                confidence_threshold=confidence_threshold,
            )
        total_millis += millis
        LOGGER.debug(
            "model_id=%r inference took %dms (%d detections)", model_id, millis, len(detections)
        )
        if tag_labels:
            tag = short_name(model_id)
            detections = [dataclasses.replace(d, label=f"{tag}:{d.label}") for d in detections]
        all_detections.extend(detections)
    return all_detections, total_millis
