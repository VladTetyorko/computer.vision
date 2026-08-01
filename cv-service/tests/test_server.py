"""Tests for cv_service.server: real-detector wiring, echo fallback.

Requires the generated stubs (`scripts/gen_proto.sh`) but NOT the `cv`
optional dependency group -- `cv_service.server` never imports
`cv_service.inference` at module scope (see `_build_default_detector`), so
these tests exercise the echo-fallback path independent of opencv/ultralytics
availability. Tests that inject a fake detector don't need `cv` either, since
the fake stands in for `YoloDetector` entirely.
"""

from __future__ import annotations

import time

import pytest

import cv_service.server as server_module
from cv_service.server import InferenceServicer, cv_pb2


class FakeDetector:
    def __init__(self, model_name="fake-model", detections=None, inference_millis=7):
        self.model_name = model_name
        self._detections = detections if detections is not None else []
        self._inference_millis = inference_millis
        self.calls: list[dict] = []

    def detect(self, **kwargs):
        self.calls.append(kwargs)
        return self._detections, self._inference_millis


def _make_request(**overrides):
    fields = dict(
        stream_id="stream-1",
        sequence=42,
        timestamp_millis=1234,
        width=4,
        height=3,
        encoding=cv_pb2.IMAGE_ENCODING_BGR24,
        data=b"\x00" * (4 * 3 * 3),
        model_id="",
        model_version="",
        confidence_threshold=0.0,
    )
    fields.update(overrides)
    return cv_pb2.FrameRequest(**fields)


def test_detect_stream_echoes_when_detector_unavailable(monkeypatch):
    # `InferenceServicer()` with no `detector=` now builds a registry (see
    # `_build_default_registry`), not a lone detector directly -- patch that
    # instead so this still exercises the "nothing could load at all" path.
    monkeypatch.setattr(server_module, "_build_default_registry", lambda: None)
    servicer = InferenceServicer()
    assert servicer._detector is None
    assert servicer._registry is None

    request = _make_request()
    (response,) = list(servicer.DetectStream(iter([request]), context=None))

    assert response.stream_id == request.stream_id
    assert response.sequence == request.sequence
    assert response.timestamp_millis == request.timestamp_millis
    assert list(response.detections) == []
    assert response.inference_millis == 0


def test_detect_stream_maps_detector_output_to_wire_shape():
    from cv_service.inference import Detection

    detector = FakeDetector(
        detections=[Detection(label="person", confidence=0.87, x=0.1, y=0.2, width=0.3, height=0.4)],
        inference_millis=12,
    )
    servicer = InferenceServicer(detector=detector)

    request = _make_request()
    (response,) = list(servicer.DetectStream(iter([request]), context=None))

    assert response.inference_millis == 12
    assert len(response.detections) == 1
    detection = response.detections[0]
    assert detection.label == "person"
    assert detection.confidence == pytest.approx(0.87)
    assert detection.box.x == pytest.approx(0.1)
    assert detection.box.y == pytest.approx(0.2)
    assert detection.box.width == pytest.approx(0.3)
    assert detection.box.height == pytest.approx(0.4)
    # stream_id/sequence/timestamp echo exactly as the Phase 0 stub did.
    assert response.stream_id == request.stream_id
    assert response.sequence == request.sequence
    assert response.timestamp_millis == request.timestamp_millis

    call = detector.calls[0]
    assert call["encoding"] == "IMAGE_ENCODING_BGR24"
    assert call["width"] == 4
    assert call["height"] == 3
    assert call["confidence_threshold"] is None


def test_detect_stream_passes_nonzero_confidence_threshold():
    detector = FakeDetector()
    servicer = InferenceServicer(detector=detector)

    request = _make_request(confidence_threshold=0.42)
    list(servicer.DetectStream(iter([request]), context=None))

    assert detector.calls[0]["confidence_threshold"] == pytest.approx(0.42)


def test_detect_stream_falls_back_to_echo_on_per_frame_failure():
    class BoomingDetector:
        model_name = "boom-model"

        def detect(self, **_kwargs):
            raise RuntimeError("decode blew up")

    servicer = InferenceServicer(detector=BoomingDetector())
    request = _make_request()

    (response,) = list(servicer.DetectStream(iter([request]), context=None))

    assert list(response.detections) == []
    assert response.inference_millis == 0
    assert response.stream_id == request.stream_id
    assert response.sequence == request.sequence


class _PacedIterator:
    """Yields `items` one at a time with a small delay before each.

    V-d's `DetectStream` now reads frames on a background thread (see
    `cv_service.server._StreamReader`) into a 1-slot latest-wins mailbox --
    a plain `iter([...])` delivers all of its items essentially
    instantaneously, which races that background thread against however
    fast this test happens to get scheduled onto the mailbox, and can
    legitimately (by design -- see `LatestOnlyMailbox`) drop items that a
    slower, more realistic producer (a real network) would never have
    bunched up in the first place. This iterator's small per-item delay
    keeps the consumer always caught up before the next item exists, making
    "every item is delivered, in order" deterministic again without
    changing what's being tested.
    """

    def __init__(self, items, delay: float = 0.02) -> None:
        self._items = iter(items)
        self._delay = delay

    def __iter__(self):
        return self

    def __next__(self):
        time.sleep(self._delay)
        return next(self._items)


