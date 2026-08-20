"""Reference-index self-calibration (docs/VISUAL-GEO-PLAN.md §3.1's `ReferenceIndexStats`,
Wave 2a): hold out a fraction of a region's tiles, encode them under a simulated view
perturbation, and sweep `(accept_similarity, accept_margin)` for the highest recall at or below
a false-fix budget -- the self-calibration §3.5 already promises for `accept_similarity`/
`accept_margin`, and that §12.3 found actually matters in practice (a region ingested at too
coarse a zoom must not silently accept a confidently-wrong threshold pair -- see MODULE.md).

Pure numpy/cv2 -- no `cv_pb2`, no encoder/index specifics beyond the plain `HoldoutResult` rows
`cv_service/geo/orchestrator.py` feeds in after running the encoder + `ReferenceIndex.search`
itself. Testable with a handful of synthetic rows, no real weights/tiles needed.
"""

from __future__ import annotations

import logging
import math
from dataclasses import dataclass
from typing import Optional, Sequence

import cv2
import numpy as np

LOGGER = logging.getLogger("cv_service.geo.calibrate")

DEFAULT_HOLDOUT_FRACTION = 0.10
DEFAULT_MAX_FALSE_FIX_RATE = 0.02
# Wave 0's §5 recall radius: at LIVE-query time a fix counts as correct within 100m of truth.
# At CALIBRATION time this is only the FLOOR of the correctness radius -- see
# `holdout_correct_radius_m` below for the §12.13 fix that made the holdout radius
# tile-quantization-aware.
RECALL_DISTANCE_METERS = 100.0
# §12.13 amendment (docs/VISUAL-GEO-PLAN.md): the old two-threshold scheme (recall <=100m,
# false-fix >300m -- `FALSE_FIX_DISTANCE_METERS`, now removed) left a 100-300m BLIND BAND the
# budget never saw: one-tile slides along a linear feature land at 196-278m, so a region like
# btn-road-x calibrated to a near-vacuous gate (0.44/0.01, holdout recall 0.00) because its
# systematic sliding errors were neither "correct" nor "budgeted false". The shipped rule is the
# coherent one: every accepted holdout item is either CORRECT (within `holdout_correct_radius_m`)
# or FALSE -- there is no unbudgeted middle. The radius itself must be quantization-aware
# (below), because a held-out tile is absent from the index by construction, so its best
# achievable top-1 is an immediately-adjacent indexed tile at exactly 1.0 tile step (~195m at
# zoom 17 / Kyiv latitudes) -- a flat 100m radius would declare EVERY zoom-17 region zero-recall,
# including known-good ones (kyiv-maidan's real holdout has a correct direct-neighbor match at
# 194.5m). 1.2 steps accepts a direct neighbor (1.0) with margin and rejects the diagonal
# (sqrt(2) ~ 1.41) one-tile slide that §12.13 measured as the dominant along-feature alias.
HOLDOUT_NEIGHBOR_SLACK = 1.2
_EARTH_CIRCUMFERENCE_METERS = 40_075_016.686
# The never-accept fallback gate: no real query's cosine similarity clears >= 1.0 against an
# index it isn't literally identical to, so `accept_similarity = 1.0` IS "never accept".
NEVER_ACCEPT_SIMILARITY = 1.0


def simulate_view_perturbation(
    image: "np.ndarray",
    rng: "np.random.Generator",
    *,
    max_scale_delta: float = 0.15,
    max_rotation_degrees: float = 15.0,
    max_brightness_delta: float = 30.0,
) -> "np.ndarray":
    """A crude scale + rotation + brightness perturbation standing in for the real difference
    between a static reference tile and what a drone's own (oblique, moving) camera actually sees
    of the same spot. Honesty note (same posture as `spikes/geo/encoders.py#DeterministicEncoder`):
    this is NOT a real viewpoint/perspective simulator -- no drone footage exists yet to calibrate
    one against (§12's whole "still NO GO on retrieval-only" finding) -- it only prevents
    `accept_similarity`/`accept_margin` from being calibrated against the trivial, unrealistically
    easy case of "encode the exact same pixels back."
    """
    height, width = image.shape[:2]
    scale = 1.0 + rng.uniform(-max_scale_delta, max_scale_delta)
    angle = rng.uniform(-max_rotation_degrees, max_rotation_degrees)
    brightness = rng.uniform(-max_brightness_delta, max_brightness_delta)
    matrix = cv2.getRotationMatrix2D((width / 2, height / 2), angle, scale)
    warped = cv2.warpAffine(image, matrix, (width, height), borderMode=cv2.BORDER_REFLECT)
    return np.clip(warped.astype(np.float32) + brightness, 0, 255).astype(np.uint8)


