"""Sequence localizer + false-convergence gate evidence (docs/plans/done/VISUAL-GEO-V2-PLAN.md
§4.4, frozen). Production port of `spikes/geo/harvested/sequence.py#SequenceLocalizer` -- the
core particle-filter math (measurement update = softmax over the similarity field at
`temperature`; motion update = odometry delta rotated/scaled by per-particle process noise +
isotropic diffusion; ESS-triggered systematic resampling; convergence = posterior spread
<= 0.4*tile-step for 3 consecutive measurement updates, min 5) is kept IDENTICAL -- §13.6 measured
it 63/63 converged, 0 confident-wrong in 1,289 CONVERGED steps.

**§4.4 Change 2 replaces the harvested module's own Wave 10b evidence extension** (an earlier,
now-superseded design from the parked branch's `docs/VISUAL-GEO-PLAN.md` §12.14, keyed on raw
per-cell similarity/margin over a `persistence`-sized window). §4.4's frozen `GeoEvidence` wire
fields are `cell_calibrated` / `supporting_frames` / `baseline_meters` / `sequence_converged` --
this module tracks the latter two (`supporting_frames`, `baseline_meters`); `cell_calibrated`
needs the region's per-tile calibration data (`cv_service.geo.calibrate`), which this module is
deliberately never given (same "no cv2" / no region-calibration-coupling posture the harvested
docstring already stated), so `cv_service/grpc/servicers.py#LocalizeStream` combines this
module's `SequenceEstimate.converged_cell_tile_id` with `calibrate.cell_is_never_accept` itself.

Per real measurement update, the caller (`cv_service.geo.localize`) passes THREE new pieces of
evidence about that update's own re-rank outcome (`cv_service.geo.rerank.RerankResult`), in
addition to the geometric similarity field itself:

- `winner_tile_id` -- the tile id of the field's own argmax row this update (the re-rank
  winner) -- `None` when the field was empty (§4.1's `GEO_LOW_TEXTURE`/no-candidates case).
- `cleared_inlier_floor` -- whether that winner's score cleared §4.2's G-a (inlier floor) alone
  -- the WEAK bar Change 2's G-b diversity count uses.
- `single_frame_accepted` -- whether that winner's score cleared EVERY §4.2 gate (i.e.
  `RerankResult.refusal is None`) -- the STRONG bar Change 2's G-a "cell calibration" clause
  checks for (a cell the single-frame path would have independently trusted, at least once).

Each `update()` appends `(winner_tile_id, cleared_inlier_floor, single_frame_accepted,
motion_since_last_update_m)` to a rolling window (`_evidence_window`, sized
`EVIDENCE_WINDOW_MULTIPLIER * max(persistence, min_supporting_frames)` -- generous enough that an
occasional non-matching or G-a-failing frame interleaved among genuine repeat views of the
converged cell does not make G-b structurally unreachable, while staying bounded to "recent"
history at the sequence's own sampling rate). At every update, `SequenceEstimate` reports, scoped
to whichever cell the CURRENT posterior mean sits in (`converged_cell_tile_id`):

- `supporting_frames` -- count of window entries whose OWN winner_tile_id matches
  `converged_cell_tile_id` AND cleared the inlier floor (§4.4's "distinct frames whose own
  inliers cleared G-a of §4.2").
- `baseline_meters` -- real platform displacement SPANNED between the first and last such
  supporting entry in the window (sum of `motion_since_last_update_m` strictly after the first
  supporting entry through the last) -- `0.0` when fewer than 2 supporting entries exist, since
  a single observation spans no baseline by definition.
- `cell_ever_single_frame_accepted` -- whether any window entry matching
  `converged_cell_tile_id` also cleared EVERY §4.2 gate (Change 2's G-a second clause).

The servicer's own CONVERGED policy (Change 2, not this module's job to enforce) is then:
`sequence_converged = filter_converged AND cell_ever_single_frame_accepted AND (not
cell_is_never_accept) AND supporting_frames >= CV_GEO_SEQ_MIN_SUPPORTING_FRAMES AND
baseline_meters >= CV_GEO_SEQ_MIN_BASELINE_M`. With no telemetry (`predict(0, 0)` every tick,
the honest "no odometry this frame" degradation), `baseline_meters` stays at or near `0.0` and
`sequence_converged` is structurally unreachable -- exactly §4.4's stated intent ("a hover ...
cannot confirm anything").

Pure `numpy` + stdlib -- no cv2, no torch, and NEVER `cv_pb2` (`cv_service/grpc/servicers.py` is
the sole wire-translation point, same discipline as every `cv_service.geo` module).
Deterministic under an injected seed/RNG: every random draw goes through the one
`numpy.random.Generator` handed to (or built by) the constructor.
"""

