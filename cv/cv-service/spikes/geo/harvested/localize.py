"""Live geolocation query path (docs/VISUAL-GEO-PLAN.md §3.1/§4, Wave 3a): given one drone
keyframe, answer "where is this?" against a region's reference index.

Four steps, in order, each able to short-circuit the rest:

1. **Quality gate** (D4/§4: "blur variance, entropy... Python-side, the cheapest place to reject
   a frame is where the pixels already are") -- Laplacian variance (blur proxy) + Shannon entropy
   of the grayscale histogram (texture/detail proxy), computed on the RAW decoded frame, before
   any encoding happens. A frame that fails this never reaches the (comparatively expensive) VPR
   encoder at all -- `GEO_LOW_TEXTURE`.
2. **Region resolution** (`resolve_regions`) -- load the region(s) to search straight off disk.
   `region_id` non-empty: that one region, if it's READY. `region_id == ""`: the frozen wire
   contract's own words (`GeoFrameRequest.region_id`'s doc comment, §3.1) -- "search every READY
   region" -- so every region with both a valid `region.json` and `index.json` is loaded and
   searched. `[]` either way it isn't -> `GEO_NO_INDEX`.
3. **Search** -- encode the frame once, search every resolved region's index (restricted to the
   request's `GeoPrior` disc when present, region-wide otherwise -- `ReferenceIndex.search`
   already does both), merge every region's own top-k by similarity, re-truncate to the global
   top-k. A prior that doesn't intersect ANY resolved region's tiles (zero candidates back from
   every region, prior given) is `GEO_OUT_OF_REGION`, distinct from `GEO_NO_FIX` (searched fine,
   nothing cleared the acceptance gate).
4. **Accept/reject** -- the winning (highest-similarity) candidate is checked against its OWN
   region's self-calibrated `accept_similarity`/`accept_margin` (`ReferenceIndexStats`, written by
   Wave 2a's `calibrate.py`) -- `GEO_FIX` if both clear, `GEO_NO_FIX` otherwise. `confidence` is a
   continuous [0,1] map of (similarity, margin) against those same two thresholds (`
   compute_confidence` below); `radius_meters` reuses the region's own `holdout_median_error_meters`
   as the 1-sigma positional uncertainty of `fix` -- an empirically-measured number this service
   already computed at calibration time, not a new invented formula.

Never imports `cv_pb2` or touches the wire -- `cv_service/grpc/servicers.py#LocalizeStream` is the
sole translation point, same discipline every other `cv_service.geo` module follows. Needs `cv2`
(Laplacian/histogram) + `numpy` -- the `cv`/`geo` extras combo, same as `calibrate.py`/`encoder.py`;
lazily imported by `servicers.py`, never at its module scope (mirrors `BuildReferenceIndex`'s own
lazy import of `cv_service.geo.orchestrator`).

**Multi-region merge is this wave's own invention (judgment call, not spelled out by the plan's
3a text)**: §3.1 freezes `region_id`'s meaning ("" = search every READY region) but the plan's
Wave 3 scope text only ever talks about "the region's index" (singular). Merging is the literal,
simplest reading of the frozen wire comment: rank every resolved region's own candidates together
by raw similarity, keep the global top-k, and apply the WINNING candidate's own region's
calibration to the accept decision (each region is independently self-calibrated -- comparing a
candidate against a threshold some OTHER region was calibrated against would be meaningless).
`margin` is computed over the merged global top-2, matching `GeoFixResponse.margin`'s own wire
definition ("candidates[0].similarity - candidates[1].similarity") literally, regardless of which
region either came from.

**No caching (known limitation, not fixed in this wave)**: `resolve_regions` reads `index.json` +
`descriptors.npy` off disk on EVERY `LocalizeStream` request -- for the `region_id == ""` "search
every region" path this means every ready region's full descriptor array is reloaded every
keyframe. Fine for the region counts/index sizes this wave was built and tested against; a
follow-up could cache loaded `ReferenceIndex`es on `GeolocationServicer` the way `ModelRegistry`
caches loaded detectors (deliberately not done here to avoid inventing a cache-invalidation story
for "a region got rebuilt mid-process" within this wave's scope).
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

import cv2
import numpy as np

from spikes.geo.harvested import index as geo_index
from spikes.geo.harvested import pack as geo_pack
from spikes.geo.harvested.encoder import Encoder

LOGGER = logging.getLogger("cv_service.geo.localize")

# Plain string statuses mirroring `GeoFixStatus` enum names one-for-one (minus the zero value
# `GEO_FIX_STATUS_UNSPECIFIED`, which this module never produces) -- kept as strings, not the
# generated `cv_pb2` enum, so this module stays wire-agnostic like every other `cv_service.geo`
# module; `cv_service/grpc/servicers.py` maps these 1:1 onto `cv_pb2.GeoFixStatus`.
STATUS_FIX = "GEO_FIX"
STATUS_NO_FIX = "GEO_NO_FIX"
STATUS_LOW_TEXTURE = "GEO_LOW_TEXTURE"
STATUS_OUT_OF_REGION = "GEO_OUT_OF_REGION"
STATUS_NO_INDEX = "GEO_NO_INDEX"

# Quality-gate thresholds (D4/§4). **UNVALIDATED placeholders** -- no real drone footage exists
# yet to calibrate these against (same honesty posture as `calibrate.py#simulate_view_perturbation`'s
# docstring: this whole feature's own Wave 0 gate was "NO GO on retrieval-only... real footage is
# the one decisive input still missing", docs/VISUAL-GEO-PLAN.md §12.5). Chosen from common
# blur-detection/entropy-of-a-256-level-grayscale-histogram literature defaults (Laplacian
# variance ~100 is a commonly-cited full-resolution blur cutoff; halved here on the assumption
# that `vision.geo.frame-width`-downscaled frames -- §3.5 default 512px -- carry less high-frequency
# detail than a full-resolution frame would, so the same absolute blur would read as a lower
# variance). Revisit once real footage is available to actually calibrate against, exactly like
# `calibrate.py`'s own view-perturbation model already flags.
DEFAULT_MIN_LAPLACIAN_VARIANCE = 50.0
DEFAULT_MIN_ENTROPY = 3.0

DEFAULT_MAX_CANDIDATES = 5  # mirrors Settings.geo_max_candidates' own default (CV_GEO_MAX_CANDIDATES)


@dataclass(frozen=True)
class TextureScore:
    laplacian_variance: float
    entropy: float


def compute_texture_score(image: "np.ndarray") -> TextureScore:
    """Laplacian variance (blur proxy: a sharp image has strong high-frequency edges, a blurry
    one doesn't) + Shannon entropy of the 8-bit grayscale histogram (texture/detail proxy: a flat
    featureless frame -- sky, a blown-out white wall -- has low entropy regardless of blur)."""
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


def is_low_texture(
    score: TextureScore,
    *,
    min_laplacian_variance: float = DEFAULT_MIN_LAPLACIAN_VARIANCE,
    min_entropy: float = DEFAULT_MIN_ENTROPY,
) -> bool:
    """Either signal failing is enough to reject -- a sharp-but-blank frame (high Laplacian
    variance from a hard edge, e.g. a runway line, but otherwise nothing to match against) is
    just as useless to VPR retrieval as a blurry-but-detailed one."""
    return score.laplacian_variance < min_laplacian_variance or score.entropy < min_entropy


@dataclass(frozen=True)
class Candidate:
    """Plain, wire-agnostic counterpart of `GeoCandidate` (§3.1), PLUS `region_id` -- which the
    wire message deliberately does NOT carry (§3.1 freezes `GeoCandidate` with no region field),
    but `cv_service/geo/verify.py` needs internally to know which region's `tiles/` directory a
    candidate's raw JPEG lives under. `cv_service/grpc/servicers.py` drops `region_id` when
    translating to the wire `GeoCandidate`."""

    tile_id: str
    lat: float
    lon: float
    similarity: float
    region_id: str = ""


@dataclass(frozen=True)
class RegionIndex:
    """One READY region's loaded search index + its calibrated acceptance stats -- what
    `resolve_regions` hands to `localize()`."""

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
    """Load the region(s) `LocalizeStream` should search, straight off disk (no caching, see
    module docstring).

    - `requested_region_id` non-empty: that one region, IF it's READY (valid `region.json` +
      `index.json` + loadable descriptors) -- `[]` otherwise (caller reports `GEO_NO_INDEX`).
    - `requested_region_id == ""`: **every** READY region, sorted by id for determinism (§3.1's
      own wire comment on `GeoFrameRequest.region_id`) -- `[]` if none exist.
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


