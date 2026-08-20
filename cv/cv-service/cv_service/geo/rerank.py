"""Geometric re-rank of retrieval candidates (docs/plans/active/VISUAL-GEO-V2-PLAN.md §4.2,
frozen; H4 production port of `spikes/geo/rerank.py` + `spikes/geo/rectify_rerank.py`).

Score `sᵢ = inlier_countᵢ` from a MAGSAC homography fit (`cv_service.geo.pose.fit_homography_pose`),
not raw match count -- §13.2 Slice P's own lesson ("81 matches on a 725m-wrong tile -- counts
alone are worthless"). Ordering is by `sᵢ` descending; `rerank_margin = (s₁-s₂)/max(s₁,1)`.

Two entry points, mirroring §4.1's stage diagram (node C: telemetry present/fresh?):

- `rerank_rectified()` -- the NORMAL path. Matches an already-IPM-rectified
  `RectifyResult.warped` pseudo-nadir image (`cv_service.geo.rectify.rectify`, run once per query
  and shared with the retrieval stage -- H0c/§9.8 defect 2's own instruction) against each
  candidate tile, mapping matched keypoints back through `rect.warped_to_input` into the CROPPED
  frame before fitting pose (`RectifyResult`'s own contract).
- `rerank()` -- the DEGRADED path (no telemetry, or rectify degenerated). Matches the RAW query
  frame directly against each candidate tile.

**`condition_query` (harvested `verify.py`'s de-rotate+GSD-rescale telemetry conditioning) is
deliberately NOT ported.** §9.8 defect 2 measured it net-harmful/redundant once real IPM exists
(`rectify.py`'s own module docstring: "a separate `condition_query` pass is redundant -- the warp
already applied heading and GSD"), and §9.9 amendment 4 is binding: rectify.py goes in front of
retrieval AND matching, unconditionally, whenever telemetry supports it. When it does not, the
raw frame (not a partial rotate-only conditioning, which needs the same altitude input IPM does
and so can never fire when IPM can't) is the honest degraded fallback -- `evidence.rectified`
reports which path ran.

Both paths share `evaluate_gates()` -- §4.2 G-a..G-f, evaluated once against the winner of an
already inlier-sorted `scored` list. Every numeric gate threshold is a plain parameter (no module
constants) -- callers (`cv_service.geo.localize`) thread `Settings.geo_*` values through, per
rule 1 (no un-configurable magic numbers).

Needs `cv2`/`numpy` to import; `cv_service.geo.matchers` (the actual matcher backend) lazily
imports `torch`/`kornia` only when building/running a backend -- this module stays importable
without the `geo` extra, unlike the harvested `verify.py` it replaces (which imported `torch` at
module scope).
"""

from __future__ import annotations

import logging
import math
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

import cv2
import numpy as np

from cv_service.geo.matchers import MatchKeypoints
from cv_service.geo.pose import MIN_MATCHES_FOR_FIT, RANSAC_REPROJ_PX, PoseResult, fit_homography_pose, parse_tile_id
from cv_service.geo.rectify import RectifyResult

LOGGER = logging.getLogger("cv_service.geo.rerank")

# Harvested from `track.py` (left behind, §1.3) / `verify.py` -- the assumed horizontal FOV used
# whenever `GeoTelemetry.horizontal_fov_deg` is absent (§3.1's own "0/absent = the region's
# assumed default").
DEFAULT_ASSUMED_HORIZONTAL_FOV_DEGREES = 84.0

# Gate-name ordering, evaluated in this order -- first `False` wins as the refusal (§4.2).
_GATE_ORDER = (
    "G-a_inlier_floor",
    "G-b_inlier_ratio",
    "G-c_residual_ceiling",
    "G-d_rerank_margin",
    "G-e_cell_calibration",
    "G-f_footprint_sanity",
)


@dataclass(frozen=True)
class Candidate:
    """Plain, wire-agnostic counterpart of `GeoCandidate` (§3.1), plus `region_id` -- which the
    wire message deliberately does not carry (§3.1 freezes `GeoCandidate` with no region field)
    but this module and `cv_service.geo.localize` need internally to know which region's `tiles/`
    directory a candidate's raw JPEG lives under -- `cv_service/grpc/servicers.py` drops
    `region_id` when translating to the wire message. `distinctiveness` carries
    `ReferenceTileMeta.distinctiveness` through for G-e's per-cell calibration check."""

    tile_id: str
    lat: float
    lon: float
    similarity: float
    region_id: str = ""
    distinctiveness: Optional[float] = None


def tile_id_to_filename(tile_id: str) -> str:
    """`"17/76648/44197"` -> `"17_76648_44197.jpg"` -- the reverse of the frozen reference-pack
    tile naming (§3.1's `ReferencePackChunk` doc comment)."""
    return tile_id.replace("/", "_") + ".jpg"


