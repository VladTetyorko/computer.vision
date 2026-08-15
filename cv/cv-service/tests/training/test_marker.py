"""Unit tests for `cv_service.training.marker`: the active-model marker
persistence (renamed from `cv_service/training.py`).

Moved out of `tests/test_training.py` (now `tests/grpc/test_training_servicer.py`).
Stdlib-only (`json`) -- no `cv`/gRPC extras needed to run these.
"""

from __future__ import annotations

from pathlib import Path

from cv_service.training.marker import ACTIVE_MODEL_MARKER, read_active_model, write_active_model


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
