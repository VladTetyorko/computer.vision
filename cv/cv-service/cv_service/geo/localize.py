"""The live per-keyframe geolocation pipeline (docs/plans/active/VISUAL-GEO-V2-PLAN.md §4.1,
frozen order): given one drone keyframe, answer "where is this?" -- H4 production rewrite of
`spikes/geo/harvested/localize.py`'s retrieval-only Wave-3a path, extended to the full frozen
five-stage pipeline `GeolocationServicer.LocalizeStream` drives:

    texture gate -> [telemetry? IPM rectify : degraded] -> retrieve top-k -> geometric re-rank
    (§4.2 G-a..G-f) -> sequence filter update (§4.4 Change 1/2) -> pose (already inside re-rank,
    via `cv_service.geo.pose`)

Each stage may short-circuit the rest (§4.1's own diagram); `localize_frame()` is the single
entry point covering every branch, returning a `FrameResult` the servicer translates 1:1 onto
`cv_pb2.GeoFix`/`GeoEvidence`. Never imports `cv_pb2` or `cv_service.config` -- wire-agnostic and
directly testable, same discipline as every other `cv_service.geo` module; the servicer resolves
`Settings.geo_*` into a `LocalizeParams` once per session and passes plain values/callables here.

**IPM rectification and the sequence filter are both restricted to a single resolved region**
(this module's own judgment call, not spelled out by §4.1's text): IPM's `target_gsd_m_per_px`
needs a reference latitude+zoom BEFORE retrieval has run (rectification's own output IS the
retrieval query, so retrieval cannot supply it first) -- H0c's own `rectify_pipeline.py#run_pass`
resolved this identically, standing in the resolved region's own mean tile latitude (negligible
error at the <0.5deg span every built region covers) and its own indexed zoom. When `region_id`
was `""` (search every READY region) and more than one resolved, there is no single well-defined
GSD to rectify to, so IPM is skipped (`evidence.rectified=false`, the honest degraded fallback) --
same reasoning independently rules out running a `SequenceLocalizer` (`from_tile_ids` needs ONE
region's own contiguous tile grid; a particle filter has no meaning spanning two regions' disjoint
coordinate spaces). Both restrictions therefore coincide: single-region search is required for
either feature, multi-region search always runs the degraded, non-sequenced path.

Needs `cv2`/`numpy` to import (`compute_texture_score`); `cv_service.geo.matchers`' actual
torch/kornia backend is built by the caller and handed in already-built, so this module itself
stays importable without the `geo` extra, unlike the harvested `localize.py` it replaces (whose
own `verify.py` companion imported torch at module scope).
"""

from __future__ import annotations

import logging
import math
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional

import cv2
import numpy as np

from cv_service.geo import calibrate as calibrate_mod
from cv_service.geo import index as geo_index
from cv_service.geo import pack as geo_pack
from cv_service.geo import rectify as rectify_mod
from cv_service.geo import rerank as rerank_mod
from cv_service.geo import sequence as sequence_mod
from cv_service.geo.encoder import Encoder
from cv_service.geo.pose import parse_tile_id
from cv_service.geo.sequence import SequenceEstimate, SequenceLocalizer

LOGGER = logging.getLogger("cv_service.geo.localize")

# Plain string statuses mirroring `GeoStatus` enum names minus the `GEO_STATUS_` prefix (this
# module never produces the zero value `GEO_STATUS_UNSPECIFIED`) -- kept as strings, not the
# generated `cv_pb2` enum, so this module stays wire-agnostic; `cv_service/grpc/servicers.py`
# maps `"GEO_" + this` onto `cv_pb2.GEO_STATUS_*` 1:1.
STATUS_FIX = "GEO_FIX"
STATUS_NO_FIX = "GEO_NO_FIX"
STATUS_LOW_TEXTURE = "GEO_LOW_TEXTURE"
STATUS_OUT_OF_REGION = "GEO_OUT_OF_REGION"
STATUS_NO_INDEX = "GEO_NO_INDEX"
STATUS_ERROR = "GEO_ERROR"

