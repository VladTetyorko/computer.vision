"""``Detector`` servicer: the stateless half of the tracker/detector split.

CV-ORCHESTRATION wave W4 (``docs/plans/active/CV-ORCHESTRATION-PLAN.md``
§4.9). A detector instance holds no identity, no gallery, no lock and no
session -- it turns pixels into boxes and reports how busy it is, on ONE
unary RPC, so an operator may run N of it behind an ordered target list
while the tracker session stays sticky to a single stream (§4.9, decision
E11). This module is the second (and last) place in cv-service that touches
generated ``cv_pb2`` types -- ``cv_service/grpc/servicers.py`` remains the
sole translation point for ``Inference``/``Training``/``Geolocation``; this
one owns ``Detector`` alone, and reuses that module's ROI crop/remap helpers
rather than re-deriving the same coordinate arithmetic twice.

**Admission, not queueing.** Every call takes the gate's ``admit()`` door
first (``cv_service.inference.concurrency.InferenceGate.admit``): a queue
already at ``Settings.detector_max_queue`` answers ``RESOURCE_EXHAUSTED``
immediately rather than making a caller that has somewhere else to go wait
behind work it could have routed around (§4.9). ``DetectorClient.acquire()``
(the in-process door every other cv-service caller uses) has no such
refusal -- an all-in-one deployment has nowhere else to send the work, so it
always waits its turn instead.

**The admission permit is NOT held across the actual detection call** --
see ``Detect()``'s own docstring for why holding it would self-deadlock a
1-permit gate the moment ``ModelRegistry.detect_composite`` (or the pinned
single-detector path) re-acquires the SAME ``InferenceGate``.
"""

from __future__ import annotations

import logging
import sys
from pathlib import Path
from typing import Optional

import grpc

from cv_service.config import Settings
from cv_service.grpc.servicers import _crop_for_roi, _map_roi_detection
from cv_service.inference.concurrency import GateFull, InferenceGate
from cv_service.tracking.engines.base import Box

# Same proto-relative sys.path bootstrap `grpc/servicers.py` uses (protoc's
# Python codegen imports rooted at the proto package path, e.g. `from
# vision.v1 import cv_pb2`) -- duplicated here rather than imported as a side
# effect of importing `servicers`, so this module stays independently
# importable regardless of import order.
_GEN_DIR = Path(__file__).resolve().parent.parent / "gen"
if str(_GEN_DIR) not in sys.path:
    sys.path.insert(0, str(_GEN_DIR))

from vision.v1 import cv_pb2, cv_pb2_grpc  # noqa: E402 - after the sys.path bootstrap above

LOGGER = logging.getLogger("cv_service.grpc.detector_servicer")


def _wire_detection(detection: "object") -> "cv_pb2.Detection":
    """A plain `Detection` (``cv_service.inference.detector.Detection`` or
    the crop-remapped equivalent ``_map_roi_detection`` returns) -> the wire
    message, with every track field left at its proto default. A detector
    has no identity to report (§4.9's whole reason for the split): setting
    `track_id`/`track_state`/etc. here would claim an opinion this servicer
    structurally cannot have.
    """
    return cv_pb2.Detection(
        label=detection.label,
        confidence=detection.confidence,
        box=cv_pb2.BoundingBox(
            x=detection.x,
            y=detection.y,
            width=detection.width,
            height=detection.height,
        ),
    )


