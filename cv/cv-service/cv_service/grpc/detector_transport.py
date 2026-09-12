"""The wire half of `RemoteDetector` (CV-ORCHESTRATION wave W4, §4.9).

`cv_service/orchestration/detector.py`'s `PoolDetectorClient` holds no wire
knowledge at all -- ordering, failover and per-target accounting live there,
against the `RemoteDetector` Protocol only. This module is that Protocol's
one production implementation: one gRPC channel + `DetectorStub` per
configured target, translating `grpc.RpcError` into the two outcomes the
pool distinguishes (`DetectorBusy` -- it answered, and said no --
vs. `DetectorUnavailable` -- it did not answer at all).

Deliberately independent of the `cv` optional dependency group at import
time (unlike `cv_service.inference.detector`, which needs `cv2`/`numpy` at
module scope): a `tracker`-role process that only ever talks to REMOTE
detectors has no reason to have YOLO/torch installed locally at all. The one
place this module needs `cv_service.inference.detector.Detection` (to hand
a wire `Detection` back in the exact plain-value shape `LocalDetectorClient`
already returns) is a lazy import inside `detect()`, mirroring
`grpc/servicers.py`'s own "the `cv` extra is only needed once a caller
actually asks for a real detection" posture.
"""

from __future__ import annotations

import logging
import sys
from pathlib import Path
from typing import Optional

import grpc

from cv_service.config import Settings
from cv_service.orchestration.detector import (
    LOCAL_TARGET,
    DetectorBusy,
    DetectorPass,
    DetectorUnavailable,
    FramePayload,
)
from cv_service.tracking.engines.base import Box

# Same proto-relative sys.path bootstrap `grpc/servicers.py`/`detector_servicer.py`
# use -- duplicated rather than relied on as an import-order side effect, so this
# module is independently importable.
_GEN_DIR = Path(__file__).resolve().parent.parent / "gen"
if str(_GEN_DIR) not in sys.path:
    sys.path.insert(0, str(_GEN_DIR))

from vision.v1 import cv_pb2, cv_pb2_grpc  # noqa: E402 - after the sys.path bootstrap above

LOGGER = logging.getLogger("cv_service.grpc.detector_transport")

# HTTP/2 keepalive for this CLIENT channel, matching `adapter-cv-grpc`'s own
# defaults for the exact same reason (`cv/grpc/MODULE.md`'s `GrpcCvSettings.
# defaults()`: `keepAliveTime=20s, keepAliveTimeout=5s, keepAliveWithoutCalls=
# true`) -- a tracker's channel to a pooled detector is no less likely to sit
# behind a flaky link than the Java backend's channel to cv-service is, and
# `grpc/server.py`'s own `_KEEPALIVE_SERVER_OPTIONS` already requires
# `keepalive_permit_without_calls` from a well-behaved client (its
# `min_ping_interval_without_data_ms=10000` floor is comfortably inside this
# 20s interval). Consistent tuning across every gRPC client this platform
# runs, not a new number invented for this one.
_KEEPALIVE_CLIENT_OPTIONS = [
    ("grpc.keepalive_time_ms", 20000),
    ("grpc.keepalive_timeout_ms", 5000),
    ("grpc.keepalive_permit_without_calls", 1),
]