def load_region_tile_image(region_dir: Path, tile_id: str) -> Optional["np.ndarray"]:
    """Load a region's raw reference tile JPEG bytes back off disk
    (`<region_dir>/tiles/<z>_<x>_<y>.jpg`, landed by `cv_service/geo/pack.py`)."""
    path = region_dir / "tiles" / tile_id_to_filename(tile_id)
    image = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if image is None:
        LOGGER.warning("could not load reference tile image %s", path)
    return image


@dataclass(frozen=True)
class CandidateScore:
    """One retrieved candidate's re-rank outcome -- match evidence + MAGSAC pose fit."""

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
    scored: list  # list[CandidateScore], sorted by inlier_count descending
    winner: Optional[CandidateScore]
    rerank_margin: float  # (s1 - s2) / max(s1, 1); 0 when k < 2
    rectified: bool  # True iff the IPM path ran (evidence.rectified, §3.1)
    gates: dict  # gate name -> bool | None (None == not evaluated, e.g. G-f with no telemetry)
    refusal: Optional[str]  # name of the first failed gate, or None if all passed


def _reprojection_rms(
    kp_query: "np.ndarray", kp_tile: "np.ndarray", H: "np.ndarray", mask: "np.ndarray"
) -> Optional[float]:
    """ρᵢ: RMS reprojection error, in tile-pixel space, over the MAGSAC inlier set only --
    `fit_homography_pose` fits H and counts inliers but never reports this; G-c does."""
    inlier_idx = mask.reshape(-1).astype(bool)
    if not inlier_idx.any():
        return None
    src = kp_query[inlier_idx].reshape(-1, 1, 2).astype(np.float64)
    dst = kp_tile[inlier_idx].astype(np.float64)
    projected = cv2.perspectiveTransform(src, H).reshape(-1, 2)
    residuals = np.linalg.norm(projected - dst, axis=1)
    return float(np.sqrt(np.mean(residuals**2)))


def evaluate_gates(
    scored: list,
    margin: float,
    *,
    inlier_floor: int = 8,
    min_inlier_ratio: float = 0.35,
    max_reprojection_rms_px: float = 4.0,
    min_rerank_margin: float = 0.15,
    cell_is_never_accept_fn: Optional[Callable[[Candidate], bool]] = None,
    agl_footprint_area_m2: Optional[float] = None,
) -> tuple[dict, Optional[str]]:
    """§4.2 gates G-a..G-f evaluated against the winner of an already-sorted (by inlier count
    descending) `scored` list. Shared by both `rerank()` and `rerank_rectified()` -- the frozen
    §4.2 table is data, not something two call sites should each reimplement.

    `cell_is_never_accept_fn` is a CALLABLE, not a plain bool -- G-e is evaluated against the
    WINNING candidate, which is only known once `scored` has been sorted (i.e. inside this
    function/its callers), so a caller cannot pre-compute the bool before calling `rerank()`. It
    is applied to `scored[0].candidate` here; `cell_calibrated` on the wire is this gate's own
    outcome, `region_is_never_accept OR cell_is_never_accept(winner.distinctiveness)`
    (`calibrate.cell_is_never_accept`, the H4 per-cell extension of the frozen table's literal
    "region-wide accept_similarity is not the never-accept sentinel" wording -- see
    `cv_service.geo.calibrate`'s per-cell-calibration section docstring for why)."""
    if not scored:
        return {}, "NO_CANDIDATES"
    winner = scored[0]
    gates: dict = {}
    gates["G-a_inlier_floor"] = winner.inlier_count >= inlier_floor
    gates["G-b_inlier_ratio"] = (
        (winner.inlier_count / winner.match_count) >= min_inlier_ratio if winner.match_count > 0 else False
    )
    gates["G-c_residual_ceiling"] = (
        winner.reprojection_rms_px is not None and winner.reprojection_rms_px <= max_reprojection_rms_px
    )
    gates["G-d_rerank_margin"] = margin >= min_rerank_margin
    gates["G-e_cell_calibration"] = (
        not cell_is_never_accept_fn(winner.candidate) if cell_is_never_accept_fn is not None else None
    )
    if agl_footprint_area_m2 is not None and winner.pose.ok and winner.pose.footprint_area_m2 is not None:
        ratio = winner.pose.footprint_area_m2 / agl_footprint_area_m2 if agl_footprint_area_m2 > 0 else math.inf
        gates["G-f_footprint_sanity"] = bool(winner.pose.sanity_ok) and (0.25 <= ratio <= 4.0)
    else:
        # No AGL/FOV to derive the *expected* footprint from -- fall back to pose.py's own
        # absolute-plausibility sanity flags rather than fabricate a relative check.
        gates["G-f_footprint_sanity"] = bool(winner.pose.sanity_ok) if winner.pose.ok else False

    refusal = None
    for name in _GATE_ORDER:
        if gates.get(name) is False:
            refusal = name
            break
    return gates, refusal