def test_multiple_requests_get_independent_responses():
    detector = FakeDetector()
    servicer = InferenceServicer(detector=detector)

    requests = [_make_request(sequence=i) for i in range(3)]
    responses = list(servicer.DetectStream(_PacedIterator(requests), context=None))

    assert [r.sequence for r in responses] == [0, 1, 2]


def test_unknown_model_id_warns_once_and_serves_default(caplog):
    detector = FakeDetector(model_name="yolo11n.pt")
    servicer = InferenceServicer(detector=detector)

    requests = [_make_request(model_id="some-other-model") for _ in range(3)]
    with caplog.at_level("INFO", logger="cv_service.server"):
        list(servicer.DetectStream(iter(requests), context=None))

    warnings = [r for r in caplog.records if "model registry is Phase 3" in r.message]
    assert len(warnings) == 1


def test_matching_model_id_does_not_warn(caplog):
    detector = FakeDetector(model_name="yolo11n.pt")
    servicer = InferenceServicer(detector=detector)

    request = _make_request(model_id="yolo11n.pt")
    with caplog.at_level("INFO", logger="cv_service.server"):
        list(servicer.DetectStream(iter([request]), context=None))

    assert not any("model registry is Phase 3" in r.message for r in caplog.records)


# --- registry-routed path (InferenceServicer(registry=...)) -----------------
#
# `InferenceServicer._detect_via_registry` lazily imports `cv_service.registry`
# (mirroring `_build_default_detector`'s lazy import of `cv_service.inference`)
# -- that import needs the `cv` extra (cv2/numpy, transitively via
# `cv_service.inference`) even though `FakeRegistry`/`FakeDetector` below never
# touch ultralytics/torch. Each test below skips cleanly rather than failing
# if the `cv` extra isn't installed, same pattern as `test_real_model.py`.


class FakeRegistry:
    """Minimal stand-in for `cv_service.registry.ModelRegistry`: only the
    `.resolve(model_id) -> list[(id, detector)]` surface `InferenceServicer`
    actually calls."""

    def __init__(self, resolved_by_model_id: dict[str, list] | None = None, *, default: list | None = None):
        self._resolved_by_model_id = resolved_by_model_id or {}
        self._default = default if default is not None else []

    def resolve(self, model_id: str):
        return self._resolved_by_model_id.get(model_id, self._default)


def test_registry_path_routes_known_model_id_to_matching_detector():
    pytest.importorskip("cv_service.registry")
    from cv_service.inference import Detection

    detector = FakeDetector(
        model_name="orion12l.pt",
        detections=[Detection(label="tank", confidence=0.8, x=0.1, y=0.1, width=0.2, height=0.2)],
        inference_millis=42,
    )
    registry = FakeRegistry(resolved_by_model_id={"orion12l.pt": [("orion12l.pt", detector)]})
    servicer = InferenceServicer(registry=registry)

    request = _make_request(model_id="orion12l.pt")
    (response,) = list(servicer.DetectStream(iter([request]), context=None))

    assert response.inference_millis == 42
    assert len(response.detections) == 1
    assert response.detections[0].label == "tank"  # single member: no prefix
    # the response envelope always echoes what the request asked for, same
    # as the pre-registry behavior -- routing doesn't rewrite it.
    assert response.model_id == "orion12l.pt"


def test_registry_path_falls_back_to_default_for_unknown_model_id():
    pytest.importorskip("cv_service.registry")
    from cv_service.inference import Detection

    default_detector = FakeDetector(
        model_name="yolo11n.pt",
        detections=[Detection(label="person", confidence=0.5, x=0.0, y=0.0, width=0.1, height=0.1)],
        inference_millis=10,
    )
    # A real ModelRegistry.resolve() would fall back to the default for any
    # unrecognized id -- FakeRegistry's `default=` simulates exactly that.
    registry = FakeRegistry(default=[("yolo11n.pt", default_detector)])
    servicer = InferenceServicer(registry=registry)

    request = _make_request(model_id="totally-unknown-model")
    (response,) = list(servicer.DetectStream(iter([request]), context=None))

    assert response.inference_millis == 10
    assert response.detections[0].label == "person"


def test_registry_path_echoes_when_nothing_resolves():
    pytest.importorskip("cv_service.registry")
    registry = FakeRegistry()  # resolve() always returns [] (no default either)
    servicer = InferenceServicer(registry=registry)

    request = _make_request(model_id="anything")
    (response,) = list(servicer.DetectStream(iter([request]), context=None))

    assert list(response.detections) == []
    assert response.inference_millis == 0


