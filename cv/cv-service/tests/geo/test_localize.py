"""Integration tests for `cv_service.geo.localize.localize_frame` -- the full frozen §4.1
pipeline (texture gate -> retrieve -> re-rank -> §4.2 gates -> sequence filter update), driven
end-to-end with an INJECTED fake `Encoder`/matcher (no real torch/kornia/network needed) -- same
"explicit injection beats a real backend" seam `InferenceServicer.detector=` already uses, and the
same approach `GeolocationServicer`'s own constructor DI supports.

`rerank_mod.load_region_tile_image` is monkeypatched (not a real `tiles/*.jpg` fixture) so these
tests need no on-disk region at all -- only `cv2`/`numpy` (the `cv` extra).
"""

from __future__ import annotations

import numpy as np
import pytest

cv2 = pytest.importorskip("cv2")

from cv_service.geo import index as geo_index
from cv_service.geo import localize as loc
from cv_service.geo import rerank as rerank_mod
from cv_service.geo.matchers import MatchKeypoints
from cv_service.geo.sequence import SequenceLocalizer

# A 5x5 zoom-17 grid; tile (row 12, the grid center) is the "true" tile every fake matches.
TILE_IDS = [f"17/{76640 + i}/{44190 + j}" for i in range(5) for j in range(5)]
TRUE_ROW = 12


def _region(accept_similarity: float = 0.5, holdout_recall_at_1: float = 0.9, distinctiveness=0.5) -> loc.RegionIndex:
    tiles, rows = [], []
    for i, tid in enumerate(TILE_IDS):
        zoom, x, y = (int(p) for p in tid.split("/"))
        lat, lon = geo_index.tile_center(x, y, zoom)
        tiles.append(geo_index.ReferenceTileMeta(tile_id=tid, lat=lat, lon=lon, distinctiveness=distinctiveness))
        v = np.zeros(8, dtype=np.float32)
        v[i % 8] = 1.0
        rows.append(v)
    index = geo_index.ReferenceIndex(tiles, np.stack(rows))
    stats = geo_index.ReferenceIndexStats(
        tile_count=25, descriptor_count=25, descriptor_dim=8, encoder_id="fake",
        accept_similarity=accept_similarity, accept_margin=0.05, holdout_recall_at_1=holdout_recall_at_1,
        holdout_median_error_meters=50.0, index_bytes=1000, never_accept_cells=0,
    )
    return loc.RegionIndex(region_id="test-region", index=index, stats=stats)


class FakeEncoder:
    dim = 8
    name = "fake"

    def encode(self, image):
        v = np.zeros(8, dtype=np.float32)
        v[TRUE_ROW % 8] = 1.0  # always retrieves TILE_IDS[TRUE_ROW] as the top candidate
        return v


class FakeMatcherHandle:
    pass


def _good_match_keypoints(handle, query_bgr, tile_bgr):
    """`n` inliers on the tile whose fake pixel marker is 200 (the true tile, see `_fake_load_tile`
    below), a bare few (below the inlier floor) on everything else -- so re-rank has a real,
    unambiguous geometric winner, not just a retrieval-similarity one."""
    tile_marker = int(tile_bgr[0, 0, 0])
    n = 30 if tile_marker == 200 else 3
    rng = np.random.default_rng(tile_marker)
    kp_query = rng.uniform(10, 200, size=(n, 2))
    kp_tile = kp_query.copy()  # identity mapping (both 256px) -> clean inliers when it's the true tile
    return MatchKeypoints(kp_query=kp_query, kp_tile=kp_tile, confidence=np.ones(n))


def _no_match_keypoints(handle, query_bgr, tile_bgr):
    """Every candidate scores a handful of matches -- never clears the inlier floor."""
    rng = np.random.default_rng(1)
    n = 3
    kp = rng.uniform(10, 200, size=(n, 2))
    return MatchKeypoints(kp_query=kp, kp_tile=kp.copy(), confidence=np.ones(n))


_TILE_MARKERS = {tid: (200 if i == TRUE_ROW else 50) for i, tid in enumerate(TILE_IDS)}


def _fake_load_tile(region_dir, tile_id):
    marker = _TILE_MARKERS.get(tile_id, 50)
    return np.full((256, 256, 3), marker, dtype=np.uint8)


@pytest.fixture(autouse=True)
def _patch_tile_loader(monkeypatch):
    monkeypatch.setattr(rerank_mod, "load_region_tile_image", _fake_load_tile)


def _textured_frame() -> np.ndarray:
    rng = np.random.default_rng(1)
    image = np.full((240, 320, 3), 100, dtype=np.uint8).astype(np.int16)
    image = (image + rng.integers(-40, 40, size=image.shape)).clip(0, 255).astype(np.uint8)
    return image


def _params(**overrides) -> loc.LocalizeParams:
    kwargs = dict(max_candidates=5, min_entropy=0.0, min_laplacian_variance=0.0)
    kwargs.update(overrides)
    return loc.LocalizeParams(**kwargs)


# --- B: quality gate ------------------------------------------------------------------------------


def test_flat_frame_is_rejected_low_texture_before_any_region_lookup():
    flat = np.full((100, 100, 3), 128, dtype=np.uint8)
    result = loc.localize_frame(
        flat, encoder=FakeEncoder(), regions=[], region_dir_by_id={},
        matcher_handle=FakeMatcherHandle(), match_keypoints_fn=_no_match_keypoints,
        params=_params(min_laplacian_variance=50.0, min_entropy=3.0),
    )
    assert result.status == loc.STATUS_LOW_TEXTURE
    assert result.refusal == "LOW_TEXTURE"


# --- no regions -------------------------------------------------------------------------------


