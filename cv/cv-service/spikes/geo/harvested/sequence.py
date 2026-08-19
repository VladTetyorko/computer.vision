"""Sequence localizer (docs/VISUAL-GEO-PLAN.md §6 Wave 7a / §13.6): a station-side particle
filter over per-frame similarity fields + odometry deltas, converging to fixes over terrain
where single-frame localization is honestly never-accept (fields/roads/forest — §13.6's
measured 63/63 converged, 0 confident-wrong in 1,289 CONVERGED steps, controls clean).

Production lift of `spikes/geo/pf_spike.py#SequenceLocalizer` — the spike class was written
importable for exactly this move, and the filter math here is kept IDENTICAL (measurement
update = softmax over the similarity field at `temperature`; motion update = odometry delta
rotated/scaled by per-particle process noise + isotropic diffusion; ESS-triggered systematic
resampling; convergence = posterior spread <= 0.4·tile-step for 3 consecutive measurement
updates, min 5). Two production-only additions, both stated:

- `from_tile_ids` — builds the filter straight from a region's indexed `tile_id`s
  ("<z>/<x>/<y>", `ReferenceTileMeta.tile_id`), deriving the grid bounding box, the
  region-center tile scale and the convergence threshold. Grid cells inside the bounding box
  with no descriptor row (the calibration holdout — real regions always have those) are
  NEUTRAL under measurement, exactly the spike's holdout-cell semantics.
- `estimate()` — a read-only peek used on time-update-only frames (`GEO_LOW_TEXTURE` produced
  no similarity field; the spike replayed measurement-only sequences and never needed this).
  It re-applies the convergence predicate against the CURRENT posterior spread, so a filter
  whose posterior has drifted wide during a measurement gap honestly reports SEARCHING again
  without fabricating a fresh measurement update.

HONEST-ABSTENTION DISCIPLINE (unchanged from the spike): CONVERGED is reported only after the
posterior spread (sqrt of covariance trace, meters) has stayed at or below `spread_fix_m` for
`persistence` consecutive measurement updates AND at least `min_updates` updates have
happened; the streak resets whenever the spread re-inflates, so the filter can honestly
UN-converge. `sequence_fix` consumers must treat anything not CONVERGED as "no position".

**Wave 10b addition (docs/VISUAL-GEO-PLAN.md §12.14/Wave 10)**: this module TRACKS, but does
NOT itself decide, extra evidence about the window of measurement updates that produced a
CONVERGED verdict — `cv_service/grpc/servicers.py#LocalizeStream` is the one place that turns
this into an actual gate on reporting `CONVERGED`/`sequence_fix` on the wire (see its own
docstring for the exact policy, including why never-accept regions are EXEMPT). Two fields per
`SequenceEstimate`, both windowed over the last `persistence` measurement updates (see
`_windowed_evidence`):

- `evidence_similarity`/`evidence_margin` — mean per-frame similarity/margin AT the converged
  cell (`_cell_evidence`), informational (the servicer does not currently gate on these alone —
  see its docstring for why a straight accept-similarity gate was tried and rejected).
- `evidence_motion_m` — TOTAL real-world displacement (odometry/visual-tracking/telemetry, the
  exact same `predict(delta_e_m, delta_n_m)` inputs the filter already consumes) accumulated
  across the window. This is the load-bearing one: §12.14's real failure was a near-static
  hover — every one of ~12 near-duplicate frames measured the SAME systematically mis-ranked
  scene, so "3 consecutive agreeing measurements" was never independent evidence to begin with.
  A genuine multi-viewpoint trajectory (what sequence localization is FOR) naturally accumulates
  real displacement across a window; a camera repeatedly fooled by the same domain-gap bias
  while barely moving does not — `DIVERSITY_MIN_TILE_STEPS` below is the servicer's own
  minimum-diversity threshold on this field, for non-never-accept regions.

`calibrate.py` (accept-similarity/margin, `is_never_accept`) is never imported here — it pulls
in `cv2`, which would break the "no cv2" rule below, and the actual accept/never-accept decision
needs region calibration this module was never given. The core filter math (predict/update
weights, resampling, the spread/persistence convergence predicate itself) is UNCHANGED by this
addition — it only adds bookkeeping the servicer reads.

Pure `numpy` + stdlib — no cv2, no torch, and NEVER `cv_pb2` (`cv_service/grpc/servicers.py`
is the sole wire-translation point, same discipline as every `cv_service.geo` module).
Deterministic under an injected seed/RNG: every random draw goes through the one
`numpy.random.Generator` handed to (or built by) the constructor.
"""

