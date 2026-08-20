"""Sequence particle-filter spike (docs/VISUAL-GEO-PLAN.md §13.6, task #49): can a
STATION-SIDE particle filter over per-frame similarity fields + odometry deltas localize
over fields / plain districts where single-frame localization honestly fails (§12.12/§12.13)?

STATION-SIDE FRAMING (design constraint, stated): the filter is a server/cockpit-level
consumer. Its only inputs are (a) the per-frame cosine-similarity field over ALL region
tiles that cv-service ALREADY computes at retrieval time (`ReferenceIndex.search`'s
`descriptors @ query_descriptor` — here consumed in full instead of top-k), and (b) the
odometry deltas (VO / telemetry) that ALREADY flow to the station. The drone does NO new
computation; the filter adds pure-numpy post-processing on data in hand.

HONEST-ABSTENTION DISCIPLINE: the filter reports a fix ONLY when a convergence statistic
(posterior spread, with ESS-triggered resampling underneath) clears a threshold for several
consecutive updates; before that it reports SEARCHING. The hard safety gate measured here:
zero confident-wrong output (a CONVERGED report farther than one tile step from truth), and
zero convergence at all on the cross-region negative control.

Experimental protocol (prior spikes used INDEPENDENT held-out queries; this one synthesizes
TRAJECTORIES): reuses the already-built danger regions on disk (linear-terrain +
homogeneous-terrain results, built through the production orchestrator) plus the shipped
kyiv-maidan demo region as the easy sanity/tuning region. Ground-truth tracks cross each
7x7 region (3 straight headings + 1 lawnmower, step = half a tile); per step the query is a
256px crop of the region tile MOSAIC centered at the true position, perturbed by the SAME
production `simulate_view_perturbation` the prior spikes used for held-out queries (scale
+-15%, rotation +-15 deg, brightness +-30). §12.3 caveat carried forward honestly: queries
derive from the SAME Esri imagery as the map (no real drone camera exists for these fields),
so absolute similarity levels are optimistic — but the AMBIGUITY structure (which tiles look
alike) is real, and that is what the filter must survive. The filter never sees coordinates.

Measurement likelihood: softmax over the similarity field, temperature tuned on the sanity
region ONLY, then FROZEN for every danger region. Motion model: ground-truth delta corrupted
by odometry sensor noise (heading sigma 6 deg, speed sigma 12%), filter process noise wider
(heading sigma 8 deg, speed sigma 15%, 8 m diffusion) — both stated in constants below.

Negative controls (mandatory): (a) cross-region — queries+odometry from a DIFFERENT region
replayed against this region's map: must NEVER converge; (b) aliased linear terrain —
vas-road queries along the road itself: the posterior must stay multi-modal / SEARCHING
until something distinctive is crossed, visible in the spread trace.

Spike-only: writes exclusively under `spikes/geo/results/pf-spike-<UTC>/`; reads existing
region builds. Production `cv_service/` untouched. Pure numpy filter; matplotlib (already
in the venv) only for diagnostic PNGs.

Usage (from cv-service/, venv active):
  python -m spikes.geo.pf_spike run [--out spikes/geo/results/pf-spike-<ts>]
"""

from __future__ import annotations

import argparse
import json
import math
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

import cv2
import numpy as np

from spikes.geo.geomath import haversine_m, tile2deg

SPIKE_ROOT = Path(__file__).resolve().parent
CV_SERVICE_ROOT = SPIKE_ROOT.parent.parent

ZOOM = 17
TILE_PIXELS = 256
ENCODER_ID = "eigenplaces_r18_512"  # the shipped default encoder

# Existing region builds reused as-is (all built through the production orchestrator).
REGION_DIRS: dict[str, Path] = {
    "kyiv-maidan": CV_SERVICE_ROOT / "demo" / "data" / "kyiv-maidan",
    "btn-road-x": SPIKE_ROOT / "results" / "linear-terrain-20260808T081337Z" / "regions" / "btn-road-x",
    "vas-road": SPIKE_ROOT / "results" / "linear-terrain-20260808T081337Z" / "regions" / "vas-road",
    "boryspil-fields": SPIKE_ROOT / "results" / "homogeneous-terrain-20260808T002058Z" / "regions" / "boryspil-fields",
    "kyiv-nw-forest": SPIKE_ROOT / "results" / "homogeneous-terrain-20260808T002058Z" / "regions" / "kyiv-nw-forest",
}
SANITY_REGION = "kyiv-maidan"          # temperature tuned here ONLY, then frozen
DANGER_REGIONS = ["btn-road-x", "vas-road", "boryspil-fields", "kyiv-nw-forest"]

# vas-road's measured road chain (linear-terrain results.json), global tile coords — the
# aliased along-road negative-control trajectory follows these tile centers.
VAS_ROAD_CHAIN = [(76586, 44403), (76587, 44404), (76587, 44405), (76588, 44406), (76588, 44407)]

