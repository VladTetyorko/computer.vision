"""cv-service/spikes/geo/rerank.py

H0 deliverable (3)/(4)/(5) shared core: the frozen §4.2 re-rank scoring, wired to swap in any of
`spikes/geo/matchers.py`'s bake-off backends (xfeat / lightglue_aliked / lightglue_disk / eloftr /
loftr) in place of `harvested/verify.py`'s hardcoded `loftr_match_keypoints`. `harvested/verify.py`
is left untouched (it is a verbatim harvest of production `cv_service/geo/verify.py`, not a spike
surface) -- this module re-implements just its `verify()` scoring loop with the matcher and the
ranking key both swapped, per §4.2's frozen rule:

    "Score sᵢ = inlier_countᵢ. Reprojection RMS ρᵢ recorded. Ordering is by sᵢ descending."

`harvested/verify.py#verify()` instead ranks by (LoFTR match count, mean confidence) -- match
count, not MAGSAC inliers -- which is the OLD pre-v2 scoring (`13.2 Slice P`'s own named lesson:
"81 matches on a 725m-wrong tile -- counts alone are worthless"). `harvested/pose.py
#fit_homography_pose` already does the MAGSAC fit + inlier count/ratio + footprint sanity; this
module drives it per-candidate, ranks by its `inlier_count`, and adds the one field pose.py does
not compute: reprojection RMS `ρᵢ` over the inlier set (needed for gate G-c).

Conditioning is reused verbatim from `harvested/verify.py#condition_query` (Wave 6a's measured
de-rotate + GSD-rescale, "both together, never one").
"""
from __future__ import annotations

import logging
import math
import time
from dataclasses import dataclass, field
from typing import Callable, Optional

import cv2
import numpy as np

from spikes.geo.harvested.localize import Candidate
from spikes.geo.harvested.pose import MIN_MATCHES_FOR_FIT, RANSAC_REPROJ_PX, PoseResult, fit_homography_pose, parse_tile_id
from spikes.geo.harvested.verify import (
    DEFAULT_ASSUMED_HORIZONTAL_FOV_DEGREES,
    ConditionedQuery,
    MatchKeypoints,
    condition_query,
)

LOGGER = logging.getLogger("spikes.geo.rerank")

# §4.2's frozen gate defaults (== the plan's own knob defaults: CV_GEO_INLIER_FLOOR,
# CV_GEO_MIN_INLIER_RATIO, CV_GEO_MAX_REPROJECTION_RMS_PX, CV_GEO_MIN_RERANK_MARGIN).
INLIER_FLOOR = 8
MIN_INLIER_RATIO = 0.35
MAX_REPROJECTION_RMS_PX = 4.0
MIN_RERANK_MARGIN = 0.15


@dataclass(frozen=True)
class CandidateScore:
    """One retrieved candidate's re-rank outcome -- match evidence + MAGSAC pose fit, so the
    caller (bake-off driver, false-convergence gate driver) never re-derives sᵢ/ρᵢ itself."""

    candidate: Candidate
    match_count: int
    mean_confidence: float
    inlier_count: int  # sᵢ, the frozen ranking key
    inlier_ratio: float
    reprojection_rms_px: Optional[float]  # ρᵢ, None when no homography could be fit
    pose: PoseResult
    match_ms: float
    fit_ms: float


@dataclass(frozen=True)
class RerankResult:
    scored: list[CandidateScore]  # sorted by inlier_count descending (the frozen ordering)
    winner: Optional[CandidateScore]
    rerank_margin: float  # (s1 - s2) / max(s1, 1); 0 when k < 2
    conditioned: bool
    gates: dict  # gate name -> bool | None (None == not evaluated, e.g. G-f with no telemetry)
    refusal: Optional[str]  # name of the first failed gate, or None if all passed


def _reprojection_rms(kp_query: np.ndarray, kp_tile: np.ndarray, H: np.ndarray, mask: np.ndarray) -> Optional[float]:
    """ρᵢ: RMS reprojection error, in tile-pixel space, over the MAGSAC inlier set only --
    `fit_homography_pose` fits H and counts inliers but never reports this (pose extraction does
    not need it); G-c does."""
    inlier_idx = mask.reshape(-1).astype(bool)
    if not inlier_idx.any():
        return None
    src = kp_query[inlier_idx].reshape(-1, 1, 2).astype(np.float64)
    dst = kp_tile[inlier_idx].astype(np.float64)
    projected = cv2.perspectiveTransform(src, H).reshape(-1, 2)
    residuals = np.linalg.norm(projected - dst, axis=1)
    return float(np.sqrt(np.mean(residuals**2)))