class DetectorServicer(cv_pb2_grpc.DetectorServicer):
    """Real-YOLO-when-available detection over the stateless `Detector` RPC.

    Two ways to give this servicer a model, exactly mirroring
    `cv_service.grpc.servicers.InferenceServicer`'s own `detector=`/
    `registry=` shape:

    - **`detector=`**: one `YoloDetector` (or test double) handles every
      request regardless of `model_id`.
    - **`registry=`**: a `cv_service.inference.registry.ModelRegistry`
      resolves `request.model_id` (comma-separated for composite) and runs
      every resolved member through `registry.detect_composite`, which does
      its own per-member `InferenceGate` acquisition.

    Both omitted (the zero-value default) means no model is loaded at all --
    every request then reports `FAILED_PRECONDITION`, never a silent empty
    detections list: unlike `InferenceServicer`, there is no echo response to
    degrade to here, because this RPC's whole contract is "boxes or a
    reported reason there are none" (§4.9 "reported degradation").
    """

    def __init__(
        self,
        *,
        registry: object = None,
        detector: object = None,
        inference_gate: InferenceGate,
        settings: Settings,
    ) -> None:
        self._registry = registry
        self._detector = detector
        self._inference_gate = inference_gate
        self._settings = settings

    def Detect(
        self, request: "cv_pb2.DetectRequest", context: grpc.ServicerContext
    ) -> "cv_pb2.DetectResponse":
        """One detection pass, full-frame or over `request.roi`.

        **Deadlock decision (§4.9, task 1):** `admit()` is used PURELY as an
        admission check -- the `with` block below does nothing but prove a
        permit was available and immediately gives it back, before any
        detection call is made. `ModelRegistry.detect_composite` (and, for
        the pinned-`detector` path, this method's own `acquire()` call) then
        re-acquires the SAME `InferenceGate` for the real work. Holding the
        `admit()` permit across that call would self-deadlock the instant
        `settings.max_concurrent_inferences == 1`: this thread would already
        own the process's one and only semaphore permit, and then block
        forever trying to acquire it a second time. Releasing first costs one
        extra uncontended semaphore round-trip -- noise against a 135-230ms
        inference call -- in exchange for never nesting two holds of one
        non-reentrant `threading.Semaphore` on the same thread.
        `tests/orchestration/test_detector_pool.py` and
        `tests/grpc/test_detector_service.py` both prove a 1-permit gate
        still serves sequential requests.
        """
        try:
            with self._inference_gate.admit(self._settings.detector_max_queue):
                pass
        except GateFull as exc:
            context.abort(
                grpc.StatusCode.RESOURCE_EXHAUSTED,
                f"detector queue full: occupancy={self._inference_gate.occupancy} "
                f"queue_depth={self._inference_gate.queue_depth} "
                f"bound={self._settings.detector_max_queue} ({exc})",
            )
            return cv_pb2.DetectResponse()

        try:
            if request.HasField("roi"):
                detections, inference_millis = self._detect_roi(request)
            else:
                detections, inference_millis = self._detect_full(request)
        except Exception as exc:  # noqa: BLE001 - reported, never silent (no echo fallback on this RPC)
            LOGGER.exception(
                "Detector.Detect failed for stream_id=%s sequence=%s",
                request.stream_id,
                request.sequence,
            )
            context.abort(grpc.StatusCode.INTERNAL, f"detection failed: {exc}")
            return cv_pb2.DetectResponse()

        if detections is None:
            context.abort(
                grpc.StatusCode.FAILED_PRECONDITION,
                f"no model resolved for model_id={request.model_id!r}",
            )
            return cv_pb2.DetectResponse()

        return cv_pb2.DetectResponse(
            detections=[_wire_detection(detection) for detection in detections],
            inference_millis=inference_millis,
            model_id=request.model_id,
            occupancy=self._inference_gate.occupancy,
            queue_depth=self._inference_gate.queue_depth,
            capacity=self._inference_gate.max_concurrent,
            max_queue=self._settings.detector_max_queue,
        )

    # ------------------------------------------------------------- detection

    def _detect_full(self, request: "cv_pb2.DetectRequest") -> "tuple[Optional[list], int]":
        return self._detect_pixels(request)

    def _detect_roi(self, request: "cv_pb2.DetectRequest") -> "tuple[Optional[list], int]":
        """Crop to `request.roi`, detect on the crop, map boxes back to
        full-frame coordinates -- reusing `servicers._crop_for_roi`/
        `_map_roi_detection`, the SAME arithmetic the in-process ROI rescue
        pass already uses, so a pooled ROI pass and a local one agree on
        coordinates by construction rather than by two implementations
        happening to match.
        """
        from cv_service.inference.detector import ENCODING_BGR24, decode_frame

        image = decode_frame(
            request.width,
            request.height,
            cv_pb2.ImageEncoding.Name(request.encoding),
            request.data,
        )
        roi = Box(x=request.roi.x, y=request.roi.y, width=request.roi.width, height=request.roi.height)
        cropped = _crop_for_roi(image, roi)
        if cropped is None:
            # No full-frame pass to fall back on here (unlike
            # `InferenceServicer._run_roi_detector`, where the frame's own
            # full-frame pass already ran regardless): a degenerate crop is
            # this WHOLE request's only chance at an answer, so it is a
            # reported failure, never a silent empty list.
            raise ValueError("degenerate ROI after clamping to the frame")
        crop_image, crop_width, crop_height = cropped

        import numpy as np

        data = np.ascontiguousarray(crop_image).tobytes()

        detections, millis = self._detect_pixels(
            request, width=crop_width, height=crop_height, encoding=ENCODING_BGR24, data=data
        )
        if not detections:
            return detections, millis
        return [_map_roi_detection(detection, roi) for detection in detections], millis

    def _detect_pixels(
        self,
        request: "cv_pb2.DetectRequest",
        *,
        width: Optional[int] = None,
        height: Optional[int] = None,
        encoding: Optional[str] = None,
        data: Optional[bytes] = None,
    ) -> "tuple[Optional[list], int]":
        """Full-frame or crop, whichever `width`/`height`/`encoding`/`data`
        (all four, or none -- `_detect_roi`'s only override shape) describe.
        Mirrors `InferenceServicer._run_detector`'s two model paths.
        """
        if self._registry is not None:
            return self._detect_via_registry(
                request, width=width, height=height, encoding=encoding, data=data
            )
        if self._detector is None:
            return None, 0
        with self._inference_gate.acquire():
            return self._detector.detect(
                width=width if width is not None else request.width,
                height=height if height is not None else request.height,
                encoding=encoding if encoding is not None else cv_pb2.ImageEncoding.Name(request.encoding),
                data=data if data is not None else request.data,
                confidence_threshold=request.confidence_threshold or None,
            )

    def _detect_via_registry(
        self,
        request: "cv_pb2.DetectRequest",
        *,
        width: Optional[int] = None,
        height: Optional[int] = None,
        encoding: Optional[str] = None,
        data: Optional[bytes] = None,
    ) -> "tuple[Optional[list], int]":
        """Registry-routed counterpart, exactly as
        `InferenceServicer._detect_via_registry` does for `FrameRequest` --
        `resolve()` + `detect_composite`, which gates each resolved member
        individually against the SAME `InferenceGate` this servicer's
        `admit()` door already proved has room.
        """
        from cv_service.inference.registry import detect_composite

        resolved = self._registry.resolve(request.model_id)
        if not resolved:
            return None, 0
        return detect_composite(
            resolved,
            gate=self._inference_gate,
            width=width if width is not None else request.width,
            height=height if height is not None else request.height,
            encoding=encoding if encoding is not None else cv_pb2.ImageEncoding.Name(request.encoding),
            data=data if data is not None else request.data,
            confidence_threshold=request.confidence_threshold or None,
        )