from __future__ import annotations

import math
from collections import deque
from dataclasses import dataclass, field
from typing import Optional

import numpy as np

# Plain string statuses, mirroring the wire `GeoSequenceStatus` names minus their prefix --
# kept as strings (not the generated enum) so this module stays wire-agnostic, same idiom as
# `cv_service/geo/localize.py`'s STATUS_* constants.
STATUS_SEARCHING = "SEARCHING"
STATUS_CONVERGED = "CONVERGED"

# §13.6's measured/frozen parameters (spikes/geo/results/pf-spike-20260808T180243Z):
# 4000 particles, softmax temperature 0.02 (tuned on the sanity region ONLY, then frozen for
# every danger region), process noise heading σ8° / speed σ15% / 8 m diffusion, resample at
# ESS < 0.5·N, outlier mix 0.05. Particles + temperature are env-tunable
# (CV_GEO_SEQUENCE_PARTICLES / CV_GEO_SEQUENCE_TEMPERATURE, `cv_service/config.py`); the rest
# are module constants until something is measured that says otherwise.
DEFAULT_PARTICLES = 4000
DEFAULT_TEMPERATURE = 0.02
DEFAULT_HEADING_SIGMA_DEG = 8.0
DEFAULT_SPEED_SIGMA = 0.15
DEFAULT_DIFFUSION_M = 8.0
DEFAULT_OUTLIER_MIX = 0.05
DEFAULT_ESS_RESAMPLE_FRAC = 0.5

# Convergence rule (§13.6, frozen): spread <= 0.4·tile-step for 3 consecutive measurement
# updates, never before 5 updates have happened.
CONVERGENCE_SPREAD_TILE_FRACTION = 0.4
CONVERGENCE_PERSISTENCE = 3
CONVERGENCE_MIN_UPDATES = 5

# Wave 10b (docs/VISUAL-GEO-PLAN.md §12.14/Wave 10): minimum cumulative real-world displacement
# across the `persistence`-update convergence window, expressed in tile-steps for the same
# reason `CONVERGENCE_SPREAD_TILE_FRACTION` is (regions at different zooms/latitudes have
# different tile sizes) -- `cv_service/grpc/servicers.py` requires `evidence_motion_m >=
# diversity_min_m` (== this × tile_m) before reporting CONVERGED on a NON-never-accept region.
# 1.0 tile-step is ~195m at zoom 17 near Kyiv -- in the same ballpark as "a couple hundred
# meters" and comfortably clear of a near-static hover's frame-to-frame ORB/RANSAC jitter (this
# module's own existing tests already move the ground truth 0.5 tile-step PER UPDATE on a
# "genuinely moving" track, so 1.0 tile-step cumulative over a 3-update window is a low bar for
# real motion, not a high one) while requiring more than zero real displacement to ever pass.
DIVERSITY_MIN_TILE_STEPS = 1.0

_EARTH_CIRCUMFERENCE_M = 40_075_016.686


def parse_tile_id(tile_id: str) -> Optional[tuple[int, int, int]]:
    """`"17/76648/44197"` -> `(zoom, x, y)`; None on anything malformed. Duplicates
    `cv_service/geo/pose.py#parse_tile_id`'s three lines rather than importing them -- pose.py
    imports cv2 at module scope and this module must stay importable with numpy alone."""
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


