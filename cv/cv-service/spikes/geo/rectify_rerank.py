"""cv-service/spikes/geo/rectify_rerank.py

H0b deliverable (4): a rectify-first re-rank -- wires `harvested/rectify.py#rectify()` (perspective
IPM, built in an earlier wave but never called from `rerank.py`'s conditioning path, per §9.8's
harness-gap finding) into the exact matching/pose-fit pipeline `rerank.py`/`mosaic_rerank.py` use.

Flow per query, given a STATED (pitch_deg, altitude_m, heading_deg) prior -- see module docs on
every caller for why "stated" (from the parked branch's blind-angle probe, §12.15) is not the same
claim as "measured telemetry":

1. `rectify()` once per query (horizon-crop + IPM-warp; independent of which candidate it's later
   matched against -- `target_gsd_m_per_px` only needs the region's own zoom/latitude, taken from
   the top candidate, matching `condition_query`'s own "subset[0].lat" convention in `rerank.py`).
   `None` means the crop/warp degenerated (e.g. the prior points the camera above the horizon at
   this `min_depression_deg`) -- reported as `refusal="RECTIFY_DEGENERATE"`, never silently
   swallowed into an unrectified fallback (this script exists to measure rectification, not to
   paper over its failure modes).
2. Match the RECTIFIED (warped) image against each candidate's target (single 256px tile, or its
   3x3 mosaic when `use_mosaic=True` -- reuses `mosaic.py` exactly as `mosaic_rerank.py` does).
3. Per `RectifyResult`'s own docstring: map matched query keypoints back through
   `rect.warped_to_input` into the CROPPED (not full-original) frame, and fit the pose against
   `rect.cropped_width/cropped_height` -- the cropped frame is the query frame from here on, so
   `pose.py`'s yaw/footprint semantics stay meaningful (sky rows would project past the horizon
   line at infinity and poison the footprint sanity gate).
4. Same gate table as every other H0b variant (`rerank.evaluate_gates`, reused verbatim).
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
from spikes.geo.harvested.rectify import focal_px_from_fov, reference_gsd_m_per_px, rectify
from spikes.geo.harvested.verify import DEFAULT_ASSUMED_HORIZONTAL_FOV_DEGREES, MatchKeypoints
from spikes.geo.rerank import CandidateScore, RerankResult, _reprojection_rms, evaluate_gates

LOGGER = logging.getLogger("spikes.geo.rectify_rerank")


def _target_image_and_origin(
    candidate: Candidate, region_dir: Path, tile_loader: Callable[[Candidate], Optional[np.ndarray]], use_mosaic: bool,
):
    """`(target_image, origin_x_tile, origin_y_tile, zoom, skip_reason)` -- the single-tile or
    mosaic match target plus the `(tile_x, tile_y)` origin `fit_homography_pose` needs, mirroring
    `rerank.score_candidate`/`mosaic_rerank.score_candidate_mosaic`'s own per-candidate lookup."""
    parsed = parse_tile_id(candidate.tile_id)
    if parsed is None:
        return None, None, None, None, "unparseable tile id"
    zoom, cx, cy = parsed
    if use_mosaic:
        mosaic = mosaic_mod.build_tile_mosaic(region_dir, cx, cy, zoom)
        if mosaic is None:
            return None, None, None, zoom, "incomplete neighbourhood"
        ox, oy = mosaic_mod.origin_tile_xy(mosaic)
        return mosaic.image, ox, oy, zoom, None
    tile_image = tile_loader(candidate)
    if tile_image is None:
        return None, None, None, zoom, "missing tile image"
    return tile_image, cx, cy, zoom, None