from __future__ import annotations

import math
from collections import deque
from dataclasses import dataclass
from typing import Optional

import numpy as np

# Plain string statuses, mirroring the wire `GeoSequenceStatus` names minus their prefix -- kept
# as strings (not the generated enum) so this module stays wire-agnostic, same idiom as
# `cv_service/geo/localize.py`'s STATUS_* constants.
STATUS_SEARCHING = "SEARCHING"
STATUS_CONVERGED = "CONVERGED"

# §13.6's measured/frozen parameters (spikes/geo/results/pf-spike-20260808T180243Z): 4000
# particles, softmax temperature 0.02 (tuned on the sanity region ONLY, then frozen for every
# danger region), process noise heading sigma 8 deg / speed sigma 15% / 8 m diffusion, resample
# at ESS < 0.5*N, outlier mix 0.05. Particles + temperature are env-tunable
# (CV_GEO_SEQUENCE_PARTICLES / CV_GEO_SEQUENCE_TEMPERATURE, `cv_service/config.py`); the rest are
# module constants until something is measured that says otherwise.
DEFAULT_PARTICLES = 4000
DEFAULT_TEMPERATURE = 0.02
DEFAULT_HEADING_SIGMA_DEG = 8.0
DEFAULT_SPEED_SIGMA = 0.15
DEFAULT_DIFFUSION_M = 8.0
DEFAULT_OUTLIER_MIX = 0.05
DEFAULT_ESS_RESAMPLE_FRAC = 0.5

# Convergence rule (§13.6, frozen -- Change 2's G-c, "harvested unchanged"): spread <=
# 0.4*tile-step for 3 consecutive measurement updates, never before 5 updates have happened.
CONVERGENCE_SPREAD_TILE_FRACTION = 0.4
CONVERGENCE_PERSISTENCE = 3
CONVERGENCE_MIN_UPDATES = 5

# §4.4 Change 2 defaults (CV_GEO_SEQ_MIN_SUPPORTING_FRAMES / CV_GEO_SEQ_MIN_BASELINE_M,
# `cv_service/config.py`) -- callers normally pass `Settings`-resolved values into
# `SequenceParams`; these are the fallback used when a caller (e.g. a unit test) builds
# `SequenceParams` directly.
DEFAULT_MIN_SUPPORTING_FRAMES = 4
DEFAULT_MIN_BASELINE_M = 40.0

# Evidence-window sizing: generous slack over the larger of "how many consecutive spread-updates
# G-c needs" and "how many supporting frames G-b needs", so an occasional frame that lands on a
# different cell or fails the inlier floor does not make G-b structurally unreachable even once
# the filter has otherwise converged. A plain multiplier (not a further hardcoded window size) --
# derived from the two configured knobs it must outlast, not invented separately.
EVIDENCE_WINDOW_MULTIPLIER = 3

_EARTH_CIRCUMFERENCE_M = 40_075_016.686


def parse_tile_id(tile_id: str) -> Optional[tuple[int, int, int]]:
    """`"17/76648/44197"` -> `(zoom, x, y)`; `None` on anything malformed. Duplicates
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
    clockwise from true north, [0,360) -- the same convention `GeoTelemetry.heading_deg` and a
    visual-tracking bearing already carry on the wire, so `cv_service/grpc/servicers.py
    #LocalizeStream` can feed either straight through."""
    theta = math.radians(bearing_degrees)
    return distance_meters * math.sin(theta), distance_meters * math.cos(theta)