def _score_from_match(
    candidate: Candidate,
    mk: MatchKeypoints,
    kp_query_native: "np.ndarray",
    query_w: int,
    query_h: int,
    origin_x: int,
    origin_y: int,
    zoom: int,
    match_ms: float,
    *,
    match_floor: int,
) -> CandidateScore:
    """Shared tail of both scoring paths once keypoints are mapped into the frame `pose.py`
    expects: cheap pre-RANSAC skip below `match_floor` (harvested `CV_GEO_MATCH_FLOOR`, an
    optimization -- G-a's own `inlier_floor` still governs acceptance either way, since
    inlier_count <= match_count), else MAGSAC fit + reprojection RMS."""
    if kp_query_native.shape[0] < MIN_MATCHES_FOR_FIT or mk.count < match_floor:
        pose = PoseResult(ok=False, reason=f"only {kp_query_native.shape[0]} matches (< {match_floor})")
        return CandidateScore(candidate, mk.count, mk.mean_confidence, 0, 0.0, None, pose, match_ms, 0.0)

    pose = fit_homography_pose(kp_query_native, mk.kp_tile, query_w, query_h, origin_x, origin_y, zoom)
    # pose.py's own fit (above) does not return H/mask, and its frozen §6/Wave-6a contract
    # (docs/VISUAL-GEO-PLAN.md) is deliberately not touched by this port -- so G-c's residual
    # needs a second MAGSAC call on the same correspondences. USAC_MAGSAC's RANSAC sampling is
    # seeded from OpenCV's global RNG, not reseeded per call, so in practice the two fits agree on
    # essentially the same inlier set on the SAME input; a few-ms second RANSAC pass on a <2048-pt
    # set is negligible next to the matcher cost it rides on. Any G-c/G-a disagreement this could
    # theoretically cause is caught by holding G-c to a tight, independent residual ceiling rather
    # than trusting the two calls to fully agree.
    rms = None
    if pose.ok:
        src = kp_query_native.reshape(-1, 1, 2).astype(np.float64)
        dst = mk.kp_tile.astype(np.float64)
        H, mask = cv2.findHomography(src, dst, cv2.USAC_MAGSAC, RANSAC_REPROJ_PX)
        if H is not None and mask is not None:
            rms = _reprojection_rms(kp_query_native, mk.kp_tile, H, mask)

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


def score_candidate(
    matcher,
    query_image: "np.ndarray",
    candidate: Candidate,
    tile_image: Optional["np.ndarray"],
    match_keypoints_fn: Callable[[object, "np.ndarray", "np.ndarray"], MatchKeypoints],
    *,
    match_floor: int = 12,
) -> CandidateScore:
    """Degraded (no-rectify) path: match the RAW query frame against one candidate's tile. A
    missing/unreadable tile image scores 0 (unverifiable)."""
    if tile_image is None:
        empty = PoseResult(ok=False, reason="tile image unavailable")
        return CandidateScore(candidate, 0, 0.0, 0, 0.0, None, empty, 0.0, 0.0)

    t0 = time.perf_counter()
    mk = match_keypoints_fn(matcher, query_image, tile_image)
    match_ms = (time.perf_counter() - t0) * 1000.0

    parsed = parse_tile_id(candidate.tile_id)
    if parsed is None:
        pose = PoseResult(ok=False, reason="unparseable tile id")
        return CandidateScore(candidate, mk.count, mk.mean_confidence, 0, 0.0, None, pose, match_ms, 0.0)
    zoom, tile_x, tile_y = parsed
    query_h, query_w = query_image.shape[:2]
    return _score_from_match(
        candidate, mk, mk.kp_query, query_w, query_h, tile_x, tile_y, zoom, match_ms, match_floor=match_floor
    )