def score_candidate_rectified(
    matcher,
    rect,  # harvested.rectify.RectifyResult
    candidate: Candidate,
    region_dir: Path,
    tile_loader: Callable[[Candidate], Optional[np.ndarray]],
    match_keypoints_fn: Callable[[object, np.ndarray, np.ndarray], MatchKeypoints],
    use_mosaic: bool,
) -> tuple[CandidateScore, Optional[str]]:
    target_image, origin_x, origin_y, zoom, skip_reason = _target_image_and_origin(candidate, region_dir, tile_loader, use_mosaic)
    if skip_reason is not None:
        empty = PoseResult(ok=False, reason=skip_reason)
        return CandidateScore(candidate, 0, 0.0, 0, 0.0, None, empty, 0.0, 0.0), skip_reason

    t0 = time.perf_counter()
    mk = match_keypoints_fn(matcher, rect.warped, target_image)
    match_ms = (time.perf_counter() - t0) * 1000.0

    kp_query = mk.kp_query
    if kp_query.size:
        kp_query = rect.warped_to_input(kp_query)  # warped-frame px -> CROPPED-frame px (RectifyResult contract)

    if kp_query.shape[0] < MIN_MATCHES_FOR_FIT:
        pose = PoseResult(ok=False, reason=f"only {kp_query.shape[0]} matches")
        return CandidateScore(candidate, mk.count, mk.mean_confidence, 0, 0.0, None, pose, match_ms, 0.0), None

    pose = fit_homography_pose(kp_query, mk.kp_tile, rect.cropped_width, rect.cropped_height, origin_x, origin_y, zoom)
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


def rerank_rectified(
    matcher,
    query_image: np.ndarray,
    candidates: list[Candidate],
    region_dir: Path,
    tile_loader: Callable[[Candidate], Optional[np.ndarray]],
    match_keypoints_fn: Callable[[object, np.ndarray, np.ndarray], MatchKeypoints],
    *,
    pitch_deg: float,
    altitude_m: float,
    heading_deg: Optional[float],
    roll_deg: float = 0.0,
    top_k: int = 5,
    fov_degrees: float = DEFAULT_ASSUMED_HORIZONTAL_FOV_DEGREES,
    use_mosaic: bool = False,
    min_depression_deg: float = 15.0,
    cell_is_never_accept: Optional[bool] = None,
) -> RerankResult:
    """Rectify-first twin of `rerank.rerank()`/`mosaic_rerank.rerank_mosaic()`: one `rectify()`
    call per query against a STATED (pitch/altitude/heading) prior -- caller's responsibility to
    label that prior honestly (this function has no way to know whether it came from telemetry or
    a guess) -- then the shared match/pose-fit/gate path, single-tile or mosaic per `use_mosaic`."""
    subset = candidates[:top_k]
    if not subset:
        return RerankResult([], None, 0.0, False, {}, "NO_CANDIDATES")

    parsed0 = parse_tile_id(subset[0].tile_id)
    zoom0 = parsed0[0] if parsed0 is not None else 17
    target_gsd = reference_gsd_m_per_px(subset[0].lat, zoom0)
    focal_px = focal_px_from_fov(query_image.shape[1], fov_degrees)

    rect = rectify(
        query_image, pitch_deg=pitch_deg, focal_px=focal_px, roll_deg=roll_deg,
        heading_deg=heading_deg, altitude_m=altitude_m, target_gsd_m_per_px=target_gsd,
        min_depression_deg=min_depression_deg,
    )
    if rect is None:
        return RerankResult([], None, 0.0, False, {"rectify_ok": False}, "RECTIFY_DEGENERATE")

    scored: list[CandidateScore] = []
    n_incomplete = 0
    for c in subset:
        score, skip_reason = score_candidate_rectified(matcher, rect, c, region_dir, tile_loader, match_keypoints_fn, use_mosaic)
        if skip_reason == "incomplete neighbourhood":
            n_incomplete += 1
        scored.append(score)
    scored.sort(key=lambda cs: cs.inlier_count, reverse=True)

    if not scored:
        return RerankResult([], None, 0.0, True, {"rectify_ok": True, "n_incomplete_neighbourhood": n_incomplete}, "NO_CANDIDATES")

    s1 = scored[0].inlier_count
    s2 = scored[1].inlier_count if len(scored) > 1 else None
    margin = (s1 - s2) / max(s1, 1) if s2 is not None else 0.0

    gates, refusal = evaluate_gates(scored, margin, cell_is_never_accept=cell_is_never_accept)
    gates["n_incomplete_neighbourhood"] = n_incomplete
    gates["rectify_ok"] = True
    gates["rectify_crop_top"] = rect.crop_top
    gates["rectify_gsd_m_per_px"] = round(rect.gsd_m_per_px, 4)

    return RerankResult(scored, scored[0], margin, True, gates, refusal)