QUERY_SEED = 20260808   # same seed family as the prior spikes
ODO_SEED = 4901
PF_SEEDS = [11, 23, 47]  # filter is stochastic -> every run repeated over these seeds

STEP_TILES = 0.5                 # trajectory step = half a tile (~98 m at lat 50)
ODO_HEADING_SIGMA_DEG = 6.0      # odometry SENSOR noise fed to the filter (stated: 5-10 deg band)
ODO_SPEED_SIGMA = 0.12           # 10-20% band
TEMPERATURE_GRID = [0.02, 0.05, 0.10, 0.20]


# --------------------------------------------------------------------- the filter itself

@dataclass
class PfParams:
    """Everything the filter is allowed to know. Distances in meters."""
    n_particles: int = 4000
    temperature: float = 0.05        # softmax temperature over the similarity field (tuned on sanity region)
    heading_sigma_deg: float = 8.0   # per-particle process noise on the odometry delta direction
    speed_sigma: float = 0.15        # per-particle multiplicative noise on the delta magnitude
    diffusion_m: float = 8.0         # isotropic per-step position diffusion
    outlier_mix: float = 0.05        # likelihood = mix*mean + (1-mix)*tile_lik — measurement-outlier guard
    ess_resample_frac: float = 0.5   # systematic resampling when ESS < frac * N
    spread_fix_m: float = 0.0        # set from tile size at construction (0.4 * tile step)
    persistence: int = 3             # consecutive updates the spread must hold below threshold
    min_updates: int = 5             # never report a fix before this many measurement updates


@dataclass
class PfEstimate:
    status: str                      # "SEARCHING" | "CONVERGED"
    mean_uv: tuple[float, float]     # posterior mean, fractional global tile coords
    spread_m: float                  # sqrt(trace of posterior covariance), meters
    ess_frac: float                  # effective sample size / N (before any resampling)
    top_cell_share: float            # posterior weight share of the heaviest tile cell (multi-modality proxy)
    resampled: bool


