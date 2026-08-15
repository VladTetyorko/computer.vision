"""Exported-YOLO dataset layout, path-safety guards, and zip landing for
``Training.UploadDataset`` (docs/plans/done/CV-TRAINING-V2-PLAN.md §2).

Stdlib-only (``os``, ``shutil``, ``zipfile``, ``uuid``, ``pathlib``) --
importable without the ``cv`` extra, same discipline as
``cv_service/training/marker.py``. This module never touches generated
``cv_pb2`` types: ``cv_service/grpc/servicers.py`` is the sole translation
point, and turns :class:`UploadOutcome` into ``cv_pb2.UploadAck`` itself.

``sanitize_dataset_id`` is the **one** definition of the filesystem-safe
sanitize rule -- previously duplicated between ``server.py``'s own
``_sanitize_dataset_id`` and an inline copy inside ``trainer.output_model_id``.
``cv_service/training/trainer.py`` imports it from here.
"""

from __future__ import annotations

import os
import shutil
import uuid
import zipfile
from dataclasses import dataclass
from pathlib import Path

# The YOLO dataset manifest + subdirectories a dataset dir must contain to be
# trainable (Phase 1's FilesystemDatasetExport layout). Format contract
# shared with another process (the platform's dataset exporter) -- these
# names are NOT environment/tuning literals, so they stay plain constants
# (see docs/plans/active/LAYERING-REFACTOR-PLAN.md §1.3's "explicitly out of scope" list).
DATASET_DIRNAME = "datasets"
DATA_YAML_NAME = "data.yaml"
IMAGES_DIRNAME = "images"
LABELS_DIRNAME = "labels"

# The frozen layout a dataset archive's entries must live under (same
# rationale as above).
_UPLOAD_ALLOWED_DIR_PREFIXES = (f"{IMAGES_DIRNAME}/", f"{LABELS_DIRNAME}/")


class DatasetNotFoundError(Exception):
    """The requested dataset is missing/malformed on the training host.

    A *normal, reported* outcome (``StartTraining`` turns it into a terminal
    ``TrainingProgress{state:FAILED}`` and ends the stream), never a gRPC
    abort -- a dataset that hasn't been rsync'd/uploaded in yet is expected,
    not an error condition of the service.
    """


def resolve_dataset_dir(datasets_root: Path, dataset_id: str) -> Path:
    """Resolve + validate ``<datasets_root>/<dataset_id>/``.

    Returns the dataset directory if it exists and carries the YOLO layout
    (``data.yaml`` + ``images/`` + ``labels/``). Raises
    :class:`DatasetNotFoundError` otherwise -- the caller reports it as a
    terminal ``FAILED`` progress, not a gRPC abort.
    """
    if not dataset_id:
        raise DatasetNotFoundError("dataset id must not be empty")
    dataset_dir = Path(datasets_root) / dataset_id
    if not dataset_dir.is_dir():
        raise DatasetNotFoundError(
            f"dataset {dataset_id!r} isn't on the training host -- export it and copy it in first"
        )
    data_yaml = dataset_dir / DATA_YAML_NAME
    images_dir = dataset_dir / IMAGES_DIRNAME
    labels_dir = dataset_dir / LABELS_DIRNAME
    missing = [
        name
        for name, present in (
            (DATA_YAML_NAME, data_yaml.is_file()),
            (f"{IMAGES_DIRNAME}/", images_dir.is_dir()),
            (f"{LABELS_DIRNAME}/", labels_dir.is_dir()),
        )
        if not present
    ]
    if missing:
        raise DatasetNotFoundError(
            f"dataset {dataset_id!r} on the training host is malformed (missing {', '.join(missing)}) "
            "-- re-export it and copy it in first"
        )
    return dataset_dir


def sanitize_dataset_id(dataset_id: str) -> str:
    """Filesystem-safe basename for `dataset_id` -- the ONE definition (was
    duplicated between `server.py`'s own sanitize step and an inline copy
    inside `trainer.output_model_id`, which now imports this instead of
    re-implementing it, before its own `-<epochs>e.pt` suffix is appended).
    """
    safe = "".join(c if (c.isalnum() or c in "-_.") else "_" for c in dataset_id)
    return safe.strip("._") or "dataset"