def test_no_regions_returns_no_index():
    result = loc.localize_frame(
        _textured_frame(), encoder=FakeEncoder(), regions=[], region_dir_by_id={},
        matcher_handle=FakeMatcherHandle(), match_keypoints_fn=_no_match_keypoints, params=_params(),
    )
    assert result.status == loc.STATUS_NO_INDEX
    assert result.refusal == "NO_INDEX"


# --- E/F/G: retrieval + re-rank + gates ---------------------------------------------------------


def test_clean_match_produces_a_fix_on_the_true_tile():
    region = _region()
    result = loc.localize_frame(
        _textured_frame(), encoder=FakeEncoder(), regions=[region],
        region_dir_by_id={"test-region": "/fake"}, matcher_handle=FakeMatcherHandle(),
        match_keypoints_fn=_good_match_keypoints, params=_params(),
    )
    assert result.status == loc.STATUS_FIX
    assert result.tile_id == TILE_IDS[TRUE_ROW]
    assert result.latitude is not None and result.longitude is not None
    assert result.evidence.inlier_count >= 8


def test_no_geometric_confirmation_refuses_no_fix_even_with_a_retrieval_hit():
    """Retrieval alone (embedding similarity) must never promote to FIX without geometric
    confirmation -- §13.2 Slice P's own lesson, exercised end-to-end here."""
    region = _region()
    result = loc.localize_frame(
        _textured_frame(), encoder=FakeEncoder(), regions=[region],
        region_dir_by_id={"test-region": "/fake"}, matcher_handle=FakeMatcherHandle(),
        match_keypoints_fn=_no_match_keypoints, params=_params(),
    )
    assert result.status == loc.STATUS_NO_FIX
    assert result.refusal is not None


def test_never_accept_region_refuses_via_ge_cell_calibration():
    region = _region(accept_similarity=1.0, holdout_recall_at_1=0.0)  # is_never_accept == True
    result = loc.localize_frame(
        _textured_frame(), encoder=FakeEncoder(), regions=[region],
        region_dir_by_id={"test-region": "/fake"}, matcher_handle=FakeMatcherHandle(),
        match_keypoints_fn=_good_match_keypoints, params=_params(),
    )
    assert result.status == loc.STATUS_NO_FIX
    assert result.refusal == "G-e_cell_calibration"


def test_prior_outside_region_returns_out_of_region():
    region = _region()
    far_prior = (0.0, 0.0, 1000.0)  # nowhere near the region's Kyiv-latitude tiles
    result = loc.localize_frame(
        _textured_frame(), encoder=FakeEncoder(), regions=[region],
        region_dir_by_id={"test-region": "/fake"}, matcher_handle=FakeMatcherHandle(),
        match_keypoints_fn=_good_match_keypoints, prior=far_prior, params=_params(),
    )
    assert result.status == loc.STATUS_OUT_OF_REGION


# --- J: sequence filter integration --------------------------------------------------------------


def test_sequence_filter_advances_and_can_converge_across_frames():
    region = _region()
    sequence = SequenceLocalizer.from_tile_ids(
        [t.tile_id for t in region.index.tiles], seed=3, n_particles=500, min_supporting_frames=3,
        min_baseline_m=20.0,
    )
    last = None
    for _ in range(8):
        last = loc.localize_frame(
            _textured_frame(), encoder=FakeEncoder(), regions=[region],
            region_dir_by_id={"test-region": "/fake"}, matcher_handle=FakeMatcherHandle(),
            match_keypoints_fn=_good_match_keypoints, sequence=sequence,
            motion_delta_e_m=8.0, motion_delta_n_m=0.0, params=_params(),
        )
    assert last.status == loc.STATUS_FIX
    assert last.evidence.sequence_updates == 8
    assert last.evidence.sequence_converged is True
    assert last.evidence.cell_calibrated is True


def test_sequence_filter_never_converges_on_a_hover_even_with_repeated_fixes():
    """The §4.4/§12.14 structural guarantee, exercised through the real `localize_frame` entry
    point (not just `SequenceLocalizer` in isolation, `test_sequence.py` already covers that): zero
    real motion between frames must keep `sequence_converged=False` in the wire evidence, no
    matter how many consistent FIXes land on the same tile."""
    region = _region()
    sequence = SequenceLocalizer.from_tile_ids(
        [t.tile_id for t in region.index.tiles], seed=3, n_particles=500, min_supporting_frames=3,
        min_baseline_m=20.0,
    )
    last = None
    for _ in range(8):
        last = loc.localize_frame(
            _textured_frame(), encoder=FakeEncoder(), regions=[region],
            region_dir_by_id={"test-region": "/fake"}, matcher_handle=FakeMatcherHandle(),
            match_keypoints_fn=_good_match_keypoints, sequence=sequence,
            motion_delta_e_m=0.0, motion_delta_n_m=0.0, params=_params(),
        )
    assert last.status == loc.STATUS_FIX  # single-frame path still fixes every time
    assert last.evidence.sequence_converged is False  # but the sequence filter must not confirm it


# --- telemetry_motion_delta -----------------------------------------------------------------------


def test_telemetry_motion_delta_zero_without_two_samples():
    assert loc.telemetry_motion_delta(None, None) == (0.0, 0.0)
    t = loc.Telemetry(latitude=50.45, longitude=30.52)
    assert loc.telemetry_motion_delta(None, t) == (0.0, 0.0)


def test_telemetry_motion_delta_north_positive_for_increasing_latitude():
    prev = loc.Telemetry(latitude=50.450, longitude=30.520)
    curr = loc.Telemetry(latitude=50.451, longitude=30.520)
    east, north = loc.telemetry_motion_delta(prev, curr)
    assert north > 0
    assert east == pytest.approx(0.0, abs=1e-6)
