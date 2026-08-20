"""LoFTR geometric verification for visual-geolocation retrieval candidates
(docs/VISUAL-GEO-PLAN.md §3.1/§4/§12.6/§12.7; keypoints + telemetry conditioning added by Wave 6a,
§6/§12.11).

Production port of `spikes/geo/loftr_verify.py`'s `build_matcher`/`rerank_with_loftr` matching
logic (validated §12.6/§12.7 on two independent real-world test sets), extended per Wave 6a with
the two things `spikes/geo/homography_pose.py` measured working (§12.11, ported -- not
redesigned):

1. **Keypoints are kept, not thrown away.** `loftr_match_keypoints` (replacing the old
   `loftr_match_score`, whose count/mean-confidence it still reports) tracks BOTH images' resize
   scale factors so every correspondence maps back to native pixel coordinates -- what
   `cv_service/geo/pose.py#fit_homography_pose` fits its MAGSAC homography on.

2. **Telemetry-conditioned matching.** §12.11's load-bearing discovery: kornia LoFTR COLLAPSES
   under combined large in-plane rotation + GSD (scale) mismatch -- SITL raw (~90 deg rotation,
   ~4.3x scale gap) produced ~8 spurious matches; de-rotation alone failed, rescaling alone
   failed, but both together jump to 88-94 matches at ~100% inliers (and cut LoFTR itself 3.6x
   by shrinking the effective image). Both priors are already on the wire
   (`TelemetrySnapshot.heading_degrees`/`altitude_meters`): when BOTH are present, the query is
   de-rotated to north-up by the heading and rescaled to the reference tiles' ground sampling
   distance from the altitude BEFORE LoFTR (`condition_query`, the spike's `normalize_query` math
   ported exactly), and every returned query keypoint is mapped back through the INVERSE
   conditioning transform afterwards -- so downstream consumers (pose fitting) always see native
   query pixels, and the heading/scale prior is folded back into the fitted homography (which is
   what makes `pose.py`'s extracted yaw the compass-corrected absolute yaw, see its docstring).
   When either prior is absent, or conditioning degenerates (sub-32px conditioned image), the
   unconditioned path runs exactly as before Wave 6a.

**Design consequence (§4's frozen semantics, gate revised by Wave 6a)**: verification always RUNS
whenever `request_verification=true` and at least one retrieval candidate exists, but promotion
(`GeoFixResponse.fix`/`verified=true`) is now gated on the MAGSAC homography's RANSAC inlier count
clearing `Settings.geo_verify_inlier_floor` (default 8 -- §12.11: precision 1.0 at >=8 inliers vs
0.83 for raw match count), decided in `cv_service/grpc/servicers.py#LocalizeStream`, NOT here. The
old confident-match-count floor (`Settings.geo_verify_match_floor`, default 12) is still read and
reported through `VerifyResult.verified` for diagnostics/continuity, but it no longer promotes.
This module only reports which candidate won, its match evidence, and the keypoints -- the caller
fits the pose and decides.

**Latency mitigation chosen (§4's explicitly-flagged open question -- "the Wave 0 spike measured
~2s/candidate pair on CPU; a 2s keyframe interval with top-k=5 does not fit"): verify only
`candidates[:2]`, not the full top-k.** This is the plan's own named cheapest option and was
picked over the two alternatives it names (EfficientLoFTR: not a pip-installable kornia-adjacent
drop-in; cadence-limiting: trades a different staleness for the same throughput win without being
cheaper per verification). §12.11's bonus finding softens the residual risk: conditioning cuts
LoFTR 1.9s -> 0.53s/pair (3.6x) by shrinking the effective image, so the conditioned verify step
is CHEAPER than the pre-Wave-6a one -- but the unconditioned fallback path keeps the old ~2s/pair
worst case, still documented rather than silently assumed solved.

Needs `kornia` (LoFTR) + `torch` (the `geo` extra) + `cv2`. Lazily imported by
`cv_service/grpc/servicers.py`'s `LocalizeStream`, same discipline `BuildReferenceIndex` already
uses for `cv_service.geo.orchestrator`/`encoder`. `KF.LoFTR(pretrained="outdoor")` needs internet
on the FIRST call per machine (downloads into `~/.cache/torch/hub/checkpoints/loftr_outdoor.ckpt`),
same caveat as `cv_service/geo/encoder.py#VprHubEncoder`.
"""