def compute_confidence(
    similarity: float,
    margin: float,
    accept_similarity: float,
    accept_margin: float,
    *,
    similarity_span: float = 0.05,
    margin_span: float = 0.05,
) -> float:
    """Continuous [0,1] confidence from (`similarity`, `margin`) relative to the region's
    calibrated (`accept_similarity`, `accept_margin`) thresholds -- exactly `0.5` right AT each
    threshold, saturating toward `0`/`1` within `+-span` of it, averaged evenly across the two
    signals.

    **This formula is this wave's own invention** -- §3.1 only fixes the field's range/spirit
    ("[0,1] -- the region's calibrated map from (similarity, margin)"), not an exact shape. A
    smooth, symmetric, monotonic ramp was chosen over a hard 0/1 step so a near-miss candidate
    still reports a low-but-nonzero number (useful for the Wave 4c diagnostics drawer) rather than
    looking identical to a wildly-wrong one. `similarity_span`/`margin_span` are plain function
    defaults, not `CV_GEO_*` knobs -- unlike `accept_similarity`/`accept_margin` themselves they
    were never asked to be region-calibrated, and inventing a second calibration axis for them
    was judged out of this wave's scope.
    """

    def _ramp(value: float, threshold: float, span: float) -> float:
        if span <= 0:
            return 1.0 if value >= threshold else 0.0
        return max(0.0, min(1.0, 0.5 + (value - threshold) / (2 * span)))

    return 0.5 * _ramp(similarity, accept_similarity, similarity_span) + 0.5 * _ramp(
        margin, accept_margin, margin_span
    )


