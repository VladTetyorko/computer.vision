"""gRPC server for the Vision CV service (Phase 0 skeleton).

* ``Inference.DetectStream`` is implemented as a working echo: for every
  incoming ``FrameRequest`` it yields a ``DetectionResponse`` with the same
  stream_id / sequence / timestamp_millis / model_id / model_version and an
  empty ``detections`` list. No model runs yet - that is Phase 2 (see
  ../../docs/PHASE0-PLAN.md). This lets the Java <-> Python gRPC wiring
  (adapter-cv-grpc, StreamPipeline) be built and tested end-to-end now.
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
from typing import Iterable, Iterator

import grpc

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


class InferenceServicer(cv_pb2_grpc.InferenceServicer):
    """Echo-stub implementation of the ``Inference`` service.

    Phase 0: always responds with zero detections. Real inference
    (Ultralytics YOLO) lands in Phase 2 behind this same RPC shape.
    """

    def DetectStream(
        self,
        request_iterator: Iterable["cv_pb2.FrameRequest"],
        context: grpc.ServicerContext,
    ) -> Iterator["cv_pb2.DetectionResponse"]:
        for request in request_iterator:
            yield cv_pb2.DetectionResponse(
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