from __future__ import annotations

import logging
import math
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional

import cv2
import numpy as np
import torch

from spikes.geo.harvested.localize import Candidate
from spikes.geo.harvested.pose import TILE_PIXELS, parse_tile_id
DEFAULT_ASSUMED_HORIZONTAL_FOV_DEGREES = 84.0  # harvested constant, cv_service/geo/track.py -- track.py itself left behind (VISUAL-GEO-V2-PLAN.md §1.3)

LOGGER = logging.getLogger("cv_service.geo.verify")

LOFTR_RESIZE = 480  # longest side, px -- LoFTR's own recommended working resolution range
CONFIDENCE_THRESHOLD = 0.5  # kornia LoFTR's own suggested default cutoff for "confident" matches
DEFAULT_VERIFY_TOP_N = 2  # latency mitigation -- see module docstring
DEFAULT_MATCH_FLOOR = 12  # §12.7's swept optimum; mirrors Settings.geo_verify_match_floor's default
# Below this conditioned edge length the GSD rescale has shrunk the query into noise -- the spike's
# own degenerate-normalization guard (`normalize_query` returned None below 32px); the
# unconditioned fallback path runs instead.
MIN_CONDITIONED_PX = 32
_EARTH_RADIUS_METERS = 6_371_000.0  # spike's normalize_query constant == index._EARTH_RADIUS_METERS


def build_matcher():
    """Real LoFTR matcher, `spikes/geo/loftr_verify.py#build_matcher` ported verbatim. Callers
    (`GeolocationServicer`) build this ONCE per `LocalizeStream` call, lazily, only if a request
    actually asks for verification -- never at servicer construction, never per-frame."""
    import kornia.feature as KF

    return KF.LoFTR(pretrained="outdoor").eval()


def prep_gray_tensor(image_bgr: "np.ndarray") -> tuple["torch.Tensor", float]:
    """Grayscale + resize-to-LOFTR_RESIZE tensor prep, RETURNING the applied scale factor
    (resized_px = native_px * scale) so keypoints can be mapped back to native pixels --
    `spikes/geo/homography_pose.py#prep_gray_tensor` ported verbatim (the old Wave 3a
    `_prep_gray_tensor` threw the scale away, which is exactly what Wave 6a needed back).
    256px reference tiles are below LOFTR_RESIZE so their scale is 1.0; the query side genuinely
    needs it."""
    gray = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2GRAY)
    h, w = gray.shape
    scale = LOFTR_RESIZE / max(h, w)
    if scale < 1.0:
        gray = cv2.resize(gray, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_AREA)
    else:
        scale = 1.0
    tensor = torch.from_numpy(gray).float()[None, None] / 255.0
    return tensor, scale


def _empty_keypoints() -> "np.ndarray":
    return np.zeros((0, 2), dtype=np.float64)


@dataclass(frozen=True)
class MatchKeypoints:
    """Confident (>= CONFIDENCE_THRESHOLD) LoFTR correspondences between one query/tile pair, in
    each image's NATIVE pixel frame (the resize-to-480 scale already undone) -- what the old
    `loftr_match_score` reduced to a bare (count, mean_confidence) tuple. NOTE: `kp_query` is
    native to the image actually GIVEN to the matcher -- when `verify()` conditioned the query,
    it (not this class) maps the winner's keypoints back to the original frame."""

    kp_query: "np.ndarray"  # [N, 2]
    kp_tile: "np.ndarray"  # [N, 2] (256px tile frame)
    confidence: "np.ndarray"  # [N]

    @property
    def count(self) -> int:
        return int(self.kp_query.shape[0])

    @property
    def mean_confidence(self) -> float:
        return float(self.confidence.mean()) if self.confidence.size else 0.0


_NO_MATCH = None  # lazily-built singleton, see _no_match()