@dataclass(frozen=True)
class LocalizeResult:
    status: str
    region_id: str = ""
    candidates: list[Candidate] = field(default_factory=list)
    fix: Optional[Candidate] = None
    margin: float = 0.0
    confidence: float = 0.0
    radius_meters: float = 0.0
    message: str = ""
    # Wave 7a (docs/VISUAL-GEO-PLAN.md §6/§13.6): the query's own encoded descriptor, set
    # whenever `localize()` reached the encode step (STATUS_FIX/STATUS_NO_FIX/
    # STATUS_OUT_OF_REGION -- never STATUS_LOW_TEXTURE/STATUS_NO_INDEX, which short-circuit
    # before encoding). `cv_service/grpc/servicers.py#LocalizeStream` reuses this -- instead of
    # a second `encoder.encode()` call -- to compute the FULL per-tile similarity field
    # (`region.index.descriptors @ descriptor`) the sequence localizer needs, which this
    # function's own top-k search never materializes on its own.
    descriptor: Optional["np.ndarray"] = None


def localize(
    image: "np.ndarray",
    *,
    encoder: Encoder,
    regions: list[RegionIndex],
    prior: Optional[tuple[float, float, float]] = None,
    max_candidates: int = DEFAULT_MAX_CANDIDATES,
    min_laplacian_variance: float = DEFAULT_MIN_LAPLACIAN_VARIANCE,
    min_entropy: float = DEFAULT_MIN_ENTROPY,
) -> LocalizeResult:
    """The core per-frame retrieval decision (§3.1/§4). Pure aside from `encoder.encode` (a
    forward pass, no disk IO of its own) -- `regions` must already be resolved
    (`resolve_regions`), so this function never touches disk and is fully testable with fake
    in-memory `RegionIndex`es, no real tiles/encoder needed.

    Order: quality gate (may reject BEFORE encoding) -> encode -> search every given region
    (prior-restricted when given) -> merge globally by similarity -> accept/reject the winner
    against ITS OWN region's calibrated thresholds.
    """
    score = compute_texture_score(image)
    if is_low_texture(score, min_laplacian_variance=min_laplacian_variance, min_entropy=min_entropy):
        return LocalizeResult(
            status=STATUS_LOW_TEXTURE,
            message=(
                f"frame rejected pre-encode: laplacian_variance={score.laplacian_variance:.1f} "
                f"(min {min_laplacian_variance:.1f}), entropy={score.entropy:.2f} (min {min_entropy:.2f})"
            ),
        )

    if not regions:
        return LocalizeResult(status=STATUS_NO_INDEX, message="no READY region to search")

    descriptor = encoder.encode(image)

    merged: list[tuple[str, "geo_index.SearchResult"]] = []
    any_region_had_tiles = False
    for region in regions:
        if len(region.index) > 0:
            any_region_had_tiles = True
        results, _search_ms = region.index.search(descriptor, top_k=max_candidates, prior=prior)
        merged.extend((region.region_id, result) for result in results)

    if not merged:
        if prior is not None and any_region_had_tiles:
            return LocalizeResult(
                status=STATUS_OUT_OF_REGION,
                message="prior disc does not intersect any READY region",
                descriptor=descriptor,
            )
        return LocalizeResult(
            status=STATUS_NO_FIX, message="searched, no candidates found", descriptor=descriptor
        )

    merged.sort(key=lambda pair: pair[1].similarity, reverse=True)
    merged = merged[:max_candidates]

    candidates = [
        Candidate(
            tile_id=result.tile.tile_id,
            lat=result.tile.lat,
            lon=result.tile.lon,
            similarity=result.similarity,
            region_id=region_id,
        )
        for region_id, result in merged
    ]

    winning_region_id, top_result = merged[0]
    winning_stats = next(r.stats for r in regions if r.region_id == winning_region_id)
    top1_similarity = top_result.similarity
    margin = (top_result.similarity - merged[1][1].similarity) if len(merged) > 1 else 0.0
    confidence = compute_confidence(
        top1_similarity, margin, winning_stats.accept_similarity, winning_stats.accept_margin
    )

    accepted = top1_similarity >= winning_stats.accept_similarity and margin >= winning_stats.accept_margin
    if not accepted:
        return LocalizeResult(
            status=STATUS_NO_FIX,
            region_id=winning_region_id,
            candidates=candidates,
            margin=margin,
            confidence=confidence,
            message="searched, similarity/margin did not clear the region's calibrated gate",
            descriptor=descriptor,
        )
    return LocalizeResult(
        status=STATUS_FIX,
        region_id=winning_region_id,
        candidates=candidates,
        fix=candidates[0],
        margin=margin,
        confidence=confidence,
        descriptor=descriptor,
        radius_meters=winning_stats.holdout_median_error_meters,
    )