class GrpcRemoteDetector:
    """One pooled `Detector` target, reached over gRPC.

    One channel + stub for this object's whole lifetime -- built once by
    `remote_detectors()` below, held by the process-wide `RemoteDetector`
    list `grpc/servicers.py` builds ONCE (never per frame, never per
    stream): §4.9's affinity is the CALLER's ordered-list mechanism, not
    anything this class does.
    """

    def __init__(self, target: str, *, settings: Settings, stub: object = None) -> None:
        self.target = target
        self._settings = settings
        # `stub=` is a test seam (mirrors this codebase's `detector=`/
        # `registry=`/`pull_source_open=` convention): when supplied, no real
        # channel is ever built, so a translation test needs no server.
        if stub is not None:
            self._channel = None
            self._stub = stub
        else:
            self._channel = grpc.insecure_channel(target, options=_KEEPALIVE_CLIENT_OPTIONS)
            self._stub = cv_pb2_grpc.DetectorStub(self._channel)

    def detect(self, payload: FramePayload, roi: Optional[Box] = None) -> DetectorPass:
        """One `Detector.Detect` call. Raises `DetectorBusy` on
        `RESOURCE_EXHAUSTED`, `DetectorUnavailable` on every other gRPC
        failure (unreachable, timed out, internal, ...) -- the two outcomes
        `PoolDetectorClient.detect()` distinguishes (refused vs. did not
        answer). Deadline is `settings.detector_timeout_millis`, matching
        `adapter-cv-grpc`'s own `RESPONSE_TIMEOUT_SECONDS` (2s default): a
        pooled hop must give up on a detector before the Java caller gives up
        on the tracker, never after.
        """
        request = cv_pb2.DetectRequest(
            stream_id=payload.stream_id,
            sequence=payload.sequence,
            width=payload.width,
            height=payload.height,
            encoding=cv_pb2.ImageEncoding.Value(payload.encoding),
            data=payload.data,
            model_id=payload.model_id,
            model_version=payload.model_version,
            confidence_threshold=payload.confidence_threshold,
        )
        if roi is not None:
            request.roi.CopyFrom(cv_pb2.BoundingBox(x=roi.x, y=roi.y, width=roi.width, height=roi.height))

        deadline_seconds = self._settings.detector_timeout_millis / 1000.0
        try:
            response = self._stub.Detect(request, timeout=deadline_seconds)
        except grpc.RpcError as exc:
            code = exc.code() if hasattr(exc, "code") else None
            details = exc.details() if hasattr(exc, "details") else str(exc)
            if code == grpc.StatusCode.RESOURCE_EXHAUSTED:
                raise DetectorBusy(f"{self.target}: {details}") from exc
            code_name = code.name if code is not None else "UNKNOWN"
            raise DetectorUnavailable(f"{self.target}: {code_name} {details}") from exc

        from cv_service.inference.detector import Detection

        return DetectorPass(
            detections=[
                Detection(
                    label=wire.label,
                    confidence=wire.confidence,
                    x=wire.box.x,
                    y=wire.box.y,
                    width=wire.box.width,
                    height=wire.box.height,
                )
                for wire in response.detections
            ],
            inference_millis=response.inference_millis,
            # This object's own `wait_ms`/`served_by`/`hops` are never read
            # back by `PoolDetectorClient.detect()` (it measures its own wall
            # time across the whole attempt sequence and stamps its own
            # `served_by`/`hops` on the `DetectorPass` it actually returns) --
            # filled in honestly anyway for a caller that uses this class
            # directly, e.g. a test.
            wait_ms=0.0,
            roi=roi is not None,
            served_by=self.target,
        )

    def close(self) -> None:
        if self._channel is not None:
            self._channel.close()


class _LocalPlaceholder:
    """The literal `local` entry in an ordered target list.

    `PoolDetectorClient._attempt` special-cases `target.target ==
    LOCAL_TARGET` and routes straight to its own embedded
    `LocalDetectorClient`, so this object's `detect()` is structurally
    unreachable -- it exists only so the `local` slot has a
    `RemoteDetector`-shaped `.target` for `remote_detectors()`'s ordered
    list to carry, with no channel and no wire knowledge behind it.
    """

    target = LOCAL_TARGET

    def detect(self, payload: FramePayload, roi: Optional[Box] = None) -> DetectorPass:  # pragma: no cover
        raise AssertionError(
            "PoolDetectorClient never calls detect() on the 'local' placeholder target"
        )


def remote_detectors(settings: Settings) -> "list":
    """`settings.detector_targets` -> the ordered `RemoteDetector` list a
    `PoolDetectorClient` tries, first to last (§4.9). Built ONCE per process
    by `grpc/servicers.py`, not per session or per frame -- each
    `GrpcRemoteDetector` holds one channel for its whole lifetime.
    """
    return [
        _LocalPlaceholder() if target == LOCAL_TARGET else GrpcRemoteDetector(target, settings=settings)
        for target in settings.detector_targets
    ]
