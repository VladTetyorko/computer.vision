"""gRPC server for the Vision CV service.

* ``Inference.DetectStream`` runs real Ultralytics YOLO inference (see
  ``cv_service/inference.py``) when the ``cv`` optional dependency group is
  installed and the model loads successfully. If it isn't installed, or the
  model can't be constructed (e.g. offline with no cached weights), the
  servicer logs one clear warning at startup and falls back to the original
  Phase 0 echo behavior: for every incoming ``FrameRequest`` it yields a
  ``DetectionResponse`` with the same stream_id / sequence / timestamp_millis
  / model_id / model_version and an empty ``detections`` list. Either way the
  service never crash-loops for lack of a model.
* ``Training`` rpcs (StartTraining, ListModels, PromoteModel) are not
  implemented yet and reply with ``UNIMPLEMENTED`` (Phase 3).

Run with::

    python -m cv_service.server

Requires the generated stubs under ``cv_service/gen`` - run
``scripts/gen_proto.sh`` first (see README.md). Generated code is never
committed.
"""

from __future__ import annotations

import logging
import signal
import sys
import threading
from concurrent import futures
from pathlib import Path
from typing import TYPE_CHECKING, Iterable, Iterator, Optional

import grpc

if TYPE_CHECKING:  # pragma: no cover - import cycle avoidance, see _build_default_detector
    from cv_service.inference import YoloDetector

# `protoc`'s Python codegen emits imports rooted at the proto package path
# (e.g. `from vision.v1 import cv_pb2`), not at `cv_service.gen...`. So the
# generated tree's root (cv_service/gen) must be on sys.path, and the code
# below imports through that proto-relative path.
_GEN_DIR = Path(__file__).resolve().parent / "gen"
if str(_GEN_DIR) not in sys.path:
    sys.path.insert(0, str(_GEN_DIR))

try:
    from vision.v1 import cv_pb2, cv_pb2_grpc
except ModuleNotFoundError as exc:  # pragma: no cover - operator guidance only
    raise ModuleNotFoundError(
        "Generated protobuf/gRPC stubs not found under "
        f"{_GEN_DIR}. Run scripts/gen_proto.sh from the cv-service/ "
        "directory (with grpcio-tools installed) before starting the "
        "server."
    ) from exc

LOGGER = logging.getLogger("cv_service.server")

DEFAULT_PORT = 50051


def _build_default_detector() -> Optional["YoloDetector"]:
    """Try to construct the default `YoloDetector`; `None` if unavailable.

    Import is local (not at module top-level) so that this module -- and the
    rest of the server -- stays importable even when the `cv` optional
    dependency group isn't installed at all (its absence would otherwise
    raise `ImportError` on `import cv2`/`ultralytics` at module load time).
    """
    try:
        from cv_service.inference import ModelUnavailableError, YoloDetector
    except ImportError as exc:
        LOGGER.warning(
            "cv-service YOLO backend unavailable (%s); DetectStream will "
            "serve the Phase 0 echo behavior (empty detections) until the "
            "'cv' optional dependency group is installed.",
            exc,
        )
        return None

    try:
        return YoloDetector()
    except ModelUnavailableError as exc:
        LOGGER.warning(
            "YOLO model could not be loaded (%s); DetectStream will serve "
            "the Phase 0 echo behavior (empty detections) until this is "
            "fixed.",
            exc,
        )
        return None


