"""cv-service/spikes/geo/rectify_pipeline.py

H0c shared core (VISUAL-GEO-V2-PLAN.md §9.8): one rectify-mode-aware retrieval+re-rank pass for a
single query frame, reused by every H0c re-measurement driver (`run_bakeoff_v2.py`,
`calibrate_rectify_sweep.py`, `false_convergence_gate.py`'s geometric field) so the three-way
`--rectify {none, condition, ipm}` switch has exactly one implementation instead of drifting
across scripts.

Three modes, all funnelling into the SAME candidate/matching/gate machinery downstream
(`rerank.rerank`/`rerank.evaluate_gates`, shared with `rerank_rectified`):

  - "none"      -- H0's original baseline: raw query image, no conditioning at all, straight into
                   `rerank.rerank(heading_degrees=None, altitude_meters=None)`.
  - "condition" -- what H0 actually measured and mislabeled "rectified" (§9.8 defect 2):
                   `harvested/verify.py#condition_query`'s de-rotate + GSD-rescale, NADIR-ONLY
                   (`pitch_degrees` stays its own default of 0.0 regardless of the real pitch).
                   `rerank.rerank(heading_degrees=..., altitude_meters=...)`.
  - "ipm"       -- the actual fix: `rectify_rerank.compute_rectification()` (full perspective IPM
                   from pitch/altitude/heading/FOV, `harvested/rectify.py`) run ONCE per frame;
                   `rect.warped` (the rectified pseudo-nadir image) becomes the query for BOTH
                   retrieval (encode+search, replacing the raw frame below) and matching
                   (`rectify_rerank.rerank_rectified`) -- never two independently rectified images.

Retrieval rank is always a FULL-index rank (not capped at `k`) of a caller-supplied
`true_tile_id`, so "true tile outside top-k" is a real, measured outcome rather than an artefact
of the search truncation (mirrors `calibrate_instrument.py#_full_rank`). Callers own the
"what counts as the true tile" policy (exact cell vs. `rank_shift.py`'s nearest-indexed
convention) -- this module just reports the rank of whatever id it's given.
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional

import numpy as np

from spikes.geo import rectify_rerank
from spikes.geo import rerank as rerank_mod
from spikes.geo.harvested.encoder import Encoder
from spikes.geo.harvested.localize import Candidate, RegionIndex
from spikes.geo.harvested.verify import DEFAULT_ASSUMED_HORIZONTAL_FOV_DEGREES, MatchKeypoints, load_region_tile_image

LOGGER = logging.getLogger("spikes.geo.rectify_pipeline")

RECTIFY_MODES = ("none", "condition", "ipm")


@dataclass(frozen=True)
class PipelinePass:
    rectify_mode: str
    ok: bool  # False only on a hard refusal BEFORE re-rank could even start
    refusal: Optional[str]  # a pre-re-rank refusal (e.g. RECTIFY_DEGENERATE), or re-rank's own
    retrieval_rank_of_true_tile: Optional[int]  # None: not found even in a full-index search
    n_indexed: int
    rerank: Optional[rerank_mod.RerankResult]
    elapsed_ms: float
    rectify_meta: dict = field(default_factory=dict)  # crop_top/gsd_m_per_px when mode == "ipm"


def _full_retrieval_rank(
    region: RegionIndex, encoder: Encoder, query_image: np.ndarray, true_tile_id: Optional[str]
):
    """`(descriptor, ordered results, rank_of_true_tile)` -- a FULL (untruncated) similarity
    search, so the true tile's rank is exact even past `k` (mirrors
    `calibrate_instrument.py#_full_rank`)."""
    descriptor = encoder.encode(query_image)
    results, _ms = region.index.search(descriptor, top_k=len(region.index))
    rank = None
    if true_tile_id is not None:
        rank = next((i + 1 for i, r in enumerate(results) if r.tile.tile_id == true_tile_id), None)
    return descriptor, results, rank


def run_pass(
    raw_image: np.ndarray,
    *,
    encoder: Encoder,
    region: RegionIndex,
    region_dir: Path,
    matcher_handle,
    match_fn: Callable[[object, np.ndarray, np.ndarray], MatchKeypoints],
    rectify_mode: str,
    k: int,
    true_tile_id: Optional[str] = None,
    heading_deg: Optional[float] = None,
    altitude_m: Optional[float] = None,
    pitch_deg: float = 0.0,
    roll_deg: float = 0.0,
    fov_degrees: float = DEFAULT_ASSUMED_HORIZONTAL_FOV_DEGREES,
    min_depression_deg: float = 15.0,
    use_mosaic: bool = False,
    cell_is_never_accept: Optional[bool] = None,
) -> PipelinePass:
    """One frame, one (rectify_mode, k, matcher, mosaic) configuration. `pitch_deg`/`altitude_m`/
    `heading_deg` are read only when `rectify_mode` needs them ("condition": heading+altitude,
    nadir assumed; "ipm": all three, degrees-FROM-NADIR pitch convention -- see
    `rectify_rerank.compute_rectification`'s docstring)."""
    if rectify_mode not in RECTIFY_MODES:
        raise ValueError(f"unknown rectify_mode {rectify_mode!r}, choose from {RECTIFY_MODES}")

    t0 = time.perf_counter()
    rectify_meta: dict = {}
    rect = None

    if rectify_mode == "ipm":
        if heading_deg is None or altitude_m is None:
            return PipelinePass("ipm", False, "NO_TELEMETRY_FOR_IPM", None, len(region.index), None, 0.0, {})
        # target_gsd/focal_px need a reference latitude, but retrieval (which would normally hand
        # us the top candidate's own tile latitude, `rerank.rerank`'s "subset[0].lat" convention)
        # hasn't run yet -- IPM has to run FIRST, since its output IS the retrieval query. Stand-in:
        # the region's own mean tile latitude; negligible error at the <0.5deg latitude span every
        # H0/H0c region covers (reference_gsd_m_per_px varies by cos(lat), ~1e-4 relative change
        # per 0.01deg of latitude at 50degN).
        ref_lat = float(np.mean([t.lat for t in region.index.tiles])) if len(region.index) else 0.0
        rect = rectify_rerank.compute_rectification(
            raw_image, pitch_deg=pitch_deg, altitude_m=altitude_m, heading_deg=heading_deg,
            tile_lat=ref_lat, zoom=17, roll_deg=roll_deg, fov_degrees=fov_degrees,
            min_depression_deg=min_depression_deg,
        )
        if rect is None:
            elapsed_ms = (time.perf_counter() - t0) * 1000.0
            return PipelinePass("ipm", False, "RECTIFY_DEGENERATE", None, len(region.index), None, elapsed_ms, {})
        rectify_meta = {"crop_top": rect.crop_top, "gsd_m_per_px": round(rect.gsd_m_per_px, 4)}
        query_for_retrieval = rect.warped
    else:
        query_for_retrieval = raw_image

    _descriptor, results, rank = _full_retrieval_rank(region, encoder, query_for_retrieval, true_tile_id)
    topk = results[:k]
    candidates = [
        Candidate(tile_id=r.tile.tile_id, lat=r.tile.lat, lon=r.tile.lon, similarity=r.similarity, region_id=region.region_id)
        for r in topk
    ]

    def load_tile(candidate: Candidate) -> Optional[np.ndarray]:
        return load_region_tile_image(region_dir, candidate.tile_id)

    if not candidates:
        elapsed_ms = (time.perf_counter() - t0) * 1000.0
        return PipelinePass(rectify_mode, True, "NO_CANDIDATES", rank, len(region.index), None, elapsed_ms, rectify_meta)

    if rectify_mode == "none":
        result = rerank_mod.rerank(
            matcher_handle, raw_image, candidates, load_tile, match_fn, top_k=k,
            heading_degrees=None, altitude_meters=None, fov_degrees=fov_degrees,
            cell_is_never_accept=cell_is_never_accept,
        )
    elif rectify_mode == "condition":
        result = rerank_mod.rerank(
            matcher_handle, raw_image, candidates, load_tile, match_fn, top_k=k,
            heading_degrees=heading_deg, altitude_meters=altitude_m, fov_degrees=fov_degrees,
            cell_is_never_accept=cell_is_never_accept,
        )
    else:  # ipm
        result = rectify_rerank.rerank_rectified(
            matcher_handle, rect, candidates, region_dir, load_tile, match_fn, top_k=k,
            use_mosaic=use_mosaic, cell_is_never_accept=cell_is_never_accept,
        )

    elapsed_ms = (time.perf_counter() - t0) * 1000.0
    return PipelinePass(rectify_mode, True, result.refusal, rank, len(region.index), result, elapsed_ms, rectify_meta)
