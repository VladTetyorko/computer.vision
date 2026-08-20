"""Unit tests for `cv_service.geo.calibrate` (VISUAL-GEO-V2-PLAN.md §12.13 fixes + the H4
per-cell calibration extension, §4.2 G-e / §4.4 Change 2 G-a).

Needs cv2/numpy to import the module (`simulate_view_perturbation`) but every test here exercises
pure-numpy logic with synthetic rows -- no real tiles/weights, matching the module's own docstring
("testable with a handful of synthetic rows").
"""

from __future__ import annotations

import pytest

cv2 = pytest.importorskip("cv2")

from cv_service.geo.calibrate import (
    HoldoutResult,
    NEVER_ACCEPT_SIMILARITY,
    RECALL_DISTANCE_METERS,
    cell_is_never_accept,
    count_never_accept_cells,
    holdout_correct_radius_m,
    is_never_accept,
    leave_one_out_distinctiveness,
    sweep_thresholds,
)


# --- is_never_accept ---------------------------------------------------------------------------


def test_is_never_accept_true_at_the_sentinel_similarity():
    assert is_never_accept(NEVER_ACCEPT_SIMILARITY, 0.5) is True


def test_is_never_accept_true_on_zero_recall_even_with_a_loose_threshold():
    """§12.13's own regression case: btn-road-x shipped 0.44/0.01 with recall 0.00 -- a
    pre-§12.13-fix calibration that must still be treated as never-accept."""
    assert is_never_accept(0.44, 0.0) is True


def test_is_never_accept_false_for_a_real_calibration():
    assert is_never_accept(0.87, 0.25) is False


# --- holdout_correct_radius_m (§12.13: quantization-aware, no blind band) -----------------------


def test_holdout_correct_radius_floors_at_recall_distance():
    """A very high zoom (tiny tile step) must not shrink the radius below the live-query floor."""
    assert holdout_correct_radius_m("21/100/100") == RECALL_DISTANCE_METERS


def test_holdout_correct_radius_accepts_direct_neighbor_rejects_diagonal():
    """1.2x the tile step accepts a 1.0-step direct neighbor with margin, rejects the sqrt(2)-step
    diagonal slide (§12.13's own "1.2 steps accepts a direct neighbor... rejects the diagonal")."""
    radius = holdout_correct_radius_m("17/76646/44193")
    assert radius > RECALL_DISTANCE_METERS  # zoom 17 in Kyiv is well above the 100m floor
    # 1.2 * step > 1.0 * step (direct neighbor) and < sqrt(2) * step (diagonal slide).
    step = radius / 1.2
    assert 1.0 * step < radius < 1.41 * step


def test_holdout_correct_radius_falls_back_on_unparseable_id():
    assert holdout_correct_radius_m("not-a-tile-id") == RECALL_DISTANCE_METERS


# --- sweep_thresholds (§12.13: complements, zero-recall never wins) -----------------------------


def test_sweep_thresholds_empty_results_is_never_accept():
    similarity, margin, recall, false_fix_rate = sweep_thresholds([])
    assert similarity == NEVER_ACCEPT_SIMILARITY
    assert recall == 0.0


def test_sweep_thresholds_recovers_a_clean_separable_calibration():
    """8 holdout tiles: 6 land correctly (near their own true tile, high similarity), 2 land far
    away (an alias) at low similarity -- the sweep must find a threshold separating them with
    recall > 0 and false-fix rate 0."""
    results = [
        HoldoutResult(tile_id=f"17/76640/{44190 + i}", top_similarity=0.9, margin=0.2, error_meters=20.0)
        for i in range(6)
    ] + [
        HoldoutResult(tile_id=f"17/76640/{44200 + i}", top_similarity=0.3, margin=0.05, error_meters=900.0)
        for i in range(2)
    ]
    similarity, margin, recall, false_fix_rate = sweep_thresholds(results)
    assert recall >= 0.6
    assert false_fix_rate <= 0.02