def _parse_tile_id(tile_id: str) -> Optional[tuple[int, int, int]]:
    """`"17/76648/44197"` -> `(zoom, x, y)`; `None` on anything else (synthetic test rows use
    plain names -- they fall back to the flat `RECALL_DISTANCE_METERS` radius)."""
    parts = tile_id.split("/")
    if len(parts) != 3:
        return None
    try:
        zoom, x, y = (int(p) for p in parts)
    except ValueError:
        return None
    if zoom < 0 or x < 0 or y < 0:
        return None
    return zoom, x, y


def holdout_correct_radius_m(tile_id: str) -> float:
    """The distance within which a held-out tile's top-1 pick counts as CORRECT (§12.13).

    A held-out tile is not in the index, so the best any retrieval can do is an immediately-
    adjacent indexed tile at 1.0 tile step -- `HOLDOUT_NEIGHBOR_SLACK` (1.2) steps accepts that
    with jitter margin while rejecting the sqrt(2)-step diagonal slide. Tile step size comes from
    the tile's own zoom + latitude (Web-Mercator ground resolution); floored at
    `RECALL_DISTANCE_METERS` so a sub-100m-spacing region (higher zoom / extreme latitude) keeps
    live-query semantics, and an unparseable tile id falls back to the flat 100m radius."""
    parsed = _parse_tile_id(tile_id)
    if parsed is None:
        return RECALL_DISTANCE_METERS
    zoom, _x, y = parsed
    # Center latitude of tile row y (exact inverse Web-Mercator).
    lat = math.degrees(math.atan(math.sinh(math.pi * (1.0 - 2.0 * (y + 0.5) / (1 << zoom)))))
    step_m = _EARTH_CIRCUMFERENCE_METERS * math.cos(math.radians(lat)) / (1 << zoom)
    return max(RECALL_DISTANCE_METERS, HOLDOUT_NEIGHBOR_SLACK * step_m)


def leave_one_out_distinctiveness(descriptors: "np.ndarray") -> "np.ndarray":
    """Per-tile distinctiveness for EVERY indexed tile (§13.3 item 1, Slice A) -- computed at
    region-build time from the reference descriptors alone, persisted per tile row in
    `tiles.json`, and consumed by `LocalizeStream`'s verification candidate ordering.

    **Definition (documented judgment call, honesty over neatness)**: `1 - max_{j != i}
    cos(d_i, d_j)` -- the leave-one-out self-margin. A true query at tile *i* scores ~1.0 on
    tile *i* itself and at most `max_{j != i} sim(d_i, d_j)` on any alias, so this IS the
    expected retrieval accept-margin when the query genuinely is at that tile: near 0 for
    repeated-texture aliases (§12.13's "every seeing tile shows the same line" sliding case),
    high for a tile nothing else in the region resembles. The §12.12/§12.13 never-accept region
    verdict is the degenerate whole-region case of this per-tile score (plan §13.3's own note).

    Why NOT the calibration holdout margins the plan names: those are measured for only the
    ~10% held-out tiles -- which, being held out, are absent from the index and never appear as
    verification candidates at all -- so they cannot honestly label the tiles that DO need a
    score. This leave-one-out form is defined identically for every indexed tile, costs one
    N x N cosine matmul at build time (milliseconds at the <=500-tile scale, chunked below to
    stay memory-bounded at 10^4+), and needs no neutral-default mixing. Regions built BEFORE
    this field existed load with `distinctiveness=None` per tile -- consumers treat that as
    neutral (candidate ordering degrades to pure retrieval order).

    Descriptors are assumed L2-normalized (every `Encoder` guarantees it); computed in float32
    regardless of the stored dtype (the index itself ships fp16 as of Slice A). A 1-tile region
    has no other tile to be confused with -- distinctiveness 1.0.
    """
    reference = np.asarray(descriptors, dtype=np.float32)
    n = reference.shape[0]
    if n == 0:
        return np.zeros(0, dtype=np.float32)
    if n == 1:
        return np.ones(1, dtype=np.float32)
    out = np.empty(n, dtype=np.float32)
    chunk = 1024  # bounds the sim-matrix slab at chunk x n floats
    for start in range(0, n, chunk):
        stop = min(start + chunk, n)
        sims = reference[start:stop] @ reference.T
        for row, i in enumerate(range(start, stop)):
            sims[row, i] = -np.inf  # exclude self
        out[start:stop] = 1.0 - sims.max(axis=1)
    return out