_METERS_PER_DEGREE = 111_320.0  # matches pose.py#quad_area_m2's own local equirectangular const


# --- quality gate (§4.1 node B) ------------------------------------------------------------------


@dataclass(frozen=True)
class TextureScore:
    laplacian_variance: float
    entropy: float


def compute_texture_score(image: "np.ndarray") -> TextureScore:
    """Laplacian variance (blur proxy) + Shannon entropy of the 8-bit grayscale histogram
    (texture/detail proxy) -- ported verbatim from `spikes/geo/harvested/localize.py`."""
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    laplacian_variance = float(cv2.Laplacian(gray, cv2.CV_64F).var())
    histogram = cv2.calcHist([gray], [0], None, [256], [0, 256]).ravel()
    total = float(histogram.sum())
    if total <= 0:
        entropy = 0.0
    else:
        probabilities = histogram[histogram > 0] / total
        entropy = float(-np.sum(probabilities * np.log2(probabilities)))
    return TextureScore(laplacian_variance=laplacian_variance, entropy=entropy)


def is_low_texture(score: TextureScore, *, min_laplacian_variance: float, min_entropy: float) -> bool:
    """Either signal failing is enough to reject -- a sharp-but-blank frame is just as useless to
    retrieval as a blurry-but-detailed one."""
    return score.laplacian_variance < min_laplacian_variance or score.entropy < min_entropy


# --- region resolution -----------------------------------------------------------------------


@dataclass(frozen=True)
class RegionIndex:
    """One READY region's loaded search index + its calibrated acceptance stats."""

    region_id: str
    index: "geo_index.ReferenceIndex"
    stats: "geo_index.ReferenceIndexStats"


def _load_region(region_dir: Path, region_id: str) -> Optional[RegionIndex]:
    region_meta = geo_pack.read_region_meta(region_dir)
    index_meta = geo_index.read_index_json(region_dir)
    if region_meta is None or index_meta is None:
        return None
    try:
        index = geo_index.ReferenceIndex.load(region_dir)
    except (OSError, ValueError) as exc:
        LOGGER.warning(
            "region %s has a valid index.json but its descriptors failed to load (%s); "
            "treating as not-ready",
            region_id,
            exc,
        )
        return None
    return RegionIndex(region_id=region_id, index=index, stats=index_meta.stats)


def resolve_regions(data_dir: Path, requested_region_id: str) -> list[RegionIndex]:
    """Load the region(s) `LocalizeStream` should search, straight off disk.

    - `requested_region_id` non-empty: that one region, IF it's READY -- `[]` otherwise.
    - `requested_region_id == ""`: **every** READY region, sorted by id for determinism (§3.1's
      own wire comment on `GeoControl.region_id`) -- `[]` if none exist.

    **No caching** (known limitation, carried from the harvested module): reads `index.json` +
    `descriptors.npy` off disk on every call. The servicer decides call frequency (a per-session
    cache is a reasonable follow-up, deliberately not built this wave to avoid inventing a
    cache-invalidation story for "a region got rebuilt mid-session").
    """
    if requested_region_id:
        loaded = _load_region(data_dir / requested_region_id, requested_region_id)
        return [loaded] if loaded is not None else []

    if not data_dir.is_dir():
        return []
    regions: list[RegionIndex] = []
    for region_dir in sorted(p for p in data_dir.iterdir() if p.is_dir()):
        loaded = _load_region(region_dir, region_dir.name)
        if loaded is not None:
            regions.append(loaded)
    return regions


def _region_zoom(region: RegionIndex) -> Optional[int]:
    """The region's own indexed zoom, read off its first tile id -- regions are single-zoom by
    construction (`cv_service.geo.orchestrator` indexes one zoom per region; mirrors
    `SequenceLocalizer.from_tile_ids`'s own "mixed zooms" rejection)."""
    if not region.index.tiles:
        return None
    parsed = parse_tile_id(region.index.tiles[0].tile_id)
    return parsed[0] if parsed is not None else None


# --- telemetry --------------------------------------------------------------------------------