def tile_to_latlon(u: float, v: float, zoom: int) -> tuple[float, float]:
    """Fractional global Web-Mercator tile coordinates -> WGS-84 (lat, lon). Same math as
    `spikes/geo/geomath.py#tile2deg` (and `cv_service/geo/index.py#_tile_nw_corner`)."""
    n = 2.0**zoom
    lon = u / n * 360.0 - 180.0
    lat = math.degrees(math.atan(math.sinh(math.pi * (1.0 - 2.0 * v / n))))
    return lat, lon


def tile_size_meters(lat_degrees: float, zoom: int) -> float:
    """Ground size of one tile step at `lat_degrees` (Web Mercator), meters."""
    return _EARTH_CIRCUMFERENCE_M * math.cos(math.radians(lat_degrees)) / (2.0**zoom)


def bearing_distance_to_en(bearing_degrees: float, distance_meters: float) -> tuple[float, float]:
    """A (bearing, distance) motion estimate -> (east, north) meters. `bearing_degrees` is
    clockwise from true north, [0,360) -- the same convention `VisualTrackingEstimate.
    bearing_degrees` and `TelemetrySnapshot.heading_degrees` already carry on the wire, so
    `cv_service/grpc/servicers.py#LocalizeStream` can feed either straight through."""
    theta = math.radians(bearing_degrees)
    return distance_meters * math.sin(theta), distance_meters * math.cos(theta)


@dataclass(frozen=True)
class SequenceParams:
    """Everything the filter is allowed to know. Distances in meters. Field-for-field the
    spike's `PfParams` (frozen here, matching this codebase's dataclass idiom)."""

    n_particles: int = DEFAULT_PARTICLES
    temperature: float = DEFAULT_TEMPERATURE
    heading_sigma_deg: float = DEFAULT_HEADING_SIGMA_DEG
    speed_sigma: float = DEFAULT_SPEED_SIGMA
    diffusion_m: float = DEFAULT_DIFFUSION_M
    outlier_mix: float = DEFAULT_OUTLIER_MIX
    ess_resample_frac: float = DEFAULT_ESS_RESAMPLE_FRAC
    spread_fix_m: float = 0.0  # set from tile size at construction (0.4 * tile step)
    persistence: int = CONVERGENCE_PERSISTENCE
    min_updates: int = CONVERGENCE_MIN_UPDATES
    # Wave 10b: set from tile size at construction (DIVERSITY_MIN_TILE_STEPS * tile step) --
    # the servicer's own minimum-cumulative-displacement gate, see this module's docstring.
    diversity_min_m: float = 0.0

    def __post_init__(self) -> None:
        if self.n_particles < 1:
            raise ValueError(f"n_particles must be >= 1, got {self.n_particles}")
        if self.temperature <= 0:
            raise ValueError(f"temperature must be > 0, got {self.temperature}")


@dataclass(frozen=True)
class SequenceEstimate:
    """One posterior snapshot. `lat`/`lon` are the posterior MEAN regardless of status --
    consumers must publish a position ONLY when `status == STATUS_CONVERGED` (the wire
    contract's "never a fabricated position while SEARCHING")."""

    status: str  # STATUS_SEARCHING | STATUS_CONVERGED
    mean_uv: tuple[float, float]  # posterior mean, fractional global tile coords
    lat: float  # posterior mean, WGS-84
    lon: float
    spread_m: float  # sqrt(trace of posterior covariance), meters
    ess_frac: float  # effective sample size / N (before any resampling)
    top_cell_share: float  # posterior weight share of the heaviest tile cell
    resampled: bool
    update_count: int  # measurement updates since (re)init
    # Wave 10b (docs/VISUAL-GEO-PLAN.md §12.14/Wave 10): windowed evidence over the last
    # `persistence` measurement updates (see `_windowed_evidence`) -- informational fields the
    # SERVICER (not this module) turns into an actual gate on reporting CONVERGED. Populated on
    # every `SequenceEstimate`, not just a converged one, so a caller/diagnostic can inspect the
    # running evidence at any time.
    # Mean per-frame similarity AT the converged cell; `None` when every update in the window
    # landed on a cell with no descriptor row (a calibration-holdout cell) -- treat that the
    # same as "unknown", never as "fine".
    evidence_similarity: Optional[float] = None
    # Mean per-frame margin (the FIELD's own global top1-top2 gap each update, not cell-
    # specific -- see `_cell_evidence`); always a real number, `0.0` before any update.
    evidence_margin: float = 0.0
    # TOTAL real-world displacement (meters) accumulated across the window's `predict()` inputs
    # -- the primary Wave 10b signal (see module docstring's "load-bearing one" note); `0.0`
    # before any update, and genuinely `0.0` (not "unknown") when motion truly was zero/absent
    # every tick in the window (diffusion-only fallback).
    evidence_motion_m: float = 0.0


