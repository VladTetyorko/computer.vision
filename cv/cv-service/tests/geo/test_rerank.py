"""Unit tests for `cv_service.geo.rerank.evaluate_gates` (§4.2 G-a..G-f, frozen order/logic) and
`tile_id_to_filename`/`load_region_tile_image`.

`evaluate_gates` is pure logic (no cv2 call inside it) but the module imports cv2 at top level
(`score_candidate`'s MAGSAC path) -- gated with `importorskip` so this file skips cleanly, not
errors, without the `cv` extra.
"""

from __future__ import annotations

from pathlib import Path

import pytest

cv2 = pytest.importorskip("cv2")

from cv_service.geo.pose import PoseResult
from cv_service.geo.rerank import Candidate, CandidateScore, evaluate_gates, load_region_tile_image, tile_id_to_filename


def _candidate(tile_id: str = "17/76648/44197", distinctiveness=0.5) -> Candidate:
    return Candidate(tile_id=tile_id, lat=50.45, lon=30.52, similarity=0.8, distinctiveness=distinctiveness)


def _score(
    *, inlier_count: int = 20, match_count: int = 40, reprojection_rms_px=1.5, sanity_ok: bool = True,
    footprint_area_m2=1000.0, candidate: Candidate = None,
) -> CandidateScore:
    candidate = candidate if candidate is not None else _candidate()
    pose = PoseResult(
        ok=True, sanity_ok=sanity_ok, inlier_count=inlier_count,
        inlier_ratio=(inlier_count / match_count if match_count else 0.0), footprint_area_m2=footprint_area_m2,
    )
    return CandidateScore(
        candidate=candidate, match_count=match_count, mean_confidence=0.9, inlier_count=inlier_count,
        inlier_ratio=(inlier_count / match_count if match_count else 0.0),
        reprojection_rms_px=reprojection_rms_px, pose=pose, match_ms=1.0, fit_ms=1.0,
    )


def test_evaluate_gates_empty_scored_refuses_no_candidates():
    gates, refusal = evaluate_gates([], margin=0.0)
    assert gates == {}
    assert refusal == "NO_CANDIDATES"


def test_evaluate_gates_all_pass_no_refusal():
    scored = [_score(inlier_count=20, match_count=40, reprojection_rms_px=1.0)]
    gates, refusal = evaluate_gates(scored, margin=0.5, agl_footprint_area_m2=1000.0)
    assert refusal is None
    assert all(v is not False for v in gates.values())


def test_ga_inlier_floor_refuses_below_floor():
    scored = [_score(inlier_count=5, match_count=40)]
    gates, refusal = evaluate_gates(scored, margin=0.5, inlier_floor=8)
    assert refusal == "G-a_inlier_floor"
    assert gates["G-a_inlier_floor"] is False


def test_gb_inlier_ratio_refuses_below_ratio_even_with_many_raw_matches():
    """§13.2 Slice P's own lesson: 81 matches on a wrong tile -- inlier COUNT alone (G-a) is not
    enough; the ratio (G-b) must also clear."""
    scored = [_score(inlier_count=10, match_count=200)]  # ratio 0.05, floor clears (>=8) but ratio doesn't
    gates, refusal = evaluate_gates(scored, margin=0.5, inlier_floor=8, min_inlier_ratio=0.35)
    assert gates["G-a_inlier_floor"] is True
    assert refusal == "G-b_inlier_ratio"


def test_gc_residual_ceiling_refuses_a_thin_but_exact_fit():
    scored = [_score(inlier_count=20, match_count=40, reprojection_rms_px=9.0)]
    gates, refusal = evaluate_gates(scored, margin=0.5, max_reprojection_rms_px=4.0)
    assert refusal == "G-c_residual_ceiling"


def test_gc_residual_ceiling_refuses_when_no_homography_could_be_fit():
    scored = [_score(inlier_count=20, match_count=40, reprojection_rms_px=None)]
    gates, refusal = evaluate_gates(scored, margin=0.5)
    assert gates["G-c_residual_ceiling"] is False
    assert refusal == "G-c_residual_ceiling"


def test_gd_rerank_margin_refuses_a_near_tie():
    """§12.13's alias-class shape: believable inliers, but a near-tied second candidate."""
    scored = [_score(inlier_count=20, match_count=40, reprojection_rms_px=1.0)]
    gates, refusal = evaluate_gates(scored, margin=0.05, min_rerank_margin=0.15)
    assert refusal == "G-d_rerank_margin"


def test_ge_cell_calibration_refuses_a_never_accept_cell():
    scored = [_score(inlier_count=20, match_count=40)]
    gates, refusal = evaluate_gates(
        scored, margin=0.5, cell_is_never_accept_fn=lambda c: True,
    )
    assert gates["G-e_cell_calibration"] is False
    assert refusal == "G-e_cell_calibration"


def test_ge_cell_calibration_none_when_no_calibration_fn_given():
    scored = [_score(inlier_count=20, match_count=40)]
    gates, refusal = evaluate_gates(scored, margin=0.5, cell_is_never_accept_fn=None)
    assert gates["G-e_cell_calibration"] is None
    assert refusal is None  # a None gate never itself refuses


def test_gf_footprint_sanity_refuses_an_implausible_footprint_ratio():
    """A fitted footprint 10x larger than AGL+FOV would imply -- outside the [0.25, 4]x band."""
    scored = [_score(inlier_count=20, match_count=40, footprint_area_m2=10_000.0)]
    gates, refusal = evaluate_gates(scored, margin=0.5, agl_footprint_area_m2=1000.0)
    assert refusal == "G-f_footprint_sanity"


def test_gf_footprint_sanity_falls_back_to_pose_sanity_ok_without_agl():
    scored = [_score(inlier_count=20, match_count=40, sanity_ok=False)]
    gates, refusal = evaluate_gates(scored, margin=0.5, agl_footprint_area_m2=None)
    assert gates["G-f_footprint_sanity"] is False
    assert refusal == "G-f_footprint_sanity"


def test_gate_order_first_failure_wins_not_last():
    """Both G-a and G-d fail here -- G-a (earlier in `_GATE_ORDER`) must be the reported refusal,
    not G-d."""
    scored = [_score(inlier_count=2, match_count=40)]  # fails G-a
    gates, refusal = evaluate_gates(scored, margin=0.0, inlier_floor=8, min_rerank_margin=0.15)
    assert refusal == "G-a_inlier_floor"


def test_tile_id_to_filename():
    assert tile_id_to_filename("17/76648/44197") == "17_76648_44197.jpg"


def test_load_region_tile_image_missing_returns_none(tmp_path: Path):
    assert load_region_tile_image(tmp_path, "17/1/1") is None