def _no_match() -> MatchKeypoints:
    global _NO_MATCH
    if _NO_MATCH is None:
        _NO_MATCH = MatchKeypoints(_empty_keypoints(), _empty_keypoints(), np.zeros(0))
    return _NO_MATCH


def loftr_match_keypoints(
    matcher, query_image_bgr: "np.ndarray", tile_image_bgr: "np.ndarray"
) -> MatchKeypoints:
    """LoFTR forward pass KEEPING keypoints (what the old `loftr_match_score` dropped), confident
    matches only, mapped back to each image's native pixel frame --
    `spikes/geo/homography_pose.py#loftr_match_keypoints` ported directly (image-level signature
    so it stays the same kind of injection seam `loftr_match_score` was)."""
    query_tensor, query_scale = prep_gray_tensor(query_image_bgr)
    tile_tensor, tile_scale = prep_gray_tensor(tile_image_bgr)
    with torch.no_grad():
        out = matcher({"image0": query_tensor, "image1": tile_tensor})
    confidence = out["confidence"].cpu().numpy() if len(out["confidence"]) else np.zeros(0)
    if confidence.size == 0:
        return _no_match()
    keep = confidence >= CONFIDENCE_THRESHOLD
    kp_query = out["keypoints0"].cpu().numpy()[keep].astype(np.float64) / query_scale
    kp_tile = out["keypoints1"].cpu().numpy()[keep].astype(np.float64) / tile_scale
    return MatchKeypoints(kp_query=kp_query, kp_tile=kp_tile, confidence=confidence[keep])


# --- telemetry conditioning (Wave 6a, §12.11) --------------------------------------------------


@dataclass(frozen=True)
class ConditionedQuery:
    """A query frame de-rotated to north-up and rescaled to the reference tiles' GSD, plus the
    inverse affine that maps conditioned pixels back to the original frame's native pixels."""

    image: "np.ndarray"
    heading_degrees: float  # the de-rotation prior applied
    inverse: "np.ndarray"  # 2x3 affine, conditioned px -> native px

    def to_native(self, points: "np.ndarray") -> "np.ndarray":
        """[N, 2] conditioned-frame pixels -> [N, 2] native-frame pixels."""
        pts = np.asarray(points, dtype=np.float64)
        return pts @ self.inverse[:, :2].T + self.inverse[:, 2]


def reference_meters_per_pixel(tile_lat: float, zoom: int) -> float:
    """Ground sampling distance of a 256px slippy tile at `tile_lat`/`zoom` -- the spike's
    `normalize_query` mpp formula, verbatim."""
    return (
        math.cos(math.radians(tile_lat)) * 2.0 * math.pi * _EARTH_RADIUS_METERS / (TILE_PIXELS * 2**zoom)
    )


