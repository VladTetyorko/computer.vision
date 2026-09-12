"""`Detector`: the stateless RPC (CV-ORCHESTRATION wave W4, §4.9) plus its
wire transport (`GrpcRemoteDetector`) and the `InferenceServicer` wiring that
picks a session's detector placement.

Three postures, deliberately:

* `DetectorServicer.Detect()` is exercised through DIRECT calls (no real gRPC
  server) for every functional/failure-mode case below -- deterministic,
  fast, and the same "call the servicer method directly" posture every other
  `tests/grpc/test_*_servicer.py` file in this repo already uses.
* `GrpcRemoteDetector` is exercised against a FAKE stub (no real server) for
  its gRPC-status-code translation -- the "explicit injection beats a real
  backend" seam its own `stub=` constructor argument exists for.
* ONE test (`test_detect_and_grpc_remote_detector_round_trip_over_a_real_server`)
  runs both ends over a REAL in-process `grpc.server`, on `localhost:0` (an
  OS-assigned free port), to prove the whole stack -- wire encode, network,
  wire decode -- actually agrees with itself once, since every other test
  here deliberately avoids paying that cost per case.

The two concurrency tests (`test_detect_answers_resource_exhausted_...` and
`test_a_one_permit_gate_serves_sequential_requests_without_deadlocking`) use
real `threading.Thread`s and a real `InferenceGate`, matching this codebase's
general preference for exercising the real primitive over mocking it (see
`tests/inference/test_concurrency.py`).
"""

from __future__ import annotations

import threading
import time
from concurrent import futures

import grpc
import numpy as np
import pytest

from cv_service.config import Settings
from cv_service.grpc.detector_servicer import DetectorServicer, cv_pb2, cv_pb2_grpc
from cv_service.grpc.detector_transport import GrpcRemoteDetector, remote_detectors
from cv_service.grpc.servicers import InferenceServicer, _FrameDetect
from cv_service.inference.concurrency import InferenceGate
from cv_service.inference.detector import Detection
from cv_service.orchestration.detector import (
    DetectorBusy,
    DetectorUnavailable,
    FramePayload,
    LOCAL_TARGET,
    PoolDetectorClient,
)
from cv_service.tracking.engines.base import Box

WIDTH, HEIGHT = 8, 6
FRAME_BYTES = bytes(np.zeros((HEIGHT, WIDTH, 3), dtype=np.uint8).tobytes())

# Big enough that a quarter-frame ROI still clears `_ROI_MIN_PIXELS` (2px/side).
ROI_WIDTH, ROI_HEIGHT = 64, 48
ROI_FRAME_BYTES = bytes(np.zeros((ROI_HEIGHT, ROI_WIDTH, 3), dtype=np.uint8).tobytes())


class _AbortError(Exception):
    """Mirrors `tests/grpc/test_geolocation_servicer.py`'s own fake -- real
    `grpc.ServicerContext.abort()` raises after recording the status, and
    every servicer method here always follows an `abort()` call with an
    explicit `return` defensively, so a permissive fake must still raise."""

    def __init__(self, code, details):
        super().__init__(f"{code}: {details}")
        self.code = code
        self.details = details


class FakeContext:
    def __init__(self) -> None:
        self.aborted = None

    def abort(self, code, details):
        self.aborted = (code, details)
        raise _AbortError(code, details)


class FakeDetector:
    """Mirrors `tests/grpc/test_inspect.py`'s own `FakeDetector` shape."""

    model_name = "fake.pt"

    def __init__(self, *, detections=None, millis: int = 11, error: Exception | None = None) -> None:
        self._detections = detections if detections is not None else [Detection("car", 0.9, 0.1, 0.1, 0.2, 0.2)]
        self._millis = millis
        self._error = error
        self.calls: list[dict] = []

    def detect(self, **kwargs):
        self.calls.append(kwargs)
        if self._error is not None:
            raise self._error
        return self._detections, self._millis


class FakeRegistry:
    """`.resolve()` only -- `DetectorServicer._detect_via_registry` never
    calls anything else on it, matching `PoolDetectorClient`'s own posture of
    depending on Protocols rather than concrete types."""

    def __init__(self, members: "list[tuple[str, object]]") -> None:
        self._members = members

    def resolve(self, requested_model_id: str):
        return list(self._members)


