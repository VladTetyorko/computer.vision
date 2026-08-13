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
* ``Inference.DetectPulled`` (MEDIA-SOT-PLAN wave M3, docs/plans/active/
  MEDIA-SOT-PLAN.md §5.1/§8) is the worker-pull counterpart: the client sends
  a declarative ``PullControl`` (restated on every message, same self-healing
  doctrine as ``TrackingConfig``) instead of frame bytes, and this worker
  dials the pulled RTSP source itself (``cv_service.pull.source``), decodes
  it on its own deadline schedule (``cv_service.pull.loop`` -- the ported
  rate-control sampler + D8 latest-wins decode) and anchors a local capture
  clock (``cv_service.pull.clock``). Every SERVED frame is translated into a
  synthetic ``FrameRequest`` and run through the exact same ``_handle_
  request``/``_tracked_response``/``SessionRegistry``/``ModelRegistry``/
  ``InferenceGate`` machinery ``DetectStream`` uses -- ``DetectPulled`` adds
  no second inference path, only a second way frames arrive at the first
  one -- then the response is decorated with fields 16-21, which are zero in
  push mode and populated here.
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
  mechanism `docs/plans/done/CV-TRAINING-V2-PLAN.md` §2 adds in place of a manual rsync.
  The zip-landing/path-safety logic lives in ``cv_service/training/dataset.py``;
  this method only reads the wire stream and translates the result.
  ``trainer.py`` is untouched by it: the landed directory is byte-identical
  to what a manual rsync would have produced.