def condition_query(
    image_bgr: "np.ndarray",
    heading_degrees: float,
    altitude_meters: float,
    tile_lat: float,
    zoom: int,
    *,
    fov_degrees: float = DEFAULT_ASSUMED_HORIZONTAL_FOV_DEGREES,
    pitch_degrees: float = 0.0,
) -> Optional[ConditionedQuery]:
    """De-rotate the query to north-up (by the telemetry heading) and resample it to the
    reference tiles' GSD (from the telemetry altitude) -- `spikes/geo/run_homography_pose.py
    #normalize_query`'s exact math (§12.11 measured it working; not redesigned), with two
    production adaptations, both stated:

    - the spike resized to a SQUARE (its SITL renders were square); here the same
      footprint-derived scale (`footprint_m = 2 * slant * tan(fov/2)` spans the frame WIDTH,
      matching `track.py#ground_sample_distance_m_per_px`'s horizontal-FOV convention) is applied
      UNIFORMLY so non-square production frames keep their aspect ratio -- byte-identical to the
      spike for square inputs;
    - the GSD latitude comes from the winning candidate TILE's latitude (the georeference
      actually in hand -- `Candidate.lat`), not ground-truth query latitude (which production
      does not have); the cos(lat) difference across one tile is negligible.

    `pitch_degrees` defaults to 0 (nadir): §12.11's honest negative stands -- oblique frames stay
    broken under similarity-only conditioning (~450m/42 deg; perspective pre-rectification from
    gimbal pitch is a later slice) -- and no gimbal pitch reaches this service today anyway.

    `None` when conditioning degenerates (conditioned edge < MIN_CONDITIONED_PX) -- the caller
    falls back to the unconditioned path."""
    h, w = image_bgr.shape[:2]
    rotation = cv2.getRotationMatrix2D((w / 2.0, h / 2.0), heading_degrees, 1.0)
    north_up = cv2.warpAffine(image_bgr, rotation, (w, h))
    slant = altitude_meters / max(math.cos(math.radians(pitch_degrees)), 1e-6)
    footprint_m = 2.0 * slant * math.tan(math.radians(fov_degrees / 2.0))
    mpp = reference_meters_per_pixel(tile_lat, zoom)
    if footprint_m <= 0 or mpp <= 0:
        return None
    scale = (footprint_m / mpp) / w
    new_w, new_h = int(round(w * scale)), int(round(h * scale))
    if min(new_w, new_h) < MIN_CONDITIONED_PX:
        return None
    resized = cv2.resize(north_up, (new_w, new_h), interpolation=cv2.INTER_AREA)

    # Full forward affine (native -> conditioned): per-axis resize scaling composed onto the
    # rotation; inverted once here so keypoints map back exactly.
    forward = rotation.copy()
    forward[0, :] *= new_w / w
    forward[1, :] *= new_h / h
    inverse = cv2.invertAffineTransform(forward)
    return ConditionedQuery(image=resized, heading_degrees=heading_degrees, inverse=inverse)


# --- tile loading -------------------------------------------------------------------------------


def tile_id_to_filename(tile_id: str) -> str:
    """`"17/76648/44197"` -> `"17_76648_44197.jpg"` -- the reverse of the frozen reference-pack
    tile naming (§3.1's `ReferencePackChunk` doc comment; `cv_service.geo.index.ReferenceTileMeta
    .tile_id` is the forward direction, `"<z>/<x>/<y>"`)."""
    return tile_id.replace("/", "_") + ".jpg"


def load_region_tile_image(region_dir: Path, tile_id: str) -> Optional["np.ndarray"]:
    """Load a region's raw reference tile JPEG bytes back off disk
    (`<region_dir>/tiles/<z>_<x>_<y>.jpg`, landed by `cv_service/geo/pack.py`) -- what geometric
    verification matches the query frame against, as opposed to the tile's already-encoded
    descriptor (`index.py`'s job, used for retrieval, not verification). `None` (logged) if the
    file is missing/unreadable -- the caller treats that candidate as unverifiable, not a hard
    error (mirrors `cv_service.geo.encoder.load_image`'s forgiving contract)."""
    path = region_dir / "tiles" / tile_id_to_filename(tile_id)
    image = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if image is None:
        LOGGER.warning("verification: failed to load reference tile image %s", path)
    return image


# --- verify -------------------------------------------------------------------------------------


@dataclass(frozen=True)
class VerifyResult:
    """`winning_index` indexes into the ORIGINAL `candidates` list `verify()` was given (not just
    the `top_n` subset actually scored) -- the caller can use it directly against
    `LocalizeResult.candidates` to find the winning `Candidate`.

    Wave 6a additions: `kp_query`/`kp_tile` are the WINNER's confident correspondences in NATIVE
    pixel frames (query conditioning, if applied, already inverted) -- ready for
    `pose.fit_homography_pose`; `conditioned` records whether telemetry conditioning ran.
    `verified` still reports the OLD match-count floor for diagnostics/continuity -- it no longer
    gates promotion (the servicer's inlier gate does, see module docstring)."""

    winning_index: int
    match_count: int
    mean_confidence: float
    verified: bool
    kp_query: "np.ndarray" = field(default_factory=_empty_keypoints)
    kp_tile: "np.ndarray" = field(default_factory=_empty_keypoints)
    conditioned: bool = False