def _full_frame_request(**overrides) -> "cv_pb2.DetectRequest":
    fields = dict(
        stream_id="stream-1",
        sequence=1,
        width=WIDTH,
        height=HEIGHT,
        encoding=cv_pb2.IMAGE_ENCODING_BGR24,
        data=FRAME_BYTES,
        model_id="fake.pt",
        model_version="v1",
        confidence_threshold=0.0,
    )
    fields.update(overrides)
    return cv_pb2.DetectRequest(**fields)


def _roi_request(**overrides) -> "cv_pb2.DetectRequest":
    fields = dict(
        stream_id="stream-1",
        sequence=1,
        width=ROI_WIDTH,
        height=ROI_HEIGHT,
        encoding=cv_pb2.IMAGE_ENCODING_BGR24,
        data=ROI_FRAME_BYTES,
        model_id="fake.pt",
    )
    fields.update(overrides)
    request = cv_pb2.DetectRequest(**fields)
    request.roi.CopyFrom(cv_pb2.BoundingBox(x=0.25, y=0.25, width=0.5, height=0.5))
    return request


# --- Detect(): full-frame + ROI, pinned `detector=` path ---------------------


def test_detect_full_frame_returns_detections_and_admission_facts():
    gate = InferenceGate(3)
    settings = Settings(detector_max_queue=5)
    detector = FakeDetector()
    servicer = DetectorServicer(detector=detector, inference_gate=gate, settings=settings)

    response = servicer.Detect(_full_frame_request(), FakeContext())

    assert [d.label for d in response.detections] == ["car"]
    assert response.detections[0].box.width == pytest.approx(0.2)
    assert response.inference_millis == 11
    assert response.model_id == "fake.pt"
    # The gate is fully released again by the time the response is built --
    # `admit()` was purely an admission check (see DetectorServicer.Detect's
    # own docstring), so occupancy/queue_depth read back to zero.
    assert response.occupancy == 0
    assert response.queue_depth == 0
    assert response.capacity == 3
    assert response.max_queue == 5
    # `_detect_pixels`'s pinned-detector path passes width/height/encoding
    # straight through for a full-frame request.
    assert detector.calls == [
        dict(width=WIDTH, height=HEIGHT, encoding="IMAGE_ENCODING_BGR24", data=FRAME_BYTES, confidence_threshold=None)
    ]


def test_detect_roi_crops_detects_on_the_crop_and_remaps_back_to_full_frame():
    from cv_service.grpc.servicers import _map_roi_detection

    # A detection reported in the CROP's own normalized space -- centered,
    # half the crop's extent on each side.
    crop_detection = Detection("car", 0.8, 0.25, 0.25, 0.5, 0.5)
    detector = FakeDetector(detections=[crop_detection])
    gate = InferenceGate(1)
    servicer = DetectorServicer(detector=detector, inference_gate=gate, settings=Settings())

    response = servicer.Detect(_roi_request(), FakeContext())

    roi = Box(x=0.25, y=0.25, width=0.5, height=0.5)
    expected = _map_roi_detection(crop_detection, roi)
    assert len(response.detections) == 1
    got = response.detections[0]
    assert got.box.x == pytest.approx(expected.x, abs=1e-3)
    assert got.box.y == pytest.approx(expected.y, abs=1e-3)
    assert got.box.width == pytest.approx(expected.width, abs=1e-3)
    assert got.box.height == pytest.approx(expected.height, abs=1e-3)
    # The crop, not the full frame, is what actually reached the detector.
    assert len(detector.calls) == 1
    call = detector.calls[0]
    assert call["width"] < ROI_WIDTH and call["height"] < ROI_HEIGHT