@dataclass(frozen=True)
class SequenceParams:
    """Everything the filter is allowed to know. Distances in meters. Field-for-field the
    spike's `PfParams` (frozen here, matching this codebase's dataclass idiom), plus §4.4 Change
    2's two knobs."""

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
    # §4.4 Change 2 -- G-b evidence diversity + baseline.
    min_supporting_frames: int = DEFAULT_MIN_SUPPORTING_FRAMES
    min_baseline_m: float = DEFAULT_MIN_BASELINE_M

    def __post_init__(self) -> None:
        if self.n_particles < 1:
            raise ValueError(f"n_particles must be >= 1, got {self.n_particles}")
        if self.temperature <= 0:
            raise ValueError(f"temperature must be > 0, got {self.temperature}")
        if self.min_supporting_frames < 1:
            raise ValueError(f"min_supporting_frames must be >= 1, got {self.min_supporting_frames}")


@dataclass(frozen=True)
class SequenceEstimate:
    """One posterior snapshot. `lat`/`lon` are the posterior MEAN regardless of status --
    consumers must publish a position ONLY when `status == STATUS_CONVERGED` (the wire
    contract's "never a fabricated position while SEARCHING"), and even then `sequence_converged`
    on the wire additionally requires the servicer's own Change 2 policy over the fields below
    (this module reports the raw evidence; it does not decide `sequence_converged` itself, since
    `cell_calibrated` needs region calibration data this module is never given)."""

    status: str  # STATUS_SEARCHING | STATUS_CONVERGED (G-c: the spread/persistence rule alone)
    mean_uv: tuple[float, float]  # posterior mean, fractional global tile coords
    lat: float  # posterior mean, WGS-84
    lon: float
    spread_m: float  # sqrt(trace of posterior covariance), meters
    ess_frac: float  # effective sample size / N (before any resampling)
    top_cell_share: float  # posterior weight share of the heaviest tile cell
    resampled: bool
    update_count: int  # measurement updates since (re)init
    # §4.4 Change 2 evidence, scoped to whichever cell the posterior mean currently sits in --
    # `None` when the mean has drifted outside the region's tile grid, or onto a cell with no
    # descriptor row (a calibration-holdout cell): the servicer treats a `None` cell as "cannot
    # be CONVERGED", the same honest-abstention posture as everywhere else in this module.
    converged_cell_tile_id: Optional[str] = None
    supporting_frames: int = 0
    baseline_meters: float = 0.0
    cell_ever_single_frame_accepted: bool = False


