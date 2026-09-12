"""gRPC composition root for the Vision CV service.

``serve()``/``main()`` live here: resolve :class:`cv_service.config.Settings`
once, build the shared `ModelRegistry`/`InferenceGate`, wire the servicers
this process's `Settings.role` calls for
(`cv_service.grpc.servicers.InferenceServicer`/`TrainingServicer`/
`GeolocationServicer`), start the gRPC server, and handle `SIGTERM`/`SIGINT`
for graceful shutdown. No servicer behavior lives here -- see
`cv_service/grpc/servicers.py` for wire<->domain translation,
`cv_service/inference/` for detection, `cv_service/training/` for the
training control plane, and `cv_service/geo/` for visual geolocation
(VISUAL-GEO-V2-PLAN.md).

Run with::

    python -m cv_service.grpc.server

which serves whatever `Settings.role` (`CV_SERVICE_ROLE`, default `all`)
resolves to. `python -m cv_service.grpc.server_inference` and
`cv_service.grpc.server_training` (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md
R6) are thin wrappers around the exact same `main()` that just default the
role for their process -- see those modules' own docstrings and this
module's "Process roles" section below. No servicer-construction code is
duplicated between the three: `serve()` is the one place that lives.

Requires the generated stubs under ``cv_service/gen`` - run
``scripts/gen_proto.sh`` first (see README.md). Generated code is never
committed. This module stays importable without the ``cv`` optional
dependency group installed (``ultralytics``/``cv2``/``torch``) -- registry
construction is lazily imported inside ``serve()`` and degrades to the
Phase 0 echo behavior (``registry=None``) if the extra is absent, exactly
like before this module's own split out of the former ``cv_service/server.py``.

Process roles (`Settings.role`, `CV_SERVICE_ROLE`)
====================================================
`Inference` is a ~50ms-budget-per-frame hot path; `Training`/`Geolocation`
are long-running/GPU-hungry. Sharing one process (and one GIL) means a
training run or a geolocation index build can degrade every live stream's
detection -- the defect this knob closes
(docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md T2/R6). CV-ORCHESTRATION
wave W4 (§4.9) adds a second split, orthogonal to the first: the stateless
`Detector` RPC (pure pixels-to-boxes, no session/identity) may scale to N
instances behind a `tracker`'s ordered `CV_DETECTOR_TARGETS` list, because a
detector carries no per-stream affinity to lose. Five roles total, selected
once at process start, never changed at runtime:

- ``all`` (default) -- every servicer (`Inference` + `Detector` + `Training`
  + `Geolocation`), one process. Byte-identical to every deployment that
  predates either split; the only mode a plain
  ``python -m cv_service.grpc.server`` with no ``CV_SERVICE_ROLE`` set has
  ever produced -- `Detector`'s addition here is new surface, not a new
  default behavior (nothing calls it unless something is pointed at it).
- ``inference`` -- `Inference` only, unchanged by W4. `Training`/
  `Geolocation`/`Detector` are not registered on the gRPC server AND their
  collaborators (`InferenceGate`, the tracker registry) are never
  constructed -- this process never spends a cycle or a GPU byte on
  training/geolocation/pooled-detector work.
- ``tracker`` (CV-ORCHESTRATION W4) -- `Inference` only, exactly like
  ``inference`` above, but the intended deployment shape is "point me at a
  `CV_DETECTOR_TARGETS` list instead of detecting in-process": every
  session's `StreamTrackingSession.detect_through()` gets a
  `PoolDetectorClient` over that list (see `cv_service.grpc.servicers.
  InferenceServicer._new_detector_client`). An operator who sets this role
  with an EMPTY `CV_DETECTOR_TARGETS` gets exactly the ``inference`` role's
  behavior (every session falls back to a plain `LocalDetectorClient`) plus
  one startup warning -- this process never refuses to start over a
  configuration mismatch it can recover from by serving locally instead.
- ``detector`` (CV-ORCHESTRATION W4) -- `Detector` only: the stateless RPC a
  `tracker`'s pool dials. No `Inference`, no tracker registry, no session
  registry -- a detector instance holds no per-stream state at all. Still
  builds a `ModelRegistry` (to resolve `DetectRequest.model_id`) and one
  `InferenceGate` (`DetectorServicer`'s own admission door), exactly as
  ``inference`` does for the same reasons.
- ``training`` -- `Training` + `Geolocation` only (they are grouped: both
  are long-running/heavy, neither is latency-sensitive the way `Inference`
  is). `InferenceServicer`/`DetectorServicer` are not registered and their
  `InferenceGate`/tracker registry are never built.

The model registry (`ModelRegistry`) is built regardless of role -- both
`inference` (the detector) and `training` (`ListModels`/`PromoteModel`
bookkeeping) need it. **Known limitation of a split deployment**: each
process holds its own in-memory `ModelRegistry`. `TrainingServicer.
PromoteModel` persists the active-model marker to `settings.model_dir` (see
its own docstring) so the choice survives *that process's* restart, but does
**not** reach across processes -- an `inference`-role process only picks up
a promotion made against a different `training`-role process the next time
it restarts and re-reads the marker (`build_default_registry`). This is
unchanged from the pre-split `all`-in-one-process story where the "same
registry" claim held because there was only ever one process; a split
deployment that needs a promotion to take effect on a *running* inference
process without a restart is not built here.
"""