def test_detect_roi_reports_internal_on_a_degenerate_crop():
    # A roi that rounds down to nothing after clamping -- `_crop_for_roi`
    # returns None, and unlike the in-process ROI rescue pass (which still
    # has a full-frame pass to fall back on), this whole request has nothing
    # else to answer with.
    detector = FakeDetector()
    servicer = DetectorServicer(detector=detector, inference_gate=InferenceGate(1), settings=Settings())
    request = _roi_request()
    request.roi.CopyFrom(cv_pb2.BoundingBox(x=0.0, y=0.0, width=0.001, height=0.001))

    context = FakeContext()
    with pytest.raises(_AbortError):
        servicer.Detect(request, context)

    assert context.aborted[0] == grpc.StatusCode.INTERNAL
    assert detector.calls == []


# --- Detect(): registry-routed composite path ---------------------------------


def test_detect_via_registry_runs_every_resolved_member_and_concatenates():
    member_a = FakeDetector(detections=[Detection("car", 0.9, 0.0, 0.0, 0.1, 0.1)], millis=5)
    member_b = FakeDetector(detections=[Detection("boat", 0.7, 0.0, 0.0, 0.1, 0.1)], millis=9)
    registry = FakeRegistry([("a.pt", member_a), ("b.pt", member_b)])
    servicer = DetectorServicer(registry=registry, inference_gate=InferenceGate(2), settings=Settings())

    response = servicer.Detect(_full_frame_request(model_id="a.pt,b.pt"), FakeContext())

    # `detect_composite` tags labels once more than one model actually ran.
    assert sorted(d.label for d in response.detections) == ["a:car", "b:boat"]
    assert response.inference_millis == 14
    assert len(member_a.calls) == 1
    assert len(member_b.calls) == 1


# --- Detect(): reported failure modes, never a silent empty list -------------


def test_detect_reports_failed_precondition_when_no_model_is_available():
    servicer = DetectorServicer(inference_gate=InferenceGate(1), settings=Settings())

    context = FakeContext()
    with pytest.raises(_AbortError):
        servicer.Detect(_full_frame_request(), context)

    assert context.aborted[0] == grpc.StatusCode.FAILED_PRECONDITION


def test_detect_reports_failed_precondition_when_the_registry_resolves_nothing():
    servicer = DetectorServicer(registry=FakeRegistry([]), inference_gate=InferenceGate(1), settings=Settings())

    context = FakeContext()
    with pytest.raises(_AbortError):
        servicer.Detect(_full_frame_request(model_id="unknown.pt"), context)

    assert context.aborted[0] == grpc.StatusCode.FAILED_PRECONDITION


def test_detect_reports_internal_on_an_unexpected_detector_exception():
    detector = FakeDetector(error=RuntimeError("decoder exploded"))
    servicer = DetectorServicer(detector=detector, inference_gate=InferenceGate(1), settings=Settings())

    context = FakeContext()
    with pytest.raises(_AbortError):
        servicer.Detect(_full_frame_request(), context)

    assert context.aborted[0] == grpc.StatusCode.INTERNAL
    assert "decoder exploded" in context.aborted[1]


# --- Admission: RESOURCE_EXHAUSTED, and the deadlock-avoidance proof ---------


def test_detect_answers_resource_exhausted_when_the_admission_queue_is_already_full():
    """CV-ORCHESTRATION §4.9's whole point: a pooled caller has somewhere else
    to go, so a full queue is refused immediately rather than made to wait."""
    gate = InferenceGate(1)
    settings = Settings(detector_max_queue=1)
    servicer = DetectorServicer(detector=FakeDetector(), inference_gate=gate, settings=settings)

    # Hold the gate's one permit externally so every admit() call below has
    # to queue instead of proceeding straight through.
    holder_ready = threading.Event()
    release_holder = threading.Event()

    def hold_permit():
        with gate.acquire():
            holder_ready.set()
            release_holder.wait(timeout=5)

    holder = threading.Thread(target=hold_permit)
    holder.start()
    assert holder_ready.wait(timeout=2)

    # One Detect() call now queues (waiting=1) and blocks inside admit()'s
    # semaphore wait -- `max_queue=1` still admits THIS call (0 waiting when
    # it checks), it just never gets a permit until the holder releases.
    queued_entered = threading.Event()

    def queued_detect():
        queued_entered.set()
        servicer.Detect(_full_frame_request(), FakeContext())

    queued_thread = threading.Thread(target=queued_detect)
    queued_thread.start()
    assert queued_entered.wait(timeout=2)
    deadline = time.monotonic() + 2
    while gate.queue_depth < 1 and time.monotonic() < deadline:
        time.sleep(0.01)
    assert gate.queue_depth == 1, "the queued Detect() call never reached admission"

    # A THIRD caller now finds the queue already at its bound and is refused.
    context = FakeContext()
    with pytest.raises(_AbortError):
        servicer.Detect(_full_frame_request(), context)
    assert context.aborted[0] == grpc.StatusCode.RESOURCE_EXHAUSTED

    release_holder.set()
    holder.join(timeout=2)
    queued_thread.join(timeout=2)
    assert not holder.is_alive() and not queued_thread.is_alive()


