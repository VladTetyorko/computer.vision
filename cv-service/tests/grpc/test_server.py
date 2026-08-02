"""Tests for `cv_service.grpc.server`: the composition root (`serve()`/
keepalive options).

Requires the generated stubs (`scripts/gen_proto.sh`) but NOT the `cv`
optional dependency group -- registry construction is only ever imported
lazily inside `serve()`, and this test fakes it out entirely (a real
registry build loads a model, slow and environment-dependent).
"""

from __future__ import annotations

import cv_service.grpc.server as server_module
from cv_service.config import Settings

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
    # `serve()` builds one shared registry and passes it to both servicers
    # (so PromoteModel re-points the same registry inference routes
    # against) -- fake the build (a real one loads a model, slow and
    # environment-dependent) and accept the kwargs both servicers now take.
    monkeypatch.setattr(server_module, "_build_default_registry", lambda settings: None)
    monkeypatch.setattr(server_module, "InferenceServicer", lambda **kwargs: object())
    monkeypatch.setattr(server_module, "TrainingServicer", lambda **kwargs: object())
    monkeypatch.setattr(server_module.cv_pb2_grpc, "add_InferenceServicer_to_server", lambda servicer, srv: None)
    monkeypatch.setattr(server_module.cv_pb2_grpc, "add_TrainingServicer_to_server", lambda servicer, srv: None)

    result = server_module.serve(Settings(port=0))

    assert captured["options"] == server_module._KEEPALIVE_SERVER_OPTIONS
    assert captured["started"] is True
    assert captured["address"] == "[::]:0"
    assert isinstance(result, _FakeServer)
