"""gRPC composition root for the Vision CV service.

``serve()``/``main()`` live here: resolve :class:`cv_service.config.Settings`
once, build the shared `ModelRegistry`/`InferenceGate`, wire the two
servicers (`cv_service.grpc.servicers.InferenceServicer`/`TrainingServicer`),
start the gRPC server, and handle `SIGTERM`/`SIGINT` for graceful shutdown.
No servicer behavior lives here -- see `cv_service/grpc/servicers.py` for
wire<->domain translation, `cv_service/inference/` for detection, and
`cv_service/training/` for the training control plane.

Run with::

    python -m cv_service.grpc.server

Requires the generated stubs under ``cv_service/gen`` - run
``scripts/gen_proto.sh`` first (see README.md). Generated code is never
committed. This module stays importable without the ``cv`` optional
dependency group installed (``ultralytics``/``cv2``/``torch``) -- registry
construction is lazily imported inside ``serve()`` and degrades to the
Phase 0 echo behavior (``registry=None``) if the extra is absent, exactly
like before this module's own split out of the former ``cv_service/server.py``.
"""

from __future__ import annotations

import logging
import signal
import threading
from concurrent import futures

import grpc

from cv_service.config import Settings
from cv_service.inference.concurrency import InferenceGate
from cv_service.grpc.servicers import (  # cv_pb2_grpc re-exported so tests can monkeypatch it here
    InferenceServicer,
    TrainingServicer,
    cv_pb2_grpc,
)

LOGGER = logging.getLogger("cv_service.grpc.server")

# HTTP/2 keepalive tuning for a flaky Wi-Fi/VPN link between the laptop
# (backend, adapter-cv-grpc) and this service -- docs/REMOTE-CV-PLAN.md
# "Transport decisions" P1. The client channel
# (`GrpcDetectionPort.KEEPALIVE_TIME_SECONDS`) pings every 20s, including
# while idle; a grpc Python server otherwise GOAWAYs ("too_many_pings") a
# client pinging with no active calls, so `keepalive_permit_without_calls`
# is required for the client-side tuning to have any effect at all.
# `http2.min_ping_interval_without_data_ms` (10s, half the client's 20s
# interval, leaving jitter headroom) is the floor below which the server
# would otherwise still reject pings as abusive. The server also pings its
# own side (`keepalive_time_ms`/`keepalive_timeout_ms`) so it detects a dead
# client and frees that stream's thread promptly, not just the other way
# around.
_KEEPALIVE_SERVER_OPTIONS = [
    ("grpc.keepalive_permit_without_calls", 1),
    ("grpc.http2.min_ping_interval_without_data_ms", 10000),
    ("grpc.keepalive_time_ms", 30000),
    ("grpc.keepalive_timeout_ms", 10000),
]


def _build_default_registry(settings: Settings):
    """Lazily import + build the production `ModelRegistry`; `None` if the
    `cv` extra is absent or the default model can't be loaded (echo-mode
    degrade). Local import keeps this module -- and hence
    `python -m cv_service.grpc.server` -- importable without the `cv` extra.
    """
    try:
        from cv_service.inference.registry import build_default_registry
    except ImportError as exc:
        LOGGER.warning(
            "cv-service YOLO backend unavailable (%s); DetectStream will "
            "serve the Phase 0 echo behavior (empty detections) until the "
            "'cv' optional dependency group is installed.",
            exc,
        )
        return None
    return build_default_registry(settings)


def serve(settings: Settings | None = None) -> grpc.Server:
    """Build, start, and return a gRPC server.

    Configured with ``_KEEPALIVE_SERVER_OPTIONS`` (see module-level comment)
    so this server tolerates and reciprocates the client channel's HTTP/2
    keepalive pings instead of GOAWAY-ing it or leaving a dead client's
    stream thread parked indefinitely.
    """
    settings = settings if settings is not None else Settings.from_env()

    server = grpc.server(
        futures.ThreadPoolExecutor(max_workers=settings.grpc_workers),
        options=_KEEPALIVE_SERVER_OPTIONS,
    )

    # Build the registry ONCE and share it between both servicers, so
    # `Training.PromoteModel` re-points the very registry the inference
    # `DetectStream` path routes against -- one source of truth. `None`
    # (no model loaded) is passed through explicitly: `InferenceServicer`
    # degrades to echo, `TrainingServicer` reports an empty roster.
    registry = _build_default_registry(settings)
    gate = InferenceGate(settings.max_concurrent_inferences)

    cv_pb2_grpc.add_InferenceServicer_to_server(
        InferenceServicer(registry=registry, inference_gate=gate), server
    )
    cv_pb2_grpc.add_TrainingServicer_to_server(
        TrainingServicer(
            registry=registry,
            model_dir=settings.model_dir,
            dataset_dir=settings.dataset_dir,
            max_upload_bytes=settings.max_upload_bytes,
        ),
        server,
    )
    server.add_insecure_port(f"[::]:{settings.port}")
    server.start()
    LOGGER.info("cv-service gRPC server listening on :%d", settings.port)
    return server


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    settings = Settings.from_env()
    server = serve(settings)
    stop_event = threading.Event()

    def _handle_signal(signum: int, _frame) -> None:
        if stop_event.is_set():
            return
        stop_event.set()
        LOGGER.info("received signal %s, shutting down gracefully...", signal.Signals(signum).name)
        server.stop(grace=settings.shutdown_grace_seconds)

    signal.signal(signal.SIGTERM, _handle_signal)
    signal.signal(signal.SIGINT, _handle_signal)

    server.wait_for_termination()
    LOGGER.info("cv-service stopped")


if __name__ == "__main__":
    main()