def is_safe_dataset_id(dataset_id: str) -> bool:
    """`UploadDataset`'s accept test for a `dataset_id`: anything
    `sanitize_dataset_id` would rewrite (blank, a path separator, `..`, ...)
    is rejected outright rather than silently accepted under a different
    name -- caller turns a `False` into `INVALID_ARGUMENT`.
    """
    return bool(dataset_id) and sanitize_dataset_id(dataset_id) == dataset_id


def is_safe_zip_entry(name: str) -> bool:
    """Zip-slip protection for one archive member's path: accepts only the
    frozen layout (`data.yaml`, `images/<name>`, `labels/<stem>.txt`),
    rejecting an absolute path, a `..` path segment, or any other top-level
    prefix outright.
    """
    if not name:
        return False
    normalized = name.replace("\\", "/")
    if normalized.startswith("/") or ":" in normalized:
        return False
    parts = normalized.split("/")
    if ".." in parts or any(part == "" for part in parts[:-1]):
        return False
    if normalized == DATA_YAML_NAME:
        return True
    return normalized.startswith(_UPLOAD_ALLOWED_DIR_PREFIXES)


@dataclass(frozen=True)
class UploadOutcome:
    """Plain (wire-agnostic) result of landing an uploaded dataset archive.

    `cv_service/grpc/servicers.py` is the only place that turns this into a
    `cv_pb2.UploadAck` -- this module never imports `cv_pb2` (see module
    docstring).
    """

    ok: bool
    message: str
    dataset_id: str = ""
    bytes_received: int = 0
    file_count: int = 0


def land_dataset(
    zip_path: Path, datasets_root: Path, dataset_id: str, bytes_received: int
) -> UploadOutcome:
    """Validate + extract `zip_path` into a sibling temp dir under
    `datasets_root`, then atomically replace `<datasets_root>/<dataset_id>/`
    (remove-then-`os.replace`). Every failure from here on is a *reported*
    `ok=False` outcome -- never raised -- matching `Training.UploadDataset`'s
    "content problems are reported, not aborted" contract (see
    `cv_service/grpc/servicers.py`'s `UploadDataset` docstring).
    """
    temp_dir = datasets_root / f".upload-{dataset_id}-{uuid.uuid4().hex}"
    try:
        with zipfile.ZipFile(zip_path) as zf:
            bad_entry = zf.testzip()
            if bad_entry is not None:
                return UploadOutcome(
                    ok=False,
                    dataset_id=dataset_id,
                    bytes_received=bytes_received,
                    message=f"corrupt dataset archive (bad entry: {bad_entry!r})",
                )
            members = [member for member in zf.infolist() if not member.is_dir()]
            for member in members:
                if not is_safe_zip_entry(member.filename):
                    return UploadOutcome(
                        ok=False,
                        dataset_id=dataset_id,
                        bytes_received=bytes_received,
                        message=f"dataset archive contains an unsafe entry: {member.filename!r}",
                    )
            temp_dir.mkdir()
            zf.extractall(path=temp_dir, members=members)
    except (zipfile.BadZipFile, OSError, EOFError) as exc:
        shutil.rmtree(temp_dir, ignore_errors=True)
        return UploadOutcome(
            ok=False,
            dataset_id=dataset_id,
            bytes_received=bytes_received,
            message=f"corrupt or unreadable dataset archive: {exc}",
        )

    try:
        resolve_dataset_dir(datasets_root, temp_dir.name)
    except DatasetNotFoundError:
        shutil.rmtree(temp_dir, ignore_errors=True)
        return UploadOutcome(
            ok=False,
            dataset_id=dataset_id,
            bytes_received=bytes_received,
            message=(
                f"dataset {dataset_id!r} archive is malformed -- must contain "
                f"{DATA_YAML_NAME}, {IMAGES_DIRNAME}/, and {LABELS_DIRNAME}/"
            ),
        )

    file_count = sum(1 for path in temp_dir.rglob("*") if path.is_file())

    final_dir = datasets_root / dataset_id
    if final_dir.exists():
        shutil.rmtree(final_dir)
    os.replace(temp_dir, final_dir)

    return UploadOutcome(
        ok=True,
        dataset_id=dataset_id,
        bytes_received=bytes_received,
        file_count=file_count,
        message=f"dataset {dataset_id!r} ready ({file_count} files)",
    )