@dataclass(frozen=True)
class Telemetry:
    """Plain, wire-agnostic counterpart of `GeoTelemetry` (§3.1) -- every field optional, same
    "an asset may have heading without a fix" contract the wire message documents.
    `camera_pitch_deg` is already in `rectify.py`'s degrees-FROM-NADIR convention (the Java codec
    converts from `Attitude`'s positive-up gimbal pitch exactly once, D6) -- this module performs
    no further conversion."""

    latitude: Optional[float] = None
    longitude: Optional[float] = None
    amsl_meters: Optional[float] = None
    agl_meters: Optional[float] = None
    heading_degrees: Optional[float] = None
    groundspeed_mps: Optional[float] = None
    camera_pitch_deg: Optional[float] = None
    camera_roll_deg: Optional[float] = None
    camera_yaw_deg: Optional[float] = None
    horizontal_fov_deg: Optional[float] = None
    sample_millis: int = 0
    gps_radius_meters: Optional[float] = None


def telemetry_motion_delta(prev: Optional[Telemetry], curr: Optional[Telemetry]) -> tuple[float, float]:
    """`(delta_east_m, delta_north_m)` the platform moved between two telemetry samples, from
    their own reported lat/lon (a local equirectangular approximation at `prev`'s latitude --
    adequate at the meters-per-update scale this feeds `SequenceLocalizer.predict`, the same
    convention `pose.py#quad_area_m2` already uses). `(0.0, 0.0)` -- the honest "no odometry this
    tick" degradation -- whenever either sample or either lat/lon is missing: a hover, an absent
    telemetry stream, or the first frame of a session all degrade identically."""
    if (
        prev is None
        or curr is None
        or prev.latitude is None
        or prev.longitude is None
        or curr.latitude is None
        or curr.longitude is None
    ):
        return 0.0, 0.0
    north_m = (curr.latitude - prev.latitude) * _METERS_PER_DEGREE
    east_m = (curr.longitude - prev.longitude) * _METERS_PER_DEGREE * math.cos(math.radians(prev.latitude))
    return east_m, north_m


def _expected_footprint_area_m2(telemetry: Optional[Telemetry], image_width: int, image_height: int) -> Optional[float]:
    """A coarse, flat-NADIR-approximation ground footprint estimate from AGL + FOV, for G-f's
    relative plausibility band (`[0.25, 4]x`, generous enough that ignoring pitch's true
    perspective enlargement does not need correcting for a coarse sanity gate) -- `None` when
    there isn't enough telemetry to estimate one at all (G-f then falls back to `pose.py`'s own
    absolute sanity flags, `rerank.evaluate_gates`'s own contract)."""
    if telemetry is None or telemetry.agl_meters is None or telemetry.agl_meters <= 0 or image_width <= 0:
        return None
    fov_deg = telemetry.horizontal_fov_deg or rerank_mod.DEFAULT_ASSUMED_HORIZONTAL_FOV_DEGREES
    width_m = 2.0 * telemetry.agl_meters * math.tan(math.radians(fov_deg / 2.0))
    height_m = width_m * (image_height / image_width) if image_width > 0 else width_m
    return width_m * height_m


# --- pipeline parameters + result -------------------------------------------------------------


@dataclass(frozen=True)
class LocalizeParams:
    """Every §4.2/§4.4 numeric gate + feature toggle this pipeline needs, resolved by the caller
    (`GeolocationServicer`, typically straight from `Settings.geo_*`) -- this module never
    imports `cv_service.config` itself (kept directly testable with plain values, matching
    `cv_service.geo.rerank`'s own posture)."""

    max_candidates: int = 20
    match_floor: int = 12
    inlier_floor: int = 8
    min_inlier_ratio: float = 0.35
    max_reprojection_rms_px: float = 4.0
    min_rerank_margin: float = 0.15
    min_laplacian_variance: float = 50.0
    min_entropy: float = 3.0
    rectify_enabled: bool = True
    rectify_min_pitch_deg: float = 10.0
    sequence_enabled: bool = True
    seq_min_supporting_frames: int = 4
    seq_min_baseline_m: float = 40.0
    # D8's OSM tie-breaker (`osm_fingerprint.py`) is NOT ported this wave (H4 scope cut, see
    # MODULE.md) -- this knob exists so the wire's own `× (1 + w · osm_prior)` retrieval-rescale
    # term (§4.1) is structurally inert at its default (`w = 0.0`) rather than silently absent;
    # `FrameEvidence.osm_prior` always reports `1.0` (the D8 tie-breaker's own "inert" value).
    osm_weight: float = 0.0


