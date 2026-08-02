"""Tests for the `Training` gRPC servicer: `ListModels` / `PromoteModel` /
`StartTraining` / `UploadDataset` wire behavior (CV-TRAINING Phase 2 +
CV-TRAINING-V2 Wave W2).

The servicer tests drive a real `ModelRegistry` with a fake
`detector_factory` (no ultralytics/torch, exactly like
`tests/inference/test_registry.py`) -- so they need the `cv` extra
(`cv_service.inference.registry` imports `cv_service.inference.detector`)
but never load real weights. Dataset-resolution/marker-persistence unit
tests moved to `tests/training/test_dataset.py`/`test_marker.py` (they test
`cv_service.training.dataset`/`marker` directly, not this servicer); this
file keeps only the tests that exercise `TrainingServicer` itself.

`StartTraining` runs a real Ultralytics fine-tune in production, but these
tests inject a FAKE trainer (`train_fn`) so they are fast and hardware-free --
no ultralytics/torch, no real multi-epoch train. They cover the streaming
contract (RUNNING per epoch -> terminal SUCCEEDED), the produced model landing
in the model dir + showing in ListModels, a missing dataset yielding a
reported FAILED (not a gRPC abort), a training exception yielding FAILED, and
client cancellation stopping the run.

`UploadDataset` tests (CV-TRAINING-V2 Wave W2, docs/CV-TRAINING-V2-PLAN.md §2)
drive real tmp dirs and real (small, in-memory-built) zip archives -- no
ultralytics/torch either. They cover the happy path incl. overwrite-of-a-
previous-upload, protocol-level `dataset_id` problems (blank/mid-stream-
changed/path-unsafe) aborting `INVALID_ARGUMENT`, content problems (corrupt
zip, zip-slip entry, malformed extracted tree, zero chunks) reported as
`ok:false` rather than aborted, and the size cap aborting `RESOURCE_EXHAUSTED`.
"""

from __future__ import annotations

import io
import threading
import zipfile
from pathlib import Path

import grpc
import pytest
from google.protobuf import empty_pb2