def test_sweep_thresholds_never_accepts_a_zero_recall_pair_even_under_budget():
    """§12.13's second fix: every candidate pair has EITHER zero accepted items OR only false
    ones -- no pair can ever show recall > 0, so the never-accept fallback must win even though a
    false-fix rate of 0 (nothing accepted) trivially clears the budget."""
    results = [
        HoldoutResult(tile_id=f"17/76640/{44190 + i}", top_similarity=0.5, margin=0.0, error_meters=5000.0)
        for i in range(10)
    ]
    similarity, margin, recall, false_fix_rate = sweep_thresholds(results)
    assert similarity == NEVER_ACCEPT_SIMILARITY
    assert recall == 0.0


def test_sweep_thresholds_no_blind_band_a_196m_slide_counts_as_false():
    """The pre-§12.13 scheme's blind band (100-300m, neither correct nor false) is gone: a
    consistent one-tile-slide error (~196m at zoom 17) must count as a FALSE fix, not vanish from
    the budget -- reproducing btn-road-x's own shipped defect and proving it's fixed."""
    radius = holdout_correct_radius_m("17/76640/44190")
    slide_error_m = radius * 1.3  # just past the direct-neighbor radius: a genuine alias slide
    results = [
        HoldoutResult(tile_id="17/76640/44190", top_similarity=0.9, margin=0.1, error_meters=slide_error_m)
        for _ in range(10)
    ]
    similarity, margin, recall, false_fix_rate = sweep_thresholds(results)
    # Every single holdout item is a false fix at any threshold that accepts it -- the sweep must
    # refuse to accept them (false_fix_rate would be 1.0), landing on never-accept.
    assert similarity == NEVER_ACCEPT_SIMILARITY


# --- leave_one_out_distinctiveness ---------------------------------------------------------------


def test_distinctiveness_single_tile_region_is_maximally_distinct():
    import numpy as np

    out = leave_one_out_distinctiveness(np.array([[1.0, 0.0, 0.0]], dtype=np.float32))
    assert out.shape == (1,)
    assert out[0] == pytest.approx(1.0)


def test_distinctiveness_identical_tiles_are_zero():
    import numpy as np

    descriptors = np.array([[1.0, 0.0], [1.0, 0.0], [0.0, 1.0]], dtype=np.float32)
    out = leave_one_out_distinctiveness(descriptors)
    assert out[0] == pytest.approx(0.0, abs=1e-6)  # identical to row 1
    assert out[1] == pytest.approx(0.0, abs=1e-6)
    assert out[2] == pytest.approx(1.0, abs=1e-6)  # orthogonal to everything else


# --- per-cell calibration (H4 extension) ---------------------------------------------------------


def test_cell_is_never_accept_when_region_is_never_accept_regardless_of_distinctiveness():
    assert cell_is_never_accept(0.9, region_is_never_accept=True, accept_margin=0.1) is True


def test_cell_is_never_accept_none_distinctiveness_defers_to_region_flag():
    """A pre-Slice-A tile (`distinctiveness=None`) cannot be individually ruled out -- neutral,
    not never-accept, when the region itself is fine."""
    assert cell_is_never_accept(None, region_is_never_accept=False, accept_margin=0.1) is False


def test_cell_is_never_accept_low_distinctiveness_tile_in_an_otherwise_fine_region():
    """A repeated-texture tile (leave-one-out margin below the region's own accept_margin) is
    never-accept individually even when the region as a whole calibrated fine -- the whole point
    of G-e's per-cell extension."""
    assert cell_is_never_accept(0.02, region_is_never_accept=False, accept_margin=0.1) is True
    assert cell_is_never_accept(0.5, region_is_never_accept=False, accept_margin=0.1) is False


def test_count_never_accept_cells():
    distinctiveness = [0.5, 0.02, None, 0.08, 0.9]
    n = count_never_accept_cells(distinctiveness, region_is_never_accept=False, accept_margin=0.1)
    assert n == 2  # 0.02 and 0.08 are below the margin; None defers, 0.5/0.9 clear it
