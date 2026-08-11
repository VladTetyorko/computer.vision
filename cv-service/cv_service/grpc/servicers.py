"""gRPC servicers for the Vision CV service: wire <-> domain translation ONLY.

This module is the **sole place** that touches generated ``cv_pb2`` message
types -- every other cv-service module (``cv_service.inference``,
``cv_service.training``) works with plain Python values/dataclasses and
never imports the generated stubs. That invariant predates this module's
own split out of the former ``cv_service/server.py`` monolith (which also
carried training orchestration, dataset zip IO, and process bootstrap); this
file now narrows to exactly the translation role, with those other concerns
living in ``cv_service/training/`` and ``cv_service/grpc/server.py``.

* ``Inference.DetectStream`` runs real Ultralytics YOLO inference (see
  ``cv_service/inference/detector.py``) when the ``cv`` optional dependency
  group is installed and the model loads successfully. If it isn't
  installed, or the model can't be constructed (e.g. offline with no cached
  weights), the servicer logs one clear warning at startup and falls back to
  the original Phase 0 echo behavior: for every incoming ``FrameRequest`` it
  yields a ``DetectionResponse`` with the same stream_id / sequence /
  timestamp_millis / model_id / model_version and an empty ``detections``
  list. Either way the service never crash-loops for lack of a model.
* ``Training.ListModels`` / ``Training.PromoteModel`` are implemented against
  the same ``ModelRegistry`` the inference path uses: ``ListModels`` reports
  the roster (``stage="active"`` for the current default, ``"available"`` for
  the rest); ``PromoteModel`` re-points the default and persists the choice
  (``cv_service.training.marker``) so it survives a restart -- matching the
  offline-train -> rsync-in -> promote operational loop. ``StartTraining``
  runs a real Ultralytics YOLO fine-tune on an exported dataset (rsync'd or
  uploaded onto the host under ``CV_DATASET_DIR``), streams
  ``TrainingProgress`` per epoch, and writes the produced model into the
  model dir so this same ``ListModels``/``PromoteModel`` loop can surface +
  promote it. The job-lifecycle/queue-poll state machine itself lives in
  ``cv_service/training/orchestrator.py``; this method only translates that
  to/from the wire. It is device-agnostic (CPU here -- slow -- or CUDA where
  present) and never auto-promotes.
* ``Training.UploadDataset`` (client-streaming) receives a YOLO dataset
  archive over gRPC and lands it at ``<CV_DATASET_DIR>/<dataset_id>/``,
  atomically replacing any prior upload for the same id -- the delivery
  mechanism `docs/CV-TRAINING-V2-PLAN.md` §2 adds in place of a manual rsync.
  The zip-landing/path-safety logic lives in ``cv_service/training/dataset.py``;
  this method only reads the wire stream and translates the result.
  ``trainer.py`` is untouched by it: the landed directory is byte-identical
  to what a manual rsync would have produced.

Requires the generated stubs under ``cv_service/gen`` - run
``scripts/gen_proto.sh`` first (see README.md). Generated code is never
committed.
"""

from __future__ import annotations

import logging
import os
import shutil
import sys
import tempfile
import threading
import time
import uuid
from pathlib import Path
from typing import TYPE_CHECKING, Any, Callable, Iterable, Iterator, Optional

import grpc

from cv_service.config import DEFAULT_MAX_UPLOAD_BYTES, Settings
from cv_service.inference.concurrency import InferenceGate, LatestOnlyMailbox, process_gate
from cv_service.tracking import params as tracking_params
from cv_service.tracking.registry import TrackerRegistry
from cv_service.tracking.session import FrameOutcome, StreamTrackingSession
from cv_service.training import dataset, orchestrator, trainer
from cv_service.training.marker import write_active_model

if TYPE_CHECKING:  # pragma: no cover - import cycle avoidance, see build_default_registry
    from cv_service.inference.registry import ModelRegistry

