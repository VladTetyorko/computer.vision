"""Unit tests for `cv_service.training.dataset`: exported-YOLO dataset layout
resolution/validation.

Moved out of `tests/test_training.py` (now `tests/grpc/test_training_servicer.py`)
now that `resolve_dataset_dir`/`DatasetNotFoundError` live in
`cv_service.training.dataset` rather than `cv_service.training.trainer` --
`trainer.py` re-exports both names, so this test could still import them via
`trainer`, but importing directly from `dataset` matches where the code
actually is.

Stdlib-only -- no `cv`/gRPC extras needed to run these.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from cv_service.training import dataset


def _write_dataset(datasets_root: Path, dataset_id: str) -> Path:
    """Create a minimal valid exported-YOLO dataset dir (Phase 1 layout)."""
    dataset_dir = datasets_root / dataset_id
    (dataset_dir / dataset.IMAGES_DIRNAME).mkdir(parents=True)
    (dataset_dir / dataset.LABELS_DIRNAME).mkdir(parents=True)
    (dataset_dir / dataset.DATA_YAML_NAME).write_text(
        "names: [building]\nnc: 1\ntrain: images\nval: images\n", encoding="utf-8"
    )
    return dataset_dir


def test_resolve_dataset_dir_accepts_valid_layout(tmp_path: Path):
    _write_dataset(tmp_path, "good")
    resolved = dataset.resolve_dataset_dir(tmp_path, "good")
    assert resolved == tmp_path / "good"


def test_resolve_dataset_dir_rejects_missing(tmp_path: Path):
    with pytest.raises(dataset.DatasetNotFoundError):
        dataset.resolve_dataset_dir(tmp_path, "absent")


def test_resolve_dataset_dir_rejects_malformed(tmp_path: Path):
    # dir exists but has no data.yaml/images/labels.
    (tmp_path / "half").mkdir()
    with pytest.raises(dataset.DatasetNotFoundError):
        dataset.resolve_dataset_dir(tmp_path, "half")


def test_resolve_dataset_dir_rejects_empty_id(tmp_path: Path):
    with pytest.raises(dataset.DatasetNotFoundError):
        dataset.resolve_dataset_dir(tmp_path, "")


# --- sanitize_dataset_id / is_safe_dataset_id -------------------------------


def test_sanitize_dataset_id_strips_unsafe_characters():
    assert dataset.sanitize_dataset_id("a/b") == "a_b"
    assert dataset.sanitize_dataset_id("..") == "dataset"
    assert dataset.sanitize_dataset_id("") == "dataset"


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("good-id_1.0", True),
        ("", False),
        ("../evil", False),
        ("a/b", False),
        ("a\\b", False),
        ("..", False),
    ],
)
def test_is_safe_dataset_id(value, expected):
    assert dataset.is_safe_dataset_id(value) is expected


# --- is_safe_zip_entry -------------------------------------------------------


@pytest.mark.parametrize(
    ("name", "expected"),
    [
        ("data.yaml", True),
        ("images/frame1.jpg", True),
        ("labels/frame1.txt", True),
        ("", False),
        ("/etc/evil.txt", False),
        ("../../etc/evil.txt", False),
        ("other/evil.txt", False),
        ("readme.txt", False),
        ("images/../../evil.txt", False),
    ],
)
def test_is_safe_zip_entry(name, expected):
    assert dataset.is_safe_zip_entry(name) is expected