class SequenceLocalizer:
    """Station-side sequence localizer over (similarity field, odometry delta) pairs.

    State: `n_particles` particles over continuous position inside the region, in fractional
    Web-Mercator tile coordinates (meters via the region-center tile scale — the region is
    ~1.4 km across, so a single scale is honest). Uninformed init: uniform over the region.

    predict(delta_e_m, delta_n_m): each particle applies the odometry delta rotated by
    N(0, heading_sigma) and scaled by N(1, speed_sigma), plus isotropic diffusion.

    update(similarities): per-tile likelihood exp((sim - max)/T); a particle takes the
    likelihood of the tile cell containing it; cells without a descriptor (the calibration
    holdout — real production regions have those too) are NEUTRAL (mean likelihood); outside
    the region entirely -> 0.1 * min likelihood (strong but non-annihilating). An
    `outlier_mix` fraction of mean likelihood is mixed in so one bad frame cannot kill the
    true mode. Weights multiply, normalize; systematic resampling on ESS < frac*N.

    HONEST ABSTENTION: `estimate()` reports CONVERGED only after the posterior spread
    (sqrt of covariance trace) has stayed <= spread_fix_m for `persistence` consecutive
    updates AND >= min_updates updates have happened; otherwise SEARCHING. Spread
    re-inflating resets the streak (the filter can honestly UN-converge).
    """

    def __init__(self, tile_rows_xy: np.ndarray, grid_origin: tuple[int, int],
                 grid_size: tuple[int, int], tile_size_m: float,
                 params: PfParams, seed: int) -> None:
        self.params = params
        self.tile_m = tile_size_m
        self.x0, self.y0 = grid_origin
        self.nx, self.ny = grid_size
        self.rng = np.random.default_rng(seed)
        # cell -> descriptor row lookup grid (-1 = no descriptor: holdout cell)
        self.row_grid = np.full((self.ny, self.nx), -1, dtype=np.int64)
        for row, (tx, ty) in enumerate(tile_rows_xy):
            self.row_grid[int(ty) - self.y0, int(tx) - self.x0] = row
        n = params.n_particles
        self.pu = self.rng.uniform(self.x0, self.x0 + self.nx, size=n)
        self.pv = self.rng.uniform(self.y0, self.y0 + self.ny, size=n)
        self.w = np.full(n, 1.0 / n)
        self._streak = 0
        self._updates = 0

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

    def update(self, similarities: np.ndarray) -> PfEstimate:
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
        ess = 1.0 / float(np.sum(self.w ** 2))
        ess_frac = ess / self.w.size
        est = self._estimate_pos()
        self._updates += 1
        self._streak = self._streak + 1 if est[1] <= p.spread_fix_m else 0
        converged = self._streak >= p.persistence and self._updates >= p.min_updates
        top_share = self._top_cell_share()
        resampled = False
        if ess_frac < p.ess_resample_frac:
            self._systematic_resample()
            resampled = True
        return PfEstimate(
            status="CONVERGED" if converged else "SEARCHING",
            mean_uv=est[0], spread_m=est[1], ess_frac=ess_frac,
            top_cell_share=top_share, resampled=resampled,
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
        return self.pu, self.pv, self.w


# ------------------------------------------------------------------ region + trajectory prep

@dataclass
class RegionData:
    region_id: str
    x0: int
    y0: int
    nx: int
    ny: int
    tile_m: float
    descriptors: np.ndarray        # (M, D) float32, L2-normalized (production index)
    tile_rows_xy: np.ndarray       # (M, 2) global tile x,y per descriptor row
    mosaic: np.ndarray             # (ny*256, nx*256, 3) BGR


def load_region(region_id: str) -> RegionData:
    region_dir = REGION_DIRS[region_id]
    descriptors = np.load(region_dir / "descriptors.npy").astype(np.float32)
    tiles = json.loads((region_dir / "tiles.json").read_text())
    rows_xy = np.array([[int(t["tileId"].split("/")[1]), int(t["tileId"].split("/")[2])] for t in tiles])
    tile_files = sorted((region_dir / "tiles").glob("*.jpg"))
    coords = []
    for p in tile_files:
        z, x, y = (int(v) for v in p.stem.split("_"))
        coords.append((x, y, p))
    xs = sorted({c[0] for c in coords})
    ys = sorted({c[1] for c in coords})
    x0, y0, nx, ny = xs[0], ys[0], len(xs), len(ys)
    mosaic = np.zeros((ny * TILE_PIXELS, nx * TILE_PIXELS, 3), dtype=np.uint8)
    for x, y, p in coords:
        img = cv2.imread(str(p), cv2.IMREAD_COLOR)
        assert img is not None and img.shape[:2] == (TILE_PIXELS, TILE_PIXELS), p
        mosaic[(y - y0) * TILE_PIXELS:(y - y0 + 1) * TILE_PIXELS,
               (x - x0) * TILE_PIXELS:(x - x0 + 1) * TILE_PIXELS] = img
    center_lat = tile2deg(x0 + nx / 2, y0 + ny / 2, ZOOM)[0]
    tile_m = 40_075_016.686 * math.cos(math.radians(center_lat)) / (2 ** ZOOM)
    return RegionData(region_id, x0, y0, nx, ny, tile_m, descriptors, rows_xy, mosaic)


def resample_polyline(points_uv: list[tuple[float, float]], step: float) -> list[tuple[float, float]]:
    """Equal-`step` points along the polyline (tile units), endpoints included."""
    pts = [np.array(p, dtype=np.float64) for p in points_uv]
    seg_len = [float(np.linalg.norm(pts[i + 1] - pts[i])) for i in range(len(pts) - 1)]
    total = sum(seg_len)
    n_steps = max(1, int(round(total / step)))
    out = []
    for k in range(n_steps + 1):
        d = total * k / n_steps
        acc = 0.0
        for i, sl in enumerate(seg_len):
            if d <= acc + sl or i == len(seg_len) - 1:
                t = 0.0 if sl == 0 else (d - acc) / sl
                p = pts[i] + t * (pts[i + 1] - pts[i])
                out.append((float(p[0]), float(p[1])))
                break
            acc += sl
    return out


def trajectories_for(region: RegionData) -> dict[str, list[tuple[float, float]]]:
    """Ground-truth tracks in GLOBAL fractional tile coords, inset >=1 tile from the region
    edge (so the 256px query crop always stays inside the mosaic)."""
    x0, y0 = region.x0, region.y0
    rel = {
        "east": [(1.0, 2.5), (6.0, 2.5)],
        "south": [(4.5, 1.0), (4.5, 6.0)],
        "diag": [(1.0, 1.0), (6.0, 6.0)],
        "lawnmower": [(1.0, 1.0), (6.0, 1.0), (6.0, 2.75), (1.0, 2.75), (1.0, 4.5), (6.0, 4.5)],
    }
    return {name: resample_polyline([(x0 + u, y0 + v) for u, v in pts], STEP_TILES)
            for name, pts in rel.items()}


def crop_query(region: RegionData, u: float, v: float) -> np.ndarray:
    """256px window of the region mosaic centered at global fractional tile (u, v)."""
    px = (u - region.x0) * TILE_PIXELS
    py = (v - region.y0) * TILE_PIXELS
    x1 = int(round(px - TILE_PIXELS / 2))
    y1 = int(round(py - TILE_PIXELS / 2))
    x1 = max(0, min(x1, region.mosaic.shape[1] - TILE_PIXELS))
    y1 = max(0, min(y1, region.mosaic.shape[0] - TILE_PIXELS))
    return region.mosaic[y1:y1 + TILE_PIXELS, x1:x1 + TILE_PIXELS].copy()


@dataclass
class SequenceStep:
    uv: tuple[float, float]            # ground truth (never shown to the filter)
    sims: np.ndarray                   # FULL similarity field over the map region's tiles
    odo_e_m: float                     # noisy odometry delta since previous step (0 at step 0)
    odo_n_m: float
    field_ms: float                    # encode + full-field similarity wall time


def build_sequence(query_region: RegionData, map_region: RegionData,
                   points_uv: list[tuple[float, float]], encoder,
                   rng_q: np.random.Generator, rng_o: np.random.Generator) -> list[SequenceStep]:
    """Queries + GT + noisy odometry from `query_region`; similarity fields against
    `map_region`'s index (identical regions in normal runs; different ones in the
    cross-region negative control)."""
    from cv_service.geo.calibrate import simulate_view_perturbation

    steps: list[SequenceStep] = []
    prev: Optional[tuple[float, float]] = None
    for (u, v) in points_uv:
        query = simulate_view_perturbation(crop_query(query_region, u, v), rng_q)
        t0 = time.perf_counter()
        desc = encoder.encode(query)
        sims = map_region.descriptors @ desc.astype(np.float32)
        field_ms = (time.perf_counter() - t0) * 1000.0
        odo_e = odo_n = 0.0
        if prev is not None:
            de = (u - prev[0]) * query_region.tile_m
            dn = -(v - prev[1]) * query_region.tile_m
            theta = math.radians(rng_o.normal(0.0, ODO_HEADING_SIGMA_DEG))
            scale = rng_o.normal(1.0, ODO_SPEED_SIGMA)
            odo_e = (de * math.cos(theta) - dn * math.sin(theta)) * scale
            odo_n = (de * math.sin(theta) + dn * math.cos(theta)) * scale
        steps.append(SequenceStep((u, v), sims, odo_e, odo_n, field_ms))
        prev = (u, v)
    return steps


# --------------------------------------------------------------------------- PF replay

def single_frame_stats(map_region: RegionData, seq: list[SequenceStep]) -> dict:
    """The honesty baseline: what would SINGLE-FRAME argmax retrieval say on the exact same
    queries? (No gates, best case for single frames.) If this were already near-perfect, the
    sequence filter would prove nothing; §13.6 needs it mediocre while the PF stays clean."""
    correct_tile = correct_100 = 0
    margins = []
    for s in seq:
        order = np.argsort(-s.sims)
        top_xy = map_region.tile_rows_xy[order[0]]
        tlat, tlon = tile2deg(top_xy[0] + 0.5, top_xy[1] + 0.5, ZOOM)
        glat, glon = uv_to_latlon(*s.uv)
        err = haversine_m(glat, glon, tlat, tlon)
        if err <= map_region.tile_m * 1.05:  # within one tile step (their own quantization)
            correct_tile += 1
        if err <= 100.0:
            correct_100 += 1
        margins.append(float(s.sims[order[0]] - s.sims[order[1]]))
    n = len(seq)
    return {
        "top1_within_tile_step": round(correct_tile / n, 3),
        "top1_within_100m": round(correct_100 / n, 3),
        "margin_median": round(float(np.median(margins)), 4),
    }

def uv_to_latlon(u: float, v: float) -> tuple[float, float]:
    return tile2deg(u, v, ZOOM)


def run_pf(map_region: RegionData, seq: list[SequenceStep], params: PfParams, seed: int,
           gt_in_map_frame: bool, snapshot_steps: Optional[list[int]] = None) -> dict:
    """Replay one cached sequence through a fresh filter. `gt_in_map_frame=False` marks the
    cross-region control, where GT coords are meaningless against this map: any CONVERGED
    step counts as a false convergence there."""
    loc = SequenceLocalizer(map_region.tile_rows_xy, (map_region.x0, map_region.y0),
                            (map_region.nx, map_region.ny), map_region.tile_m, params, seed)
    trace = []
    snapshots = {}
    t0 = time.perf_counter()
    for i, s in enumerate(seq):
        if i > 0:
            loc.predict(s.odo_e_m, s.odo_n_m)
        est = loc.update(s.sims)
        err_m = None
        if gt_in_map_frame:
            glat, glon = uv_to_latlon(*s.uv)
            elat, elon = uv_to_latlon(*est.mean_uv)
            err_m = haversine_m(glat, glon, elat, elon)
        trace.append({
            "step": i, "status": est.status, "spread_m": round(est.spread_m, 1),
            "ess_frac": round(est.ess_frac, 3), "top_cell_share": round(est.top_cell_share, 3),
            "err_m": None if err_m is None else round(err_m, 1),
        })
        if snapshot_steps and i in snapshot_steps:
            pu, pv, w = loc.particles()
            snapshots[i] = (pu.copy(), pv.copy(), w.copy(), est.mean_uv, s.uv)
    pf_ms = (time.perf_counter() - t0) * 1000.0
    conv = [t for t in trace if t["status"] == "CONVERGED"]
    tile_m = map_region.tile_m
    if gt_in_map_frame:
        wrong = [t for t in conv if t["err_m"] is not None and t["err_m"] > tile_m]
    else:
        wrong = conv  # cross-region: ANY convergence is a false convergence
    conv_errs = [t["err_m"] for t in conv if t["err_m"] is not None]
    return {
        "seed": seed,
        "steps": len(trace),
        "first_converged_step": conv[0]["step"] if conv else None,
        "converged_steps": len(conv),
        "confident_wrong": len(wrong),
        "err_at_first_convergence_m": conv[0]["err_m"] if conv else None,
        "median_err_converged_m": round(float(np.median(conv_errs)), 1) if conv_errs else None,
        "max_err_converged_m": round(float(np.max(conv_errs)), 1) if conv_errs else None,
        "final_status": trace[-1]["status"],
        "final_err_m": trace[-1]["err_m"],
        "final_spread_m": trace[-1]["spread_m"],
        "pf_ms_total": round(pf_ms, 1),
        "trace": trace,
        "_snapshots": snapshots,
    }


# ------------------------------------------------------------------------------ plotting

INK = "#334155"          # muted slate ink for text/axes (dataviz: recessive axes, text tokens)
ACCENT = "#2563eb"       # single-series blue
NEUTRAL = "#94a3b8"


def save_spread_trace(path: Path, title: str, runs: list[dict], spread_fix_m: float) -> None:
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except Exception:
        return
    fig, ax = plt.subplots(figsize=(7, 3.2), dpi=110)
    for j, r in enumerate(runs):
        xs = [t["step"] for t in r["trace"]]
        ys = [t["spread_m"] for t in r["trace"]]
        ax.plot(xs, ys, lw=2 if j == 0 else 1.2, color=ACCENT, alpha=1.0 if j == 0 else 0.35)
    ax.axhline(spread_fix_m, color=NEUTRAL, lw=1.2, ls="--")
    ax.text(0.2, spread_fix_m * 1.06, f"fix threshold {spread_fix_m:.0f} m",
            color=INK, fontsize=8, va="bottom")
    ax.set_title(title, color=INK, fontsize=10, loc="left")
    ax.set_xlabel("step", color=INK, fontsize=9)
    ax.set_ylabel("posterior spread (m)", color=INK, fontsize=9)
    ax.tick_params(colors=INK, labelsize=8)
    for s in ax.spines.values():
        s.set_color("#cbd5e1")
    ax.grid(True, color="#e2e8f0", lw=0.6)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def save_posterior_heatmaps(path: Path, title: str, region: RegionData,
                            snapshots: dict, res: int = 4) -> None:
    """Posterior weight histograms (res cells per tile) at a few steps; GT x, estimate o."""
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except Exception:
        return
    steps = sorted(snapshots)
    if not steps:
        return
    fig, axes = plt.subplots(1, len(steps), figsize=(3.1 * len(steps), 3.4), dpi=110)
    if len(steps) == 1:
        axes = [axes]
    for ax, step in zip(axes, steps):
        pu, pv, w, mean_uv, gt_uv = snapshots[step]
        gx = np.clip(((pu - region.x0) * res).astype(int), 0, region.nx * res - 1)
        gy = np.clip(((pv - region.y0) * res).astype(int), 0, region.ny * res - 1)
        h = np.zeros((region.ny * res, region.nx * res))
        np.add.at(h, (gy, gx), w)
        ax.imshow(h, cmap="Blues", origin="upper",
                  extent=(0, region.nx, region.ny, 0), aspect="equal")
        ax.plot(gt_uv[0] - region.x0, gt_uv[1] - region.y0, "x", color="#dc2626", ms=8, mew=2)
        ax.plot(mean_uv[0] - region.x0, mean_uv[1] - region.y0, "o", mfc="none",
                mec="#16a34a", ms=9, mew=2)
        ax.set_title(f"step {step}", color=INK, fontsize=9)
        ax.tick_params(colors=INK, labelsize=7)
        for s in ax.spines.values():
            s.set_color("#cbd5e1")
    fig.suptitle(f"{title}  (x = ground truth, o = posterior mean)", color=INK, fontsize=10, x=0.01, ha="left")
    fig.tight_layout(rect=(0, 0, 1, 0.93))
    fig.savefig(path)
    plt.close(fig)


# ---------------------------------------------------------------------------- experiment

def aggregate(runs: list[dict]) -> dict:
    """Across-PF-seed aggregate for one (region, trajectory, params) cell."""
    conv_runs = [r for r in runs if r["first_converged_step"] is not None]
    return {
        "seeds": len(runs),
        "runs_converged": len(conv_runs),
        "confident_wrong_total": sum(r["confident_wrong"] for r in runs),
        "median_first_converged_step": (float(np.median([r["first_converged_step"] for r in conv_runs]))
                                        if conv_runs else None),
        "median_err_at_convergence_m": (lambda errs: round(float(np.median(errs)), 1) if errs else None)(
            [r["err_at_first_convergence_m"] for r in conv_runs
             if r["err_at_first_convergence_m"] is not None]),
        "median_final_err_m": (round(float(np.median(
            [r["final_err_m"] for r in runs if r["final_err_m"] is not None])), 1)
            if any(r["final_err_m"] is not None for r in runs) else None),
        "worst_max_err_converged_m": (max((r["max_err_converged_m"] or 0.0) for r in conv_runs)
                                      if conv_runs else None),
    }


def cmd_run(out_dir: Path) -> None:
    from cv_service.geo.encoder import build_encoder

    out_dir.mkdir(parents=True, exist_ok=True)
    wall_start = time.perf_counter()
    print(f"loading encoder {ENCODER_ID!r} (cached weights) ...")
    encoder = build_encoder(ENCODER_ID)

    regions = {rid: load_region(rid) for rid in REGION_DIRS}
    for rid, r in regions.items():
        print(f"[{rid}] grid {r.nx}x{r.ny} at ({r.x0},{r.y0}), tile {r.tile_m:.1f} m, "
              f"{r.descriptors.shape[0]} indexed tiles")

    # ---- build all similarity-field sequences ONCE (fields don't depend on PF params)
    rng_q = np.random.default_rng(QUERY_SEED)
    rng_o = np.random.default_rng(ODO_SEED)
    sequences: dict[tuple[str, str], list[SequenceStep]] = {}
    for rid, region in regions.items():
        for tname, pts in trajectories_for(region).items():
            sequences[(rid, tname)] = build_sequence(region, region, pts, encoder, rng_q, rng_o)
            n = len(sequences[(rid, tname)])
            print(f"  [{rid}/{tname}] {n} steps encoded")
    # (b) aliased along-road trajectory on vas-road (road chain tile centers)
    vas = regions["vas-road"]
    road_pts = resample_polyline([(x + 0.5, y + 0.5) for x, y in VAS_ROAD_CHAIN], STEP_TILES)
    sequences[("vas-road", "along-road")] = build_sequence(vas, vas, road_pts, encoder, rng_q, rng_o)
    print(f"  [vas-road/along-road] {len(sequences[('vas-road', 'along-road')])} steps encoded")
    # (a) cross-region controls: queries+odometry from another region vs this map
    controls = [("boryspil-fields", "east", "vas-road"),
                ("kyiv-maidan", "east", "boryspil-fields")]
    for src_rid, tname, dst_rid in controls:
        pts = trajectories_for(regions[src_rid])[tname]
        sequences[(f"XR:{src_rid}->{dst_rid}", tname)] = build_sequence(
            regions[src_rid], regions[dst_rid], pts, encoder, rng_q, rng_o)
        print(f"  [XR {src_rid}->{dst_rid}/{tname}] encoded")

    field_ms = [s.field_ms for seq in sequences.values() for s in seq]
    encode_stats = {"frames": len(field_ms), "field_ms_median": round(float(np.median(field_ms)), 1),
                    "field_ms_p90": round(float(np.percentile(field_ms, 90)), 1)}
    print(f"similarity fields: {encode_stats}")

    def params_for(region: RegionData, temperature: float) -> PfParams:
        return PfParams(temperature=temperature, spread_fix_m=0.4 * region.tile_m)

    # ---- 1) temperature tuning on the sanity region ONLY
    sanity = regions[SANITY_REGION]
    tuning: dict[str, dict] = {}
    for temp in TEMPERATURE_GRID:
        cells = {}
        for tname in ("east", "south", "diag", "lawnmower"):
            runs = [run_pf(sanity, sequences[(SANITY_REGION, tname)], params_for(sanity, temp), seed, True)
                    for seed in PF_SEEDS]
            cells[tname] = aggregate(runs)
        total_wrong = sum(c["confident_wrong_total"] for c in cells.values())
        total_conv = sum(c["runs_converged"] for c in cells.values())
        steps_list = [c["median_first_converged_step"] for c in cells.values()
                      if c["median_first_converged_step"] is not None]
        tuning[str(temp)] = {
            "cells": cells, "confident_wrong_total": total_wrong,
            "runs_converged_total": total_conv,
            "mean_median_first_converged_step": (round(float(np.mean(steps_list)), 1)
                                                 if steps_list else None),
        }
        print(f"  [tune T={temp}] converged {total_conv}/12 runs, wrong {total_wrong}, "
              f"steps {tuning[str(temp)]['mean_median_first_converged_step']}")
    # pick: zero confident-wrong first, then most runs converged, then fastest
    def tune_key(item):
        t, v = item
        return (v["confident_wrong_total"] > 0, -v["runs_converged_total"],
                v["mean_median_first_converged_step"] if v["mean_median_first_converged_step"] is not None else 99.0)
    chosen_temp = float(min(tuning.items(), key=tune_key)[0])
    print(f"FROZEN temperature = {chosen_temp}")

    # ---- 2) main runs: sanity + danger regions, frozen temperature
    results: dict = {}
    heat_targets = {("kyiv-maidan", "east"), ("boryspil-fields", "east"),
                    ("vas-road", "along-road"), ("btn-road-x", "diag")}
    for rid in [SANITY_REGION] + DANGER_REGIONS:
        region = regions[rid]
        params = params_for(region, chosen_temp)
        region_out = {"tile_m": round(region.tile_m, 1), "spread_fix_m": round(params.spread_fix_m, 1),
                      "trajectories": {}}
        tnames = ["east", "south", "diag", "lawnmower"] + (["along-road"] if rid == "vas-road" else [])
        for tname in tnames:
            seq = sequences[(rid, tname)]
            snap_steps = [1, len(seq) // 2, len(seq) - 1] if (rid, tname) in heat_targets else None
            runs = [run_pf(region, seq, params, seed, True, snapshot_steps=snap_steps if seed == PF_SEEDS[0] else None)
                    for seed in PF_SEEDS]
            agg = aggregate(runs)
            sf = single_frame_stats(region, seq)
            region_out["trajectories"][tname] = {"aggregate": agg, "single_frame": sf,
                                                 "runs": [{k: v for k, v in r.items() if k != "_snapshots"}
                                                          for r in runs]}
            print(f"  [{rid}/{tname}] conv {agg['runs_converged']}/{agg['seeds']} "
                  f"step~{agg['median_first_converged_step']} err~{agg['median_err_at_convergence_m']}m "
                  f"wrong={agg['confident_wrong_total']}")
            if snap_steps:
                save_posterior_heatmaps(out_dir / f"posterior-{rid}-{tname}.png",
                                        f"{rid} / {tname} — posterior", region, runs[0]["_snapshots"])
            if tname == "along-road" or (rid, tname) in heat_targets:
                save_spread_trace(out_dir / f"spread-{rid}-{tname}.png",
                                  f"{rid} / {tname} — posterior spread (3 PF seeds)",
                                  runs, params.spread_fix_m)
        results[rid] = region_out

    # ---- 3) negative control (a): cross-region replays — must NEVER converge
    control_out = {}
    for src_rid, tname, dst_rid in controls:
        dst = regions[dst_rid]
        params = params_for(dst, chosen_temp)
        seq = sequences[(f"XR:{src_rid}->{dst_rid}", tname)]
        runs = [run_pf(dst, seq, params, seed, False,
                       snapshot_steps=[len(seq) - 1] if seed == PF_SEEDS[0] else None)
                for seed in PF_SEEDS]
        agg = aggregate(runs)
        key = f"{src_rid}->{dst_rid}"
        control_out[key] = {"aggregate": agg,
                            "runs": [{k: v for k, v in r.items() if k != "_snapshots"} for r in runs]}
        print(f"  [control {key}] converged runs {agg['runs_converged']} (must be 0), "
              f"false-convergence steps {agg['confident_wrong_total']}")
        save_spread_trace(out_dir / f"spread-control-{src_rid}-to-{dst_rid}.png",
                          f"NEGATIVE CONTROL {src_rid} queries vs {dst_rid} map — spread",
                          runs, params.spread_fix_m)

    wall_s = time.perf_counter() - wall_start
    payload = {
        "spike": "pf_spike (§13.6 sequence particle filter)",
        "generated_utc": datetime.now(timezone.utc).isoformat(),
        "encoder": ENCODER_ID,
        "protocol": {
            "step_tiles": STEP_TILES,
            "query_perturbation": "cv_service.geo.calibrate.simulate_view_perturbation "
                                  "(scale ±15%, rot ±15°, brightness ±30) on 256px mosaic crops",
            "odometry_sensor_noise": {"heading_sigma_deg": ODO_HEADING_SIGMA_DEG,
                                      "speed_sigma": ODO_SPEED_SIGMA},
            "pf_params": {k: v for k, v in vars(PfParams(temperature=chosen_temp)).items()},
            "pf_seeds": PF_SEEDS,
            "confident_wrong_definition": "CONVERGED report with error > 1 tile step "
                                          "(cross-region control: ANY CONVERGED step)",
            "same_source_caveat": "queries derive from the same Esri imagery as the map "
                                  "(§12.3): similarity LEVELS optimistic, ambiguity structure real",
        },
        "field_compute": encode_stats,
        "temperature_tuning_sanity_region_only": tuning,
        "frozen_temperature": chosen_temp,
        "regions": results,
        "cross_region_controls": control_out,
        "wall_clock_seconds": round(wall_s, 1),
    }
    (out_dir / "pf_results.json").write_text(json.dumps(payload, indent=2))
    write_report(out_dir, payload)
    print(f"done in {wall_s:.0f}s -> {out_dir}")


def write_report(out_dir: Path, p: dict) -> None:
    lines = [
        "# §13.6 sequence particle-filter spike — results",
        "",
        f"Generated {p['generated_utc']} · encoder `{p['encoder']}` · wall clock {p['wall_clock_seconds']}s",
        "",
        "Station-side PF over the FULL per-frame similarity field (what production retrieval",
        "already computes) + noisy odometry deltas. Honest abstention: SEARCHING until posterior",
        f"spread ≤ 0.4·tile for {p['protocol']['pf_params']['persistence']} consecutive updates "
        f"(min {p['protocol']['pf_params']['min_updates']} updates).",
        f"Frozen temperature **{p['frozen_temperature']}** (tuned on {SANITY_REGION} only).",
        f"Odometry sensor noise: heading σ {p['protocol']['odometry_sensor_noise']['heading_sigma_deg']}°, "
        f"speed σ {p['protocol']['odometry_sensor_noise']['speed_sigma']*100:.0f}%. "
        f"PF process noise: heading σ {p['protocol']['pf_params']['heading_sigma_deg']}°, "
        f"speed σ {p['protocol']['pf_params']['speed_sigma']*100:.0f}%, "
        f"diffusion {p['protocol']['pf_params']['diffusion_m']} m. "
        f"{p['protocol']['pf_params']['n_particles']} particles, uninformed init.",
        "",
        f"Similarity-field cost: median {p['field_compute']['field_ms_median']} ms/frame "
        f"({p['field_compute']['frames']} frames).",
        "",
        "## Convergence per region (3 PF seeds per trajectory; step = half a tile ≈ 98 m)",
        "",
        "| region | trajectory | 1-frame top-1 ≤tile | converged runs | steps to conv (med) | "
        "err@conv (med) | final err (med) | max err while conv | confident-wrong |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for rid, rv in p["regions"].items():
        for tname, tv in rv["trajectories"].items():
            a = tv["aggregate"]
            sf = tv["single_frame"]
            lines.append(
                f"| {rid} | {tname} | {sf['top1_within_tile_step']*100:.0f}% "
                f"(margin {sf['margin_median']:.3f}) | {a['runs_converged']}/{a['seeds']} | "
                f"{a['median_first_converged_step']} | {a['median_err_at_convergence_m']} m | "
                f"{a['median_final_err_m']} m | {a['worst_max_err_converged_m']} m | "
                f"**{a['confident_wrong_total']}** |")
    lines += [
        "",
        "## Negative controls",
        "",
        "| control | converged runs (must be 0) | false-convergence steps |",
        "|---|---|---|",
    ]
    for key, cv_ in p["cross_region_controls"].items():
        a = cv_["aggregate"]
        lines.append(f"| cross-region {key} | {a['runs_converged']}/{a['seeds']} | {a['confident_wrong_total']} |")
    lines += [
        "",
        "Aliased along-road control: see `vas-road / along-road` row above and "
        "`spread-vas-road-along-road.png` (posterior must stay wide until something distinctive).",
        "",
        "## Temperature sweep (sanity region only, then frozen)",
        "",
        "| T | runs converged /12 | confident-wrong | mean median steps-to-conv |",
        "|---|---|---|---|",
    ]
    for t, tv in p["temperature_tuning_sanity_region_only"].items():
        lines.append(f"| {t} | {tv['runs_converged_total']} | {tv['confident_wrong_total']} | "
                     f"{tv['mean_median_first_converged_step']} |")
    lines += [
        "",
        f"Same-source caveat: {p['protocol']['same_source_caveat']}.",
        "",
        "Raw per-step traces: `pf_results.json`. Posterior heatmaps + spread traces: `*.png`.",
    ]
    (out_dir / "report.md").write_text("\n".join(lines) + "\n")


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_run = sub.add_parser("run")
    p_run.add_argument("--out", default=None)
    args = parser.parse_args(argv)
    if args.out:
        out_dir = Path(args.out)
    else:
        ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        out_dir = SPIKE_ROOT / "results" / f"pf-spike-{ts}"
    cmd_run(out_dir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