def score_candidate(
    matcher,
    query_image_native: np.ndarray,
    effective_query_image: np.ndarray,
    conditioned: Optional[ConditionedQuery],
    candidate: Candidate,
    tile_image: Optional[np.ndarray],
    match_keypoints_fn: Callable[[object, np.ndarray, np.ndarray], MatchKeypoints],
) -> CandidateScore:
    """Match `effective_query_image` (conditioned if `conditioned` is not None, else identical to
    `query_image_native`) against one candidate's tile, then MAGSAC-fit + score. A missing/unreadable
    tile image scores 0 (unverifiable), matching `harvested/verify.py#verify`'s own convention."""
    if tile_image is None:
        empty = PoseResult(ok=False, reason="tile image unavailable")
        return CandidateScore(candidate, 0, 0.0, 0, 0.0, None, empty, 0.0, 0.0)

    t0 = time.perf_counter()
    mk = match_keypoints_fn(matcher, effective_query_image, tile_image)
    match_ms = (time.perf_counter() - t0) * 1000.0

    kp_query = mk.kp_query
    kp_tile = mk.kp_tile
    if conditioned is not None and kp_query.size:
        kp_query = conditioned.to_native(kp_query)

    query_h, query_w = query_image_native.shape[:2]
    parsed = parse_tile_id(candidate.tile_id)
    if parsed is None or kp_query.shape[0] < MIN_MATCHES_FOR_FIT:
        pose = PoseResult(ok=False, reason=f"only {kp_query.shape[0]} matches or unparseable tile id")
        return CandidateScore(candidate, mk.count, mk.mean_confidence, 0, 0.0, None, pose, match_ms, 0.0)

    zoom, tile_x, tile_y = parsed
    pose = fit_homography_pose(kp_query, kp_tile, query_w, query_h, tile_x, tile_y, zoom)

    rms = None
    if pose.ok:
        # Re-derive H/mask once more for the RMS-over-inliers figure pose.py itself doesn't keep.
        src = kp_query.reshape(-1, 1, 2).astype(np.float64)
        dst = kp_tile.astype(np.float64)
        H, mask = cv2.findHomography(src, dst, cv2.USAC_MAGSAC, RANSAC_REPROJ_PX)
        if H is not None and mask is not None:
            rms = _reprojection_rms(kp_query, kp_tile, H, mask)

    return CandidateScore(
        candidate=candidate,
        match_count=mk.count,
        mean_confidence=mk.mean_confidence,
        inlier_count=pose.inlier_count,
        inlier_ratio=pose.inlier_ratio,
        reprojection_rms_px=rms,
        pose=pose,
        match_ms=match_ms,
        fit_ms=pose.fit_ms,
    )


def rerank(
    matcher,
    query_image: np.ndarray,
    candidates: list[Candidate],
    load_tile_image: Callable[[Candidate], Optional[np.ndarray]],
    match_keypoints_fn: Callable[[object, np.ndarray, np.ndarray], MatchKeypoints],
    *,
    top_k: int = 5,
    heading_degrees: Optional[float] = None,
    altitude_meters: Optional[float] = None,
    fov_degrees: float = DEFAULT_ASSUMED_HORIZONTAL_FOV_DEGREES,
    cell_is_never_accept: Optional[bool] = None,
    agl_footprint_area_m2: Optional[float] = None,
) -> RerankResult:
    """§4.2 step 3, frozen: condition once (both de-rotate + GSD-rescale, or neither), match+MAGSAC
    every one of `candidates[:top_k]`, rank by inlier count `sᵢ` descending, then evaluate gates
    G-a..G-f on the winner. Mirrors `harvested/verify.py#verify`'s conditioning/subset shape but
    with the ranking key and gate set both replaced per §4.2 (see module docstring)."""
    subset = candidates[:top_k]
    conditioned: Optional[ConditionedQuery] = None
    if heading_degrees is not None and altitude_meters is not None and subset:
        parsed = parse_tile_id(subset[0].tile_id)
        if parsed is not None:
            zoom = parsed[0]
            conditioned = condition_query(
                query_image, heading_degrees, altitude_meters, subset[0].lat, zoom, fov_degrees=fov_degrees
            )
    effective_query = conditioned.image if conditioned is not None else query_image

    scored = [
        score_candidate(matcher, query_image, effective_query, conditioned, c, load_tile_image(c), match_keypoints_fn)
        for c in subset
    ]
    scored.sort(key=lambda cs: cs.inlier_count, reverse=True)

    if not scored:
        return RerankResult([], None, 0.0, conditioned is not None, {}, "NO_CANDIDATES")

    s1 = scored[0].inlier_count
    s2 = scored[1].inlier_count if len(scored) > 1 else None
    margin = (s1 - s2) / max(s1, 1) if s2 is not None else 0.0
    winner = scored[0]

    gates: dict[str, Optional[bool]] = {}
    gates["G-a_inlier_floor"] = winner.inlier_count >= INLIER_FLOOR
    gates["G-b_inlier_ratio"] = (
        (winner.inlier_count / winner.match_count) >= MIN_INLIER_RATIO if winner.match_count > 0 else False
    )
    gates["G-c_residual_ceiling"] = (
        winner.reprojection_rms_px is not None and winner.reprojection_rms_px <= MAX_REPROJECTION_RMS_PX
    )
    gates["G-d_rerank_margin"] = margin >= MIN_RERANK_MARGIN
    gates["G-e_cell_calibration"] = (not cell_is_never_accept) if cell_is_never_accept is not None else None
    if agl_footprint_area_m2 is not None and winner.pose.ok and winner.pose.footprint_area_m2 is not None:
        ratio = winner.pose.footprint_area_m2 / agl_footprint_area_m2 if agl_footprint_area_m2 > 0 else math.inf
        gates["G-f_footprint_sanity"] = bool(winner.pose.sanity_ok) and (0.25 <= ratio <= 4.0)
    else:
        # No AGL/FOV to derive the *expected* footprint from (no telemetry) -- fall back to
        # pose.py's own absolute-plausibility sanity flags rather than fabricate a relative check.
        gates["G-f_footprint_sanity"] = bool(winner.pose.sanity_ok) if winner.pose.ok else False

    refusal = None
    for name in ("G-a_inlier_floor", "G-b_inlier_ratio", "G-c_residual_ceiling", "G-d_rerank_margin", "G-e_cell_calibration", "G-f_footprint_sanity"):
        if gates[name] is False:
            refusal = name
            break

    return RerankResult(scored, winner, margin, conditioned is not None, gates, refusal)