# Sentinel distinguishing "no `registry` argument passed" (auto-build the
# default registry -- the production `InferenceServicer()` path and every
# pre-registry test) from an explicit `registry=None` (echo mode, no model:
# `cv_service.grpc.server.serve()` passes the already-built shared registry,
# which may be `None`, and must NOT trigger a second, wasteful build).
_UNSET_REGISTRY: object = object()

# `protoc`'s Python codegen emits imports rooted at the proto package path
# (e.g. `from vision.v1 import cv_pb2`), not at `cv_service.gen...`. So the
# generated tree's root (cv_service/gen) must be on sys.path, and the code
# below imports through that proto-relative path. Resolved from this file's
# grandparent (cv_service/grpc/servicers.py -> cv_service/ -> cv_service/gen)
# so it is independent of which module first imports it.
_GEN_DIR = Path(__file__).resolve().parent.parent / "gen"
if str(_GEN_DIR) not in sys.path:
    sys.path.insert(0, str(_GEN_DIR))

try:
    from google.protobuf import empty_pb2
    from vision.v1 import cv_pb2, cv_pb2_grpc
except ModuleNotFoundError as exc:  # pragma: no cover - operator guidance only
    raise ModuleNotFoundError(
        "Generated protobuf/gRPC stubs not found under "
        f"{_GEN_DIR}. Run scripts/gen_proto.sh from the cv-service/ "
        "directory (with grpcio-tools installed) before starting the "
        "server."
    ) from exc

LOGGER = logging.getLogger("cv_service.grpc.servicers")


# --------------------------------------------------------------- tracking
#
# `cv_service/tracking/` never imports `cv_pb2` (that package's own
# docstring states the rule); these four functions are the whole translation
# between `cv_pb2.TrackingConfig`/`TargetLock` and the plain
# `TrackingRequest`/`LockRequest`, and between a `FrameOutcome` and the
# response's tracking fields. Enum-valued fields cross as the proto enum's
# own VALUE NAMES, so `Name()`/`Value()` is the entire mapping and there is
# no lookup table to drift.


def _tracking_mode_name(mode: int) -> str:
    try:
        return cv_pb2.TrackingMode.Name(mode)
    except ValueError:
        # A mode number this build does not know can only come from a NEWER
        # client. `normalize_mode` turns it into OFF, which is the same
        # defensive posture proto3 additivity already gives an unset field.
        return tracking_params.MODE_UNSPECIFIED


def _lock_request_from_wire(message: "cv_pb2.TargetLock") -> tracking_params.LockRequest:
    box = None
    if message.HasField("box"):
        box = (message.box.x, message.box.y, message.box.width, message.box.height)
    return tracking_params.LockRequest(
        lock_seq=message.lock_seq,
        track_id=message.track_id,
        point_x=message.point_x,
        point_y=message.point_y,
        box=box,
        release=message.release,
    )


def _tracking_request_from_wire(
    message: "cv_pb2.TrackingConfig",
) -> tracking_params.TrackingRequest:
    return tracking_params.TrackingRequest(
        mode=_tracking_mode_name(message.mode),
        engine_id=message.engine_id,
        verify_every_millis=message.verify_every_millis,
        redetect_iou_threshold=message.redetect_iou_threshold,
        max_age_frames=message.max_age_frames,
        min_hits=message.min_hits,
        lock=_lock_request_from_wire(message.lock) if message.HasField("lock") else None,
    )


def _tracked_detection(box: "object") -> "cv_pb2.Detection":
    """One `TrackedBox` as a wire `Detection`.

    An untracked box (`track is None`) sets NO track field at all, so it
    serializes to exactly the three bytes-worth of fields it did before
    tracking existed -- `track_id == 0` is the wire's one spelling of
    "untracked" (TRACKING-ORCHESTRATION §6 rule 2).
    """
    detection = cv_pb2.Detection(
        label=box.label,
        confidence=box.confidence,
        box=cv_pb2.BoundingBox(
            x=box.box.x, y=box.box.y, width=box.box.width, height=box.box.height
        ),
    )
    track = box.track
    if track is not None:
        detection.track_id = track.track_id
        detection.track_state = cv_pb2.TrackState.Value(track.state)
        detection.source = cv_pb2.DetectionSource.Value(track.source)
        detection.velocity_x = track.velocity_x
        detection.velocity_y = track.velocity_y
        detection.track_age_frames = track.age_frames
    return detection


