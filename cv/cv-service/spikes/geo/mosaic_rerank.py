"""cv-service/spikes/geo/mosaic_rerank.py

H0b deliverable (2): re-rank each retrieval candidate against its OWN 3x3 tile mosaic (768x768,
built from the region's already-fetched tiles on disk, `mosaic.py`) instead of its single 256px
reference tile.

Motivation (§9.8 task 2, measured): at fov=84deg the query's own ground footprint is already
0.56-1.11x one z17 tile's ~194.5m width (50.4degN) at 60-120m AGL NADIR -- and 3.9-7.9x a tile's
width at 45deg oblique (see the H0b report's footprint table). A single 256px reference tile can
then only ever contain a FRACTION of the query's true content, capping the achievable inlier
count/ratio regardless of matcher quality -- a harness/design ceiling, not something re-ranking
can tune its way past. Matching against the 3x3 neighbourhood instead removes that ceiling for the
nadir case (up to ~3 tiles-worth of width); it does NOT fully remove it for 45deg oblique, where
even the mosaic's own ~584m width is smaller than the query's 763-1527m footprint at 60-120m AGL --
stated explicitly in the H0b report rather than silently left implied by a green number.

Reuses `rerank.py`'s `CandidateScore`/`RerankResult`/`evaluate_gates` unchanged (H0b factored the
gate logic out of `rerank()` for exactly this reuse) -- only the match target and the pose-fit's
`(tile_x, tile_y)` origin differ: the mosaic's own top-left tile, not the candidate's own tile.
"""
from __future__ import annotations

import logging
import time
from pathlib import Path
from typing import Callable, Optional

import cv2
import numpy as np

from spikes.geo import mosaic as mosaic_mod
from spikes.geo.harvested.localize import Candidate
from spikes.geo.harvested.pose import MIN_MATCHES_FOR_FIT, RANSAC_REPROJ_PX, PoseResult, fit_homography_pose, parse_tile_id
from spikes.geo.harvested.verify import (
    DEFAULT_ASSUMED_HORIZONTAL_FOV_DEGREES,
    ConditionedQuery,
    MatchKeypoints,
    condition_query,
)
from spikes.geo.rerank import CandidateScore, RerankResult, _reprojection_rms, evaluate_gates

LOGGER = logging.getLogger("spikes.geo.mosaic_rerank")


def score_candidate_mosaic(
    matcher,
    effective_query_image: np.ndarray,
    conditioned: Optional[ConditionedQuery],
    query_native_hw: tuple[int, int],
    candidate: Candidate,
    region_dir: Path,
    match_keypoints_fn: Callable[[object, np.ndarray, np.ndarray], MatchKeypoints],
) -> tuple[CandidateScore, Optional[str]]:
    """`rerank.score_candidate`'s mosaic-candidate twin. Returns `(score, skip_reason)` --
    `skip_reason` names why the candidate scored 0 (harmless "unverifiable" sentinel, mirroring
    `rerank.score_candidate`'s own missing-tile-image convention) when its neighbourhood isn't a
    full 3x3 (an edge-of-region candidate)."""
    parsed = parse_tile_id(candidate.tile_id)
    if parsed is None:
        empty = PoseResult(ok=False, reason="unparseable tile id")
        return CandidateScore(candidate, 0, 0.0, 0, 0.0, None, empty, 0.0, 0.0), "unparseable tile id"
    zoom, cx, cy = parsed
    mosaic = mosaic_mod.build_tile_mosaic(region_dir, cx, cy, zoom)
    if mosaic is None:
        empty = PoseResult(ok=False, reason="mosaic unavailable (incomplete 3x3 neighbourhood)")
        return CandidateScore(candidate, 0, 0.0, 0, 0.0, None, empty, 0.0, 0.0), "incomplete neighbourhood"

    query_h, query_w = query_native_hw
    t0 = time.perf_counter()
    mk = match_keypoints_fn(matcher, effective_query_image, mosaic.image)
    match_ms = (time.perf_counter() - t0) * 1000.0

    kp_query = mk.kp_query
    if conditioned is not None and kp_query.size:
        kp_query = conditioned.to_native(kp_query)

    if kp_query.shape[0] < MIN_MATCHES_FOR_FIT:
        pose = PoseResult(ok=False, reason=f"only {kp_query.shape[0]} matches")
        return CandidateScore(candidate, mk.count, mk.mean_confidence, 0, 0.0, None, pose, match_ms, 0.0), None

    origin_x_tile, origin_y_tile = mosaic_mod.origin_tile_xy(mosaic)
    pose = fit_homography_pose(kp_query, mk.kp_tile, query_w, query_h, origin_x_tile, origin_y_tile, zoom)
    rms = None
    if pose.ok:
        src = kp_query.reshape(-1, 1, 2).astype(np.float64)
        dst = mk.kp_tile.astype(np.float64)
        H, mask = cv2.findHomography(src, dst, cv2.USAC_MAGSAC, RANSAC_REPROJ_PX)
        if H is not None and mask is not None:
            rms = _reprojection_rms(kp_query, mk.kp_tile, H, mask)

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
    ), None


def rerank_mosaic(
    matcher,
    query_image: np.ndarray,
    candidates: list[Candidate],
    region_dir: Path,
    match_keypoints_fn: Callable[[object, np.ndarray, np.ndarray], MatchKeypoints],
    *,
    top_k: int = 5,
    heading_degrees: Optional[float] = None,
    altitude_meters: Optional[float] = None,
    fov_degrees: float = DEFAULT_ASSUMED_HORIZONTAL_FOV_DEGREES,
    cell_is_never_accept: Optional[bool] = None,
) -> RerankResult:
    """`rerank.rerank()`'s mosaic-candidate twin: same conditioning (`condition_query`, reused
    verbatim), same gate table (`rerank.evaluate_gates`) -- only the per-candidate match target
    (a 3x3 mosaic, not the candidate's own 256px tile) and the pose-fit origin (the mosaic's own
    top-left tile) differ. `gates["n_incomplete_neighbourhood"]` counts scored-as-0 candidates
    whose neighbourhood wasn't a full 3x3, so a caller can tell "lost to an edge-of-region gap"
    from "lost on the merits"."""
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
    native_hw = (query_image.shape[0], query_image.shape[1])

    scored: list[CandidateScore] = []
    n_incomplete = 0
    for c in subset:
        score, skip_reason = score_candidate_mosaic(
            matcher, effective_query, conditioned, native_hw, c, region_dir, match_keypoints_fn
        )
        if skip_reason == "incomplete neighbourhood":
            n_incomplete += 1
        scored.append(score)
    scored.sort(key=lambda cs: cs.inlier_count, reverse=True)

    if not scored:
        return RerankResult([], None, 0.0, conditioned is not None, {"n_incomplete_neighbourhood": n_incomplete}, "NO_CANDIDATES")

    s1 = scored[0].inlier_count
    s2 = scored[1].inlier_count if len(scored) > 1 else None
    margin = (s1 - s2) / max(s1, 1) if s2 is not None else 0.0

    gates, refusal = evaluate_gates(scored, margin, cell_is_never_accept=cell_is_never_accept)
    gates["n_incomplete_neighbourhood"] = n_incomplete

    return RerankResult(scored, scored[0], margin, conditioned is not None, gates, refusal)