def verify(
    matcher,
    query_image: "np.ndarray",
    candidates: list[Candidate],
    load_tile_image: Callable[[Candidate], Optional["np.ndarray"]],
    *,
    top_n: int = DEFAULT_VERIFY_TOP_N,
    match_floor: int = DEFAULT_MATCH_FLOOR,
    heading_degrees: Optional[float] = None,
    altitude_meters: Optional[float] = None,
    fov_degrees: float = DEFAULT_ASSUMED_HORIZONTAL_FOV_DEGREES,
    match_keypoints_fn: Callable[
        [object, "np.ndarray", "np.ndarray"], MatchKeypoints
    ] = loftr_match_keypoints,
) -> Optional[VerifyResult]:
    """Re-score `candidates[:top_n]` (§4's latency mitigation, see module docstring) by LoFTR
    confident-match count against `query_image`. `None` only when `candidates` is empty -- nothing
    to verify (§3.1: `request_verification=true` + `>=1 candidate` is exactly the run condition).

    **Telemetry conditioning (Wave 6a)**: when BOTH `heading_degrees` and `altitude_meters` are
    given, the query is conditioned ONCE (`condition_query`, using the top-scored candidate's
    tile latitude/zoom -- top-2 candidates share a zoom and near-identical latitude, so one
    conditioning serves the whole subset, exactly as the spike conditioned once per query) and
    every candidate is matched against the conditioned image; the winner's query keypoints are
    mapped back to native pixels through the inverse transform before being returned. Either
    prior absent, an unparseable tile id, or degenerate conditioning -> the pre-Wave-6a
    unconditioned path, unchanged.

    The winner is whichever of the scored subset has the highest match count -- ties broken by
    mean confidence, then by original retrieval rank (stable sort) -- `spikes/geo/loftr_verify.py
    #rerank_with_loftr`'s own tie-break, ported directly. `verified` reports whether that winner
    cleared `match_floor` (diagnostics -- promotion is the caller's inlier gate now).

    `load_tile_image` is the caller's injection seam for loading a candidate's raw reference tile
    JPEG (needs to know which region's `tiles/` directory it lives under -- see
    `Candidate.region_id` -- which this function itself never resolves). A candidate whose tile
    image fails to load scores 0 matches (unverifiable, not a hard error) rather than aborting the
    whole verification pass. `match_keypoints_fn` is the injection seam tests use to avoid a real
    `kornia`/torch forward pass (successor to Wave 3a's `match_score_fn` seam, same idiom) --
    production always uses the default, `loftr_match_keypoints`.
    """
    if not candidates:
        return None
    subset = candidates[:top_n]

    conditioned: Optional[ConditionedQuery] = None
    if heading_degrees is not None and altitude_meters is not None:
        parsed = parse_tile_id(subset[0].tile_id)
        if parsed is not None:
            zoom = parsed[0]
            conditioned = condition_query(
                query_image,
                heading_degrees,
                altitude_meters,
                subset[0].lat,
                zoom,
                fov_degrees=fov_degrees,
            )
            if conditioned is None:
                LOGGER.info(
                    "verification: conditioning degenerate (alt=%.1fm); falling back to the "
                    "unconditioned path",
                    altitude_meters,
                )
    effective_query = conditioned.image if conditioned is not None else query_image

    scored: list[tuple[int, MatchKeypoints]] = []
    for original_index, candidate in enumerate(subset):
        tile_image = load_tile_image(candidate)
        if tile_image is None:
            scored.append((original_index, _no_match()))
            continue
        scored.append((original_index, match_keypoints_fn(matcher, effective_query, tile_image)))
    scored.sort(key=lambda t: (t[1].count, t[1].mean_confidence), reverse=True)
    winning_index, winner = scored[0]

    kp_query = winner.kp_query
    if conditioned is not None and kp_query.size:
        kp_query = conditioned.to_native(kp_query)
    return VerifyResult(
        winning_index=winning_index,
        match_count=winner.count,
        mean_confidence=winner.mean_confidence,
        verified=winner.count >= match_floor,
        kp_query=kp_query,
        kp_tile=winner.kp_tile,
        conditioned=conditioned is not None,
    )