from __future__ import annotations

import logging
import signal
import threading
from concurrent import futures

import grpc

from cv_service.config import (
    DEFAULT_ROLE,
    ROLE_DETECTOR,
    ROLE_INFERENCE,
    ROLE_TRACKER,
    ROLE_TRAINING,
    Settings,
)
from cv_service.inference.concurrency import InferenceGate
from cv_service.grpc.detector_servicer import DetectorServicer
from cv_service.grpc.servicers import (  # cv_pb2_grpc re-exported so tests can monkeypatch it here
    GeolocationServicer,
    InferenceServicer,
    TrainingServicer,
    cv_pb2_grpc,
)

LOGGER = logging.getLogger("cv_service.grpc.server")

# HTTP/2 keepalive tuning for a flaky Wi-Fi/VPN link between the laptop
# (backend, adapter-cv-grpc) and this service -- docs/plans/done/REMOTE-CV-PLAN.md
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


def _build_tracker_registry(settings: Settings):
    """Build + probe the `TrackerRegistry` at STARTUP, not on first frame.

    Probing here is the whole point (TRACKING-PLAN R3/R11): each engine is
    constructed once and the roster that actually survived is logged at INFO,
    so an operator can see which trackers are routable on this box without
    reading code -- exactly what `discover_roster` already does for models.
    A box where nothing constructs still starts and still serves detections;
    its sessions just degrade to OFF.
    """
    try:
        from cv_service.tracking.registry import build_default_registry as build_trackers

        return build_trackers(settings)
    except Exception as exc:  # noqa: BLE001 - never block startup on a tracker
        LOGGER.warning(
            "cv-service tracker registry unavailable (%s); DetectStream will serve "
            "detections without track ids until the 'cv' optional dependency group "
            "is installed.",
            exc,
        )
        return None


