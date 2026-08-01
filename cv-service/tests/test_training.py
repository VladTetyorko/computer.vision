"""Tests for the Training control plane: ListModels / PromoteModel and the
active-model marker persistence (CV-TRAINING Phase 2, unblocked half).

The servicer tests drive a real `ModelRegistry` with a fake
`detector_factory` (no ultralytics/torch, exactly like `test_registry.py`) --
so they need the `cv` extra (registry.py imports `cv_service.inference`) but
never load real weights. The marker helpers (`cv_service.training`) are
stdlib-only and tested directly.

`StartTraining` stays UNIMPLEMENTED by design (training runs offline on a
GPU host; this box is Intel-only / no CUDA) -- asserted here so the gate is
regression-protected.
"""

from __future__ import annotations

from pathlib import Path

import grpc
import pytest
from google.protobuf import empty_pb2

from cv_service.registry import ModelRegistry
from cv_service.server import TrainingServicer, cv_pb2
from cv_service.training import (
    ACTIVE_MODEL_MARKER,
    read_active_model,
    write_active_model,
)


class FakeDetector:
    def __init__(self, model_name):
        self.model_name = model_name

    def detect(self, **kwargs):  # pragma: no cover - not exercised by these tests
        return [], 0


def _factory_returning(detectors_by_name):
    def factory(*, model_name):
        return detectors_by_name[model_name]

    return factory


def _registry(default_id="yolo26n.pt"):
    names = {"yolo26n.pt": FakeDetector("yolo26n.pt"), "orion12l.pt": FakeDetector("orion12l.pt")}
    return ModelRegistry(
        roster={"yolo26n.pt": "yolo26n.pt", "orion12l.pt": "orion12l.pt"},
        default_id=default_id,
        detector_factory=_factory_returning(names),
    )


class _AbortError(Exception):
    """Stand-in for the exception `grpc`'s real `context.abort` raises."""


class FakeContext:
    def __init__(self):
        self.code = None
        self.details = None

    def abort(self, code, details):
        self.code = code
        self.details = details
        raise _AbortError(details)


# --- ListModels ------------------------------------------------------------


def test_list_models_reflects_roster_and_marks_active(tmp_path: Path):
    servicer = TrainingServicer(registry=_registry(default_id="yolo26n.pt"), model_dir=tmp_path)

    result = servicer.ListModels(empty_pb2.Empty(), FakeContext())

    by_id = {m.id: m for m in result.models}
    assert set(by_id) == {"yolo26n.pt", "orion12l.pt"}
    assert by_id["yolo26n.pt"].stage == "active"  # the current default
    assert by_id["orion12l.pt"].stage == "available"
    # no per-model version tracked by the registry today.
    assert by_id["yolo26n.pt"].version == ""


def test_list_models_empty_when_no_registry():
    servicer = TrainingServicer(registry=None, model_dir=None)

    result = servicer.ListModels(empty_pb2.Empty(), FakeContext())

    assert list(result.models) == []


# --- PromoteModel ----------------------------------------------------------


def test_promote_model_changes_default_and_persists(tmp_path: Path):
    registry = _registry(default_id="yolo26n.pt")
    servicer = TrainingServicer(registry=registry, model_dir=tmp_path)

    ack = servicer.PromoteModel(cv_pb2.ModelRefMsg(id="orion12l.pt"), FakeContext())

    assert ack.ok is True
    # in-memory default re-pointed -> subsequent default resolution uses it.
    assert registry.default_id == "orion12l.pt"
    # persisted to the marker file for restart-survival.
    assert read_active_model(tmp_path) == "orion12l.pt"
    assert (tmp_path / ACTIVE_MODEL_MARKER).is_file()


def test_promote_survives_restart(tmp_path: Path):
    """A fresh registry + servicer (simulating a cv-service restart) reads the
    persisted promotion back as the active model."""
    servicer = TrainingServicer(registry=_registry(default_id="yolo26n.pt"), model_dir=tmp_path)
    assert servicer.PromoteModel(cv_pb2.ModelRefMsg(id="orion12l.pt"), FakeContext()).ok is True

    # Simulate a restart: `server._build_default_registry` reads the marker
    # and seeds a fresh registry's default_id from it (roster unchanged).
    persisted = read_active_model(tmp_path)
    fresh_registry = _registry(default_id=persisted)
    fresh_servicer = TrainingServicer(registry=fresh_registry, model_dir=tmp_path)

    result = fresh_servicer.ListModels(empty_pb2.Empty(), FakeContext())
    by_id = {m.id: m for m in result.models}
    assert by_id["orion12l.pt"].stage == "active"
    assert by_id["yolo26n.pt"].stage == "available"


def test_promote_unknown_id_returns_ok_false_and_does_not_persist(tmp_path: Path):
    registry = _registry(default_id="yolo26n.pt")
    servicer = TrainingServicer(registry=registry, model_dir=tmp_path)

    ack = servicer.PromoteModel(cv_pb2.ModelRefMsg(id="ghost.pt"), FakeContext())

    assert ack.ok is False
    assert "ghost.pt" in ack.message
    assert registry.default_id == "yolo26n.pt"  # unchanged
    assert not (tmp_path / ACTIVE_MODEL_MARKER).exists()  # nothing persisted


def test_promote_empty_id_returns_ok_false(tmp_path: Path):
    servicer = TrainingServicer(registry=_registry(), model_dir=tmp_path)

    ack = servicer.PromoteModel(cv_pb2.ModelRefMsg(id=""), FakeContext())

    assert ack.ok is False
    assert not (tmp_path / ACTIVE_MODEL_MARKER).exists()


def test_promote_no_registry_returns_ok_false():
    servicer = TrainingServicer(registry=None, model_dir=None)

    ack = servicer.PromoteModel(cv_pb2.ModelRefMsg(id="anything.pt"), FakeContext())

    assert ack.ok is False


# --- StartTraining (still gated) -------------------------------------------


def test_start_training_still_unimplemented_and_points_at_offline_flow():
    servicer = TrainingServicer(registry=_registry(), model_dir=None)
    context = FakeContext()

    with pytest.raises(_AbortError):
        servicer.StartTraining(cv_pb2.TrainingJobSpec(base_model="yolo26n.pt", epochs=10), context)

    assert context.code == grpc.StatusCode.UNIMPLEMENTED
    # message points at the offline train -> rsync -> promote flow, not "Phase 3".
    assert "offline" in context.details.lower()
    assert "promotemodel" in context.details.lower()
    assert "phase 3" not in context.details.lower()


# --- marker persistence (cv_service.training) ------------------------------


def test_marker_round_trips_id_and_version(tmp_path: Path):
    write_active_model(tmp_path, "orion12l.pt", version="v2")

    assert read_active_model(tmp_path) == "orion12l.pt"


def test_marker_missing_reads_as_none(tmp_path: Path):
    assert read_active_model(tmp_path) is None


def test_marker_corrupt_json_reads_as_none(tmp_path: Path):
    (tmp_path / ACTIVE_MODEL_MARKER).write_text("{ not json", encoding="utf-8")

    assert read_active_model(tmp_path) is None


def test_marker_without_id_reads_as_none(tmp_path: Path):
    (tmp_path / ACTIVE_MODEL_MARKER).write_text('{"version": "v1"}', encoding="utf-8")

    assert read_active_model(tmp_path) is None