@dataclass(frozen=True)
class FrameEvidence:
    """Plain, wire-agnostic counterpart of `GeoEvidence` (§3.1) -- field-for-field, same
    "0/false unless computed" defaults the wire's own non-optional fields carry."""

    candidate_count: int = 0
    match_count: int = 0
    inlier_count: int = 0
    inlier_ratio: float = 0.0
    rerank_margin: float = 0.0
    reprojection_rms_px: float = 0.0
    rectified: bool = False
    cell_calibrated: bool = False
    supporting_frames: int = 0
    baseline_meters: float = 0.0
    sequence_converged: bool = False
    sequence_spread_meters: float = 0.0
    sequence_updates: int = 0
    osm_prior: float = 1.0


@dataclass(frozen=True)
class FrameResult:
    """Plain, wire-agnostic counterpart of `GeoFix` (§3.1) minus the framing fields
    (`stream_id`/`sequence`/`frame_millis`/`telemetry_age_millis`/`latency_millis`) that only
    exist at the `CaptureClock`/session level the servicer owns, not this per-frame function.
    `implied_agl_meters` is always `None` this wave (documented scope cut, see MODULE.md -- no
    consumer needs it yet; the wire field stays `optional` and simply absent)."""

    status: str
    region_id: str = ""
    tile_id: str = ""
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    yaw_degrees: Optional[float] = None
    radius_meters: Optional[float] = None
    implied_agl_meters: Optional[float] = None
    evidence: FrameEvidence = field(default_factory=FrameEvidence)
    refusal: str = ""
    message: str = ""


def _position_radius_meters(
    winner: "rerank_mod.CandidateScore", region: RegionIndex, zoom: Optional[int]
) -> float:
    """1-sigma horizontal radius for an accepted fix: the measured MAGSAC reprojection residual
    (tile pixels) converted to meters via the winning tile's own ground sampling distance --
    grounded in the *actual* homography fit's own precision (pose.py's docstring: refined
    position measured 4.8m median under the shipped gate), not the region's coarse
    tile-retrieval-level `holdout_median_error_meters` (which would overstate a refined fix's
    uncertainty by roughly the tile-quantization factor pose.py's own refinement removed).
    Falls back to the region's holdout figure when the residual or zoom is unavailable (should
    not happen on a genuine `STATUS_FIX` -- G-c already requires a real residual -- kept as a
    defensive floor, never a crash)."""
    if winner.reprojection_rms_px is not None and zoom is not None:
        gsd = rectify_mod.reference_gsd_m_per_px(winner.candidate.lat, zoom)
        return max(winner.reprojection_rms_px, 1.0) * gsd
    return region.stats.holdout_median_error_meters


def _sequence_converged_policy(
    estimate: Optional[SequenceEstimate],
    *,
    min_supporting_frames: int,
    min_baseline_m: float,
    region: RegionIndex,
) -> bool:
    """§4.4 Change 2's full `sequence_converged` policy -- G-a (per-cell calibration, both the
    "not never-accept" AND "has produced >=1 single-frame accept" clauses), G-b (evidence
    diversity + baseline), G-c (spread/persistence, already `estimate.status`). Any missing
    ingredient (no filter, no converged cell, cell not indexed) is an honest `False`, never an
    exception -- absence of evidence is not evidence, same posture as every other honest-
    abstention gate in this pipeline."""
    if estimate is None or estimate.status != sequence_mod.STATUS_CONVERGED:
        return False
    if estimate.converged_cell_tile_id is None or not estimate.cell_ever_single_frame_accepted:
        return False
    region_is_never_accept = calibrate_mod.is_never_accept(
        region.stats.accept_similarity, region.stats.holdout_recall_at_1
    )
    cell_distinctiveness = next(
        (t.distinctiveness for t in region.index.tiles if t.tile_id == estimate.converged_cell_tile_id), None
    )
    if calibrate_mod.cell_is_never_accept(
        cell_distinctiveness, region_is_never_accept=region_is_never_accept, accept_margin=region.stats.accept_margin
    ):
        return False
    return (
        estimate.supporting_frames >= min_supporting_frames and estimate.baseline_meters >= min_baseline_m
    )