def _tracked_response(
    request: "cv_pb2.FrameRequest", outcome: FrameOutcome
) -> "cv_pb2.DetectionResponse":
    """A `DetectionResponse` carrying this frame's tracking telemetry.

    Only reached when tracking is ACTIVE. In `OFF` the servicer builds its
    pre-tracking response instead and sets none of these fields, so a stream
    that never asks for tracking (or an old Java client, whose `FrameRequest`
    carries no `tracking` at all) gets a **byte-identical** response to the
    one it got before this wave existed -- which is the acceptance criterion
    for proto3 additivity here, and why `detector_ran` is not asserted true
    on a frame where no duty cycle was ever running to report on.
    """
    return cv_pb2.DetectionResponse(
        stream_id=request.stream_id,
        sequence=request.sequence,
        timestamp_millis=request.timestamp_millis,
        model_id=request.model_id,
        model_version=request.model_version,
        detections=[_tracked_detection(box) for box in outcome.boxes or []],
        inference_millis=outcome.inference_millis,
        tracker_millis=outcome.tracker_millis,
        detector_ran=outcome.detector_ran,
        tracker_engine_id=outcome.engine_id,
        locked_track_id=outcome.locked_track_id,
        detector_reason=cv_pb2.DetectorReason.Value(outcome.detector_reason),
    )


def _frame_loader(request: "cv_pb2.FrameRequest") -> Callable[[], Any]:
    """A memoized decoder for this frame's pixels, for the FOLLOW path.

    Lazy for two reasons: `ASSOCIATE` never needs pixels at all, and
    `cv_service.inference.detector` imports `cv2`/`numpy` at module scope --
    importing it here would break this module's (and hence
    `python -m cv_service.grpc.server`'s) ability to start without the `cv`
    extra. Memoized because a FOLLOW verify frame touches the frame twice
    (re-anchor plus the response), and decoding a JPEG twice for that would
    be a real, avoidable per-frame cost.
    """
    decoded: list[Any] = []

    def load() -> Any:
        if not decoded:
            from cv_service.inference.detector import decode_frame

            decoded.append(
                decode_frame(
                    request.width,
                    request.height,
                    cv_pb2.ImageEncoding.Name(request.encoding),
                    request.data,
                )
            )
        return decoded[0]

    return load