def is_never_accept(accept_similarity: float, holdout_recall_at_1: float) -> bool:
    """True when a region's calibration is (or must be treated as) the never-accept fallback --
    the §12.13 promotion-block predicate `cv_service/grpc/servicers.py` consults before letting
    verification promote a retrieval refusal to `GEO_FIX`.

    Two conditions, deliberately: `accept_similarity >= NEVER_ACCEPT_SIMILARITY` is the fallback
    gate itself (a threshold no real query clears -- compared with `>=`, never float equality);
    `holdout_recall_at_1 <= 0` additionally catches calibrations persisted BEFORE the §12.13
    sweep fix, which could carry a loose gate alongside zero measured recall (btn-road-x shipped
    0.44/0.01 with recall 0.00) -- with the fixed sweep the two conditions coincide, so this only
    ever tightens. Such regions must be rebuilt to become promotion-eligible again."""
    return accept_similarity >= NEVER_ACCEPT_SIMILARITY or holdout_recall_at_1 <= 0.0


@dataclass(frozen=True)
class HoldoutResult:
    """One held-out tile's outcome: the encoder's own top-1 similarity + top1/top2 margin against
    the reference index (built from the OTHER tiles), and how far that top-1 pick actually is
    from the held-out tile's true position. `margin` is `0.0` when the reference index had fewer
    than 2 tiles to compare against (mirrors `GeoFixResponse.margin`'s "0 if <2" wire contract,
    §3.1)."""

    tile_id: str
    top_similarity: float
    margin: float
    error_meters: float


@dataclass(frozen=True)
class CalibrationResult:
    accept_similarity: float
    accept_margin: float
    holdout_recall_at_1: float
    false_fix_rate: float
    holdout_median_error_meters: float