from cv_service.grpc.servicers import TrainingServicer, cv_pb2
from cv_service.inference.registry import ModelRegistry
from cv_service.training import trainer
from cv_service.training.marker import (
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


# --- StartTraining (real fine-tune, faked trainer) -------------------------


def _write_dataset(datasets_root: Path, dataset_id: str) -> Path:
    """Create a minimal valid exported-YOLO dataset dir (Phase 1 layout)."""
    dataset_dir = datasets_root / dataset_id
    (dataset_dir / trainer.IMAGES_DIRNAME).mkdir(parents=True)
    (dataset_dir / trainer.LABELS_DIRNAME).mkdir(parents=True)
    (dataset_dir / trainer.DATA_YAML_NAME).write_text(
        "names: [building]\nnc: 1\ntrain: images\nval: images\n", encoding="utf-8"
    )
    return dataset_dir


class CancelContext:
    """Fake gRPC context whose `is_active()` flips to False after N polls,
    standing in for a client that disconnects mid-training."""

    def __init__(self, active_polls: int):
        self._remaining = active_polls
        self._callbacks: list = []

    def is_active(self) -> bool:
        if self._remaining <= 0:
            return False
        self._remaining -= 1
        return True

    def add_callback(self, callback) -> None:
        self._callbacks.append(callback)


def _fake_trainer_reporting(epochs: int, best_src: Path):
    """A `train_fn` that reports `epochs` epochs then returns `best_src`,
    without touching ultralytics."""

    def train_fn(spec, *, on_epoch, is_cancelled):
        for i in range(1, epochs + 1):
            if is_cancelled():
                raise trainer.TrainingCancelled("cancelled")
            on_epoch(
                trainer.EpochProgress(
                    epoch=i, total_epochs=epochs, loss=1.0 / i, map50=0.1 * i
                )
            )
        return best_src

    return train_fn


def test_start_training_streams_epochs_then_succeeds_and_publishes_model(tmp_path: Path):
    datasets_root = tmp_path / "datasets"
    model_dir = tmp_path / "models"
    model_dir.mkdir()
    _write_dataset(datasets_root, "ds-1")

    # A stand-in produced `best.pt` the fake trainer "returns".
    best_src = tmp_path / "best.pt"
    best_src.write_bytes(b"fake-weights")

    registry = _registry(default_id="yolo26n.pt")
    servicer = TrainingServicer(
        registry=registry,
        model_dir=model_dir,
        dataset_dir=datasets_root,
        train_fn=_fake_trainer_reporting(epochs=3, best_src=best_src),
    )

    updates = list(
        servicer.StartTraining(
            cv_pb2.TrainingJobSpec(base_model="yolo26n.pt", dataset_id="ds-1", epochs=3),
            FakeContext(),
        )
    )

    # 3 RUNNING (one per epoch) + 1 terminal SUCCEEDED.
    running = [u for u in updates if u.state == cv_pb2.JobState.RUNNING]
    terminal = updates[-1]
    assert len(running) == 3
    assert [u.epoch for u in running] == [1, 2, 3]
    assert all(u.total_epochs == 3 for u in running)
    assert terminal.state == cv_pb2.JobState.SUCCEEDED
    # one job_id rides every message of the call.
    assert len({u.job_id for u in updates}) == 1
    assert updates[0].job_id

    # produced artifact landed in the model dir under the documented scheme.
    model_id = "ds-1-3e.pt"
    assert (model_dir / model_id).is_file()
    assert (model_dir / model_id).read_bytes() == b"fake-weights"
    assert model_id in terminal.message

    # ...and shows in ListModels (registered live, no restart), NOT promoted.
    listed = {m.id: m for m in servicer.ListModels(empty_pb2.Empty(), FakeContext()).models}
    assert model_id in listed
    assert listed[model_id].stage == "available"
    assert registry.default_id == "yolo26n.pt"  # not auto-promoted


def test_start_training_missing_dataset_yields_failed_not_abort(tmp_path: Path):
    servicer = TrainingServicer(
        registry=_registry(),
        model_dir=tmp_path,
        dataset_dir=tmp_path / "datasets",  # nothing under here
        train_fn=_fake_trainer_reporting(epochs=1, best_src=tmp_path / "nope.pt"),
    )
    context = FakeContext()

    updates = list(
        servicer.StartTraining(
            cv_pb2.TrainingJobSpec(base_model="yolo26n.pt", dataset_id="ghost", epochs=1),
            context,
        )
    )

    # exactly one terminal FAILED message, no gRPC abort.
    assert context.code is None  # never aborted
    assert len(updates) == 1
    assert updates[0].state == cv_pb2.JobState.FAILED
    assert "ghost" in updates[0].message
    assert "copy it in" in updates[0].message.lower()


def test_start_training_exception_yields_failed(tmp_path: Path):
    datasets_root = tmp_path / "datasets"
    _write_dataset(datasets_root, "ds-x")

    def boom(spec, *, on_epoch, is_cancelled):
        on_epoch(trainer.EpochProgress(epoch=1, total_epochs=2, loss=0.5, map50=0.2))
        raise RuntimeError("cuda blew up")

    servicer = TrainingServicer(
        registry=_registry(), model_dir=tmp_path, dataset_dir=datasets_root, train_fn=boom
    )

    updates = list(
        servicer.StartTraining(
            cv_pb2.TrainingJobSpec(base_model="yolo26n.pt", dataset_id="ds-x", epochs=2),
            FakeContext(),
        )
    )

    assert updates[0].state == cv_pb2.JobState.RUNNING
    assert updates[-1].state == cv_pb2.JobState.FAILED
    assert "cuda blew up" in updates[-1].message


def test_start_training_client_cancel_stops_training(tmp_path: Path):
    datasets_root = tmp_path / "datasets"
    _write_dataset(datasets_root, "ds-c")

    started = threading.Event()
    observed_cancel = threading.Event()

    def blocking_trainer(spec, *, on_epoch, is_cancelled):
        started.set()
        # Emulate a long train that keeps checking for cancellation.
        for _ in range(1000):
            if is_cancelled():
                observed_cancel.set()
                raise trainer.TrainingCancelled("cancelled")
            threading.Event().wait(0.01)
        return tmp_path / "best.pt"

    servicer = TrainingServicer(
        registry=_registry(),
        model_dir=tmp_path,
        dataset_dir=datasets_root,
        train_fn=blocking_trainer,
    )
    # is_active() returns True a couple of polls, then False (client gone).
    context = CancelContext(active_polls=2)

    updates = list(
        servicer.StartTraining(
            cv_pb2.TrainingJobSpec(base_model="yolo26n.pt", dataset_id="ds-c", epochs=100),
            context,
        )
    )

    # The generator returned (client gone) and the worker observed cancellation
    # and stopped rather than running all 100 "epochs".
    assert started.is_set()
    assert observed_cancel.wait(timeout=5)
    # No SUCCEEDED emitted (it was cancelled before finishing).
    assert all(u.state != cv_pb2.JobState.SUCCEEDED for u in updates)


# --- UploadDataset (cv_service.grpc.servicers, CV-TRAINING-V2 Wave W2) -----
#
# Dataset-resolution tests (`cv_service.training.dataset.resolve_dataset_dir`)
# moved to `tests/training/test_dataset.py`; artifact-naming
# (`cv_service.training.trainer.output_model_id`) moved to
# `tests/training/test_trainer.py`; active-model marker persistence
# (`cv_service.training.marker`) moved to `tests/training/test_marker.py`.


def _zip_bytes(entries: dict) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, content in entries.items():
            zf.writestr(name, content)
    return buf.getvalue()


def _valid_dataset_entries(image_content: bytes = b"fake-jpeg-bytes") -> dict:
    """A minimal valid archive body for the frozen §5 YOLO layout."""
    return {
        trainer.DATA_YAML_NAME: b"names: [building]\nnc: 1\ntrain: images\nval: images\n",
        f"{trainer.IMAGES_DIRNAME}/frame1.jpg": image_content,
        f"{trainer.LABELS_DIRNAME}/frame1.txt": b"0 0.5 0.5 0.2 0.2\n",
    }


def _chunks(dataset_id: str, data: bytes, chunk_size: int = 37) -> list:
    """Split `data` into several small `DatasetChunk`s -- never one giant
    message -- so tests exercise the streamed-reassembly path, not just a
    single-shot upload."""
    if not data:
        return [cv_pb2.DatasetChunk(dataset_id=dataset_id, content=b"")]
    return [
        cv_pb2.DatasetChunk(dataset_id=dataset_id, content=data[i : i + chunk_size])
        for i in range(0, len(data), chunk_size)
    ]


def _extracted_names(dataset_dir: Path) -> set:
    return {str(p.relative_to(dataset_dir)).replace("\\", "/") for p in dataset_dir.rglob("*") if p.is_file()}


def test_upload_dataset_happy_path(tmp_path: Path):
    datasets_root = tmp_path / "datasets"
    servicer = TrainingServicer(dataset_dir=datasets_root)
    zip_bytes = _zip_bytes(_valid_dataset_entries())

    ack = servicer.UploadDataset(iter(_chunks("ds-1", zip_bytes)), FakeContext())

    assert ack.ok is True
    assert ack.dataset_id == "ds-1"
    assert ack.bytes_received == len(zip_bytes)
    assert ack.file_count == 3
    assert "ds-1" in ack.message
    assert "ready" in ack.message

    dataset_dir = datasets_root / "ds-1"
    assert _extracted_names(dataset_dir) == {"data.yaml", "images/frame1.jpg", "labels/frame1.txt"}
    # no leftover temp files/dirs beside the landed dataset.
    assert list(datasets_root.iterdir()) == [dataset_dir]


def test_upload_dataset_overwrite_replaces_previous_upload(tmp_path: Path):
    """A re-upload of the same dataset_id fully supersedes the previous
    one -- the idempotency the "label more, train again" loop needs."""
    datasets_root = tmp_path / "datasets"
    servicer = TrainingServicer(dataset_dir=datasets_root)

    first = _zip_bytes(_valid_dataset_entries())
    assert servicer.UploadDataset(iter(_chunks("ds-1", first)), FakeContext()).ok is True

    second_entries = _valid_dataset_entries()
    del second_entries[f"{trainer.IMAGES_DIRNAME}/frame1.jpg"]
    second_entries[f"{trainer.IMAGES_DIRNAME}/frame2.jpg"] = b"new-jpeg-bytes"
    second_entries[f"{trainer.LABELS_DIRNAME}/frame1.txt"] = b"REPLACED\n"
    second = _zip_bytes(second_entries)

    ack2 = servicer.UploadDataset(iter(_chunks("ds-1", second)), FakeContext())
    assert ack2.ok is True

    dataset_dir = datasets_root / "ds-1"
    assert _extracted_names(dataset_dir) == {"data.yaml", "images/frame2.jpg", "labels/frame1.txt"}
    assert (dataset_dir / trainer.LABELS_DIRNAME / "frame1.txt").read_bytes() == b"REPLACED\n"
    # still exactly one dataset dir under the root -- no orphaned temp dirs.
    assert list(datasets_root.iterdir()) == [dataset_dir]


def test_upload_dataset_blank_id_aborts(tmp_path: Path):
    servicer = TrainingServicer(dataset_dir=tmp_path)
    context = FakeContext()

    with pytest.raises(_AbortError):
        servicer.UploadDataset(iter([cv_pb2.DatasetChunk(dataset_id="", content=b"x")]), context)

    assert context.code == grpc.StatusCode.INVALID_ARGUMENT


def test_upload_dataset_mid_stream_id_change_aborts(tmp_path: Path):
    servicer = TrainingServicer(dataset_dir=tmp_path)
    context = FakeContext()
    chunks = [
        cv_pb2.DatasetChunk(dataset_id="ds-1", content=b"aa"),
        cv_pb2.DatasetChunk(dataset_id="ds-2", content=b"bb"),
    ]

    with pytest.raises(_AbortError):
        servicer.UploadDataset(iter(chunks), context)

    assert context.code == grpc.StatusCode.INVALID_ARGUMENT


@pytest.mark.parametrize("bad_id", ["../evil", "a/b", "a\\b", "..", ".", "  "])
def test_upload_dataset_unsafe_id_aborts(tmp_path: Path, bad_id: str):
    servicer = TrainingServicer(dataset_dir=tmp_path)
    context = FakeContext()

    with pytest.raises(_AbortError):
        servicer.UploadDataset(iter([cv_pb2.DatasetChunk(dataset_id=bad_id, content=b"x")]), context)

    assert context.code == grpc.StatusCode.INVALID_ARGUMENT
    # nothing landed, no litter.
    assert list(tmp_path.iterdir()) == []


def test_upload_dataset_corrupt_zip_returns_ok_false(tmp_path: Path):
    servicer = TrainingServicer(dataset_dir=tmp_path)
    garbage = b"this is not a zip file, just garbage bytes" * 5

    ack = servicer.UploadDataset(iter(_chunks("ds-1", garbage)), FakeContext())

    assert ack.ok is False
    assert ack.dataset_id == "ds-1"
    assert ack.bytes_received == len(garbage)
    assert "archive" in ack.message.lower()
    assert not (tmp_path / "ds-1").exists()
    assert list(tmp_path.iterdir()) == []  # no temp litter


@pytest.mark.parametrize(
    "bad_entry_name",
    [
        "../../etc/evil.txt",
        "/etc/evil.txt",
        "other/evil.txt",
        "readme.txt",
        "images/../../evil.txt",
    ],
)
def test_upload_dataset_rejects_zip_slip_entries(tmp_path: Path, bad_entry_name: str):
    servicer = TrainingServicer(dataset_dir=tmp_path)
    entries = _valid_dataset_entries()
    entries[bad_entry_name] = b"pwned"
    zip_bytes = _zip_bytes(entries)

    ack = servicer.UploadDataset(iter(_chunks("ds-1", zip_bytes)), FakeContext())

    assert ack.ok is False
    assert "unsafe entry" in ack.message
    assert not (tmp_path / "ds-1").exists()
    assert list(tmp_path.iterdir()) == []  # no temp litter, no partial extraction


def test_upload_dataset_malformed_extracted_tree_returns_ok_false(tmp_path: Path):
    """A zip that unzips fine but doesn't carry the full §5 layout (here:
    only data.yaml, no images/labels) is rejected by `resolve_dataset_dir`,
    unmodified -- reported, not aborted."""
    servicer = TrainingServicer(dataset_dir=tmp_path)
    zip_bytes = _zip_bytes({trainer.DATA_YAML_NAME: b"names: [x]\nnc: 1\n"})

    ack = servicer.UploadDataset(iter(_chunks("ds-1", zip_bytes)), FakeContext())

    assert ack.ok is False
    assert "ds-1" in ack.message
    assert not (tmp_path / "ds-1").exists()
    assert list(tmp_path.iterdir()) == []


def test_upload_dataset_zero_chunks_returns_ok_false(tmp_path: Path):
    servicer = TrainingServicer(dataset_dir=tmp_path)

    ack = servicer.UploadDataset(iter([]), FakeContext())

    assert ack.ok is False
    assert ack.dataset_id == ""
    assert "no dataset chunks" in ack.message.lower()
    assert list(tmp_path.iterdir()) == []


def test_upload_dataset_exceeds_size_cap_aborts(tmp_path: Path, monkeypatch):
    servicer = TrainingServicer(dataset_dir=tmp_path, max_upload_bytes=10)
    context = FakeContext()
    chunks = [
        cv_pb2.DatasetChunk(dataset_id="ds-1", content=b"0123456789"),  # exactly the cap: ok
        cv_pb2.DatasetChunk(dataset_id="ds-1", content=b"x"),  # tips it over
    ]

    with pytest.raises(_AbortError):
        servicer.UploadDataset(iter(chunks), context)

    assert context.code == grpc.StatusCode.RESOURCE_EXHAUSTED
    assert list(tmp_path.iterdir()) == []  # temp file cleaned up even on abort