def _build_default_registry() -> Optional["ModelRegistry"]:
    """Lazily import + build the production `ModelRegistry` from a freshly
    resolved `Settings`; `None` if the `cv` extra is absent or the default
    model can't be loaded. Only reached by `InferenceServicer`'s
    omitted-`registry` construction path (below) -- the production
    composition root, `cv_service.grpc.server.serve()`, always builds and
    passes a registry explicitly instead (see that module).
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
    return build_default_registry(Settings.from_env())


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
      `_build_default_registry()`): a `cv_service.inference.registry.ModelRegistry`
      routes each request's `model_id` (optionally a comma-separated
      composite list, see `registry.detect_composite`) to the matching
      locally-discovered model(s), falling back to the registry's own
      default for an unknown/absent id. See `cv_service/inference/registry.py`
      and MODULE.md "Model registry" for the full design.

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
        detector: object = None,
        *,
        inference_gate: Optional[InferenceGate] = None,
        registry: object = _UNSET_REGISTRY,
        settings: Optional[Settings] = None,
        tracker_registry: object = _UNSET_REGISTRY,
    ) -> None:
        self._inference_gate = inference_gate if inference_gate is not None else process_gate()
        self._warned_model_ids: set[str] = set()
        # `Settings` is needed for the `CV_TRACK_*` defaults `params.resolve`
        # falls back to; resolved fresh here when not supplied, exactly like
        # `YoloDetector` does (see cv_service/config.py's module docstring).
        self._settings = settings if settings is not None else Settings.from_env()
        # Omitted -> built LAZILY, on the first frame that actually asks for
        # an active tracking mode, so neither the OFF path nor a test that
        # never tracks pays for constructing (and probing) engines. The
        # production composition root, `serve()`, passes an already-probed
        # registry instead -- that is what makes the roster get logged at
        # STARTUP, per TRACKING-PLAN R3/R11, rather than on first use.
        self._tracker_registry: object = tracker_registry
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

        # Per-stream tracking state, created here for exactly the reason
        # `_StreamReader` is: it belongs to one bidi call, not to this
        # servicer, which every stream shares. Construction is free -- no
        # engine exists until a frame asks for an active tracking mode.
        session = StreamTrackingSession(
            settings=self._settings, registry_provider=self._resolve_tracker_registry
        )

        # Claim the first frame directly and synchronously -- see
        # _StreamReader's docstring for why the background reader thread
        # only starts on the *rest* of the stream, not this one.
        try:
            first_request = next(request_iterator)
        except StopIteration:
            return

        reader = _StreamReader(request_iterator)
        try:
            yield self._handle_request(first_request, session)
            while True:
                request = reader.next()
                if request is None:
                    return
                yield self._handle_request(request, session)
        finally:
            reader.stop()

    def _handle_request(
        self,
        request: "cv_pb2.FrameRequest",
        session: Optional[StreamTrackingSession] = None,
    ) -> "cv_pb2.DetectionResponse":
        try:
            if session is not None and self._sync_tracking(session, request):
                outcome = session.process(
                    # A LOCAL monotonic clock, deliberately, not
                    # `request.timestamp_millis`: the duty cycle is about how
                    # much wall time this host has spent since its last
                    # detector pass, and a capture timestamp comes from
                    # another machine's clock and can go backwards across a
                    # reconnect.
                    now_millis=time.monotonic() * 1000.0,
                    detect=lambda: self._run_detector(request),
                    frame=_frame_loader(request),
                )
                if outcome.boxes is None:
                    return self._echo(request)
                return _tracked_response(request, outcome)

            detections, inference_millis = self._run_detector(request)
            if detections is None:
                return self._echo(request)
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

    def _sync_tracking(
        self, session: StreamTrackingSession, request: "cv_pb2.FrameRequest"
    ) -> bool:
        """Fold this frame's restated `TrackingConfig` in; report whether
        tracking is active.

        `TrackingConfig` is restated on EVERY frame by design (TRACKING-PLAN
        invariant P2: the mailbox may drop a frame silently, so only a
        restated desired state is self-healing). The per-frame cost of that
        design is exactly the protobuf equality check below -- `resolve()`
        and any engine rebuild happen only when the config genuinely
        changed (TRACKING-ORCHESTRATION §4.2).
        """
        wire = request.tracking
        if wire != session.applied_wire_config:
            session.apply_config(_tracking_request_from_wire(wire), wire)
        return session.active

    def _run_detector(self, request: "cv_pb2.FrameRequest") -> tuple[Optional[list], int]:
        """One full detector pass. **The only place `InferenceGate` is taken.**

        Both model paths live here so the tracking session can spend a
        detector pass through one callable without knowing which one this
        servicer was built with -- and so the gate acquisition stays in a
        single, greppable location (TRACKING-ORCHESTRATION §3.1).
        """
        if self._registry is not None:
            return self._detect_via_registry(request)
        self._warn_once_on_unknown_model(request.model_id)
        with self._inference_gate.acquire():
            return self._detector.detect(
                width=request.width,
                height=request.height,
                encoding=cv_pb2.ImageEncoding.Name(request.encoding),
                data=request.data,
                confidence_threshold=request.confidence_threshold or None,
            )

    def _resolve_tracker_registry(self) -> Optional[TrackerRegistry]:
        """The shared `TrackerRegistry`, built on first actual use.

        `None` only when the `cv` extra is missing entirely, in which case
        every session degrades to OFF -- the same "never crash-loop for lack
        of a backend" posture the model registry already takes.
        """
        if self._tracker_registry is _UNSET_REGISTRY:
            try:
                from cv_service.tracking.registry import build_default_registry

                self._tracker_registry = build_default_registry(self._settings)
            except Exception as exc:  # noqa: BLE001 - never a dead stream
                LOGGER.warning(
                    "cv-service tracker registry unavailable (%s); streams requesting "
                    "tracking will fall back to detector-only behavior.",
                    exc,
                )
                self._tracker_registry = None
        return self._tracker_registry  # type: ignore[return-value]

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
        from cv_service.inference.registry import detect_composite

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
    routes against (wired identically in `cv_service.grpc.server.serve()`),
    so a promotion takes effect for subsequent default-model resolution
    immediately -- there is no second source of truth.

    `StartTraining` runs a real Ultralytics YOLO fine-tune on an exported
    dataset that was rsync'd/uploaded onto this host
    (`<CV_DATASET_DIR>/<dataset_id>/`), streams per-epoch progress, and
    writes the produced `best.pt` into the model directory under a new id so
    this same `ListModels`/`PromoteModel` loop can surface + promote it. It
    is device-agnostic (CUDA if present, else CPU -- slow on this Intel
    appliance, logged once) and never auto-promotes: the operator promotes
    deliberately. The job-lifecycle state machine itself lives in
    `cv_service/training/orchestrator.py`; see `cv_service/training/trainer.py`
    for the training core.

    `UploadDataset` (client-streaming) is the delivery mechanism that lands a
    dataset at `<CV_DATASET_DIR>/<dataset_id>/` over this same gRPC channel,
    in place of a manual rsync (`docs/CV-TRAINING-V2-PLAN.md` §2). The
    zip-landing/path-safety logic lives in `cv_service/training/dataset.py`;
    it never touches `trainer.py`/`StartTraining` -- the directory it
    produces is byte-identical to what a manual rsync would have produced,
    so `resolve_dataset_dir` keeps validating both the same way.
    """

    def __init__(
        self,
        *,
        registry: object = None,
        model_dir: Optional[Path] = None,
        dataset_dir: Optional[Path] = None,
        train_fn: Optional[trainer.TrainFn] = None,
        max_upload_bytes: int = DEFAULT_MAX_UPLOAD_BYTES,
    ) -> None:
        # `registry` may be a `ModelRegistry` or `None` (echo mode / no model
        # loaded) -- kept as `object` to avoid importing `ModelRegistry` at
        # module scope (it needs the `cv` extra; this servicer must not).
        self._registry = registry
        # Directory the active-model marker is persisted into AND where a
        # produced training artifact is written. `None` is a legitimate,
        # deliberately-preserved value (tests pass it explicitly to mean
        # "no marker persistence") -- resolved to `Settings.from_env().model_dir`
        # lazily, at point of use, only when a method actually needs a
        # concrete directory (see `_resolved_model_dir`) and none was given.
        self._model_dir = model_dir
        # Root under which `dataset_id` resolves to an exported YOLO dataset.
        # Same "resolve lazily at point of use" contract as `_model_dir`.
        self._dataset_dir = dataset_dir
        # Injectable trainer seam: default is the real (lazy-ultralytics)
        # `trainer.ultralytics_train`; tests inject a fast fake. Resolved lazily
        # in `StartTraining` so importing this module never touches ultralytics.
        self._train_fn = train_fn
        # Total content bytes accepted for one UploadDataset call before it
        # aborts RESOURCE_EXHAUSTED -- a realistic dataset is low tens of MB
        # (docs/CV-TRAINING-V2-PLAN.md design decision D); the 2 GiB default
        # is a generous, pinned safety cap, not a target. Overridable via
        # `CV_MAX_UPLOAD_BYTES` (see `Settings`/`cv_service/grpc/server.py`).
        self._max_upload_bytes = max_upload_bytes

    def _resolved_model_dir(self) -> Path:
        """`self._model_dir` if given, else `Settings.from_env().model_dir`.

        Only used where a concrete directory is actually required
        (`StartTraining`/`_publish_artifact`/`UploadDataset`) -- `PromoteModel`'s
        marker persistence deliberately keeps its own `self._model_dir is not
        None` check unresolved (see that method): a `None` there means "don't
        persist a marker", not "use the default directory."
        """
        return self._model_dir if self._model_dir is not None else Settings.from_env().model_dir

    def _resolved_dataset_dir(self) -> Path:
        """`self._dataset_dir` if given, else `Settings.from_env().dataset_dir`."""
        return (
            self._dataset_dir if self._dataset_dir is not None else Settings.from_env().dataset_dir
        )

    def StartTraining(
        self, request: "cv_pb2.TrainingJobSpec", context: grpc.ServicerContext
    ) -> Iterator["cv_pb2.TrainingProgress"]:
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

        The job-lifecycle/queue-poll state machine lives in
        `cv_service.training.orchestrator.run_training_job`; this method
        only resolves the dataset, wires the gRPC-specific callables
        (`context.is_active`/`context.add_callback`) into it, and translates
        each plain `JobEvent` it yields into a `cv_pb2.TrainingProgress`.
        """
        job_id = uuid.uuid4().hex
        base_model = request.base_model
        dataset_id = request.dataset_id
        epochs = request.epochs

        try:
            spec = trainer.build_spec(
                job_id=job_id,
                base_model=base_model,
                dataset_id=dataset_id,
                epochs=epochs,
                datasets_root=self._resolved_dataset_dir(),
                output_dir=self._resolved_model_dir(),
            )
        except dataset.DatasetNotFoundError as exc:
            LOGGER.warning("StartTraining job=%s: %s", job_id, exc)
            yield cv_pb2.TrainingProgress(
                job_id=job_id, state=cv_pb2.JobState.FAILED, message=str(exc)
            )
            return

        train_fn = self._train_fn if self._train_fn is not None else trainer.ultralytics_train

        def register_cancel_callback(callback) -> None:
            if hasattr(context, "add_callback"):
                context.add_callback(callback)

        for event in orchestrator.run_training_job(
            spec,
            job_id=job_id,
            train_fn=train_fn,
            is_context_active=lambda: _context_active(context),
            register_cancel_callback=register_cancel_callback,
            publish_artifact=self._publish_artifact,
        ):
            yield cv_pb2.TrainingProgress(
                job_id=job_id,
                epoch=event.epoch,
                total_epochs=event.total_epochs,
                loss=event.loss,
                map50=event.map50,
                state=_JOB_STATE_BY_EVENT_KIND[event.kind],
                message=event.message,
            )

    def _publish_artifact(self, best_weights: Path, spec: "trainer.TrainingSpec") -> str:
        """Copy the produced `best.pt` into the model dir under a new id and
        register it so `ListModels`/`PromoteModel` see it immediately.

        NOT auto-promoted -- the operator promotes deliberately. On restart
        `discover_roster` re-finds the file on disk anyway; `register()` just
        avoids needing a restart.
        """
        model_id = trainer.output_model_id(spec.dataset_id, spec.epochs)
        destination = self._resolved_model_dir() / model_id
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

    def UploadDataset(
        self,
        request_iterator: Iterable["cv_pb2.DatasetChunk"],
        context: grpc.ServicerContext,
    ) -> "cv_pb2.UploadAck":
        """Receive a streamed YOLO dataset archive and land it at
        `<CV_DATASET_DIR>/<dataset_id>/`, atomically replacing any prior
        upload for the same id (docs/CV-TRAINING-V2-PLAN.md §2).

        The concatenation of every `DatasetChunk.content`, in stream order,
        must be a ZIP of the frozen §5 layout (`data.yaml`, `images/<name>`,
        `labels/<stem>.txt`); `dataset_id` must be identical on every chunk.

        Two distinct failure postures, deliberately:
        - **Protocol-level `dataset_id` problems** (blank, changed mid-stream,
          or path-unsafe -- see `dataset.is_safe_dataset_id`) and an
          **oversize** upload (> `self._max_upload_bytes`) are
          `context.abort()`s (`INVALID_ARGUMENT` / `RESOURCE_EXHAUSTED`) --
          the caller sent a request this servicer will never be able to
          honor.
        - **Content problems** (zero chunks, a corrupt/unreadable zip, a
          zip-slip entry, or an extracted tree `trainer.resolve_dataset_dir`
          rejects) are *reported*, never aborted: `UploadAck{ok:false,
          message}` -- the same posture `PromoteModel` already takes for an
          unknown model id. The caller (a labeled dataset that failed to
          compose correctly) can retry without the RPC itself looking broken.

        `trainer.py`/`StartTraining` are never touched: after a successful
        upload, `<CV_DATASET_DIR>/<dataset_id>/` is byte-identical to what a
        manual rsync would have produced, so the manual path keeps working.
        """
        datasets_root = self._resolved_dataset_dir()
        datasets_root.mkdir(parents=True, exist_ok=True)

        dataset_id: Optional[str] = None
        bytes_received = 0
        chunk_count = 0

        tmp_fd, tmp_name = tempfile.mkstemp(prefix=".upload-", suffix=".zip", dir=datasets_root)
        zip_path = Path(tmp_name)
        try:
            with os.fdopen(tmp_fd, "wb") as tmp_zip:
                for chunk in request_iterator:
                    if dataset_id is None:
                        if not dataset.is_safe_dataset_id(chunk.dataset_id):
                            context.abort(
                                grpc.StatusCode.INVALID_ARGUMENT,
                                f"invalid dataset id {chunk.dataset_id!r}",
                            )
                        dataset_id = chunk.dataset_id
                    elif chunk.dataset_id != dataset_id:
                        context.abort(
                            grpc.StatusCode.INVALID_ARGUMENT,
                            f"dataset_id changed mid-stream ({dataset_id!r} -> {chunk.dataset_id!r})",
                        )

                    chunk_count += 1
                    bytes_received += len(chunk.content)
                    if bytes_received > self._max_upload_bytes:
                        context.abort(
                            grpc.StatusCode.RESOURCE_EXHAUSTED,
                            f"dataset upload exceeds the {self._max_upload_bytes}-byte cap",
                        )
                    tmp_zip.write(chunk.content)

            if chunk_count == 0:
                return cv_pb2.UploadAck(ok=False, message="no dataset chunks received")

            outcome = dataset.land_dataset(zip_path, datasets_root, dataset_id, bytes_received)
            return cv_pb2.UploadAck(
                ok=outcome.ok,
                dataset_id=outcome.dataset_id,
                bytes_received=outcome.bytes_received,
                file_count=outcome.file_count,
                message=outcome.message,
            )
        finally:
            zip_path.unlink(missing_ok=True)

    def ListModels(
        self, request: "empty_pb2.Empty", context: grpc.ServicerContext
    ) -> "cv_pb2.ModelList":
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

    def PromoteModel(
        self, request: "cv_pb2.ModelRefMsg", context: grpc.ServicerContext
    ) -> "cv_pb2.Ack":
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


_JOB_STATE_BY_EVENT_KIND = {
    "running": cv_pb2.JobState.RUNNING,
    "succeeded": cv_pb2.JobState.SUCCEEDED,
    "failed": cv_pb2.JobState.FAILED,
}