def serve(settings: Settings | None = None) -> grpc.Server:
    """Build, start, and return a gRPC server registering whichever
    servicer(s) ``settings.role`` calls for (see module docstring's
    "Process roles" section).

    Configured with ``_KEEPALIVE_SERVER_OPTIONS`` (see module-level comment)
    so this server tolerates and reciprocates the client channel's HTTP/2
    keepalive pings instead of GOAWAY-ing it or leaving a dead client's
    stream thread parked indefinitely.
    """
    settings = settings if settings is not None else Settings.from_env()
    # `tracker` (CV-ORCHESTRATION W4) registers `Inference` only, exactly
    # like `inference` -- its whole distinction is the DEPLOYMENT INTENT
    # (`CV_DETECTOR_TARGETS` pointed at a pool) that `InferenceServicer`
    # itself already reads from `settings`, not a different servicer set.
    serves_inference = settings.role in (DEFAULT_ROLE, ROLE_INFERENCE, ROLE_TRACKER)
    serves_detector = settings.role in (DEFAULT_ROLE, ROLE_DETECTOR)
    serves_training = settings.role in (DEFAULT_ROLE, ROLE_TRAINING)

    if settings.role == ROLE_TRACKER and not settings.detector_targets:
        # Reported, never refused: this process still serves `Inference`
        # perfectly well by falling back to in-process detection (every
        # session's `LocalDetectorClient` default) -- see the module
        # docstring's `tracker` bullet. A misconfigured pool is an operator
        # mistake worth ONE log line at startup, not a reason to crash-loop
        # a process that can still do useful work.
        LOGGER.warning(
            "CV_SERVICE_ROLE=tracker but CV_DETECTOR_TARGETS is empty; this "
            "process will detect in-process (LocalDetectorClient) instead of "
            "pooling to any remote Detector instance."
        )

    server = grpc.server(
        futures.ThreadPoolExecutor(max_workers=settings.grpc_workers),
        options=_KEEPALIVE_SERVER_OPTIONS,
    )

    # Built regardless of role: `inference`/`tracker` need it for detection,
    # `detector` needs it to resolve `DetectRequest.model_id`, `training`
    # needs it for ListModels/PromoteModel bookkeeping -- see the module
    # docstring's "Known limitation of a split deployment" for what this
    # shared-in-name-only registry does NOT give a split deployment (live
    # cross-process promotion). `None` (no model loaded) is passed through
    # explicitly: `InferenceServicer`/`DetectorServicer` degrade to
    # echo/`FAILED_PRECONDITION`, `TrainingServicer` reports an empty roster.
    registry = _build_default_registry(settings)

    # ONE `InferenceGate` shared between `InferenceServicer` and
    # `DetectorServicer` when both are registered together (role=`all`) --
    # CV-ORCHESTRATION §4.9's admission accounting is about how many
    # `detect()` calls THIS PROCESS runs concurrently, regardless of which
    # RPC asked for them; two separate gates on one process would let both
    # servicers independently believe they had the full permit count.
    gate = InferenceGate(settings.max_concurrent_inferences) if (serves_inference or serves_detector) else None

    if serves_inference:
        tracker_registry = _build_tracker_registry(settings)
        cv_pb2_grpc.add_InferenceServicer_to_server(
            InferenceServicer(
                registry=registry,
                inference_gate=gate,
                settings=settings,
                tracker_registry=tracker_registry,
            ),
            server,
        )

    if serves_detector:
        # No tracker registry, no `SessionRegistry` -- a detector instance
        # holds no per-stream state at all (module docstring's `detector`
        # bullet); `DetectorServicer` needs only the model registry and the
        # (possibly shared) gate above.
        cv_pb2_grpc.add_DetectorServicer_to_server(
            DetectorServicer(registry=registry, inference_gate=gate, settings=settings),
            server,
        )

    if serves_training:
        cv_pb2_grpc.add_TrainingServicer_to_server(
            TrainingServicer(
                registry=registry,
                model_dir=settings.model_dir,
                dataset_dir=settings.dataset_dir,
                max_upload_bytes=settings.max_upload_bytes,
            ),
            server,
        )
        # `GeolocationServicer` builds its own encoder/matcher backend ONCE, here, at
        # construction (same "probe/build at startup, log the roster, degrade to UNAVAILABLE
        # per-call rather than block startup" posture as `registry`/`tracker_registry` above) --
        # see its own docstring. Grouped with `training`, not `inference`: both are
        # long-running/heavy, neither is on the per-frame hot path.
        cv_pb2_grpc.add_GeolocationServicer_to_server(GeolocationServicer(settings=settings), server)

    server.add_insecure_port(f"[::]:{settings.port}")
    server.start()
    LOGGER.info("cv-service gRPC server listening on :%d (role=%s)", settings.port, settings.role)
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