class SequenceLocalizer:
    """Station-side sequence localizer over (similarity field, odometry delta) pairs.

    State: `n_particles` particles over continuous position inside the region, in fractional
    global Web-Mercator tile coordinates (meters via the region-center tile scale -- regions
    are ~1-2 km across, so a single scale is honest). Uninformed init: uniform over the
    region's tile bounding box.

    `predict(delta_e_m, delta_n_m)`: each particle applies the odometry delta rotated by
    N(0, heading_sigma) and scaled by N(1, speed_sigma), plus isotropic diffusion. A
    (0, 0) delta degrades to diffusion-only -- the honest "no odometry this frame" motion.

    `update(similarities)`: per-tile likelihood exp((sim - max)/T); a particle takes the
    likelihood of the tile cell containing it; cells without a descriptor row (the
    calibration holdout) are NEUTRAL (mean likelihood); outside the region entirely ->
    0.1 * min likelihood (strong but non-annihilating). An `outlier_mix` fraction of mean
    likelihood is mixed in so one bad frame cannot kill the true mode. Weights multiply,
    normalize; systematic resampling on ESS < frac*N.
    """

    def __init__(
        self,
        tile_rows_xy: np.ndarray,
        grid_origin: tuple[int, int],
        grid_size: tuple[int, int],
        tile_size_m: float,
        zoom: int,
        params: SequenceParams,
        *,
        seed: Optional[int] = None,
        rng: Optional[np.random.Generator] = None,
    ) -> None:
        if tile_size_m <= 0:
            raise ValueError(f"tile_size_m must be > 0, got {tile_size_m}")
        self.params = params
        self.tile_m = float(tile_size_m)
        self.zoom = int(zoom)
        self.x0, self.y0 = grid_origin
        self.nx, self.ny = grid_size
        self.rng = rng if rng is not None else np.random.default_rng(seed)
        # cell -> descriptor row lookup grid (-1 = no descriptor: holdout cell)
        self.row_grid = np.full((self.ny, self.nx), -1, dtype=np.int64)
        for row, (tx, ty) in enumerate(np.asarray(tile_rows_xy)):
            self.row_grid[int(ty) - self.y0, int(tx) - self.x0] = row
        self.n_rows = int(np.asarray(tile_rows_xy).shape[0])
        n = params.n_particles
        self.pu = self.rng.uniform(self.x0, self.x0 + self.nx, size=n)
        self.pv = self.rng.uniform(self.y0, self.y0 + self.ny, size=n)
        self.w = np.full(n, 1.0 / n)
        self._streak = 0
        self._updates = 0
        # Wave 10b: rolling window of the last `persistence` updates' (cell_similarity,
        # cell_margin, motion_since_last_update_m) triples (see `_cell_evidence`/
        # `_windowed_evidence`). A plain fixed-size deque, appended once per `update()` call
        # regardless of streak state -- because the streak resets to 0 on any spread
        # re-inflation (see `update()`), whenever `converged` is True the last `persistence`
        # appends are GUARANTEED to be exactly the streak that produced it (no reset happened
        # in between, by the streak's own definition), so this needs no separate streak-aware
        # bookkeeping. `_pending_motion_m` accumulates `predict()`'s own input displacement
        # between measurement updates; `update()` captures + resets it into the window.
        self._evidence_window: "deque" = deque(maxlen=max(1, params.persistence))
        self._pending_motion_m = 0.0

    @classmethod
    def from_tile_ids(
        cls,
        tile_ids: list[str],
        *,
        n_particles: int = DEFAULT_PARTICLES,
        temperature: float = DEFAULT_TEMPERATURE,
        seed: Optional[int] = None,
        rng: Optional[np.random.Generator] = None,
    ) -> "SequenceLocalizer":
        """Build a filter for a region straight from its indexed tile ids, IN ROW ORDER (the
        i-th tile id must correspond to row i of every similarity field later fed to
        `update`). Raises ValueError on an empty list, a malformed tile id, or mixed zooms --
        callers treat that as "this region cannot host a sequence filter" (OFF), never a
        crash."""
        parsed = []
        for tile_id in tile_ids:
            triple = parse_tile_id(tile_id)
            if triple is None:
                raise ValueError(f"unparsable tile id {tile_id!r}")
            parsed.append(triple)
        if not parsed:
            raise ValueError("no tile ids")
        zooms = {z for z, _, _ in parsed}
        if len(zooms) != 1:
            raise ValueError(f"mixed zooms {sorted(zooms)} in one region")
        zoom = zooms.pop()
        xs = [x for _, x, _ in parsed]
        ys = [y for _, _, y in parsed]
        x0, y0 = min(xs), min(ys)
        nx, ny = max(xs) - x0 + 1, max(ys) - y0 + 1
        center_lat, _ = tile_to_latlon(x0 + nx / 2.0, y0 + ny / 2.0, zoom)
        tile_m = tile_size_meters(center_lat, zoom)
        params = SequenceParams(
            n_particles=n_particles,
            temperature=temperature,
            spread_fix_m=CONVERGENCE_SPREAD_TILE_FRACTION * tile_m,
            diversity_min_m=DIVERSITY_MIN_TILE_STEPS * tile_m,
        )
        tile_rows_xy = np.array([[x, y] for _, x, y in parsed], dtype=np.int64)
        return cls(
            tile_rows_xy, (x0, y0), (nx, ny), tile_m, zoom, params, seed=seed, rng=rng
        )

    @property
    def update_count(self) -> int:
        """Measurement updates since (re)init -- the wire `sequence_update_count`."""
        return self._updates

    def predict(self, delta_e_m: float, delta_n_m: float) -> None:
        p = self.params
        n = self.pu.size
        theta = np.radians(self.rng.normal(0.0, p.heading_sigma_deg, size=n))
        scale = self.rng.normal(1.0, p.speed_sigma, size=n)
        cos_t, sin_t = np.cos(theta), np.sin(theta)
        de = (delta_e_m * cos_t - delta_n_m * sin_t) * scale
        dn = (delta_e_m * sin_t + delta_n_m * cos_t) * scale
        de += self.rng.normal(0.0, p.diffusion_m, size=n)
        dn += self.rng.normal(0.0, p.diffusion_m, size=n)
        self.pu += de / self.tile_m
        self.pv -= dn / self.tile_m  # tile v grows SOUTH
        # Wave 10b: accumulate the INPUT displacement magnitude (the caller's own odometry/
        # visual-tracking/telemetry estimate, not the noisy per-particle realization above)
        # since the last measurement update -- `update()` captures + resets this into the
        # evidence window (`_windowed_evidence`'s `evidence_motion_m`).
        self._pending_motion_m += math.hypot(delta_e_m, delta_n_m)

    def update(self, similarities: np.ndarray) -> SequenceEstimate:
        similarities = np.asarray(similarities, dtype=np.float64).ravel()
        if similarities.shape[0] != self.n_rows:
            raise ValueError(
                f"similarity field has {similarities.shape[0]} rows, filter expects {self.n_rows}"
            )
        p = self.params
        lik_tiles = np.exp((similarities - similarities.max()) / p.temperature)
        neutral = float(lik_tiles.mean())
        floor = 0.1 * float(lik_tiles.min())
        cx = np.floor(self.pu).astype(np.int64) - self.x0
        cy = np.floor(self.pv).astype(np.int64) - self.y0
        inside = (cx >= 0) & (cx < self.nx) & (cy >= 0) & (cy < self.ny)
        lik = np.full(self.pu.size, floor)
        rows = self.row_grid[cy[inside], cx[inside]]
        cell_lik = np.where(rows >= 0, lik_tiles[np.clip(rows, 0, None)], neutral)
        lik[inside] = cell_lik
        lik = p.outlier_mix * neutral + (1.0 - p.outlier_mix) * lik
        self.w *= lik
        total = self.w.sum()
        if total <= 0 or not np.isfinite(total):  # pathological frame: reset to uniform weights
            self.w[:] = 1.0 / self.w.size
        else:
            self.w /= total
        ess = 1.0 / float(np.sum(self.w**2))
        ess_frac = ess / self.w.size
        mean_uv, spread_m = self._estimate_pos()
        self._updates += 1
        self._streak = self._streak + 1 if spread_m <= p.spread_fix_m else 0
        converged = self._streak >= p.persistence and self._updates >= p.min_updates
        top_share = self._top_cell_share()
        resampled = False
        if ess_frac < p.ess_resample_frac:
            self._systematic_resample()
            resampled = True
        lat, lon = tile_to_latlon(mean_uv[0], mean_uv[1], self.zoom)
        # Wave 10b: record THIS update's own cell evidence (computed against the pre-resample
        # posterior mean, same `mean_uv` the wire position uses) plus the real displacement
        # accumulated since the last update into the rolling window, then report the window's
        # current aggregate -- see `_cell_evidence`/`_windowed_evidence`.
        motion_since_last_update = self._pending_motion_m
        self._pending_motion_m = 0.0
        cell_similarity, cell_margin = self._cell_evidence(similarities, mean_uv)
        self._evidence_window.append((cell_similarity, cell_margin, motion_since_last_update))
        evidence_similarity, evidence_margin, evidence_motion_m = self._windowed_evidence()
        return SequenceEstimate(
            status=STATUS_CONVERGED if converged else STATUS_SEARCHING,
            mean_uv=mean_uv,
            lat=lat,
            lon=lon,
            spread_m=spread_m,
            ess_frac=ess_frac,
            top_cell_share=top_share,
            resampled=resampled,
            update_count=self._updates,
            evidence_similarity=evidence_similarity,
            evidence_margin=evidence_margin,
            evidence_motion_m=evidence_motion_m,
        )

    def estimate(self) -> SequenceEstimate:
        """Read-only posterior peek for time-update-only frames (no similarity field this
        frame): never mutates the streak/update counters OR the Wave 10b evidence window (a
        pending `predict()`-only motion delta stays PENDING, only folded into the window by the
        next real `update()` -- an `estimate()` call reports the window exactly as it stood
        after the last real measurement). The convergence predicate is re-applied against the
        CURRENT spread, so a posterior that drifted wide during a measurement gap honestly
        reports SEARCHING again without fabricating a fresh measurement update."""
        mean_uv, spread_m = self._estimate_pos()
        converged = (
            self._streak >= self.params.persistence
            and self._updates >= self.params.min_updates
            and spread_m <= self.params.spread_fix_m
        )
        lat, lon = tile_to_latlon(mean_uv[0], mean_uv[1], self.zoom)
        evidence_similarity, evidence_margin, evidence_motion_m = self._windowed_evidence()
        return SequenceEstimate(
            status=STATUS_CONVERGED if converged else STATUS_SEARCHING,
            mean_uv=mean_uv,
            lat=lat,
            lon=lon,
            spread_m=spread_m,
            ess_frac=1.0 / float(np.sum(self.w**2)) / self.w.size,
            top_cell_share=self._top_cell_share(),
            resampled=False,
            update_count=self._updates,
            evidence_similarity=evidence_similarity,
            evidence_margin=evidence_margin,
            evidence_motion_m=evidence_motion_m,
        )

    def _estimate_pos(self) -> tuple[tuple[float, float], float]:
        mu = float(np.sum(self.w * self.pu))
        mv = float(np.sum(self.w * self.pv))
        var_u = float(np.sum(self.w * (self.pu - mu) ** 2))
        var_v = float(np.sum(self.w * (self.pv - mv) ** 2))
        spread_m = math.sqrt(max(var_u + var_v, 0.0)) * self.tile_m
        return (mu, mv), spread_m

    def _top_cell_share(self) -> float:
        cx = np.clip(np.floor(self.pu).astype(np.int64) - self.x0, 0, self.nx - 1)
        cy = np.clip(np.floor(self.pv).astype(np.int64) - self.y0, 0, self.ny - 1)
        flat = cy * self.nx + cx
        sums = np.bincount(flat, weights=self.w, minlength=self.nx * self.ny)
        return float(sums.max())

    def _cell_evidence(
        self, similarities: np.ndarray, mean_uv: tuple[float, float]
    ) -> tuple[Optional[float], float]:
        """Wave 10b, one update's own raw evidence, before windowing: `similarities` at the
        grid cell containing `mean_uv` (the posterior mean this same update just computed) --
        `None` if that cell has no descriptor row (calibration holdout) or `mean_uv` has
        drifted outside the region's tile grid entirely -- plus the FIELD's global top1-top2
        margin (mirrors `calibrate.py`/`localize.py`'s own margin definition: `0.0` when the
        field has fewer than 2 rows, same "0 if <2" contract `HoldoutResult.margin` uses)."""
        u, v = mean_uv
        cx = int(math.floor(u)) - self.x0
        cy = int(math.floor(v)) - self.y0
        cell_similarity: Optional[float] = None
        if 0 <= cx < self.nx and 0 <= cy < self.ny:
            row = int(self.row_grid[cy, cx])
            if row >= 0:
                cell_similarity = float(similarities[row])
        if similarities.shape[0] >= 2:
            top2 = np.partition(similarities, -2)[-2:]
            cell_margin = float(top2[1] - top2[0])
        else:
            cell_margin = 0.0
        return cell_similarity, cell_margin

    def _windowed_evidence(self) -> tuple[Optional[float], float, float]:
        """Wave 10b: aggregate the rolling `_evidence_window` (up to the last `persistence`
        updates) into `(evidence_similarity, evidence_margin, evidence_motion_m)` --
        `SequenceEstimate`'s own field trio. Similarity is averaged only over entries that
        actually landed on an indexed cell (`None` if none did -- "unknown", not "neutral");
        margin and motion are always real numbers, defaulting to `0.0`/`0.0` before any update."""
        if not self._evidence_window:
            return None, 0.0, 0.0
        sims = [s for s, _m, _d in self._evidence_window if s is not None]
        margins = [m for _s, m, _d in self._evidence_window]
        motions = [d for _s, _m, d in self._evidence_window]
        evidence_similarity = float(np.mean(sims)) if sims else None
        evidence_margin = float(np.mean(margins)) if margins else 0.0
        evidence_motion_m = float(np.sum(motions))
        return evidence_similarity, evidence_margin, evidence_motion_m

    def _systematic_resample(self) -> None:
        n = self.w.size
        positions = (self.rng.random() + np.arange(n)) / n
        cumsum = np.cumsum(self.w)
        cumsum[-1] = 1.0
        idx = np.searchsorted(cumsum, positions)
        self.pu = self.pu[idx].copy()
        self.pv = self.pv[idx].copy()
        self.w[:] = 1.0 / n

    def particles(self) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        """Diagnostics/tests only."""
        return self.pu, self.pv, self.w
