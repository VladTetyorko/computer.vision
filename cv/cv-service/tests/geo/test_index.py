"""Unit tests for `cv_service.geo.index` (VISUAL-GEO-V2-PLAN.md D1/§3.1 + the H4
`never_accept_cells`/Slice-A extensions).

`parse_tile_filename`/`tile_center`/`haversine_m`/`read_index_json`/`write_index_json` are pure
stdlib; `ReferenceIndex` needs numpy (imported lazily by the module itself) -- gated with
`importorskip` so this file still collects (and mostly runs) without the `geo`/`cv` extra.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from cv_service.geo.index import (
    INDEX_JSON_FILENAME,
    ReferenceIndexStats,
    haversine_m,
    parse_tile_filename,
    read_index_json,
    tile_center,
    write_index_json,
)


def test_parse_tile_filename_roundtrips():
    assert parse_tile_filename("17_76648_44197.jpg") == (17, 76648, 44197)


def test_parse_tile_filename_rejects_malformed():
    assert parse_tile_filename("not-a-tile.jpg") is None
    assert parse_tile_filename("17_76648.jpg") is None


def test_tile_center_is_between_nw_and_se_corners():
    lat, lon = tile_center(76648, 44197, 17)
    assert 50.0 < lat < 51.0  # Kyiv-latitude sanity, not a precise fixture
    assert 30.0 < lon < 31.0


def test_haversine_zero_for_identical_points():
    assert haversine_m(50.45, 30.52, 50.45, 30.52) == pytest.approx(0.0, abs=1e-6)


def test_haversine_matches_a_known_one_degree_latitude_span():
    # 1 degree of latitude is ~111.2 km everywhere.
    d = haversine_m(0.0, 0.0, 1.0, 0.0)
    assert d == pytest.approx(111_195.0, rel=0.01)


# --- index.json round trip + H4's backward-compatible neverAcceptCells default -------------------


def _stats(**overrides) -> ReferenceIndexStats:
    kwargs = dict(
        tile_count=10, descriptor_count=10, descriptor_dim=512, encoder_id="eigenplaces_r18_512",
        accept_similarity=0.87, accept_margin=0.05, holdout_recall_at_1=0.25,
        holdout_median_error_meters=194.5, index_bytes=1024, never_accept_cells=2,
    )
    kwargs.update(overrides)
    return ReferenceIndexStats(**kwargs)


def test_write_then_read_index_json_round_trips(tmp_path: Path):
    write_index_json(tmp_path, _stats(), built_at_millis=1_700_000_000_000)
    meta = read_index_json(tmp_path)
    assert meta is not None
    assert meta.stats == _stats()
    assert meta.built_at_millis == 1_700_000_000_000


def test_read_index_json_missing_file_returns_none(tmp_path: Path):
    assert read_index_json(tmp_path) is None


def test_read_index_json_corrupt_file_returns_none_not_raises(tmp_path: Path):
    (tmp_path / INDEX_JSON_FILENAME).write_text("{not json")
    assert read_index_json(tmp_path) is None


def test_read_index_json_defaults_never_accept_cells_when_absent(tmp_path: Path):
    """A real pre-Slice-A on-disk exercise of the backward-compat default: `kyiv-maidan`'s own
    committed `index.json` predates `neverAcceptCells` -- this reproduces that shape directly."""
    payload = {
        "tileCount": 49, "descriptorCount": 49, "descriptorDim": 512, "encoderId": "eigenplaces_r18_512",
        "acceptSimilarity": 0.87, "acceptMargin": 0.0, "holdoutRecallAt1": 0.25,
        "holdoutMedianErrorMeters": 194.5, "indexBytes": 56340, "builtAtMillis": 1787172760503,
        # no "neverAcceptCells" key at all
    }
    (tmp_path / INDEX_JSON_FILENAME).write_text(json.dumps(payload))
    meta = read_index_json(tmp_path)
    assert meta is not None
    assert meta.stats.never_accept_cells == 0


# --- ReferenceIndex (needs numpy) -----------------------------------------------------------------

np = pytest.importorskip("numpy")

from cv_service.geo.index import ReferenceIndex, ReferenceTileMeta  # noqa: E402


def _built_index(n: int = 4) -> ReferenceIndex:
    tiles = [ReferenceTileMeta(tile_id=f"17/{76640+i}/44190", lat=50.45 + i * 0.001, lon=30.52) for i in range(n)]
    descriptors = np.eye(n, dtype=np.float32)  # orthonormal -- each tile is its own perfect match
    return ReferenceIndex(tiles, descriptors)


def test_reference_index_rejects_mismatched_tile_and_descriptor_counts():
    with pytest.raises(ValueError):
        ReferenceIndex([ReferenceTileMeta(tile_id="17/1/1", lat=0.0, lon=0.0)], np.zeros((2, 4), dtype=np.float32))


def test_search_returns_the_exact_match_first():
    index = _built_index(4)
    query = np.array([0.0, 1.0, 0.0, 0.0], dtype=np.float32)  # exactly row 1
    results, _ms = index.search(query, top_k=2)
    assert results[0].tile.tile_id == "17/76641/44190"
    assert results[0].similarity == pytest.approx(1.0)


def test_search_empty_index_returns_nothing():
    index = ReferenceIndex([], np.zeros((0, 4), dtype=np.float32))
    results, _ms = index.search(np.array([1.0, 0.0, 0.0, 0.0], dtype=np.float32))
    assert results == []


def test_search_prior_restricts_to_the_geographic_disc():
    """A tight prior disc around tile 0 must exclude a tile far outside it, even if that far tile
    would otherwise win on pure similarity."""
    tiles = [
        ReferenceTileMeta(tile_id="17/0/0", lat=50.450, lon=30.520),
        ReferenceTileMeta(tile_id="17/0/1", lat=51.500, lon=31.500),  # ~140km away
    ]
    descriptors = np.array([[0.0, 1.0], [1.0, 0.0]], dtype=np.float32)
    index = ReferenceIndex(tiles, descriptors)
    query = np.array([1.0, 0.0], dtype=np.float32)  # matches tile 1 exactly, tile 0 not at all
    results, _ms = index.search(query, top_k=2, prior=(50.450, 30.520, 5000.0))
    assert len(results) == 1
    assert results[0].tile.tile_id == "17/0/0"  # only the in-disc tile survives, despite scoring 0


def test_save_then_load_round_trips(tmp_path: Path):
    index = _built_index(3)
    index.save(tmp_path)
    loaded = ReferenceIndex.load(tmp_path)
    assert len(loaded) == 3
    assert loaded.tiles[1].tile_id == index.tiles[1].tile_id
    np.testing.assert_allclose(loaded.descriptors, index.descriptors)


def test_save_omits_distinctiveness_key_when_unset(tmp_path: Path):
    index = _built_index(2)
    index.save(tmp_path)
    payload = json.loads((tmp_path / "tiles.json").read_text())
    assert "distinctiveness" not in payload[0]


def test_load_defaults_distinctiveness_to_none_when_absent(tmp_path: Path):
    index = _built_index(2)
    index.save(tmp_path)
    loaded = ReferenceIndex.load(tmp_path)
    assert loaded.tiles[0].distinctiveness is None
