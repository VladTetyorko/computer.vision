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

from cv_service.concurrency import InferenceGate, LatestOnlyMailbox, process_gate

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

    A `YoloDetector` (see `cv_service/inference.py`) is built once at
    construction time. If that fails for any reason -- `ultralytics` not
    installed, weights unavailable offline, etc. -- `detector` is `None` and
    every frame gets the original Phase 0 echo response instead: the service
    must never crash-loop for lack of a model.

    Per-frame inference failures (bad frame bytes, a transient model error)
    are also caught and degrade to an echo response for that one frame,
    rather than tearing down the whole bidi stream.

    **Concurrency (V-d):** when a detector is loaded, each `DetectStream`
    call spawns a `_StreamReader` background thread so frame receipt and
    inference overlap within that one stream (see its docstring), and every
    `detect()` call is gated by `inference_gate` (default: the process-wide
    `InferenceGate`, shared across every `InferenceServicer` instance/stream)
    so the number of *concurrent* inferences across all streams stays
    bounded regardless of how many streams are open. The echo-only path (no
    detector) skips both -- there's nothing to overlap or bound without a
    model in the loop, so it stays the plain synchronous loop it always was.
    """

    def __init__(
        self,
        detector: Optional["YoloDetector"] = None,
        *,
        inference_gate: Optional[InferenceGate] = None,
    ) -> None:
        self._detector = detector if detector is not None else _build_default_detector()
        self._warned_model_ids: set[str] = set()
        self._inference_gate = inference_gate if inference_gate is not None else process_gate()

    def DetectStream(
        self,
        request_iterator: Iterable["cv_pb2.FrameRequest"],
        context: grpc.ServicerContext,
    ) -> Iterator["cv_pb2.DetectionResponse"]:
        if self._detector is None:
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
        self._warn_once_on_unknown_model(request.model_id)

        try:
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
