"""Per-configuration metrics, exactly what docs/VISUAL-GEO-PLAN.md §5 asks
the Wave 0 spike to report: recall@1/@5 <=100m, median/p90 position error,
false-fix rate at a calibrated threshold, encode ms/frame, search ms over
the pack, bootstrap length.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Optional

RECALL_DISTANCE_M = 100.0
FALSE_FIX_DISTANCE_M = 300.0
DEFAULT_FALSE_FIX_TARGET = 0.02
DEFAULT_BOOTSTRAP_FRAMES = 3  # matches vision.geo.fusion.bootstrap-frames default (§3.5)


@dataclass(frozen=True)
class QueryOutcome:
    query_id: str
    true_lat: float
    true_lon: float
    timestamp_ms: Optional[int]
    top1_lat: Optional[float]
    top1_lon: Optional[float]
    top1_similarity: Optional[float]
    margin: float
    top1_distance_m: Optional[float]
    best5_within_100m: bool
    encode_ms: float
    search_ms: float
    n_candidates: int


@dataclass(frozen=True)
class ConfigMetrics:
    config_name: str
    encoder_name: str
    zoom: int
    augmented: bool
    with_prior: bool
    n_queries: int
    n_matched: int  # queries with >=1 candidate returned (nonempty prior disc)
    recall_at_1_100m: float
    recall_at_5_100m: float
    median_error_m: float
    p90_error_m: float
    accept_similarity: float
    false_fix_rate: float
    n_accepted: int
    encode_ms_per_frame: float
    search_ms: float
    bootstrap_frames: Optional[int]
    bootstrap_note: str


def _percentile(sorted_values: list[float], pct: float) -> float:
    if not sorted_values:
        return float("nan")
    if len(sorted_values) == 1:
        return sorted_values[0]
    k = (len(sorted_values) - 1) * (pct / 100.0)
    lo, hi = math.floor(k), math.ceil(k)
    if lo == hi:
        return sorted_values[int(k)]
    return sorted_values[lo] * (hi - k) + sorted_values[hi] * (k - lo)


def pick_accept_threshold(
    outcomes: list[QueryOutcome], target_false_fix: float = DEFAULT_FALSE_FIX_TARGET
) -> tuple[float, float, int]:
    """Simple threshold pick (docs/VISUAL-GEO-PLAN.md §5: "you'll need a
    simple threshold pick, e.g. maximize recall subject to false-fix <= some
    small rate"): sweep every observed top-1 similarity as a candidate
    "accept iff similarity >= threshold" gate, keep the one admitting the
    most within-100m fixes among those with false_fix_rate <= target; if
    none clears the target, fall back to the threshold with the lowest
    false_fix_rate (report is then honest that no gate cleared the bar).
    Returns (threshold, false_fix_rate, n_accepted)."""
    matched = [o for o in outcomes if o.top1_similarity is not None and o.top1_distance_m is not None]
    if not matched:
        return float("nan"), float("nan"), 0

    candidate_thresholds = sorted({o.top1_similarity for o in matched})
    best: Optional[tuple[float, float, int, int]] = None  # thr, false_fix_rate, n_accepted, n_good
    fallback: Optional[tuple[float, float, int, int]] = None

    for thr in candidate_thresholds:
        accepted = [o for o in matched if o.top1_similarity >= thr]
        if not accepted:
            continue
        n_false = sum(1 for o in accepted if o.top1_distance_m > FALSE_FIX_DISTANCE_M)
        false_fix_rate = n_false / len(accepted)
        n_good = sum(1 for o in accepted if o.top1_distance_m <= RECALL_DISTANCE_M)
        row = (thr, false_fix_rate, len(accepted), n_good)

        if fallback is None or false_fix_rate < fallback[1] or (
            false_fix_rate == fallback[1] and len(accepted) > fallback[2]
        ):
            fallback = row

        if false_fix_rate <= target_false_fix:
            if best is None or n_good > best[3] or (n_good == best[3] and len(accepted) > best[2]):
                best = row

    chosen = best if best is not None else fallback
    if chosen is None:
        return float("nan"), float("nan"), 0
    thr, false_fix_rate, n_accepted, _n_good = chosen
    return thr, false_fix_rate, n_accepted


def bootstrap_frames_to_first_fix(
    outcomes_in_time_order: list[QueryOutcome], recall_distance_m: float = RECALL_DISTANCE_M
) -> tuple[Optional[int], str]:
    """Keyframes from cold start to the first correct fix (§5's "bootstrap
    length"). This is a SIMPLIFIED single-frame version -- the first
    keyframe whose own top-1 candidate lands within `recall_distance_m` of
    truth -- not the full §4 N-consecutive-agreement bootstrap gate (that
    gate lives in Java's PositionFusion, Wave 3/4; this spike only proves
    retrieval quality, not the track state machine). Only meaningful when
    `outcomes_in_time_order` is a real time-ordered sequence (video mode);
    manifest/smoke-test frames have no track order, so callers pass an
    empty list and get (None, "N/A: ...") back."""
    if not outcomes_in_time_order:
        return None, "N/A: no time-ordered sequence (manifest/pre-labeled frames have no track to bootstrap along)"
    for index, outcome in enumerate(outcomes_in_time_order, start=1):
        if outcome.top1_distance_m is not None and outcome.top1_distance_m <= recall_distance_m:
            return index, "simplified: first single keyframe within 100m, not the full N-consecutive-agreement gate"
    return None, f"never achieved a fix within {recall_distance_m:.0f}m across {len(outcomes_in_time_order)} keyframes"


def compute_config_metrics(
    *,
    config_name: str,
    encoder_name: str,
    zoom: int,
    augmented: bool,
    with_prior: bool,
    outcomes: list[QueryOutcome],
    bootstrap_outcomes_in_time_order: Optional[list[QueryOutcome]] = None,
    false_fix_target: float = DEFAULT_FALSE_FIX_TARGET,
) -> ConfigMetrics:
    n_queries = len(outcomes)
    matched = [o for o in outcomes if o.top1_distance_m is not None]
    n_matched = len(matched)

    recall1 = (sum(1 for o in matched if o.top1_distance_m <= RECALL_DISTANCE_M) / n_queries) if n_queries else 0.0
    recall5 = (sum(1 for o in matched if o.best5_within_100m) / n_queries) if n_queries else 0.0

    distances = sorted(o.top1_distance_m for o in matched)
    median_err = _percentile(distances, 50)
    p90_err = _percentile(distances, 90)

    accept_sim, false_fix_rate, n_accepted = pick_accept_threshold(outcomes, target_false_fix=false_fix_target)

    encode_values = [o.encode_ms for o in outcomes]
    search_values = [o.search_ms for o in outcomes]
    encode_ms = sum(encode_values) / len(encode_values) if encode_values else 0.0
    search_ms = sum(search_values) / len(search_values) if search_values else 0.0

    bootstrap, bootstrap_note = bootstrap_frames_to_first_fix(bootstrap_outcomes_in_time_order or [])

    return ConfigMetrics(
        config_name=config_name,
        encoder_name=encoder_name,
        zoom=zoom,
        augmented=augmented,
        with_prior=with_prior,
        n_queries=n_queries,
        n_matched=n_matched,
        recall_at_1_100m=recall1,
        recall_at_5_100m=recall5,
        median_error_m=median_err,
        p90_error_m=p90_err,
        accept_similarity=accept_sim,
        false_fix_rate=false_fix_rate,
        n_accepted=n_accepted,
        encode_ms_per_frame=encode_ms,
        search_ms=search_ms,
        bootstrap_frames=bootstrap,
        bootstrap_note=bootstrap_note,
    )
