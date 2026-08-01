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
* ``Training.ListModels`` / ``Training.PromoteModel`` are implemented against
  the same ``ModelRegistry`` the inference path uses: ``ListModels`` reports
  the roster (``stage="active"`` for the current default, ``"available"`` for
  the rest); ``PromoteModel`` re-points the default and persists the choice
  (``cv_service.training`` marker) so it survives a restart -- matching the
  offline-train -> rsync-in -> promote operational loop. ``StartTraining``
  runs a real Ultralytics YOLO fine-tune on an exported dataset (rsync'd onto
  the host under ``CV_DATASET_DIR``), streams ``TrainingProgress`` per epoch,
  and writes the produced model into the model dir so this same
  ``ListModels``/``PromoteModel`` loop can surface + promote it (see
  ``cv_service/trainer.py``). It is device-agnostic (CPU here -- slow -- or
  CUDA where present) and never auto-promotes.

Run with::

    python -m cv_service.server

Requires the generated stubs under ``cv_service/gen`` - run
``scripts/gen_proto.sh`` first (see README.md). Generated code is never
committed.
"""

from __future__ import annotations

import logging
import os
import queue
import shutil
import signal
import sys
import threading
import uuid
from concurrent import futures
from pathlib import Path
from typing import TYPE_CHECKING, Iterable, Iterator, Optional

import grpc

from cv_service import trainer
from cv_service.concurrency import InferenceGate, LatestOnlyMailbox, process_gate
from cv_service.training import read_active_model, write_active_model

if TYPE_CHECKING:  # pragma: no cover - import cycle avoidance, see _build_default_detector
    from cv_service.inference import YoloDetector
    from cv_service.registry import ModelRegistry

# Sentinel distinguishing "no `registry` argument passed" (auto-build the
# default registry -- the production `InferenceServicer()` path and every
# pre-registry test) from an explicit `registry=None` (echo mode, no model:
# `serve()` passes the already-built shared registry, which may be `None`,
# and must NOT trigger a second, wasteful build).
_UNSET_REGISTRY: object = object()

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


# Directory the registry scans for local `*.pt` weights / exported
# `*_openvino_model` dirs: `cv_service/server.py`'s grandparent, i.e. the
# `cv-service/` checkout directory itself -- the same place `YOLO("x.pt")`'s
# own cwd-relative download/cache behavior already lands weights in when the
# server/tests are run from there (see MODULE.md "CV_MODEL / weights
# location"), and where the Dockerfile's `WORKDIR /app/cv-service` puts them
# too. Resolved from `__file__` rather than `os.getcwd()` so discovery is
# independent of the directory the process happens to be launched from.
_MODEL_SEARCH_DIR = Path(__file__).resolve().parent.parent

# Root directory `Training.StartTraining` resolves `dataset_id` under: an
# exported YOLO dataset lives at `<datasets>/<dataset_id>/` (data.yaml +
# images/ + labels/, Phase 1's FilesystemDatasetExport layout). Configured by
# `CV_DATASET_DIR` (following the module's env-config idiom, e.g. `CV_MODEL`),
# defaulting to `<cv-service>/datasets` -- the same rsync-in operating model
# the model artifacts use (MEMORY: gb4005-inference-box). Resolved once here.
_DATASET_SEARCH_DIR = Path(
    os.environ.get("CV_DATASET_DIR", str(_MODEL_SEARCH_DIR / trainer.DATASET_DIRNAME))
)


def _build_default_registry() -> Optional["ModelRegistry"]:
    """Try to build the default `ModelRegistry`; `None` if unavailable.

    Mirrors `_build_default_detector()`'s import/degrade contract exactly
    (local import so the rest of the server stays importable without the
    `cv` extra; any failure to load the *default* model degrades the whole
    servicer to the Phase 0 echo behavior, same as before the registry
    existed) -- the registry only changes what happens for *non-default*
    `model_id`s, never the "no model at all" story.
    """
    try:
        from cv_service.inference import DEFAULT_MODEL
        from cv_service.registry import ModelRegistry, discover_roster
    except ImportError as exc:
        LOGGER.warning(
            "cv-service YOLO backend unavailable (%s); DetectStream will "
            "serve the Phase 0 echo behavior (empty detections) until the "
            "'cv' optional dependency group is installed.",
            exc,
        )
        return None

    default_model = os.environ.get("CV_MODEL", DEFAULT_MODEL)
    roster = discover_roster(_MODEL_SEARCH_DIR, default_model)

    # Apply a persisted promotion (`Training.PromoteModel`) if one is
    # recorded AND still names a roster id -- so the last live promotion
    # survives this restart. A marker naming an id that is no longer present
    # (artifact removed) or no marker at all falls back to the env/DEFAULT
    # model, never an error.
    promoted = read_active_model(_MODEL_SEARCH_DIR)
    if promoted is not None and promoted in roster:
        active_id = promoted
        LOGGER.info("cv-service applying persisted promoted model_id=%r as default", promoted)
    else:
        if promoted is not None:
            LOGGER.warning(
                "persisted promoted model_id=%r is not in the current roster %s; "
                "falling back to default %r",
                promoted,
                sorted(roster),
                default_model,
            )
        active_id = default_model

    registry = ModelRegistry(roster=roster, default_id=active_id)

    if registry.default_detector() is None:
        LOGGER.warning(
            "YOLO model could not be loaded (model_id=%r); DetectStream will "
            "serve the Phase 0 echo behavior (empty detections) until this "
            "is fixed.",
            default_model,
        )
        return None

    LOGGER.info(
        "cv-service model registry roster: %s (default=%r)",
        sorted(registry.roster),
        active_id,
    )
    return registry


def _context_active(context: object) -> bool:
    """Whether the gRPC call is still live (client hasn't cancelled/disconnected).

    `grpc.ServicerContext.is_active()` reports this; guarded with `hasattr`
    so a lightweight fake context in tests (without `is_active`) is treated as
    always-active rather than crashing the stream.
    """
    is_active = getattr(context, "is_active", None)
    if is_active is None:
        return True
    try:
        return bool(is_active())
    except Exception:  # noqa: BLE001 - a context probe failure shouldn't kill the stream
        return True


class _StreamReader:
    """Background thread draining the *rest* of a `DetectStream`
    request_iterator into a `LatestOnlyMailbox`, decoupling *receiving*
    frames over the network from *inferring* on them -- one instance per
    active `DetectStream` call, started only after the stream's first frame
    has already been claimed directly by the consumer (see `DetectStream`):
    there's nothing to overlap with before the first frame's inference even
    begins, and starting the reader any earlier lets it race the consumer's
    very first `next()`/`get()` call for that same first frame -- confirmed
    empirically to actually happen (not just theoretical) with a
    zero-latency in-memory iterator, which is exactly what this module's own
    tests use; a real network is never that instantaneous, but the reader
    shouldn't depend on that to behave correctly.

    Without this, `for request in request_iterator: infer(request); yield`
    means the next frame's bytes only start being read off the network
    *after* the current frame's inference has finished and its response has
    been handed back to gRPC -- inference and network transfer never
    overlap. With a dedicated reader thread continuously pulling from
    `request_iterator` into a 1-slot latest-wins mailbox, the consumer
    thread (the one gRPC actually drives as the `DetectStream` generator)
    can be inside `detect()` for frame N while frame N+1 is already arriving
    in the background; if frame N+2 also arrives before the consumer catches
    up, N+1 is silently dropped (never inferred, never yields a response) --
    see `LatestOnlyMailbox`. See MODULE.md "V-d: per-stream concurrency" for
    the full design writeup, including why drops are expected to be rare in
    practice (the Java caller's own sampling/in-flight cap).
    """

    def __init__(self, request_iterator: Iterable["cv_pb2.FrameRequest"]) -> None:
        self._iterator = request_iterator
        self._mailbox: LatestOnlyMailbox["cv_pb2.FrameRequest"] = LatestOnlyMailbox()
        self._error: Optional[BaseException] = None
        self._thread = threading.Thread(
            target=self._run, name="cv-detectstream-reader", daemon=True
        )
        self._thread.start()

    def _run(self) -> None:
        try:
            for request in self._iterator:
                self._mailbox.put(request)
        except Exception as exc:  # noqa: BLE001 - re-surfaced to the consumer via next()
            self._error = exc
        finally:
            self._mailbox.close()

    def next(self) -> Optional["cv_pb2.FrameRequest"]:
        """Block for the next (latest) frame; `None` once the stream ends.

        Re-raises whatever `request_iterator` itself raised, once the
        mailbox has been drained -- mirrors what iterating it directly would
        have done, just from the consumer's thread instead of the reader's.
        """
        request = self._mailbox.get()
        if request is not None:
            return request
        if self._error is not None:
            raise self._error
        return None

    def stop(self) -> None:
        """Best-effort: stop queueing further frames for a consumer that's
        going away (client cancel, or the generator returning/raising).

        Does not (and cannot) interrupt a blocking read already in progress
        on `request_iterator` -- gRPC itself unblocks that on its own once
        the call ends (verified empirically: cancelling a call unblocks a
        *background* thread's `next(request_iterator)` within milliseconds,
        the same as it would the main thread), at which point `_run`'s `for`
        loop ends and the thread exits on its own. This just guards against
        a frame that arrives right at teardown being queued into a mailbox
        nobody will ever drain again.
        """
        self._mailbox.close()


class InferenceServicer(cv_pb2_grpc.InferenceServicer):
    """Real-YOLO-when-available, echo-otherwise implementation of ``Inference``.

    Two ways to give this servicer a model, mutually exclusive:

    - **`detector=`** (explicit injection -- what every pre-registry test
      still uses): exactly one `YoloDetector` handles every frame,
      regardless of `model_id`; an unrecognized `model_id` just logs once
      (`_warn_once_on_unknown_model`) and serves this same detector. No
      registry, no composite mode -- this path is unchanged from before the
      registry existed.
    - **`registry=`** (default when `detector` is omitted -- see
      `_build_default_registry()`): a `cv_service.registry.ModelRegistry`
      routes each request's `model_id` (optionally a comma-separated
      composite list, see `registry.detect_composite`) to the matching
      locally-discovered model(s), falling back to the registry's own
      default for an unknown/absent id. See `cv_service/registry.py` and
      MODULE.md "Model registry" for the full design.

    Either way, if no model could be loaded at all (neither `detector` nor a
    usable `registry` default), every frame gets the original Phase 0 echo
    response: the service must never crash-loop for lack of a model.

    Per-frame inference failures (bad frame bytes, a transient model error)
    are also caught and degrade to an echo response for that one frame,
    rather than tearing down the whole bidi stream.

    **Concurrency (V-d):** when a model is loaded (either path above), each
    `DetectStream` call spawns a `_StreamReader` background thread so frame
    receipt and inference overlap within that one stream (see its
    docstring), and every `detect()` call is gated by `inference_gate`
    (default: the process-wide `InferenceGate`, shared across every
    `InferenceServicer` instance/stream) so the number of *concurrent*
    inferences across all streams stays bounded regardless of how many
    streams are open -- composite mode's member calls are gated the same
    way, individually (see `registry.detect_composite`), not as one bigger
    unit. The echo-only path (no model at all) skips both -- there's nothing
    to overlap or bound without a model in the loop, so it stays the plain
    synchronous loop it always was.
    """

    def __init__(
        self,
        detector: Optional["YoloDetector"] = None,
        *,
        inference_gate: Optional[InferenceGate] = None,
        registry: object = _UNSET_REGISTRY,
    ) -> None:
        self._inference_gate = inference_gate if inference_gate is not None else process_gate()
        self._warned_model_ids: set[str] = set()
        if detector is not None:
            # Explicit single-detector injection: registry routing is
            # bypassed entirely, see class docstring.
            self._detector = detector
            self._registry = None
        else:
            self._detector = None
            # `registry` omitted -> auto-build the default (production path +
            # every pre-registry test). An explicit `registry=` (including
            # `None`, which `serve()` may pass in echo mode) is used as-is,
            # never rebuilt -- see `_UNSET_REGISTRY`.
            if registry is _UNSET_REGISTRY:
                self._registry = _build_default_registry()
            else:
                self._registry = registry

    def DetectStream(
        self,
        request_iterator: Iterable["cv_pb2.FrameRequest"],
        context: grpc.ServicerContext,
    ) -> Iterator["cv_pb2.DetectionResponse"]:
        if self._detector is None and self._registry is None:
            for request in request_iterator:
                yield self._echo(request)
            return

        # Claim the first frame directly and synchronously -- see
        # _StreamReader's docstring for why the background reader thread
        # only starts on the *rest* of the stream, not this one.
        try:
            first_request = next(request_iterator)
        except StopIteration:
            return

        reader = _StreamReader(request_iterator)
        try:
            yield self._handle_request(first_request)
            while True:
                request = reader.next()
                if request is None:
                    return
                yield self._handle_request(request)
        finally:
            reader.stop()

    def _handle_request(self, request: "cv_pb2.FrameRequest") -> "cv_pb2.DetectionResponse":
        try:
            if self._registry is not None:
                detections, inference_millis = self._detect_via_registry(request)
                if detections is None:
                    return self._echo(request)
            else:
                self._warn_once_on_unknown_model(request.model_id)
                with self._inference_gate.acquire():
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
            return self._echo(request)

        return cv_pb2.DetectionResponse(
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

    def _detect_via_registry(
        self, request: "cv_pb2.FrameRequest"
    ) -> tuple[Optional[list], int]:
        """Registry-routed counterpart of the explicit-`detector` branch above.

        Resolves `request.model_id` (comma-separated for composite mode)
        against `self._registry`'s roster and runs every resolved member via
        `registry.detect_composite` (which does its own per-member
        `inference_gate` acquisition -- see that function's docstring).
        Returns ``(None, 0)`` when nothing resolved at all (registry present
        but even its own default failed to load) so the caller echoes this
        one frame, same as any other per-frame failure.
        """
        from cv_service.registry import detect_composite

        resolved = self._registry.resolve(request.model_id)
        if not resolved:
            return None, 0
        detections, inference_millis = detect_composite(
            resolved,
            gate=self._inference_gate,
            width=request.width,
            height=request.height,
            encoding=cv_pb2.ImageEncoding.Name(request.encoding),
            data=request.data,
            confidence_threshold=request.confidence_threshold or None,
        )
        return detections, inference_millis

    def _warn_once_on_unknown_model(self, requested_model_id: str) -> None:
        """Log-and-serve-default for a requested `model_id`, explicit-`detector` path only.

        The registry path (`self._registry is not None`) has its own
        equivalent, deduplicated warning inside `ModelRegistry.resolve()` --
        this method only runs for the legacy single-`detector` injection
        branch (see class docstring), where there is no registry to ask.
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
    """Model-registry control plane over the shared `ModelRegistry`.

    `ListModels`/`PromoteModel` are the "unblocked half" of CV-TRAINING
    Phase 2 (`docs/CV-TRAINING-PLAN.md` §6): a model rsync'd onto the host
    becomes selectable, and the operator promotes it live. Both read/write
    the *same* `ModelRegistry` instance the inference `DetectStream` path
    routes against (wired identically in `serve()`), so a promotion takes
    effect for subsequent default-model resolution immediately -- there is no
    second source of truth.

    `StartTraining` runs a real Ultralytics YOLO fine-tune on an exported
    dataset that was rsync'd onto this host (`<CV_DATASET_DIR>/<dataset_id>/`),
    streams per-epoch progress, and writes the produced `best.pt` into the
    model directory under a new id so this same `ListModels`/`PromoteModel`
    loop can surface + promote it. It is device-agnostic (CUDA if present,
    else CPU -- slow on this Intel appliance, logged once) and never
    auto-promotes: the operator promotes deliberately. See
    `cv_service/trainer.py` for the training core.
    """

    def __init__(
        self,
        *,
        registry: object = None,
        model_dir: Optional[Path] = None,
        dataset_dir: Optional[Path] = None,
        train_fn: Optional[trainer.TrainFn] = None,
    ) -> None:
        # `registry` may be a `ModelRegistry` or `None` (echo mode / no model
        # loaded) -- kept as `object` to avoid importing `ModelRegistry` at
        # module scope (it needs the `cv` extra; this servicer must not).
        self._registry = registry
        # Directory the active-model marker is persisted into AND where a
        # produced training artifact is written -- the same `_MODEL_SEARCH_DIR`
        # the roster is discovered from.
        self._model_dir = model_dir
        # Root under which `dataset_id` resolves to an exported YOLO dataset.
        self._dataset_dir = dataset_dir
        # Injectable trainer seam: default is the real (lazy-ultralytics)
        # `trainer.ultralytics_train`; tests inject a fast fake. Resolved lazily
        # in `StartTraining` so importing this module never touches ultralytics.
        self._train_fn = train_fn

    def StartTraining(self, request, context):
        """Fine-tune `base_model` on `dataset_id` for `epochs`, streaming progress.

        Server-streaming contract (`TrainingProgress`):
        - a `job_id` (assigned here) rides every message of this call;
        - one `RUNNING` update per training epoch (`epoch`/`total_epochs`/
          `loss`/`map50` from Ultralytics' `on_fit_epoch_end` hook);
        - then exactly one terminal message: `SUCCEEDED` (with the produced
          model id in `message`) or `FAILED` (with the failure/`message`).

        A missing/malformed dataset is a **normal reported outcome**: one
        terminal `FAILED` message, stream ends -- NOT a gRPC abort. Client
        (or `context`) cancellation stops the underlying training.
        """
        job_id = uuid.uuid4().hex
        base_model = request.base_model
        dataset_id = request.dataset_id
        epochs = request.epochs

        # --- resolve + validate the dataset (a miss is a reported FAILED) ---
        try:
            spec = trainer.build_spec(
                job_id=job_id,
                base_model=base_model,
                dataset_id=dataset_id,
                epochs=epochs,
                datasets_root=self._dataset_dir if self._dataset_dir is not None else _DATASET_SEARCH_DIR,
                output_dir=self._model_dir if self._model_dir is not None else _MODEL_SEARCH_DIR,
            )
        except trainer.DatasetNotFoundError as exc:
            LOGGER.warning("StartTraining job=%s: %s", job_id, exc)
            yield cv_pb2.TrainingProgress(
                job_id=job_id, state=cv_pb2.JobState.FAILED, message=str(exc)
            )
            return

        # --- run the actual training OFF this gRPC thread (mirrors how
        # DetectStream keeps its long-lived work on a background thread): the
        # blocking `.train()` runs in `worker`, progress flows back through a
        # queue, and this generator thread only polls + yields, so it stays
        # responsive to client cancellation and never wedges compute onto the
        # server's stream thread. -------------------------------------------
        events: "queue.Queue[tuple[str, object]]" = queue.Queue()
        cancel_event = threading.Event()
        train_fn = self._train_fn if self._train_fn is not None else trainer.ultralytics_train

        def worker() -> None:
            try:
                best = train_fn(
                    spec,
                    on_epoch=lambda progress: events.put(("epoch", progress)),
                    is_cancelled=cancel_event.is_set,
                )
                events.put(("done", best))
            except trainer.TrainingCancelled:
                events.put(("cancelled", None))
            except Exception as exc:  # noqa: BLE001 - reported to the client as FAILED
                LOGGER.exception("StartTraining job=%s failed", job_id)
                events.put(("error", exc))

        thread = threading.Thread(target=worker, name=f"cv-training-{job_id[:8]}", daemon=True)
        thread.start()

        # Promptly signal cancellation when the client disconnects, on top of
        # the polling `context.is_active()` check below (belt and braces).
        if hasattr(context, "add_callback"):
            context.add_callback(cancel_event.set)

        last: Optional[trainer.EpochProgress] = None
        try:
            while True:
                if not _context_active(context):
                    cancel_event.set()
                    return
                try:
                    kind, payload = events.get(timeout=0.5)
                except queue.Empty:
                    continue

                if kind == "epoch":
                    last = payload  # type: ignore[assignment]
                    yield cv_pb2.TrainingProgress(
                        job_id=job_id,
                        epoch=payload.epoch,
                        total_epochs=payload.total_epochs,
                        loss=payload.loss,
                        map50=payload.map50,
                        state=cv_pb2.JobState.RUNNING,
                    )
                elif kind == "done":
                    model_id = self._publish_artifact(payload, spec)  # type: ignore[arg-type]
                    yield cv_pb2.TrainingProgress(
                        job_id=job_id,
                        epoch=spec.epochs,
                        total_epochs=spec.epochs,
                        loss=last.loss if last else 0.0,
                        map50=last.map50 if last else 0.0,
                        state=cv_pb2.JobState.SUCCEEDED,
                        message=(
                            f"trained model saved as {model_id!r}; it now shows in ListModels -- "
                            f"promote it via PromoteModel to make it the live default"
                        ),
                    )
                    return
                elif kind == "cancelled":
                    LOGGER.info("StartTraining job=%s cancelled", job_id)
                    return
                elif kind == "error":
                    yield cv_pb2.TrainingProgress(
                        job_id=job_id,
                        total_epochs=spec.epochs,
                        state=cv_pb2.JobState.FAILED,
                        message=f"training failed: {payload}",
                    )
                    return
        finally:
            # Whatever ends this generator (return, client cancel, exception),
            # make sure the worker is told to stop and reaped best-effort.
            cancel_event.set()
            thread.join(timeout=5)

    def _publish_artifact(self, best_weights: Path, spec: "trainer.TrainingSpec") -> str:
        """Copy the produced `best.pt` into the model dir under a new id and
        register it so `ListModels`/`PromoteModel` see it immediately.

        NOT auto-promoted -- the operator promotes deliberately. On restart
        `discover_roster` re-finds the file on disk anyway; `register()` just
        avoids needing a restart.
        """
        model_id = trainer.output_model_id(spec.dataset_id, spec.epochs)
        model_dir = self._model_dir if self._model_dir is not None else _MODEL_SEARCH_DIR
        destination = Path(model_dir) / model_id
        shutil.copyfile(best_weights, destination)
        LOGGER.info("StartTraining job=%s wrote artifact %s", spec.job_id, destination)
        if self._registry is not None and hasattr(self._registry, "register"):
            self._registry.register(model_id, str(destination))
        else:
            LOGGER.info(
                "no registry to register %r into; it will be discovered on the next restart",
                model_id,
            )
        return model_id

    def ListModels(self, request, context):
        """Report the registry roster as a `ModelList`.

        One `ModelInfo` per known model id: ``stage="active"`` for the
        current default (the promoted/routed-to-by-default model),
        ``"available"`` for the rest; ``version`` empty (the registry tracks
        no per-model version today); ``metrics`` empty. Returns an empty list
        (never aborts) when no registry is configured (echo mode / no model).
        """
        if self._registry is None:
            return cv_pb2.ModelList()

        active_id = self._registry.default_id
        models = [
            cv_pb2.ModelInfo(
                id=model_id,
                version="",
                stage="active" if model_id == active_id else "available",
            )
            for model_id in sorted(self._registry.roster)
        ]
        return cv_pb2.ModelList(models=models)

    def PromoteModel(self, request, context):
        """Make `request.id` the registry's active/default model + persist it.

        On success re-points the shared registry default (so subsequent
        `DetectStream` default-model resolution uses it) and writes the
        active-model marker so the choice survives a restart, returning
        ``Ack{ok:true}``. An unknown id -- one the registry roster does not
        know -- is a normal, reported outcome: ``Ack{ok:false, message}``,
        NOT an abort. A missing registry is likewise reported, not aborted.
        """
        model_id = request.id
        if self._registry is None:
            return cv_pb2.Ack(
                ok=False, message="no model registry configured (no model loaded on this host)"
            )
        if not model_id:
            return cv_pb2.Ack(ok=False, message="model id must not be empty")

        if not self._registry.promote(model_id):
            return cv_pb2.Ack(
                ok=False,
                message=(
                    f"unknown model id {model_id!r}; not in the registry roster "
                    f"{sorted(self._registry.roster)} -- rsync the model artifact into the "
                    f"cv-service model directory first"
                ),
            )

        if self._model_dir is not None:
            try:
                write_active_model(self._model_dir, model_id, request.version)
            except OSError as exc:  # pragma: no cover - unusual FS error
                # The in-memory promotion already took effect; only the
                # restart-survival guarantee is lost. Report it honestly
                # rather than pretend it fully succeeded.
                LOGGER.warning("promoted %r in memory but could not persist marker (%s)", model_id, exc)
                return cv_pb2.Ack(
                    ok=True,
                    message=f"promoted {model_id!r} (WARNING: not persisted, will not survive restart: {exc})",
                )

        return cv_pb2.Ack(ok=True, message=f"promoted {model_id!r} to the active/default model")


def serve(port: int = DEFAULT_PORT) -> grpc.Server:
    """Build, start, and return a gRPC server bound to ``port``.

    Configured with ``_KEEPALIVE_SERVER_OPTIONS`` (see module-level comment)
    so this server tolerates and reciprocates the client channel's HTTP/2
    keepalive pings instead of GOAWAY-ing it or leaving a dead client's
    stream thread parked indefinitely.
    """
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10), options=_KEEPALIVE_SERVER_OPTIONS)
    # Build the registry ONCE and share it between both servicers, so
    # `Training.PromoteModel` re-points the very registry the inference
    # `DetectStream` path routes against -- one source of truth. `None`
    # (no model loaded) is passed through explicitly: `InferenceServicer`
    # degrades to echo, `TrainingServicer` reports an empty roster.
    registry = _build_default_registry()
    cv_pb2_grpc.add_InferenceServicer_to_server(InferenceServicer(registry=registry), server)
    cv_pb2_grpc.add_TrainingServicer_to_server(
        TrainingServicer(
            registry=registry, model_dir=_MODEL_SEARCH_DIR, dataset_dir=_DATASET_SEARCH_DIR
        ),
        server,
    )
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