# --- the pipeline itself (§4.1) -----------------------------------------------------------------


def localize_frame(
    image: "np.ndarray",
    *,
    encoder: Encoder,
    regions: list[RegionIndex],
    region_dir_by_id: dict[str, Path],
    matcher_handle,
    match_keypoints_fn: Callable[[object, "np.ndarray", "np.ndarray"], "rerank_mod.MatchKeypoints"],
    telemetry: Optional[Telemetry] = None,
    prior: Optional[tuple[float, float, float]] = None,
    sequence: Optional[SequenceLocalizer] = None,
    motion_delta_e_m: float = 0.0,
    motion_delta_n_m: float = 0.0,
    params: LocalizeParams = LocalizeParams(),
) -> FrameResult:
    """One keyframe through the full frozen §4.1 pipeline. `regions` must already be resolved
    (`resolve_regions`); `sequence`, when given, is MUTATED in place (`predict`/`update`/
    `estimate` all carry real filter state) -- the caller owns the filter's lifetime across a
    session, this function only ever advances it by exactly one frame's worth of evidence.
    """
    # --- B: quality gate -----------------------------------------------------------------------
    score = compute_texture_score(image)
    if is_low_texture(score, min_laplacian_variance=params.min_laplacian_variance, min_entropy=params.min_entropy):
        # §4.1: LOW_TEXTURE short-circuits before the sequence filter is ever reached -- still
        # advance the filter's own motion model (predict-only) so it stays temporally current.
        if sequence is not None:
            sequence.predict(motion_delta_e_m, motion_delta_n_m)
        return FrameResult(
            status=STATUS_LOW_TEXTURE,
            refusal="LOW_TEXTURE",
            message=(
                f"frame rejected pre-encode: laplacian_variance={score.laplacian_variance:.1f} "
                f"(min {params.min_laplacian_variance:.1f}), entropy={score.entropy:.2f} "
                f"(min {params.min_entropy:.2f})"
            ),
        )

    if not regions:
        if sequence is not None:
            sequence.predict(motion_delta_e_m, motion_delta_n_m)
        return FrameResult(status=STATUS_NO_INDEX, refusal="NO_INDEX", message="no READY region to search")

    # --- C/D/D0: telemetry-conditional IPM rectify, single-region only (see module docstring) --
    single_region = regions[0] if len(regions) == 1 else None
    rect = None
    rectified = False
    if (
        params.rectify_enabled
        and single_region is not None
        and len(single_region.index) > 0
        and telemetry is not None
        and telemetry.camera_pitch_deg is not None
        and telemetry.agl_meters is not None
        and telemetry.agl_meters > 0
        and telemetry.camera_pitch_deg >= params.rectify_min_pitch_deg
    ):
        zoom = _region_zoom(single_region)
        if zoom is not None:
            ref_lat = float(np.mean([t.lat for t in single_region.index.tiles]))
            target_gsd = rectify_mod.reference_gsd_m_per_px(ref_lat, zoom)
            fov_deg = telemetry.horizontal_fov_deg or rerank_mod.DEFAULT_ASSUMED_HORIZONTAL_FOV_DEGREES
            focal_px = rectify_mod.focal_px_from_fov(image.shape[1], fov_deg)
            rect = rectify_mod.rectify(
                image,
                pitch_deg=telemetry.camera_pitch_deg,
                focal_px=focal_px,
                roll_deg=telemetry.camera_roll_deg or 0.0,
                heading_deg=telemetry.camera_yaw_deg,
                altitude_m=telemetry.agl_meters,
                target_gsd_m_per_px=target_gsd,
            )
            rectified = rect is not None
    query_for_retrieval = rect.warped if rect is not None else image

    # --- E: retrieve top-k, merged across every resolved region --------------------------------
    descriptor = encoder.encode(query_for_retrieval)
    merged: list[tuple[str, "geo_index.SearchResult"]] = []
    any_region_had_tiles = False
    for region in regions:
        if len(region.index) > 0:
            any_region_had_tiles = True
        results, _search_ms = region.index.search(descriptor, top_k=params.max_candidates, prior=prior)
        merged.extend((region.region_id, r) for r in results)

    if not merged:
        estimate = None
        if sequence is not None:
            sequence.predict(motion_delta_e_m, motion_delta_n_m)
            estimate = sequence.estimate()
        evidence = _idle_sequence_evidence(estimate)
        if prior is not None and any_region_had_tiles:
            return FrameResult(
                status=STATUS_OUT_OF_REGION,
                refusal="OUT_OF_REGION",
                message="prior disc does not intersect any READY region",
                evidence=evidence,
            )
        return FrameResult(
            status=STATUS_NO_FIX,
            refusal="NO_CANDIDATES",
            message="searched, no candidates found",
            evidence=evidence,
        )

    merged.sort(key=lambda pair: pair[1].similarity, reverse=True)
    merged = merged[: params.max_candidates]
    candidates = [
        rerank_mod.Candidate(
            tile_id=r.tile.tile_id,
            lat=r.tile.lat,
            lon=r.tile.lon,
            similarity=r.similarity,
            region_id=region_id,
            distinctiveness=r.tile.distinctiveness,
        )
        for region_id, r in merged
    ]
    winning_region_id = merged[0][0]
    winning_region = next(r for r in regions if r.region_id == winning_region_id)

    def load_tile(candidate: "rerank_mod.Candidate") -> Optional["np.ndarray"]:
        region_dir = region_dir_by_id.get(candidate.region_id)
        if region_dir is None:
            return None
        return rerank_mod.load_region_tile_image(region_dir, candidate.tile_id)

    region_is_never_accept = calibrate_mod.is_never_accept(
        winning_region.stats.accept_similarity, winning_region.stats.holdout_recall_at_1
    )

    def cell_never_accept_fn(candidate: "rerank_mod.Candidate") -> bool:
        return calibrate_mod.cell_is_never_accept(
            candidate.distinctiveness,
            region_is_never_accept=region_is_never_accept,
            accept_margin=winning_region.stats.accept_margin,
        )

    agl_footprint_area_m2 = _expected_footprint_area_m2(telemetry, image.shape[1], image.shape[0])

    # --- F/G: geometric re-rank + §4.2 gates ----------------------------------------------------
    rerank_kwargs = dict(
        top_k=params.max_candidates,
        match_floor=params.match_floor,
        inlier_floor=params.inlier_floor,
        min_inlier_ratio=params.min_inlier_ratio,
        max_reprojection_rms_px=params.max_reprojection_rms_px,
        min_rerank_margin=params.min_rerank_margin,
        cell_is_never_accept_fn=cell_never_accept_fn,
        agl_footprint_area_m2=agl_footprint_area_m2,
    )
    if rect is not None:
        result = rerank_mod.rerank_rectified(
            matcher_handle, rect, candidates, load_tile, match_keypoints_fn, **rerank_kwargs
        )
    else:
        result = rerank_mod.rerank(
            matcher_handle, query_for_retrieval, candidates, load_tile, match_keypoints_fn, **rerank_kwargs
        )

    winner = result.winner
    single_frame_accepted = result.refusal is None
    cleared_inlier_floor = bool(result.gates.get("G-a_inlier_floor"))

    # --- H/I/J: sequence filter update (§4.4 Change 1: geometric field, not raw similarity) ----
    estimate: Optional[SequenceEstimate] = None
    if sequence is not None and single_region is not None:
        tile_row = {t.tile_id: i for i, t in enumerate(single_region.index.tiles)}
        seq_field = np.zeros(len(single_region.index.tiles), dtype=np.float64)
        for cs in result.scored:
            row = tile_row.get(cs.candidate.tile_id)
            if row is not None:
                seq_field[row] = cs.inlier_ratio
        sequence.predict(motion_delta_e_m, motion_delta_n_m)
        estimate = sequence.update(
            seq_field,
            winner_tile_id=winner.candidate.tile_id if winner is not None else None,
            cleared_inlier_floor=cleared_inlier_floor,
            single_frame_accepted=single_frame_accepted,
        )
    elif sequence is not None:
        # Multi-region search: the filter's own grid belongs to a single region it was built
        # from, which does not correspond to `regions` here -- keep it temporally current
        # (predict-only) rather than feed it a field over the wrong tile space.
        sequence.predict(motion_delta_e_m, motion_delta_n_m)
        estimate = sequence.estimate()

    sequence_converged = False
    if estimate is not None and single_region is not None:
        sequence_converged = _sequence_converged_policy(
            estimate,
            min_supporting_frames=params.seq_min_supporting_frames,
            min_baseline_m=params.seq_min_baseline_m,
            region=single_region,
        )

    evidence = FrameEvidence(
        candidate_count=len(result.scored),
        match_count=winner.match_count if winner is not None else 0,
        inlier_count=winner.inlier_count if winner is not None else 0,
        inlier_ratio=winner.inlier_ratio if winner is not None else 0.0,
        rerank_margin=result.rerank_margin,
        reprojection_rms_px=(
            winner.reprojection_rms_px if winner is not None and winner.reprojection_rms_px is not None else 0.0
        ),
        rectified=rectified,
        cell_calibrated=bool(result.gates.get("G-e_cell_calibration")),
        supporting_frames=estimate.supporting_frames if estimate is not None else 0,
        baseline_meters=estimate.baseline_meters if estimate is not None else 0.0,
        sequence_converged=sequence_converged,
        sequence_spread_meters=estimate.spread_m if estimate is not None else 0.0,
        sequence_updates=estimate.update_count if estimate is not None else 0,
        osm_prior=1.0,
    )

    # --- status/refusal: purely §4.2's own gate outcome -- sequence evidence rides along but
    # never blocks/promotes a single frame's own STATUS_FIX/STATUS_NO_FIX (§4.4: "the fix, if
    # any, can reach at most PROBABLE" is a JAVA-side ceiling, §4.3's own table, not a Python
    # refusal) -----------------------------------------------------------------------------------
    if result.refusal is not None or winner is None:
        return FrameResult(
            status=STATUS_NO_FIX,
            region_id=winning_region_id,
            tile_id=winner.candidate.tile_id if winner is not None else "",
            evidence=evidence,
            refusal=result.refusal or "NO_CANDIDATES",
            message="searched and re-ranked, gate refused",
        )

    zoom = _region_zoom(winning_region)
    return FrameResult(
        status=STATUS_FIX,
        region_id=winning_region_id,
        tile_id=winner.candidate.tile_id,
        latitude=winner.pose.lat,
        longitude=winner.pose.lon,
        yaw_degrees=winner.pose.yaw_deg,
        radius_meters=_position_radius_meters(winner, winning_region, zoom),
        evidence=evidence,
    )


def _idle_sequence_evidence(estimate: Optional[SequenceEstimate]) -> FrameEvidence:
    """Evidence for a frame that never reached re-rank (`NO_CANDIDATES`/`OUT_OF_REGION`) --
    everything re-rank-derived is the honest zero/false default; only the sequence filter's own
    (predict-only) peek, when one was taken, is real."""
    if estimate is None:
        return FrameEvidence()
    return FrameEvidence(
        supporting_frames=estimate.supporting_frames,
        baseline_meters=estimate.baseline_meters,
        sequence_converged=False,  # G-a/G-b need a real re-rank outcome this tick; never inferred
        sequence_spread_meters=estimate.spread_m,
        sequence_updates=estimate.update_count,
    )