Requires the generated stubs under ``cv_service/gen`` - run
``scripts/gen_proto.sh`` first (see README.md). Generated code is never
committed.
"""

from __future__ import annotations

import dataclasses
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

from cv_service.config import DEFAULT_CONFIDENCE, DEFAULT_MAX_UPLOAD_BYTES, Settings
from cv_service.inference.concurrency import InferenceGate, LatestOnlyMailbox, process_gate
from cv_service.pull.clock import CaptureClock
from cv_service.pull.loop import PullDecodeLoop, PullStalledError
from cv_service.pull.source import PullSource, PullSourceError
from cv_service.tracking import params as tracking_params
from cv_service.tracking.engines.base import Box, CameraPose
from cv_service.tracking.registry import TrackerRegistry
from cv_service.tracking.session import FrameOutcome, StreamTrackingSession
from cv_service.tracking.sessions import SessionRegistry
from cv_service.tracking.track import STATE_COASTING, STATE_CONFIRMED
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
        # motion_engine_id (field 8, TRACKING-V2-PLAN wave C2), appearance_
        # engine_id (field 9, wave C3), memory_ttl_millis (field 10, wave C4
        # -- `params.py`'s `resolve()` is what turns the `<=0` sentinel into
        # a number, same as `verify_every_millis`/`max_age_frames` above).
        motion_engine_id=message.motion_engine_id,
        appearance_engine_id=message.appearance_engine_id,
        memory_ttl_millis=message.memory_ttl_millis,
    )


def _camera_pose_from_wire(message: "cv_pb2.CameraPose") -> CameraPose:
    """`cv_pb2.CameraPose` -> the plain `CameraPose` (`engines/base.py`).

    No `HasField` check: every field this reads is a scalar whose proto3
    zero already means what the plain type's own default means (`hfov_
    degrees == 0` -> "unknown" either way), so an absent `camera_pose` on
    the wire and an explicitly all-zero one map to the identical value.
    """
    return CameraPose(
        yaw_degrees=message.yaw_degrees,
        pitch_degrees=message.pitch_degrees,
        roll_degrees=message.roll_degrees,
        hfov_degrees=message.hfov_degrees,
        vfov_degrees=message.vfov_degrees,
        timestamp_millis=message.pose_timestamp_millis,
    )


def _report_threshold_for(request: "cv_pb2.FrameRequest") -> float:
    """The operator's threshold for this frame, with the detector's own default
    standing in for an unset wire field -- the same `or DEFAULT_CONFIDENCE`
    fallback `YoloDetector.detect` applies, kept in step deliberately."""
    return request.confidence_threshold or DEFAULT_CONFIDENCE


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
        # TRACKING-V2-PLAN wave C4 (fields 10/11) -- read from `TrackedBox`
        # itself, not from `track`: a recovery is a fact about THIS FRAME,
        # not about the persistent `Track`, and `box.identity_confidence`/
        # `.dormant_millis` are already `0.0`/`0` on every frame that is not
        # the exact one a recovery happened on (`session.py`'s `TrackedBox`
        # docstring). Setting proto3 scalar zeros here is a no-op on the
        # wire either way, so this needs no `track is not None` guard.
        identity_confidence=box.identity_confidence,
        dormant_millis=box.dormant_millis,
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


def _reportable(box: "object", report_threshold: float) -> bool:
    """Whether one tracked box belongs in the response the operator sees.

    The counterpart of the low detector floor (`cv_service/config.py`'s
    `DEFAULT_DETECT_FLOOR`): the detector now emits weak boxes so the
    associators' low-confidence stage has something to work with, and this is
    where the operator's own `confidence_threshold` is applied instead.

    A weak box survives on ONE ground -- it is carrying an identity that has
    already been earned. `CONFIRMED`/`COASTING` mean the track cleared
    `min_hits` detector confirmations, so "0.18, and it is the thing we have
    been following for two seconds" is a different claim from "0.18, and it
    just appeared". `TENTATIVE` deliberately does not qualify: that is exactly
    the noise a lower floor produces more of, and letting it through would
    trade a recall win for a screen full of flicker.
    """
    if box.confidence >= report_threshold:
        return True
    track = box.track
    return track is not None and track.state in (STATE_CONFIRMED, STATE_COASTING)


def _tracked_response(
    request: "cv_pb2.FrameRequest", outcome: FrameOutcome, report_threshold: float = 0.0
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
        detections=[
            _tracked_detection(box)
            for box in outcome.boxes or []
            if _reportable(box, report_threshold)
        ],
        inference_millis=outcome.inference_millis,
        tracker_millis=outcome.tracker_millis,
        detector_ran=outcome.detector_ran,
        tracker_engine_id=outcome.engine_id,
        locked_track_id=outcome.locked_track_id,
        detector_reason=cv_pb2.DetectorReason.Value(outcome.detector_reason),
        motion_millis=outcome.motion_millis,
        motion_engine_id=outcome.motion_engine_id,
        detector_roi=outcome.detector_roi,
    )


def _frame_loader(request: "cv_pb2.FrameRequest") -> Callable[[], Any]:
    """A memoized decoder for this frame's pixels, for the FOLLOW path and
    (TRACKING-V2-PLAN wave C5c) the ROI re-detection pass.

    Lazy for two reasons: `ASSOCIATE` never needs pixels at all, and
    `cv_service.inference.detector` imports `cv2`/`numpy` at module scope --
    importing it here would break this module's (and hence
    `python -m cv_service.grpc.server`'s) ability to start without the `cv`
    extra. Memoized because a FOLLOW verify frame touches the frame twice
    (re-anchor plus the response), and decoding a JPEG twice for that would
    be a real, avoidable per-frame cost -- wave C5c's `_run_roi_detector`
    shares this SAME instance (built once per frame in `_handle_request`)
    rather than decoding a third time for its own crop.
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


# A crop below this many pixels on either side cannot be a useful detector
# input -- TRACKING-V2-PLAN wave C5c's own floor, independent of `Box.valid`
# (which is a NORMALIZED-space check; this one is pixel-space, after
# rounding, which is where a crop can round down to nothing even though the
# normalized `Box` it came from looked fine).
_ROI_MIN_PIXELS = 2


def _crop_for_roi(image: Any, roi: Box) -> "Optional[tuple[Any, int, int]]":
    """Normalized `roi` -> a pixel crop of `image` (shape `(H, W, 3)`),
    rounding to the nearest pixel (TRACKING-V2-PLAN wave C5c).

    Returns `(cropped_image, width, height)`, or `None` for a crop that
    rounds down to fewer than `_ROI_MIN_PIXELS` on either side -- a
    degenerate, unusable detector input, not a real one (P5). Coordinates
    are clamped into `[0, width]`/`[0, height]` defensively: `roi` itself is
    already frame-clamped by `session.py`'s `_roi_box`, but this function
    makes no assumption about its caller.
    """
    height, width = image.shape[0], image.shape[1]
    x0 = max(0, min(width, round(roi.x * width)))
    y0 = max(0, min(height, round(roi.y * height)))
    x1 = max(0, min(width, round((roi.x + roi.width) * width)))
    y1 = max(0, min(height, round((roi.y + roi.height) * height)))
    if x1 - x0 < _ROI_MIN_PIXELS or y1 - y0 < _ROI_MIN_PIXELS:
        return None
    return image[y0:y1, x0:x1], x1 - x0, y1 - y0


def _map_roi_detection(detection: Any, roi: Box) -> Any:
    """One detection, reported in the CROP's own normalized `[0, 1]` space,
    mapped back to full-frame normalized coordinates (TRACKING-V2-PLAN wave
    C5c) -- the coordinate round-trip this wave's crop-and-re-detect pass
    lives or dies on.

    `roi` is itself already in full-frame coordinates (`session.py`'s
    `_roi_box`/`_roi_rescue`, `Box`'s own docstring), so a point at the
    crop's own origin maps to `roi`'s origin, and the crop's own unit square
    maps onto exactly `roi`'s own extent -- plain affine, no rotation:
    ``full = roi.origin + crop_relative * roi.extent``.
    """
    return dataclasses.replace(
        detection,
        x=roi.x + detection.x * roi.width,
        y=roi.y + detection.y * roi.height,
        width=detection.width * roi.width,
        height=detection.height * roi.height,
    )


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


# ------------------------------------------------------------- DetectPulled
#
# Everything below supports `InferenceServicer.DetectPulled` only. Kept
# module-level (not nested in the class) for the same reason `_StreamReader`
# above is: each is a focused, independently-testable piece of one RPC's
# machinery, not servicer state.


def _default_pull_source_open(url: str, **kwargs: Any) -> PullSource:
    """The real `PullSource` factory `DetectPulled` uses when no
    `pull_source_open=` was injected -- a thin, lazy-imported wrapper so
    `cv_service.grpc.servicers` stays importable without the `cv` extra
    (`cv_service.pull.source` only touches `cv2` inside `OpenCvPullSource`
    itself, but importing this wrapper eagerly would be one more reason to
    trip over that if it ever changed)."""
    from cv_service.pull.source import open_source

    return open_source(url, **kwargs)


def _resolve_pull_target_fps(message: "cv_pb2.PullControl", settings: Settings) -> float:
    """`PullControl.target_fps <= 0` -> the deployment default (§5.1 field 7:
    "the Java rate controller's output"; MEDIA-SOT-PLAN §7 -- that
    controller keeps running in Java and its output travels on this field,
    so <=0 here means "no opinion yet", not "stop detecting")."""
    return message.target_fps if message.target_fps > 0 else settings.pull_target_fps


def _resolve_pull_detect_width(message: "cv_pb2.PullControl", settings: Settings) -> int:
    """`PullControl.detect_width <= 0` -> the deployment default (§5.1 field 8)."""
    return message.detect_width if message.detect_width > 0 else settings.pull_max_width


def _downscale_for_detection(image: Any, detect_width: int) -> tuple[Any, int, int]:
    """Mirrors `DetectionFrameCodec`'s own rule (adapter-cv-grpc,
    `withDownscaled`): a frame wider than `detect_width` is downscaled to
    EXACTLY `detect_width`, aspect preserved, height rounded; a frame already
    at or under `detect_width` passes through byte-identical (only `>`, not
    `>=`, triggers the downscale). Returns `(image, width, height)`."""
    height, width = image.shape[0], image.shape[1]
    if detect_width <= 0 or width <= detect_width:
        return image, width, height
    import cv2

    scaled_height = round(height * detect_width / width)
    resized = cv2.resize(image, (detect_width, scaled_height), interpolation=cv2.INTER_AREA)
    return resized, detect_width, scaled_height


class _PullStreamIdMismatch(ValueError):
    """A later `PullControl` message named a different `stream_id` than the
    call's first message (§5.1 "Identity": a mismatch is `INVALID_ARGUMENT`)."""


class _PullControlState:
    """Thread-safe holder for a `DetectPulled` call's latest `PullControl`
    message.

    Every field is HOT except `source_url`/`rtsp_transport` (read from the
    FIRST message only, §5.1 "Ordering") -- restated on every message by the
    same self-healing doctrine `TrackingConfig` already uses (a message can
    be lost with no error/retry, so only a restated desired state survives
    that). Protobuf messages are never mutated in place here, only swapped
    (one fresh message object per stream item) -- so holding the lock only
    around the swap/read, not the whole message, is enough; no deep copy
    needed.
    """

    def __init__(self, first_message: "cv_pb2.PullControl") -> None:
        self._lock = threading.Lock()
        self._message = first_message

    def apply(self, message: "cv_pb2.PullControl") -> None:
        with self._lock:
            self._message = message

    def snapshot(self) -> "cv_pb2.PullControl":
        with self._lock:
            return self._message


class _PullControlReader:
    """Background thread draining the *rest* of a `DetectPulled` call's
    `PullControl` stream into `state`, mirroring `_StreamReader`'s role for
    `DetectStream`'s `FrameRequest` stream -- but control messages are
    applied IN PLACE (declarative desired state, always overwrite-to-latest),
    never dropped-and-counted the way `LatestOnlyMailbox` drops frames (D8
    is about decoded VIDEO frames; a superseded `PullControl` message is not
    a loss, its restated fields are exactly as valid the moment a newer one
    lands).

    `should_stop` becomes true when the control stream ends for ANY reason --
    an explicit `stop=true` message, the client half-closing (the iterator
    simply ends), or a read error -- so `DetectPulled`'s main loop has one
    signal to check regardless of *why* the call is ending (§5.1 "Teardown").
    `error` is `None` for the first two (clean ends) and set for the third,
    so the caller can tell "drain cleanly" apart from "abort and say why".
    """

    def __init__(
        self,
        request_iterator: Iterable["cv_pb2.PullControl"],
        stream_id: str,
        state: _PullControlState,
    ) -> None:
        self._iterator = request_iterator
        self._stream_id = stream_id
        self._state = state
        self._error: Optional[BaseException] = None
        self._closed = threading.Event()
        self._thread = threading.Thread(
            target=self._run, name="cv-pull-control-reader", daemon=True
        )
        self._thread.start()

    def _run(self) -> None:
        try:
            for message in self._iterator:
                if message.stream_id != self._stream_id:
                    self._error = _PullStreamIdMismatch(
                        f"PullControl.stream_id changed mid-call "
                        f"({self._stream_id!r} -> {message.stream_id!r})"
                    )
                    return
                self._state.apply(message)
                if message.stop:
                    return
        except Exception as exc:  # noqa: BLE001 - re-surfaced via `error`, never crashes this thread silently
            self._error = exc
        finally:
            self._closed.set()

    @property
    def error(self) -> Optional[BaseException]:
        return self._error

    @property
    def should_stop(self) -> bool:
        return self._closed.is_set()

    def stop(self) -> None:
        """Best-effort: nothing more to apply for a decode loop that's ending
        on its own (a source stall/error) -- same posture `_StreamReader.
        stop()` takes: a message that arrives right at teardown just isn't
        applied. Does not (and cannot) interrupt a blocking read already in
        progress on `request_iterator`; gRPC itself unblocks that once the
        call ends, same as `_StreamReader` relies on."""
        self._closed.set()


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
        session_registry: Optional[SessionRegistry] = None,
        pull_source_open: Optional[Callable[..., PullSource]] = None,
    ) -> None:
        self._inference_gate = inference_gate if inference_gate is not None else process_gate()
        # `DetectPulled` only -- injectable so tests can drive it against a
        # fake `PullSource` instead of a real RTSP/mediamtx dependency, same
        # "explicit injection beats a real backend" seam `detector=`/
        # `registry=` already are for DetectStream. Omitted -> the real
        # `cv_service.pull.source.open_source` (imported lazily here so this
        # class stays importable without the `cv` extra when nobody ever
        # calls DetectPulled).
        self._pull_source_open = pull_source_open if pull_source_open is not None else _default_pull_source_open
        self._warned_model_ids: set[str] = set()
        # TRACKING-V2-PLAN wave C5c -- log-once set for `_warn_roi_degraded`,
        # same shape as `_warned_model_ids` above.
        self._warned_roi_degradations: set[str] = set()
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
        # `StreamTrackingSession` pool keyed by `stream_id` (TRACKING-V2-PLAN
        # wave C5b, review finding B5) -- built eagerly, unlike the tracker
        # registry above: it is pure stdlib bookkeeping with nothing to probe
        # and no `cv` extra to defer, so there is no lazy-construction cost
        # to avoid. Injectable so tests can use a tiny grace window instead
        # of waiting on `CV_TRACK_SESSION_GRACE_MILLIS`'s real default.
        self._session_registry = (
            session_registry
            if session_registry is not None
            else SessionRegistry(
                grace_millis=self._settings.track_session_grace_millis,
                capacity=self._settings.track_session_capacity,
                session_factory=self._new_session,
            )
        )
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
        # only starts on the *rest* of the stream, not this one. It is also
        # the earliest point `stream_id` is known, which is why session
        # acquisition happens here rather than before this `next()` call.
        try:
            first_request = next(request_iterator)
        except StopIteration:
            return

        # Per-stream tracking state, POOLED by `stream_id` (TRACKING-V2-PLAN
        # wave C5b, review finding B5) rather than minted fresh per call: a
        # reconnecting stream resumes its book/gallery/lock instead of every
        # id in the scene restarting at 1. A blank `stream_id` never pools
        # (`SessionRegistry.acquire`'s own doc) -- construction stays free
        # either way, no engine exists until a frame asks for an active mode.
        stream_id = first_request.stream_id
        session = self._session_registry.acquire(stream_id)

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
            self._session_registry.release(stream_id, session)

    # --------------------------------------------------------- DetectPulled
    #
    # MEDIA-SOT-PLAN wave M3 (§5.1, §8). The worker dials `first_message.
    # source_url` itself and decodes it on its own schedule
    # (`cv_service.pull.loop.PullDecodeLoop`), instead of receiving frame
    # bytes over the RPC -- everything from "translate one decoded frame into
    # a DetectionResponse" downward is the exact same machinery DetectStream
    # already uses (`_handle_request`/`_echo`, `SessionRegistry`,
    # `ModelRegistry`, `InferenceGate`): a served pull frame is packaged into
    # a synthetic `cv_pb2.FrameRequest` and handed to `_handle_request`
    # unchanged, then the response is decorated with fields 16-21.

    def DetectPulled(
        self,
        request_iterator: Iterable["cv_pb2.PullControl"],
        context: grpc.ServicerContext,
    ) -> Iterator["cv_pb2.DetectionResponse"]:
        try:
            first_message = next(request_iterator)
        except StopIteration:
            return
        if not first_message.source_url:
            context.abort(
                grpc.StatusCode.INVALID_ARGUMENT,
                "PullControl.source_url is required on the first message of a DetectPulled call",
            )
            return

        # `source_url`/`rtsp_transport` are read from THIS message only (§5.1
        # "Ordering") -- later messages restate every other (hot) field, see
        # `_PullControlState`/`_PullControlReader` below.
        stream_id = first_message.stream_id
        state = _PullControlState(first_message)
        control_reader = _PullControlReader(request_iterator, stream_id, state)
        session = self._session_registry.acquire(stream_id)
        # Same "never crash-loop for lack of a model" guard DetectStream
        # applies before it ever calls `_handle_request` -- `_run_detector`'s
        # non-registry branch assumes `self._detector` is set, which is false
        # in full echo mode (see that method).
        no_model = self._detector is None and self._registry is None

        pull_loop: Optional[PullDecodeLoop] = None
        try:
            source = self._open_pull_source(first_message)
        except Exception as exc:  # noqa: BLE001 - §5.1: an unopenable source always ends the call with
            # UNAVAILABLE, never an unhandled crash -- `PullSourceError`/`ImportError` (no `cv` extra) are
            # the expected cases; anything else is logged as unexpected rather than silently reported the
            # same way, so a real bug here is still visible in the logs, not just a client-facing status.
            if not isinstance(exc, (PullSourceError, ImportError)):
                LOGGER.exception(
                    "DetectPulled: unexpected error opening pulled source %r", first_message.source_url
                )
            control_reader.stop()
            self._session_registry.release(stream_id, session)
            context.abort(
                grpc.StatusCode.UNAVAILABLE,
                f"DetectPulled could not open {first_message.source_url!r}: {exc}",
            )
            return

        try:
            clock = CaptureClock(
                mode=self._settings.pull_clock_mode,
                reanchor_threshold_millis=self._settings.pull_clock_reanchor_threshold_millis,
            )
            pull_loop = PullDecodeLoop(
                source,
                target_fps=_resolve_pull_target_fps(first_message, self._settings),
                clock=clock,
                stall_timeout_millis=self._settings.pull_stall_timeout_millis,
            )
            sequence = 0
            for frame, captured_at_millis, diagnostics in pull_loop.frames(
                should_continue=lambda: not control_reader.should_stop and _context_active(context)
            ):
                snapshot = state.snapshot()
                # Hot (§5.1): `target_fps` may change on any PullControl
                # message; re-applied every served frame so a rate-controller
                # update (the Java side's own output, MEDIA-SOT-PLAN §7)
                # takes effect on the NEXT deadline, not just the next call.
                pull_loop.set_target_fps(_resolve_pull_target_fps(snapshot, self._settings))
                sequence += 1  # worker-minted, per pull, monotonic (§5.1 "Correlation")
                request = self._build_pull_frame_request(
                    stream_id=stream_id,
                    sequence=sequence,
                    captured_at_millis=captured_at_millis,
                    frame=frame,
                    snapshot=snapshot,
                )
                response = self._echo(request) if no_model else self._handle_request(request, session)
                # Pull-only diagnostics (§5.1 fields 16-21) -- zero in push
                # mode; the complete, honest accounting of a decode-and-infer
                # loop that now runs on another machine (D8).
                response.decode_millis = round(frame.decode_millis)
                response.source_fps = diagnostics.source_fps
                response.achieved_fps = diagnostics.achieved_fps
                response.dropped_frames = diagnostics.dropped_frames
                response.missed_deadlines = diagnostics.missed_deadlines
                response.capture_skew_millis = diagnostics.capture_skew_millis
                yield response
        except PullStalledError as exc:
            context.abort(grpc.StatusCode.UNAVAILABLE, str(exc))
            return
        finally:
            control_reader.stop()
            if pull_loop is not None:
                pull_loop.close()
            self._session_registry.release(stream_id, session)

        if control_reader.error is not None:
            if isinstance(control_reader.error, _PullStreamIdMismatch):
                context.abort(grpc.StatusCode.INVALID_ARGUMENT, str(control_reader.error))
            else:
                context.abort(
                    grpc.StatusCode.UNKNOWN, f"DetectPulled control stream failed: {control_reader.error}"
                )

    def _open_pull_source(self, first_message: "cv_pb2.PullControl") -> PullSource:
        transport = first_message.rtsp_transport or self._settings.pull_rtsp_transport
        return self._pull_source_open(
            first_message.source_url,
            backend=self._settings.pull_decoder,
            rtsp_transport=transport,
            open_timeout_millis=self._settings.pull_open_timeout_millis,
            read_timeout_millis=self._settings.pull_stall_timeout_millis,
        )

    def _build_pull_frame_request(
        self,
        *,
        stream_id: str,
        sequence: int,
        captured_at_millis: int,
        frame: "Any",
        snapshot: "cv_pb2.PullControl",
    ) -> "cv_pb2.FrameRequest":
        """One served `PulledFrame` -> the same `cv_pb2.FrameRequest` shape
        `DetectStream` receives over the wire, so `_handle_request` cannot
        tell the two transports apart. `detect_width` is applied HERE (not
        inside `cv_service.pull.loop`, which never imports `cv2`/`numpy`) --
        mirrors `DetectionFrameCodec`'s own downscale rule
        (adapter-cv-grpc/DetectionFrameCodec.java): only when wider than the
        target, aspect preserved, rounded height, so a frame already at or
        under `detect_width` is untouched.
        """
        import numpy as np

        detect_width = _resolve_pull_detect_width(snapshot, self._settings)
        image, width, height = _downscale_for_detection(frame.image, detect_width)
        data = np.ascontiguousarray(image).tobytes()
        return cv_pb2.FrameRequest(
            stream_id=stream_id,
            sequence=sequence,
            timestamp_millis=captured_at_millis,
            width=width,
            height=height,
            encoding=cv_pb2.IMAGE_ENCODING_BGR24,
            data=data,
            model_id=snapshot.model_id,
            model_version=snapshot.model_version,
            confidence_threshold=snapshot.confidence_threshold,
            tracking=snapshot.tracking,
            camera_pose=snapshot.camera_pose,
        )

    def _new_session(self) -> StreamTrackingSession:
        """Build a fresh `StreamTrackingSession`, wired identically regardless
        of whether `SessionRegistry` is minting it for a brand-new
        `stream_id` or as its concurrent-call fallback (see that class's
        `acquire()`). The one construction site, so both paths stay in sync
        by construction rather than by two call sites remembering to agree.
        """
        return StreamTrackingSession(
            settings=self._settings, registry_provider=self._resolve_tracker_registry
        )

    def _handle_request(
        self,
        request: "cv_pb2.FrameRequest",
        session: Optional[StreamTrackingSession] = None,
    ) -> "cv_pb2.DetectionResponse":
        try:
            if session is not None and self._sync_tracking(session, request):
                # Built ONCE and shared between `frame=` (the FOLLOW path's
                # own memoized decode) and `detect=`'s own ROI branch
                # (TRACKING-V2-PLAN wave C5c) -- `_frame_loader`'s docstring
                # makes the "no second decode" promise explicit.
                loader = _frame_loader(request)
                outcome = session.process(
                    # A LOCAL monotonic clock, deliberately, not
                    # `request.timestamp_millis`. Under DetectStream (push),
                    # that timestamp is stamped by the JVM -- another
                    # machine's clock, which can jump or go backwards across
                    # a reconnect; under DetectPulled (pull), it is minted by
                    # THIS process (`cv_service.pull.clock.CaptureClock`), so
                    # the "another machine's clock" distrust no longer
                    # literally applies there. The reason to keep reading
                    # `time.monotonic()` here is independent of either
                    # transport, though: the duty cycle is about how much
                    # WALL TIME THIS HOST has spent since its last detector
                    # pass, not about when a frame was captured -- a
                    # monotonic clock is also immune to the wall-clock
                    # corrections/NTP steps a capture timestamp is not, which
                    # matters for pull's own anchored `timestamp_millis` too.
                    now_millis=time.monotonic() * 1000.0,
                    detect=lambda roi=None: self._run_detector(
                        request, roi, loader, self._detect_floor_for(request)
                    ),
                    frame=loader,
                    pose=_camera_pose_from_wire(request.camera_pose),
                )
                if outcome.boxes is None:
                    return self._echo(request)
                return _tracked_response(request, outcome, _report_threshold_for(request))

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

    def _detect_floor_for(self, request: "cv_pb2.FrameRequest") -> float:
        """The confidence the detector runs at for a tracking-ACTIVE frame.

        `min` rather than a flat substitution: an operator who deliberately
        asked for something LOWER than the floor must get what they asked for,
        so the floor can only ever widen recall, never narrow it.
        """
        return min(self._settings.detect_floor, _report_threshold_for(request))

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

    def _run_detector(
        self,
        request: "cv_pb2.FrameRequest",
        roi: Optional[Box] = None,
        frame_loader: Optional[Callable[[], Any]] = None,
        confidence: Optional[float] = None,
    ) -> tuple[Optional[list], int]:
        """One detector pass, full-frame or over a crop. **The only place
        `InferenceGate` is taken.**

        Both model paths live here so the tracking session can spend a
        detector pass through one callable without knowing which one this
        servicer was built with -- and so the gate acquisition stays in a
        single, greppable location (TRACKING-ORCHESTRATION §3.1).

        `roi` (TRACKING-V2-PLAN wave C5c, review §4.6): a normalized crop
        around a track's predicted box. `None` -- every caller before this
        wave, and the overwhelming majority of calls after it -- takes the
        SAME full-frame branch below, byte-for-byte unchanged (P1).
        `cv_service.tracking.session.StreamTrackingSession` is the only
        caller that ever passes one (`_roi_rescue`, at most once per frame),
        and only alongside `frame_loader` -- the SAME memoized decoder
        `_handle_request` already built for this frame, so a ROI pass never
        pays for a second decode.
        """
        if roi is not None:
            return self._run_roi_detector(request, roi, frame_loader, confidence)
        if self._registry is not None:
            return self._detect_via_registry(request, confidence=confidence)
        self._warn_once_on_unknown_model(request.model_id)
        with self._inference_gate.acquire():
            return self._detector.detect(
                width=request.width,
                height=request.height,
                encoding=cv_pb2.ImageEncoding.Name(request.encoding),
                data=request.data,
                confidence_threshold=confidence or request.confidence_threshold or None,
            )

    def _run_roi_detector(
        self,
        request: "cv_pb2.FrameRequest",
        roi: Box,
        frame_loader: Optional[Callable[[], Any]],
        confidence: Optional[float] = None,
    ) -> tuple[Optional[list], int]:
        """Crop the ALREADY-decoded frame to `roi`, run the SAME detector
        (single or registry-routed composite, whichever this servicer was
        built with) on the crop, and map its detections back to full-frame
        coordinates (TRACKING-V2-PLAN wave C5c).

        Reuses `frame_loader` -- the servicer's own memoized decoder, built
        once per frame in `_handle_request` -- rather than decoding a second
        time; `session.py`'s module docstring makes the same "one decode"
        promise for the FOLLOW path, and a ROI pass shares the exact same
        frame. The crop is re-packaged as raw `IMAGE_ENCODING_BGR24` bytes
        (no JPEG round-trip) so it feeds the SAME `detect(width=, height=,
        encoding=, data=, ...)` shape every other call in this file uses --
        `_detect_via_registry`'s optional overrides exist for exactly this.

        Every degradation here -- a missing loader, a decode failure, or a
        crop that rounds down to nothing usable -- costs this ONE pass's
        detections and is logged at most once per servicer instance, never
        raised (P5): the frame's own full-frame pass already ran regardless.
        """
        if frame_loader is None:
            self._warn_roi_degraded("no frame loader available for this stream")
            return [], 0
        try:
            image = frame_loader()
        except Exception as exc:  # noqa: BLE001 - a bad frame costs this pass, not the stream
            self._warn_roi_degraded(f"frame decode failed ({exc})")
            return [], 0

        cropped = _crop_for_roi(image, roi)
        if cropped is None:
            self._warn_roi_degraded("degenerate ROI after clamping to the frame")
            return [], 0
        crop_image, crop_width, crop_height = cropped

        from cv_service.inference.detector import ENCODING_BGR24

        import numpy as np

        data = np.ascontiguousarray(crop_image).tobytes()

        if self._registry is not None:
            detections, millis = self._detect_via_registry(
                request, width=crop_width, height=crop_height, encoding=ENCODING_BGR24, data=data,
                confidence=confidence,
            )
        else:
            self._warn_once_on_unknown_model(request.model_id)
            with self._inference_gate.acquire():
                detections, millis = self._detector.detect(
                    width=crop_width,
                    height=crop_height,
                    encoding=ENCODING_BGR24,
                    data=data,
                    confidence_threshold=confidence or request.confidence_threshold or None,
                )
        if not detections:
            return detections, millis
        return [_map_roi_detection(detection, roi) for detection in detections], millis

    def _warn_roi_degraded(self, reason: str) -> None:
        """Log-once-per-servicer-instance for an ROI pass that could not run
        at all (TRACKING-V2-PLAN wave C5c) -- same posture `_warn_once_on_
        unknown_model` already takes for a per-frame condition that would
        otherwise spam the log once per frame for the life of a stream.
        """
        if reason in self._warned_roi_degradations:
            return
        self._warned_roi_degradations.add(reason)
        LOGGER.warning("cv-service ROI re-detection pass skipped: %s", reason)

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
        self,
        request: "cv_pb2.FrameRequest",
        *,
        width: Optional[int] = None,
        height: Optional[int] = None,
        encoding: Optional[str] = None,
        data: Optional[bytes] = None,
        confidence: Optional[float] = None,
    ) -> tuple[Optional[list], int]:
        """Registry-routed counterpart of the explicit-`detector` branch above.

        Resolves `request.model_id` (comma-separated for composite mode)
        against `self._registry`'s roster and runs every resolved member via
        `registry.detect_composite` (which does its own per-member
        `inference_gate` acquisition -- see that function's docstring).
        Returns ``(None, 0)`` when nothing resolved at all (registry present
        but even its own default failed to load) so the caller echoes this
        one frame, same as any other per-frame failure.

        `width`/`height`/`encoding`/`data` default to `request`'s own fields
        -- every call site before TRACKING-V2-PLAN wave C5c, and still the
        overwhelming majority after it. `_run_roi_detector` is the only
        caller that ever overrides them, with a crop's own pixel dimensions
        and raw bytes, while still routing through `request.model_id`/
        `request.confidence_threshold` -- a ROI pass uses the SAME model and
        threshold the full-frame pass would have.
        """
        from cv_service.inference.registry import detect_composite

        resolved = self._registry.resolve(request.model_id)
        if not resolved:
            return None, 0
        detections, inference_millis = detect_composite(
            resolved,
            gate=self._inference_gate,
            width=width if width is not None else request.width,
            height=height if height is not None else request.height,
            encoding=encoding if encoding is not None else cv_pb2.ImageEncoding.Name(request.encoding),
            data=data if data is not None else request.data,
            confidence_threshold=confidence or request.confidence_threshold or None,
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
    Phase 2 (`docs/plans/done/CV-TRAINING-PLAN.md` §6): a model rsync'd onto the host
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
    in place of a manual rsync (`docs/plans/done/CV-TRAINING-V2-PLAN.md` §2). The
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
        # (docs/plans/done/CV-TRAINING-V2-PLAN.md design decision D); the 2 GiB default
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
        upload for the same id (docs/plans/done/CV-TRAINING-V2-PLAN.md §2).

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
