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

# --- serve(): keepalive server options (docs/plans/done/REMOTE-CV-PLAN.md "Transport decisions" P1) -----


def test_keepalive_server_options_match_transport_decisions():
    """`_KEEPALIVE_SERVER_OPTIONS` must permit the client's idle-channel
    pings and reciprocate with its own, per the values docs/plans/done/REMOTE-CV-PLAN.md
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
    # `GeolocationServicer` builds a real encoder/matcher at construction when the `geo` extra is
    # installed (network fetch on first run) -- faked out for the same "slow and
    # environment-dependent" reason the other two servicers already are above.
    monkeypatch.setattr(server_module, "GeolocationServicer", lambda **kwargs: object())
    monkeypatch.setattr(server_module.cv_pb2_grpc, "add_InferenceServicer_to_server", lambda servicer, srv: None)
    monkeypatch.setattr(server_module.cv_pb2_grpc, "add_TrainingServicer_to_server", lambda servicer, srv: None)
    monkeypatch.setattr(server_module.cv_pb2_grpc, "add_GeolocationServicer_to_server", lambda servicer, srv: None)

    result = server_module.serve(Settings(port=0))

    assert captured["options"] == server_module._KEEPALIVE_SERVER_OPTIONS
    assert captured["started"] is True
    assert captured["address"] == "[::]:0"
    assert isinstance(result, _FakeServer)


# --- serve(): process roles (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R6) -----


class _FakeGrpcServer:
    def add_generic_rpc_handlers(self, handlers):
        pass

    def add_insecure_port(self, address):
        return 0

    def start(self):
        pass


def _patch_serve_collaborators(monkeypatch, *, track_inference_only_builds=False):
    """Fakes every collaborator `serve()` touches so a role test never loads a real model or
    geolocation backend, and returns the ordered list of servicer names `serve()` registered on
    the (fake) gRPC server -- the thing every role test actually asserts on.

    When `track_inference_only_builds` is True, `InferenceGate`/`_build_tracker_registry` (the two
    collaborators only an `inference`-serving role should ever construct) are faked to additionally
    record that they were called, appended to the returned list under the same "inference"/
    "tracker_registry" markers so a `training`-role test can assert neither ran.
    """
    added = []
    monkeypatch.setattr(server_module.grpc, "server", lambda executor, options=None: _FakeGrpcServer())
    monkeypatch.setattr(server_module, "_build_default_registry", lambda settings: None)
    monkeypatch.setattr(server_module, "InferenceServicer", lambda **kwargs: object())
    monkeypatch.setattr(server_module, "TrainingServicer", lambda **kwargs: object())
    monkeypatch.setattr(server_module, "GeolocationServicer", lambda **kwargs: object())
    monkeypatch.setattr(
        server_module.cv_pb2_grpc, "add_InferenceServicer_to_server", lambda servicer, srv: added.append("inference")
    )
    monkeypatch.setattr(
        server_module.cv_pb2_grpc, "add_TrainingServicer_to_server", lambda servicer, srv: added.append("training")
    )
    monkeypatch.setattr(
        server_module.cv_pb2_grpc,
        "add_GeolocationServicer_to_server",
        lambda servicer, srv: added.append("geolocation"),
    )
    if track_inference_only_builds:
        monkeypatch.setattr(server_module, "InferenceGate", lambda n: added.append("inference_gate") or object())
        monkeypatch.setattr(
            server_module, "_build_tracker_registry", lambda settings: added.append("tracker_registry")
        )
    return added


def test_serve_with_default_role_registers_all_three_servicers(monkeypatch):
    added = _patch_serve_collaborators(monkeypatch)

    server_module.serve(Settings(port=0))

    assert added == ["inference", "training", "geolocation"]


def test_serve_with_role_all_explicit_registers_all_three_servicers(monkeypatch):
    added = _patch_serve_collaborators(monkeypatch)

    server_module.serve(Settings(port=0, role="all"))

    assert added == ["inference", "training", "geolocation"]


def test_serve_with_role_inference_registers_only_inference(monkeypatch):
    added = _patch_serve_collaborators(monkeypatch, track_inference_only_builds=True)

    server_module.serve(Settings(port=0, role="inference"))

    # inference_gate/tracker_registry are built BEFORE add_InferenceServicer_to_server is called.
    assert added == ["inference_gate", "tracker_registry", "inference"]


def test_serve_with_role_training_registers_training_and_geolocation_but_not_inference(monkeypatch):
    added = _patch_serve_collaborators(monkeypatch, track_inference_only_builds=True)

    server_module.serve(Settings(port=0, role="training"))

    assert added == ["training", "geolocation"]
    assert "inference_gate" not in added
    assert "tracker_registry" not in added


def test_serve_logs_the_resolved_role(monkeypatch, caplog):
    _patch_serve_collaborators(monkeypatch)

    with caplog.at_level("INFO", logger="cv_service.grpc.server"):
        server_module.serve(Settings(port=0, role="training"))

    assert "role=training" in caplog.text