class SequenceLocalizer:
    """Station-side sequence localizer over (geometric re-rank field, odometry delta) pairs.

    State: `n_particles` particles over continuous position inside the region, in fractional
    global Web-Mercator tile coordinates (meters via the region-center tile scale -- regions are
    ~1-2 km across, so a single scale is honest). Uninformed init: uniform over the region's tile
    bounding box.

    `predict(delta_e_m, delta_n_m)`: each particle applies the odometry delta rotated by
    N(0, heading_sigma) and scaled by N(1, speed_sigma), plus isotropic diffusion. A (0, 0) delta
    degrades to diffusion-only -- the honest "no odometry this frame" motion (what a hover, or an
    absent telemetry sample, produces).

    `update(similarities, ...)`: per-tile likelihood exp((sim - max)/T); a particle takes the
    likelihood of the tile cell containing it; cells without a descriptor row (the calibration
    holdout) are NEUTRAL (mean likelihood); outside the region entirely -> 0.1 * min likelihood
    (strong but non-annihilating). An `outlier_mix` fraction of mean likelihood is mixed in so one
    bad frame cannot kill the true mode. Weights multiply, normalize; systematic resampling on
    ESS < frac*N. `similarities` is §4.4 Change 1's field: the re-ranked geometric score
    (`inlier_ratio`, 0..1) at each scored candidate's row, `0.0` elsewhere -- NOT raw embedding
    similarity (the exact substitution §4.4 measured refuses the 771 m-wrong Pexels/Maidan cell
    the old raw-similarity field converged on)."""

    def __init__(
        self,
        tile_rows_xy: np.ndarray,
        grid_origin: tuple[int, int],
        grid_size: tuple[int, int],
        tile_size_m: float,
        zoom: int,
        params: SequenceParams,
        *,
        tile_ids: Optional[list[str]] = None,
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
        # row -> tile_id, for reporting `converged_cell_tile_id` -- `None` entries (row index has
        # no known id) degrade that field to `None` rather than fabricate a string.
        self.tile_ids = list(tile_ids) if tile_ids is not None else None
        n = params.n_particles
        self.pu = self.rng.uniform(self.x0, self.x0 + self.nx, size=n)
        self.pv = self.rng.uniform(self.y0, self.y0 + self.ny, size=n)
        self.w = np.full(n, 1.0 / n)
        self._streak = 0
        self._updates = 0
        # §4.4 Change 2: rolling window of the last `evidence_window_size` updates' own
        # (winner_tile_id, cleared_inlier_floor, single_frame_accepted,
        # motion_since_last_update_m) -- see module docstring. Sized independently from
        # `params.persistence` (G-c's own streak counter below is NOT window-bound) specifically
        # so it can hold enough history to observe `min_supporting_frames` distinct matches even
        # with the odd non-matching frame interleaved.
        window_size = max(1, EVIDENCE_WINDOW_MULTIPLIER * max(params.persistence, params.min_supporting_frames))
        self._evidence_window: "deque" = deque(maxlen=window_size)
        self._pending_motion_m = 0.0

    @classmethod
    def from_tile_ids(
        cls,
        tile_ids: list[str],
        *,
        n_particles: int = DEFAULT_PARTICLES,
        temperature: float = DEFAULT_TEMPERATURE,
        min_supporting_frames: int = DEFAULT_MIN_SUPPORTING_FRAMES,
        min_baseline_m: float = DEFAULT_MIN_BASELINE_M,
        seed: Optional[int] = None,
        rng: Optional[np.random.Generator] = None,
    ) -> "SequenceLocalizer":
        """Build a filter for a region straight from its indexed tile ids, IN ROW ORDER (the
        i-th tile id must correspond to row i of every similarity field later fed to `update`).
        Raises `ValueError` on an empty list, a malformed tile id, or mixed zooms -- callers
        treat that as "this region cannot host a sequence filter" (OFF), never a crash."""
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
            min_supporting_frames=min_supporting_frames,
            min_baseline_m=min_baseline_m,
        )
        tile_rows_xy = np.array([[x, y] for _, x, y in parsed], dtype=np.int64)
        return cls(
            tile_rows_xy,
            (x0, y0),
            (nx, ny),
            tile_m,
            zoom,
            params,
            tile_ids=list(tile_ids),
            seed=seed,
            rng=rng,
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
        # §4.4 Change 2: accumulate the INPUT displacement magnitude (the caller's own
        # odometry/visual-tracking/telemetry estimate, not the noisy per-particle realization
        # above) since the last measurement update -- `update()` captures + resets this into the
        # evidence window's `baseline_meters` accounting.
        self._pending_motion_m += math.hypot(delta_e_m, delta_n_m)

    def update(
        self,
        similarities: np.ndarray,
        *,
        winner_tile_id: Optional[str] = None,
        cleared_inlier_floor: bool = False,
        single_frame_accepted: bool = False,
    ) -> SequenceEstimate:
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

        # §4.4 Change 2: record THIS update's own (winner_tile_id, cleared_inlier_floor,
        # single_frame_accepted, motion_since_last_update_m), then derive the evidence fields
        # scoped to whichever cell the posterior mean (computed above, pre-resample) sits in.
        motion_since_last_update = self._pending_motion_m
        self._pending_motion_m = 0.0
        self._evidence_window.append(
            (winner_tile_id, bool(cleared_inlier_floor), bool(single_frame_accepted), motion_since_last_update)
        )
        converged_cell_tile_id = self._cell_tile_id(mean_uv)
        supporting_frames, baseline_meters, cell_ever_accepted = self._windowed_evidence(converged_cell_tile_id)

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
            converged_cell_tile_id=converged_cell_tile_id,
            supporting_frames=supporting_frames,
            baseline_meters=baseline_meters,
            cell_ever_single_frame_accepted=cell_ever_accepted,
        )

    def estimate(self) -> SequenceEstimate:
        """Read-only posterior peek for time-update-only frames (no similarity field this frame):
        never mutates the streak/update counters OR the evidence window (a pending
        `predict()`-only motion delta stays PENDING, only folded into the window by the next real
        `update()` -- an `estimate()` call reports the window exactly as it stood after the last
        real measurement). The convergence predicate is re-applied against the CURRENT spread, so
        a posterior that drifted wide during a measurement gap honestly reports SEARCHING again
        without fabricating a fresh measurement update."""
        mean_uv, spread_m = self._estimate_pos()
        converged = (
            self._streak >= self.params.persistence
            and self._updates >= self.params.min_updates
            and spread_m <= self.params.spread_fix_m
        )
        lat, lon = tile_to_latlon(mean_uv[0], mean_uv[1], self.zoom)
        converged_cell_tile_id = self._cell_tile_id(mean_uv)
        supporting_frames, baseline_meters, cell_ever_accepted = self._windowed_evidence(converged_cell_tile_id)
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
            converged_cell_tile_id=converged_cell_tile_id,
            supporting_frames=supporting_frames,
            baseline_meters=baseline_meters,
            cell_ever_single_frame_accepted=cell_ever_accepted,
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

    def _cell_tile_id(self, mean_uv: tuple[float, float]) -> Optional[str]:
        """Tile id of the grid cell containing `mean_uv`, or `None` when it has drifted outside
        the grid, sits on a no-descriptor (calibration-holdout) cell, or `tile_ids` was never
        supplied (a filter built without them, e.g. an old-style direct `__init__` caller, simply
        cannot report this -- degrade, never crash)."""
        if self.tile_ids is None:
            return None
        u, v = mean_uv
        cx = int(math.floor(u)) - self.x0
        cy = int(math.floor(v)) - self.y0
        if not (0 <= cx < self.nx and 0 <= cy < self.ny):
            return None
        row = int(self.row_grid[cy, cx])
        if row < 0 or row >= len(self.tile_ids):
            return None
        return self.tile_ids[row]

    def _windowed_evidence(self, converged_cell_tile_id: Optional[str]) -> tuple[int, float, bool]:
        """§4.4 Change 2's `(supporting_frames, baseline_meters, cell_ever_single_frame_accepted)`
        over `_evidence_window`, scoped to `converged_cell_tile_id`. `baseline_meters` is the real
        displacement SPANNED between the first and last matching entry (sum of
        `motion_since_last_update_m` strictly after the first match through the last) -- `0.0`
        with fewer than 2 matches, since one observation spans nothing by itself."""
        if converged_cell_tile_id is None or not self._evidence_window:
            return 0, 0.0, False
        entries = list(self._evidence_window)
        match_indices = [
            i for i, (tile_id, cleared, _accepted, _m) in enumerate(entries)
            if tile_id == converged_cell_tile_id and cleared
        ]
        cell_ever_accepted = any(
            tile_id == converged_cell_tile_id and accepted for tile_id, _cleared, accepted, _m in entries
        )
        supporting_frames = len(match_indices)
        if supporting_frames < 2:
            return supporting_frames, 0.0, cell_ever_accepted
        first_idx, last_idx = match_indices[0], match_indices[-1]
        baseline_meters = float(sum(entries[i][3] for i in range(first_idx + 1, last_idx + 1)))
        return supporting_frames, baseline_meters, cell_ever_accepted

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
