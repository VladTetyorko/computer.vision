"""H0c self-test (VISUAL-GEO-V2-PLAN.md §9.8 defect 1): the persisted region index must contain
EVERY tile whose image decodes successfully off disk -- not just the `holdout_fraction` (default
10%) `orchestrator.build_region_index` calibrates `accept_similarity`/`accept_margin` against.

Before the fix, `build_region_index` persisted the CALIBRATION-ONLY split (`reference_items`,
90% of tiles) directly -- the held-out 10% never entered `descriptors.npy`/`tiles.json` at all, so
a live query could never localize onto one of those cells no matter how good the match. Measured
concretely on kyiv-maidan: the Pexels clip's own ground-truth cell (17/76649/44196) was always one
of the held-out tiles, `n_exact_cell_indexed: 0` for all 12 real frames, both k=10 and k=20
(`spikes/geo/results/h0b/rank_shift.json`).

Runs offline against a synthetic region + a tiny torch-free fake `Encoder` (the DI idiom
`harvested/encoder.py`'s own docstring names) -- no network, no real tiles, no model download.
"""
from __future__ import annotations

from pathlib import Path

import cv2
import numpy as np
import pytest

from spikes.geo.harvested import calibrate
from spikes.geo.harvested import index as geo_index
from spikes.geo.harvested import orchestrator
from spikes.geo.harvested.encoder import Encoder


class _TinyFakeEncoder(Encoder):
    """Deterministic, torch-free stand-in: a fixed seeded random projection of the mean BGR
    pixel, L2-normalized. Not meant to discriminate real tiles -- only to exercise
    `build_region_index`'s coverage bookkeeping without a torch.hub download."""

    name = "tiny-fake"
    dim = 8

    def __init__(self) -> None:
        rng = np.random.RandomState(20260819)
        self._projection = rng.normal(size=(3, self.dim)).astype(np.float32)

    def encode(self, image: np.ndarray) -> np.ndarray:
        mean_bgr = image.reshape(-1, 3).mean(axis=0).astype(np.float32)
        vector = mean_bgr @ self._projection
        norm = float(np.linalg.norm(vector))
        return vector / norm if norm > 1e-9 else vector


def _write_synthetic_region(
    tmp_path: Path, *, n_x: int, n_y: int, zoom: int = 17, base_x: int = 76646, base_y: int = 44193
) -> Path:
    region_dir = tmp_path / "synthetic-region"
    tiles_dir = region_dir / "tiles"
    tiles_dir.mkdir(parents=True)
    rng = np.random.RandomState(1)
    for dx in range(n_x):
        for dy in range(n_y):
            x, y = base_x + dx, base_y + dy
            image = rng.randint(0, 256, size=(16, 16, 3)).astype(np.uint8)
            cv2.imwrite(str(tiles_dir / f"{zoom}_{x}_{y}.jpg"), image)
    return region_dir


def test_every_readable_tile_is_indexed(tmp_path: Path):
    # 30 tiles, well above the 1-tile holdout floor -- holdout_fraction=0.10 holds out 3 of them.
    region_dir = _write_synthetic_region(tmp_path, n_x=6, n_y=5)
    n_written = 30
    encoder = _TinyFakeEncoder()

    stats = orchestrator.build_region_index(
        region_dir, encoder, on_phase=lambda *a: None, is_cancelled=lambda: False
    )

    assert stats.tile_count == n_written
    # The defect: this used to be 27 (the 90% reference split), never 30.
    assert stats.descriptor_count == n_written

    persisted = geo_index.ReferenceIndex.load(region_dir)
    assert len(persisted) == n_written

    on_disk_tile_ids = set()
    for path in (region_dir / "tiles").glob("*.jpg"):
        zxy = geo_index.parse_tile_filename(path.name)
        assert zxy is not None
        zoom, x, y = zxy
        on_disk_tile_ids.add(f"{zoom}/{x}/{y}")

    persisted_tile_ids = {t.tile_id for t in persisted.tiles}
    assert persisted_tile_ids == on_disk_tile_ids, (
        "every tile that decoded off disk must be searchable -- "
        f"missing: {on_disk_tile_ids - persisted_tile_ids}"
    )
    # Slice A distinctiveness is still populated for every persisted tile (recomputed over the
    # full set now, not just the calibration split -- see orchestrator.py's own comment).
    assert all(t.distinctiveness is not None for t in persisted.tiles)


def test_holdout_tiles_are_still_indexed_and_calibration_still_measured(tmp_path: Path):
    region_dir = _write_synthetic_region(tmp_path, n_x=6, n_y=5)
    encoder = _TinyFakeEncoder()
    stats = orchestrator.build_region_index(
        region_dir, encoder, on_phase=lambda *a: None, is_cancelled=lambda: False, seed=20260819
    )
    # Calibration ran (measured some recall figure) -- folding the holdout tiles into the
    # persisted index afterward must not turn calibration into a no-op.
    assert 0.0 <= stats.holdout_recall_at_1 <= 1.0
    assert stats.accept_similarity <= calibrate.NEVER_ACCEPT_SIMILARITY


def test_single_tile_region_indexes_its_only_tile(tmp_path: Path):
    # Degenerate size (holdout_count=0 when len(parsed)<=1) -- guards the coverage self-test
    # doesn't misfire on the smallest possible region.
    region_dir = _write_synthetic_region(tmp_path, n_x=1, n_y=1)
    encoder = _TinyFakeEncoder()
    stats = orchestrator.build_region_index(
        region_dir, encoder, on_phase=lambda *a: None, is_cancelled=lambda: False
    )
    assert stats.tile_count == 1
    assert stats.descriptor_count == 1
    persisted = geo_index.ReferenceIndex.load(region_dir)
    assert len(persisted) == 1


def test_unreadable_tile_file_is_excluded_not_counted_as_missing(tmp_path: Path):
    region_dir = _write_synthetic_region(tmp_path, n_x=6, n_y=5)
    # Corrupt one tile file post-write -- build_region_index must skip it (both loops already
    # guard on `image is None`) without tripping the coverage self-test on the remaining 29.
    corrupt_path = next((region_dir / "tiles").glob("*.jpg"))
    corrupt_path.write_bytes(b"not a jpeg")
    encoder = _TinyFakeEncoder()

    stats = orchestrator.build_region_index(
        region_dir, encoder, on_phase=lambda *a: None, is_cancelled=lambda: False
    )

    assert stats.tile_count == 30  # still counts every FILE found
    assert stats.descriptor_count == 29  # but only the 29 that actually decoded