class InferenceServicer(cv_pb2_grpc.InferenceServicer):
    """Real-YOLO-when-available, echo-otherwise implementation of ``Inference``.

    A `YoloDetector` (see `cv_service/inference.py`) is built once at
    construction time. If that fails for any reason -- `ultralytics` not
    installed, weights unavailable offline, etc. -- `detector` is `None` and
    every frame gets the original Phase 0 echo response instead: the service
    must never crash-loop for lack of a model.

    Per-frame inference failures (bad frame bytes, a transient model error)
    are also caught and degrade to an echo response for that one frame,
    rather than tearing down the whole bidi stream.
    """

    def __init__(self, detector: Optional["YoloDetector"] = None) -> None:
        self._detector = detector if detector is not None else _build_default_detector()
        self._warned_model_ids: set[str] = set()

    def DetectStream(
        self,
        request_iterator: Iterable["cv_pb2.FrameRequest"],
        context: grpc.ServicerContext,
    ) -> Iterator["cv_pb2.DetectionResponse"]:
        for request in request_iterator:
            if self._detector is None:
                yield self._echo(request)
                continue

            self._warn_once_on_unknown_model(request.model_id)

            try:
                detections, inference_millis = self._detector.detect(
                    width=request.width,
                    height=request.height,
                    encoding=cv_pb2.ImageEncoding.Name(request.encoding),
                    data=request.data,
                    confidence_threshold=request.confidence_threshold or None,
                )
            except Exception:  # noqa: BLE001 - one bad frame must not kill the stream
                LOGGER.exception(
                    "inference failed for stream_id=%s sequence=%s; echoing "
                    "empty detections for this frame",
                    request.stream_id,
                    request.sequence,
                )
                yield self._echo(request)
                continue

            yield cv_pb2.DetectionResponse(
                stream_id=request.stream_id,
                sequence=request.sequence,
                timestamp_millis=request.timestamp_millis,
                model_id=request.model_id,
                model_version=request.model_version,
                detections=[
                    cv_pb2.Detection(
                        label=detection.label,
                        confidence=detection.confidence,
                        box=cv_pb2.BoundingBox(
                            x=detection.x,
                            y=detection.y,
                            width=detection.width,
                            height=detection.height,
                        ),
                    )
                    for detection in detections
                ],
                inference_millis=inference_millis,
            )

    def _warn_once_on_unknown_model(self, requested_model_id: str) -> None:
        """Log-and-serve-default for a requested `model_id` (registry is Phase 3).

        Warns at most once per distinct unknown `model_id` seen by this
        servicer instance, to avoid spamming logs once per frame.
        """
        if not requested_model_id or requested_model_id == self._detector.model_name:
            return
        if requested_model_id in self._warned_model_ids:
            return
        self._warned_model_ids.add(requested_model_id)
        LOGGER.info(
            "model_id=%r requested but the model registry is Phase 3 work; "
            "serving the default loaded model %r instead",
            requested_model_id,
            self._detector.model_name,
        )

    @staticmethod
    def _echo(request: "cv_pb2.FrameRequest") -> "cv_pb2.DetectionResponse":
        return cv_pb2.DetectionResponse(
            stream_id=request.stream_id,
            sequence=request.sequence,
            timestamp_millis=request.timestamp_millis,
            model_id=request.model_id,
            model_version=request.model_version,
            detections=[],
            inference_millis=0,
        )


class TrainingServicer(cv_pb2_grpc.TrainingServicer):
    """Training / model-registry control plane - not implemented until Phase 3."""

    def StartTraining(self, request, context):
        context.abort(grpc.StatusCode.UNIMPLEMENTED, "training is not implemented yet (Phase 3)")

    def ListModels(self, request, context):
        context.abort(grpc.StatusCode.UNIMPLEMENTED, "model registry is not implemented yet (Phase 3)")

    def PromoteModel(self, request, context):
        context.abort(grpc.StatusCode.UNIMPLEMENTED, "model registry is not implemented yet (Phase 3)")


def serve(port: int = DEFAULT_PORT) -> grpc.Server:
    """Build, start, and return a gRPC server bound to ``port``."""
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    cv_pb2_grpc.add_InferenceServicer_to_server(InferenceServicer(), server)
    cv_pb2_grpc.add_TrainingServicer_to_server(TrainingServicer(), server)
    server.add_insecure_port(f"[::]:{port}")
    server.start()
    LOGGER.info("cv-service gRPC server listening on :%d", port)
    return server


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    server = serve()
    stop_event = threading.Event()

    def _handle_signal(signum: int, _frame) -> None:
        if stop_event.is_set():
            return
        stop_event.set()
        LOGGER.info("received signal %s, shutting down gracefully...", signal.Signals(signum).name)
        server.stop(grace=5)

    signal.signal(signal.SIGTERM, _handle_signal)
    signal.signal(signal.SIGINT, _handle_signal)

    server.wait_for_termination()
    LOGGER.info("cv-service stopped")


if __name__ == "__main__":
    main()