def rerank(
    matcher,
    query_image: "np.ndarray",
    candidates: list,
    load_tile_image: Callable[[Candidate], Optional["np.ndarray"]],
    match_keypoints_fn: Callable[[object, "np.ndarray", "np.ndarray"], MatchKeypoints],
    *,
    top_k: int = 5,
    match_floor: int = 12,
    inlier_floor: int = 8,
    min_inlier_ratio: float = 0.35,
    max_reprojection_rms_px: float = 4.0,
    min_rerank_margin: float = 0.15,
    cell_is_never_accept_fn: Optional[Callable[[Candidate], bool]] = None,
    agl_footprint_area_m2: Optional[float] = None,
) -> RerankResult:
    """Degraded (no-rectify) path, §4.1 node D0: match+MAGSAC every one of `candidates[:top_k]`
    against the raw query frame, rank by inlier count `sᵢ` descending, evaluate gates G-a..G-f."""
    subset = candidates[:top_k]
    scored = [
        score_candidate(matcher, query_image, c, load_tile_image(c), match_keypoints_fn, match_floor=match_floor)
        for c in subset
    ]
    scored.sort(key=lambda cs: cs.inlier_count, reverse=True)
    if not scored:
        return RerankResult([], None, 0.0, False, {}, "NO_CANDIDATES")

    s1 = scored[0].inlier_count
    s2 = scored[1].inlier_count if len(scored) > 1 else None
    margin = (s1 - s2) / max(s1, 1) if s2 is not None else 0.0

    gates, refusal = evaluate_gates(
        scored,
        margin,
        inlier_floor=inlier_floor,
        min_inlier_ratio=min_inlier_ratio,
        max_reprojection_rms_px=max_reprojection_rms_px,
        min_rerank_margin=min_rerank_margin,
        cell_is_never_accept_fn=cell_is_never_accept_fn,
        agl_footprint_area_m2=agl_footprint_area_m2,
    )
    return RerankResult(scored, scored[0], margin, False, gates, refusal)


def score_candidate_rectified(
    matcher,
    rect: RectifyResult,
    candidate: Candidate,
    tile_image: Optional["np.ndarray"],
    match_keypoints_fn: Callable[[object, "np.ndarray", "np.ndarray"], MatchKeypoints],
    *,
    match_floor: int = 12,
) -> CandidateScore:
    """Normal (rectified) path: match the already-IPM-warped `rect.warped` image against one
    candidate's tile, mapping matched keypoints back into the CROPPED frame (`RectifyResult`'s
    own contract) before fitting pose."""
    if tile_image is None:
        empty = PoseResult(ok=False, reason="tile image unavailable")
        return CandidateScore(candidate, 0, 0.0, 0, 0.0, None, empty, 0.0, 0.0)

    t0 = time.perf_counter()
    mk = match_keypoints_fn(matcher, rect.warped, tile_image)
    match_ms = (time.perf_counter() - t0) * 1000.0

    kp_query_native = mk.kp_query
    if kp_query_native.size:
        kp_query_native = rect.warped_to_input(kp_query_native)  # warped px -> CROPPED-frame px

    parsed = parse_tile_id(candidate.tile_id)
    if parsed is None:
        pose = PoseResult(ok=False, reason="unparseable tile id")
        return CandidateScore(candidate, mk.count, mk.mean_confidence, 0, 0.0, None, pose, match_ms, 0.0)
    zoom, tile_x, tile_y = parsed
    return _score_from_match(
        candidate,
        mk,
        kp_query_native,
        rect.cropped_width,
        rect.cropped_height,
        tile_x,
        tile_y,
        zoom,
        match_ms,
        match_floor=match_floor,
    )


def rerank_rectified(
    matcher,
    rect: RectifyResult,
    candidates: list,
    load_tile_image: Callable[[Candidate], Optional["np.ndarray"]],
    match_keypoints_fn: Callable[[object, "np.ndarray", "np.ndarray"], MatchKeypoints],
    *,
    top_k: int = 5,
    match_floor: int = 12,
    inlier_floor: int = 8,
    min_inlier_ratio: float = 0.35,
    max_reprojection_rms_px: float = 4.0,
    min_rerank_margin: float = 0.15,
    cell_is_never_accept_fn: Optional[Callable[[Candidate], bool]] = None,
    agl_footprint_area_m2: Optional[float] = None,
) -> RerankResult:
    """Normal (rectified) path, §4.1 node D: given an already-computed `rect`
    (`cv_service.geo.rectify.rectify`, run once per query and SHARED with the retrieval stage --
    H0c/§9.8 defect 2), match `rect.warped` against each of `candidates[:top_k]`'s tile through
    the same §4.2 gate table `rerank()` uses."""
    subset = candidates[:top_k]
    scored = [
        score_candidate_rectified(
            matcher, rect, c, load_tile_image(c), match_keypoints_fn, match_floor=match_floor
        )
        for c in subset
    ]
    scored.sort(key=lambda cs: cs.inlier_count, reverse=True)
    if not scored:
        return RerankResult([], None, 0.0, True, {}, "NO_CANDIDATES")

    s1 = scored[0].inlier_count
    s2 = scored[1].inlier_count if len(scored) > 1 else None
    margin = (s1 - s2) / max(s1, 1) if s2 is not None else 0.0

    gates, refusal = evaluate_gates(
        scored,
        margin,
        inlier_floor=inlier_floor,
        min_inlier_ratio=min_inlier_ratio,
        max_reprojection_rms_px=max_reprojection_rms_px,
        min_rerank_margin=min_rerank_margin,
        cell_is_never_accept_fn=cell_is_never_accept_fn,
        agl_footprint_area_m2=agl_footprint_area_m2,
    )
    return RerankResult(scored, scored[0], margin, True, gates, refusal)