def test_a_one_permit_gate_serves_sequential_requests_without_deadlocking():
    """Proves the task-1 deadlock decision: `admit()`'s permit is released
    BEFORE `_detect_pixels`'s own `acquire()` re-takes the SAME gate. If
    `Detect()` held the admission permit across the detection call, this
    would hang forever on a 1-permit gate -- so the whole test is wrapped in
    a background thread with a strict join timeout rather than trusted to
    "just return"."""
    gate = InferenceGate(1)
    detector = FakeDetector()
    servicer = DetectorServicer(detector=detector, inference_gate=gate, settings=Settings())

    responses: list = []

    def run_ten_sequential_requests():
        for sequence in range(10):
            responses.append(servicer.Detect(_full_frame_request(sequence=sequence), FakeContext()))

    thread = threading.Thread(target=run_ten_sequential_requests)
    thread.start()
    thread.join(timeout=5)

    assert not thread.is_alive(), "Detect() deadlocked itself on a 1-permit gate"
    assert len(responses) == 10
    assert all(r.detections[0].label == "car" for r in responses)
    assert len(detector.calls) == 10


def test_a_one_permit_gate_serves_composite_registry_requests_without_deadlocking():
    """Same proof as above, but through `detect_composite`'s own per-member
    `gate.acquire()` -- the exact collaborator `Detect()`'s docstring names."""
    gate = InferenceGate(1)
    member = FakeDetector()
    registry = FakeRegistry([("a.pt", member)])
    servicer = DetectorServicer(registry=registry, inference_gate=gate, settings=Settings())

    responses: list = []

    def run_five_sequential_requests():
        for sequence in range(5):
            responses.append(
                servicer.Detect(_full_frame_request(sequence=sequence, model_id="a.pt"), FakeContext())
            )

    thread = threading.Thread(target=run_five_sequential_requests)
    thread.start()
    thread.join(timeout=5)

    assert not thread.is_alive(), "Detect() deadlocked against detect_composite's own gate.acquire()"
    assert len(responses) == 5
    assert len(member.calls) == 5


# --- GrpcRemoteDetector: gRPC status-code translation, fake stub -------------


class _FakeRpcError(grpc.RpcError):
    def __init__(self, code, details="synthetic failure"):
        super().__init__(details)
        self._code = code
        self._details = details

    def code(self):
        return self._code

    def details(self):
        return self._details


class _FailingStub:
    def __init__(self, error: grpc.RpcError) -> None:
        self._error = error

    def Detect(self, request, timeout=None):
        raise self._error


class _RecordingStub:
    """Captures the wire request it was called with and returns a canned
    `DetectResponse` -- the fake backend `stub=` exists for."""

    def __init__(self, response: "cv_pb2.DetectResponse") -> None:
        self.requests: list = []
        self.timeouts: list = []
        self._response = response

    def Detect(self, request, timeout=None):
        self.requests.append(request)
        self.timeouts.append(timeout)
        return self._response


def _payload(**overrides) -> FramePayload:
    fields = dict(
        stream_id="stream-1",
        sequence=3,
        width=WIDTH,
        height=HEIGHT,
        encoding="IMAGE_ENCODING_BGR24",
        data=FRAME_BYTES,
        model_id="fake.pt",
        model_version="v1",
        confidence_threshold=0.4,
    )
    fields.update(overrides)
    return FramePayload(**fields)