def sweep_thresholds(
    results: Sequence[HoldoutResult],
    *,
    max_false_fix_rate: float = DEFAULT_MAX_FALSE_FIX_RATE,
    similarity_steps: int = 101,
    margin_steps: int = 21,
    max_margin: float = 0.2,
) -> tuple[float, float, float, float]:
    """Grid-sweep `(accept_similarity, accept_margin)` for the operating point with the highest
    holdout recall among every pair whose false-fix rate (fraction of ACCEPTED holdout items
    beyond their own `holdout_correct_radius_m`) stays at or below `max_false_fix_rate`. Returns
    `(accept_similarity, accept_margin, recall_at_1, false_fix_rate)` for the winning pair.

    §12.13 amendments (both measured gaps, docs/VISUAL-GEO-PLAN.md):

    - **No blind band.** Correct and false are complements: an accepted holdout item is correct
      within its quantization-aware radius (`holdout_correct_radius_m` -- direct-neighbor step
      yes, diagonal slide no) and FALSE beyond it. The old scheme (correct <=100m, false >300m)
      let 196-278m one-tile slides through unbudgeted, which is exactly how btn-road-x calibrated
      to a 70%-wrong gate.
    - **A zero-recall pair is never accepted.** A threshold pair that accepts holdout items
      without a single correct one among them proves nothing except that something clears it --
      the never-accept fallback is emitted instead. (Under the old rule a zero-recall pair with
      zero >300m errors "cleared the budget".)

    No pair achieving recall > 0 within the budget (or an empty `results`) falls back to the
    strictest possible gate (`accept_similarity = NEVER_ACCEPT_SIMILARITY`, i.e. never accept) --
    an honestly-reported zero-recall region is the safe failure, not a silently-loose threshold
    (§12.3's own "reject or loudly warn" finding).
    """
    if not results:
        return NEVER_ACCEPT_SIMILARITY, max_margin, 0.0, 0.0

    similarities = np.array([r.top_similarity for r in results], dtype=np.float64)
    margins = np.array([r.margin for r in results], dtype=np.float64)
    errors = np.array([r.error_meters for r in results], dtype=np.float64)
    correct_radii = np.array([holdout_correct_radius_m(r.tile_id) for r in results], dtype=np.float64)
    correct = errors <= correct_radii
    false_fix = ~correct  # §12.13: complements -- no unbudgeted band between them
    total = len(results)

    best_similarity, best_margin_t = NEVER_ACCEPT_SIMILARITY, max_margin
    best_recall, best_false_fix_rate = 0.0, 0.0
    found = False
    for similarity_t in np.linspace(0.0, 1.0, similarity_steps):
        similarity_mask = similarities >= similarity_t
        if not similarity_mask.any():
            continue
        for margin_t in np.linspace(0.0, max_margin, margin_steps):
            accept_mask = similarity_mask & (margins >= margin_t)
            accepted = int(accept_mask.sum())
            if accepted == 0:
                continue
            false_fix_rate = float((accept_mask & false_fix).sum()) / accepted
            if false_fix_rate > max_false_fix_rate:
                continue
            recall = float((accept_mask & correct).sum()) / total
            if recall > best_recall:  # best_recall starts at 0.0 -- a zero-recall pair never wins
                found = True
                best_similarity, best_margin_t = float(similarity_t), float(margin_t)
                best_recall, best_false_fix_rate = recall, false_fix_rate

    if not found:
        LOGGER.warning(
            "no (accept_similarity, accept_margin) pair achieved recall > 0 within the %.1f%% "
            "false-fix budget over %d holdout tiles; falling back to the strictest gate (never "
            "accept) -- this region's reference imagery may be too coarse, too homogeneous, or "
            "dominated by a repeated linear feature, see docs/VISUAL-GEO-PLAN.md §12.3/§12.13",
            max_false_fix_rate * 100,
            total,
        )
        return NEVER_ACCEPT_SIMILARITY, max_margin, 0.0, 0.0
    return best_similarity, best_margin_t, best_recall, best_false_fix_rate


def calibrate(
    results: Sequence[HoldoutResult], *, max_false_fix_rate: float = DEFAULT_MAX_FALSE_FIX_RATE
) -> CalibrationResult:
    """Full calibration: sweep thresholds, then report the median position error among the
    holdout items the chosen thresholds would actually have accepted (falling back to every
    holdout item's error if none were accepted, so a degenerate region still reports a real
    number rather than a NaN)."""
    accept_similarity, accept_margin, recall, false_fix_rate = sweep_thresholds(
        results, max_false_fix_rate=max_false_fix_rate
    )
    accepted_errors = [
        r.error_meters for r in results if r.top_similarity >= accept_similarity and r.margin >= accept_margin
    ]
    if accepted_errors:
        median_error = float(np.median(accepted_errors))
    elif results:
        median_error = float(np.median([r.error_meters for r in results]))
    else:
        median_error = 0.0
    return CalibrationResult(
        accept_similarity=accept_similarity,
        accept_margin=accept_margin,
        holdout_recall_at_1=recall,
        false_fix_rate=false_fix_rate,
        holdout_median_error_meters=median_error,
    )