def test_registry_path_composite_prefixes_labels_and_sums_millis():
    pytest.importorskip("cv_service.registry")
    from cv_service.inference import Detection

    detector_general = FakeDetector(
        model_name="yolo11n.pt",
        detections=[Detection(label="person", confidence=0.9, x=0.0, y=0.0, width=0.1, height=0.1)],
        inference_millis=15,
    )
    detector_military = FakeDetector(
        model_name="orion12l.pt",
        detections=[Detection(label="tank", confidence=0.7, x=0.2, y=0.2, width=0.2, height=0.2)],
        inference_millis=45,
    )
    registry = FakeRegistry(
        resolved_by_model_id={
            "yolo11n.pt,orion12l.pt": [
                ("yolo11n.pt", detector_general),
                ("orion12l.pt", detector_military),
            ]
        }
    )
    servicer = InferenceServicer(registry=registry)

    request = _make_request(model_id="yolo11n.pt,orion12l.pt")
    (response,) = list(servicer.DetectStream(iter([request]), context=None))

    assert response.inference_millis == 60  # sum of both members
    labels = sorted(d.label for d in response.detections)
    assert labels == ["orion12l:tank", "yolo11n:person"]


def test_registry_path_per_frame_failure_still_echoes():
    pytest.importorskip("cv_service.registry")

    class BoomingRegistry:
        def resolve(self, model_id):
            raise RuntimeError("registry blew up")

    servicer = InferenceServicer(registry=BoomingRegistry())
    request = _make_request()

    (response,) = list(servicer.DetectStream(iter([request]), context=None))

    assert list(response.detections) == []
    assert response.inference_millis == 0


# --- serve(): keepalive server options (docs/REMOTE-CV-PLAN.md "Transport decisions" P1) -----


def test_keepalive_server_options_match_transport_decisions():
    """`_KEEPALIVE_SERVER_OPTIONS` must permit the client's idle-channel
    pings and reciprocate with its own, per the values docs/REMOTE-CV-PLAN.md
    "Transport decisions" settled on: `min_ping_interval_without_data_ms`
    (10s) is half of `GrpcDetectionPort.KEEPALIVE_TIME_SECONDS` (20s),
    leaving jitter headroom."""
    options = dict(server_module._KEEPALIVE_SERVER_OPTIONS)

    assert options["grpc.keepalive_permit_without_calls"] == 1
    assert options["grpc.http2.min_ping_interval_without_data_ms"] == 10_000
    assert options["grpc.keepalive_time_ms"] == 30_000
    assert options["grpc.keepalive_timeout_ms"] == 10_000


def test_serve_passes_keepalive_options_to_grpc_server(monkeypatch):
    """`serve()` must build the real `grpc.server(...)` with
    `_KEEPALIVE_SERVER_OPTIONS` -- without `keepalive_permit_without_calls`,
    a Python grpc server GOAWAYs ("too_many_pings") a client pinging with no
    active calls, silently defeating the client-side keepalive tuning.

    A real ping-timing/GOAWAY assertion would need an actual flaky-network
    harness and be slow/flaky by nature (exactly what the task calls out to
    avoid); this instead captures the `options` kwarg `serve()` passes to
    `grpc.server`, faking every other collaborator `serve()` touches
    (servicer construction, service registration) so the test doesn't pull
    in a real model/registry load.
    """
    captured = {}

    class _FakeServer:
        def add_generic_rpc_handlers(self, handlers):
            pass

        def add_insecure_port(self, address):
            captured["address"] = address
            return 0

        def start(self):
            captured["started"] = True

    def fake_grpc_server(executor, options=None):
        captured["options"] = options
        return _FakeServer()

    monkeypatch.setattr(server_module.grpc, "server", fake_grpc_server)
    # `serve()` now builds one shared registry and passes it to both
    # servicers (so PromoteModel re-points the same registry inference
    # routes against) -- fake the build (a real one loads a model, slow and
    # environment-dependent) and accept the kwargs both servicers now take.
    monkeypatch.setattr(server_module, "_build_default_registry", lambda: None)
    monkeypatch.setattr(server_module, "InferenceServicer", lambda **kwargs: object())
    monkeypatch.setattr(server_module, "TrainingServicer", lambda **kwargs: object())
    monkeypatch.setattr(server_module.cv_pb2_grpc, "add_InferenceServicer_to_server", lambda servicer, srv: None)
    monkeypatch.setattr(server_module.cv_pb2_grpc, "add_TrainingServicer_to_server", lambda servicer, srv: None)

    result = server_module.serve(port=0)

    assert captured["options"] == server_module._KEEPALIVE_SERVER_OPTIONS
    assert captured["started"] is True
    assert isinstance(result, _FakeServer)