def test_grpc_remote_detector_translates_resource_exhausted_into_detector_busy():
    stub = _FailingStub(_FakeRpcError(grpc.StatusCode.RESOURCE_EXHAUSTED, "queue full"))
    remote = GrpcRemoteDetector("gpu-box:50051", settings=Settings(), stub=stub)

    with pytest.raises(DetectorBusy) as excinfo:
        remote.detect(_payload())
    assert "gpu-box:50051" in str(excinfo.value)
    assert "queue full" in str(excinfo.value)


@pytest.mark.parametrize(
    "code", [grpc.StatusCode.UNAVAILABLE, grpc.StatusCode.DEADLINE_EXCEEDED, grpc.StatusCode.INTERNAL]
)
def test_grpc_remote_detector_translates_every_other_error_into_detector_unavailable(code):
    stub = _FailingStub(_FakeRpcError(code, "no answer"))
    remote = GrpcRemoteDetector("gpu-box:50051", settings=Settings(), stub=stub)

    with pytest.raises(DetectorUnavailable) as excinfo:
        remote.detect(_payload())
    assert code.name in str(excinfo.value)


def test_grpc_remote_detector_builds_the_wire_request_and_maps_the_response_back():
    wire_response = cv_pb2.DetectResponse(
        detections=[
            cv_pb2.Detection(label="car", confidence=0.75, box=cv_pb2.BoundingBox(x=0.1, y=0.2, width=0.3, height=0.4))
        ],
        inference_millis=42,
    )
    stub = _RecordingStub(wire_response)
    settings = Settings(detector_timeout_millis=1500)
    remote = GrpcRemoteDetector("gpu-box:50051", settings=settings, stub=stub)

    roi = Box(x=0.0, y=0.0, width=0.5, height=0.5)
    result = remote.detect(_payload(sequence=7), roi=roi)

    assert len(stub.requests) == 1
    sent = stub.requests[0]
    assert sent.stream_id == "stream-1"
    assert sent.sequence == 7
    assert sent.width == WIDTH and sent.height == HEIGHT
    assert cv_pb2.ImageEncoding.Name(sent.encoding) == "IMAGE_ENCODING_BGR24"
    assert sent.model_id == "fake.pt"
    assert sent.confidence_threshold == pytest.approx(0.4)
    assert sent.HasField("roi")
    assert sent.roi.width == pytest.approx(0.5)
    assert stub.timeouts == [1.5]  # detector_timeout_millis / 1000

    assert result.served_by == "gpu-box:50051"
    assert result.inference_millis == 42
    assert result.roi is True
    assert len(result.detections) == 1
    detection = result.detections[0]
    assert isinstance(detection, Detection)
    assert detection.label == "car"
    assert detection.confidence == pytest.approx(0.75)
    assert detection.width == pytest.approx(0.3)


def test_grpc_remote_detector_omits_roi_from_the_wire_request_when_not_given():
    stub = _RecordingStub(cv_pb2.DetectResponse())
    remote = GrpcRemoteDetector("gpu-box:50051", settings=Settings(), stub=stub)

    remote.detect(_payload())

    assert not stub.requests[0].HasField("roi")


# --- remote_detectors(): the `local` token, ordering -------------------------


def test_remote_detectors_builds_a_local_placeholder_for_the_local_token():
    targets = remote_detectors(Settings(detector_targets=(LOCAL_TARGET,)))

    assert [t.target for t in targets] == ["local"]
    # The `local` placeholder's own `detect()` is structurally unreachable
    # (`PoolDetectorClient._attempt` never calls it) -- asserted here so a
    # future refactor that DID call it fails loudly instead of silently.
    with pytest.raises(AssertionError):
        targets[0].detect(_payload())


def test_remote_detectors_preserves_configured_order_and_builds_one_channel_per_host():
    settings = Settings(detector_targets=("gpu-a:50051", "gpu-b:50051", LOCAL_TARGET))
    targets = remote_detectors(settings)

    assert [t.target for t in targets] == ["gpu-a:50051", "gpu-b:50051", "local"]
    assert isinstance(targets[0], GrpcRemoteDetector)
    assert isinstance(targets[1], GrpcRemoteDetector)
    targets[0].close()
    targets[1].close()


# --- End-to-end: a real in-process gRPC server --------------------------------


def test_detect_and_grpc_remote_detector_round_trip_over_a_real_server():
    """The one test in this file that opens a real socket -- proves the whole
    stack (encode -> network -> decode, both directions) agrees with itself,
    which no direct-call or fake-stub test above can prove on its own."""
    gate = InferenceGate(2)
    settings = Settings(detector_max_queue=4)
    detector = FakeDetector(detections=[Detection("boat", 0.66, 0.05, 0.05, 0.15, 0.15)], millis=17)
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=2))
    cv_pb2_grpc.add_DetectorServicer_to_server(
        DetectorServicer(detector=detector, inference_gate=gate, settings=settings), server
    )
    port = server.add_insecure_port("localhost:0")
    server.start()
    try:
        remote = GrpcRemoteDetector(f"localhost:{port}", settings=settings)
        try:
            result = remote.detect(_payload(sequence=1))
        finally:
            remote.close()
    finally:
        server.stop(grace=None)

    assert result.served_by == f"localhost:{port}"
    assert result.inference_millis == 17
    assert len(result.detections) == 1
    assert result.detections[0].label == "boat"


# --- InferenceServicer wiring: local stays a bare lambda, pool carries a payload


def test_local_placement_binds_a_bare_callable_with_no_frame_payload():
    """The zero-cost claim (CV-ORCHESTRATION wave W4, task 4): on a `local`
    process (`settings.detector_targets` empty, still today's default), the
    per-frame `detect` callable handed to `session.process()` is the exact
    bare lambda `_handle_request` always built pre-W4 -- no `FramePayload`,
    no `_FrameDetect` wrapper, nothing a pool needs that a local deployment
    must not pay for."""
    servicer = InferenceServicer(
        detector=FakeDetector(), inference_gate=InferenceGate(2), settings=Settings()
    )
    assert servicer._remote_detectors == ()

    session = servicer._session_registry.acquire("stream-1")
    request = cv_pb2.FrameRequest(
        stream_id="stream-1",
        sequence=0,
        width=WIDTH,
        height=HEIGHT,
        encoding=cv_pb2.IMAGE_ENCODING_BGR24,
        data=FRAME_BYTES,
        model_id="fake.pt",
    )
    request.tracking.mode = cv_pb2.TRACKING_MODE_ASSOCIATE
    servicer._handle_request(request, session)

    bound = session._client._detect
    assert not isinstance(bound, _FrameDetect)
    assert not hasattr(bound, "payload")


def test_pool_placement_binds_a_frame_detect_carrying_the_wire_payload():
    """The other half of the same claim: once `detector_targets` is non-empty
    (here the `local` token, so no real channel is opened), every session
    gets a `PoolDetectorClient` and its bound callable DOES carry a
    `FramePayload` -- `PoolDetectorClient.bind()` reads `.payload` straight
    off it (`orchestration.detector.FrameDetect`'s own contract)."""
    servicer = InferenceServicer(
        detector=FakeDetector(),
        inference_gate=InferenceGate(2),
        settings=Settings(detector_targets=(LOCAL_TARGET,)),
    )
    assert len(servicer._remote_detectors) == 1

    session = servicer._session_registry.acquire("stream-1")
    assert isinstance(session._client, PoolDetectorClient)

    request = cv_pb2.FrameRequest(
        stream_id="stream-1",
        sequence=0,
        width=WIDTH,
        height=HEIGHT,
        encoding=cv_pb2.IMAGE_ENCODING_BGR24,
        data=FRAME_BYTES,
        model_id="fake.pt",
    )
    request.tracking.mode = cv_pb2.TRACKING_MODE_ASSOCIATE
    servicer._handle_request(request, session)

    payload = session._client._payload
    assert payload is not None
    assert isinstance(payload, FramePayload)
    assert payload.stream_id == "stream-1"
    assert payload.model_id == "fake.pt"
    # Served through the embedded LocalDetectorClient (the only target is
    # the `local` token), so the pass still succeeded end to end.
    assert session._client.served_by == "local"
