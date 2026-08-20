"""cv-service/spikes/geo/calibrate_instrument.py

H0b deliverable (1): instrument calibration via self-match (VISUAL-GEO-V2-PLAN.md §9.8, H0b).

H0's own §9.3 flagged two smells that call the harness itself into question before trusting its
domain-gap conclusions: a synthetic NADIR render of the SAME indexed Esri imagery put the true
tile at retrieval rank 7/45 and only 8-14 MAGSAC inliers out of 200+ raw xfeat matches (~4-5%
inlier ratio) -- self-matching the same pixels should be near-perfect for any matcher. This script
answers the narrower question first: independent of any real domain gap, does retrieval+re-rank
recognise a query built FROM the same indexed pixels? If it doesn't, that is a harness defect, not
a finding about drone footage.

Five synthetic self-match query kinds per randomly-sampled INDEXED tile of `kyiv-pozniaky`
(seed fixed -- reproducible tile sample):

  (i)   exact tile crop, native 256px, unconditioned.
  (ii)  the same crop upsampled 2x (512px), unconditioned -- matchers resize back down to
        LOFTR_RESIZE=480px internally anyway (matchers.py#_resize_longest_side /
        verify.py#prep_gray_tensor), so this mainly checks the resize path is a no-op, not that
        upsampling helps.
  (iii) the tile rotated 30deg -- built with sitl_render.py's OWN `-heading` rotation convention
        (render_nadir's "up = direction of travel"), so condition_query's `+heading_degrees`
        de-rotation is its exact algebraic inverse. Altitude is chosen so the conditioned scale
        works out to 1.0x (same GSD as the reference tile) -- a pure rotation test, isolated from
        any GSD-rescale interaction.
  (iv)  a 512px crop from a 3x3 tile mosaic centred on the tile, offset by half a tile in x --
        tests whether a query footprint that spans more than one z17 tile still retrieves/re-ranks
        against its OWN center tile (see §9.8 task 2's footprint-vs-tile-width finding: at
        84deg FOV / 60-120m AGL a real query's footprint can exceed one z17 tile's ~195m width).
        Unconditioned: the mosaic crop is pixel-identical GSD to the reference tiles (no camera
        altitude was simulated), so conditioning would be a no-op.
  (v)   a simulated oblique (pitch 45deg) homography warp of the mosaic, sitl_render.py's OWN
        `render_oblique` math, heading=0 (north) so the forward/right decomposition stays trivial
        to reason about, altitude=60m (a plausible drone AGL, not a rescale no-op). Conditioned
        with rerank.py's condition_query -- which is NADIR-ONLY (pitch_degrees defaults to 0.0
        regardless of the actual 45deg render pitch, see rerank.py's own call site). This
        deliberately reproduces the harness's own known gap named in task 4: `harvested/rectify.py`
        (a full-perspective IPM module) exists but is never wired into rerank.py's conditioning
        path -- so case (v) is expected to behave like an UNMITIGATED oblique query even though
        the pixels are self-sourced.

For each query x each of the three non-broken bake-off matchers (xfeat, lightglue_disk, loftr;
lightglue_aliked is broken in this kornia install and eloftr is not runnable here, both per H0's
own MEASUREMENTS.md): a FULL similarity search (not truncated to k, so the true tile's retrieval
rank is exact even past k) gives the pre-rerank rank, then the top-10 candidates run through H0's
own `rerank.rerank()` path, recording inlier count/ratio/RMS/gate-pass/top-1.

Usage (foreground, resumable -- appends to results/h0b/calibration.json, `--force` to redo):
    source spikes/geo/env.sh
    .venv/bin/python -m spikes.geo.calibrate_instrument --matcher xfeat
    .venv/bin/python -m spikes.geo.calibrate_instrument --matcher lightglue_disk
    .venv/bin/python -m spikes.geo.calibrate_instrument --matcher loftr
"""
from __future__ import annotations

import argparse
import json
import logging
import math
import random
import time
from pathlib import Path
from typing import Optional

import cv2
import numpy as np

from spikes.geo import matchers as matcher_registry
from spikes.geo import mosaic as mosaic_mod
from spikes.geo import rerank as rerank_mod
from spikes.geo import sitl_render
from spikes.geo.geomath import deg2tile, tile2deg
from spikes.geo.harvested.calibrate import is_never_accept
from spikes.geo.harvested.encoder import build_encoder
from spikes.geo.harvested.localize import Candidate, resolve_regions
from spikes.geo.harvested.verify import (
    DEFAULT_ASSUMED_HORIZONTAL_FOV_DEGREES,
    load_region_tile_image,
    reference_meters_per_pixel,
)
from spikes.geo.run_bakeoff import haversine_m

LOGGER = logging.getLogger("spikes.geo.calibrate_instrument")
SPIKE_ROOT = Path(__file__).resolve().parent
REGIONS_ROOT = SPIKE_ROOT / "regions"
RESULTS_PATH = SPIKE_ROOT / "results" / "h0b" / "calibration.json"
REGION_ID = "kyiv-pozniaky"
ENCODER_ID = "eigenplaces_r18_512"
ZOOM = 17
K = 10
SEED = 20260819
N_TILES = 5
FOV_DEGREES = DEFAULT_ASSUMED_HORIZONTAL_FOV_DEGREES  # 84.0 -- matches rerank.py's own default
OBLIQUE_PITCH_DEGREES = 45.0
OBLIQUE_ALTITUDE_M = 60.0
OBLIQUE_HEADING_DEG = 0.0  # north -- keeps the forward/right decomposition trivial
ROTATE_HEADING_DEG = 30.0


def _pick_tiles(region, region_dir: Path, n: int, seed: int) -> list:
    """`n` random INDEXED tiles (not holdout-only) that additionally have a full 3x3 disk
    neighbourhood (`mosaic.py#has_full_neighbourhood`) -- cases (iv)/(v) need real neighbour
    pixels, not a black-padded mosaic."""
    rng = random.Random(seed)
    candidates = list(region.index.tiles)
    rng.shuffle(candidates)
    picked = []
    for meta in candidates:
        zoom, x, y = (int(p) for p in meta.tile_id.split("/"))
        if mosaic_mod.has_full_neighbourhood(region_dir, x, y, zoom):
            picked.append((meta, x, y, zoom))
        if len(picked) >= n:
            break
    if len(picked) < n:
        raise RuntimeError(
            f"only found {len(picked)}/{n} indexed tiles with a full 3x3 neighbourhood on disk"
        )
    return picked


def _build_query_variants(region_dir: Path, tile_meta, x: int, y: int, zoom: int) -> dict:
    """The five synthetic query images for one tile, plus per-variant conditioning priors and
    the mosaic's own georeference (needed by callers, e.g. a metres-error diagnostic)."""
    tile_image = load_region_tile_image(region_dir, tile_meta.tile_id)
    if tile_image is None:
        raise RuntimeError(f"reference tile image missing for {tile_meta.tile_id}")
    h, w = tile_image.shape[:2]
    mpp = reference_meters_per_pixel(tile_meta.lat, zoom)

    # (i) exact tile
    exact = tile_image.copy()

    # (ii) upsampled 2x
    upsampled = cv2.resize(tile_image, (w * 2, h * 2), interpolation=cv2.INTER_CUBIC)

    # (iii) rotated 30deg, sitl_render.render_nadir's OWN "-heading" convention -- the exact
    # inverse of condition_query's "+heading_degrees" de-rotation.
    center = (w / 2.0, h / 2.0)
    rot = cv2.getRotationMatrix2D(center, -ROTATE_HEADING_DEG, 1.0)
    rotated = cv2.warpAffine(tile_image, rot, (w, h), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REFLECT101)
    # altitude s.t. condition_query's footprint_m == the tile's own native footprint (256*mpp) --
    # a pure-rotation test, isolated from any GSD rescale.
    footprint_native_m = 256 * mpp
    alt_native_gsd = footprint_native_m / (2.0 * math.tan(math.radians(FOV_DEGREES / 2.0)))

    # (iv) 3x3 mosaic, 512px crop centred on the tile with a half-tile (128px) offset in x.
    mosaic = mosaic_mod.build_tile_mosaic(region_dir, x, y, zoom)
    if mosaic is None:
        raise RuntimeError(f"mosaic unavailable for {tile_meta.tile_id} despite the neighbourhood pre-check")
    mcx, mcy = mosaic.image.shape[1] / 2.0, mosaic.image.shape[0] / 2.0  # == centre tile's centre
    crop_cx, crop_cy = mcx + 128.0, mcy
    x0, y0 = int(round(crop_cx - 256.0)), int(round(crop_cy - 256.0))
    mosaic_crop = mosaic.image[y0 : y0 + 512, x0 : x0 + 512].copy()
    # ground truth of the crop's own centre pixel, for a secondary metres-error diagnostic
    gx, gy = mosaic.origin_x + crop_cx, mosaic.origin_y + crop_cy
    crop_true_lat, crop_true_lon = tile2deg(gx / 256.0, gy / 256.0, zoom)
    crop_true_tile_xy = deg2tile(crop_true_lat, crop_true_lon, zoom)

    # (v) simulated oblique (pitch 45deg) warp of the SAME mosaic, sitl_render's own math.
    oblique = sitl_render.render_oblique(
        mosaic, tile_meta.lat, tile_meta.lon, OBLIQUE_HEADING_DEG, OBLIQUE_ALTITUDE_M,
        OBLIQUE_PITCH_DEGREES, FOV_DEGREES, 512,
    )
    look_at_forward_m, look_at_right_m = sitl_render._corner_offset_meters(
        OBLIQUE_ALTITUDE_M, OBLIQUE_PITCH_DEGREES, FOV_DEGREES, 0, 0
    )
    heading_rad = math.radians(OBLIQUE_HEADING_DEG)
    forward_east, forward_north = math.sin(heading_rad), math.cos(heading_rad)
    right_east, right_north = math.cos(heading_rad), -math.sin(heading_rad)
    east_m = look_at_forward_m * forward_east + look_at_right_m * right_east
    north_m = look_at_forward_m * forward_north + look_at_right_m * right_north
    oblique_lookat_lat = tile_meta.lat + north_m / 111_320.0
    oblique_lookat_lon = tile_meta.lon + east_m / (111_320.0 * max(math.cos(math.radians(tile_meta.lat)), 1e-6))

    return {
        "i_exact": {"image": exact, "heading": None, "altitude": None, "true_lat": tile_meta.lat, "true_lon": tile_meta.lon},
        "ii_upsampled_2x": {"image": upsampled, "heading": None, "altitude": None, "true_lat": tile_meta.lat, "true_lon": tile_meta.lon},
        "iii_rotated_30deg": {"image": rotated, "heading": ROTATE_HEADING_DEG, "altitude": alt_native_gsd, "true_lat": tile_meta.lat, "true_lon": tile_meta.lon},
        "iv_mosaic_crop_half_tile_offset": {
            "image": mosaic_crop, "heading": None, "altitude": None,
            "true_lat": tile_meta.lat, "true_lon": tile_meta.lon,
            "crop_centre_true_lat": crop_true_lat, "crop_centre_true_lon": crop_true_lon,
            "crop_centre_true_tile": f"{zoom}/{crop_true_tile_xy[0]}/{crop_true_tile_xy[1]}",
        },
        "v_oblique_45deg": {
            "image": oblique, "heading": OBLIQUE_HEADING_DEG, "altitude": OBLIQUE_ALTITUDE_M,
            "true_lat": tile_meta.lat, "true_lon": tile_meta.lon,
            "lookat_true_lat": oblique_lookat_lat, "lookat_true_lon": oblique_lookat_lon,
        },
    }


def _full_rank(region, encoder, image: np.ndarray, true_tile_id: str) -> dict:
    descriptor = encoder.encode(image)
    results, _ms = region.index.search(descriptor, top_k=len(region.index))
    rank = next((i + 1 for i, r in enumerate(results) if r.tile.tile_id == true_tile_id), None)
    top1 = results[0] if results else None
    return {
        "descriptor": descriptor,
        "full_rank_of_true_tile": rank,
        "n_indexed": len(results),
        "top1_tile_id": top1.tile.tile_id if top1 else None,
        "top1_similarity": top1.similarity if top1 else None,
        "true_tile_similarity": next((r.similarity for r in results if r.tile.tile_id == true_tile_id), None),
        "topk_candidates": results[:K],
    }


def run_variant(region, region_dir, encoder, matcher_name, matcher_handle, match_fn, never_accept,
                 tile_label: str, variant_name: str, variant: dict) -> dict:
    image = variant["image"]
    true_tile_id = tile_label

    full = _full_rank(region, encoder, image, true_tile_id)
    candidates = [
        Candidate(tile_id=r.tile.tile_id, lat=r.tile.lat, lon=r.tile.lon, similarity=r.similarity, region_id=REGION_ID)
        for r in full["topk_candidates"]
    ]

    def load_tile(candidate: Candidate) -> Optional[np.ndarray]:
        return load_region_tile_image(region_dir, candidate.tile_id)

    t0 = time.perf_counter()
    result = rerank_mod.rerank(
        matcher_handle, image, candidates, load_tile, match_fn,
        top_k=K, heading_degrees=variant["heading"], altitude_meters=variant["altitude"],
        fov_degrees=FOV_DEGREES, cell_is_never_accept=never_accept,
    )
    elapsed_ms = (time.perf_counter() - t0) * 1000.0

    winner = result.winner
    top1_after_rerank = bool(winner and winner.candidate.tile_id == true_tile_id)
    error_m = None
    if winner is not None and winner.pose.ok and winner.pose.lat is not None:
        error_m = haversine_m(variant["true_lat"], variant["true_lon"], winner.pose.lat, winner.pose.lon)

    return {
        "tile": tile_label,
        "variant": variant_name,
        "matcher": matcher_name,
        "conditioned": result.conditioned,
        "retrieval_rank_of_true_tile": full["full_rank_of_true_tile"],
        "n_indexed": full["n_indexed"],
        "top1_retrieval_tile": full["top1_tile_id"],
        "top1_retrieval_similarity": full["top1_similarity"],
        "true_tile_retrieval_similarity": full["true_tile_similarity"],
        "true_tile_in_topk": true_tile_id in [c.tile_id for c in candidates],
        "winner_tile_id": winner.candidate.tile_id if winner else None,
        "top1_after_rerank": top1_after_rerank,
        "inlier_count": winner.inlier_count if winner else 0,
        "match_count": winner.match_count if winner else 0,
        "inlier_ratio": winner.inlier_ratio if winner else 0.0,
        "reprojection_rms_px": winner.reprojection_rms_px if winner else None,
        "rerank_margin": result.rerank_margin,
        "gates": result.gates,
        "refusal": result.refusal,
        "gate_pass": result.refusal is None,
        "position_error_m": error_m,
        "elapsed_ms": round(elapsed_ms, 1),
    }


def run_matcher(matcher_name: str) -> list[dict]:
    region_dir = REGIONS_ROOT / REGION_ID
    regions = resolve_regions(REGIONS_ROOT, REGION_ID)
    if not regions:
        raise SystemExit(f"region {REGION_ID} not built -- run build_regions.py --region pozniaky first")
    region = regions[0]
    never_accept = is_never_accept(region.stats.accept_similarity, region.stats.holdout_recall_at_1)
    encoder = build_encoder(ENCODER_ID)
    matcher_handle = matcher_registry.build(matcher_name)
    match_fn = matcher_registry.MATCH_FNS[matcher_name]

    picked = _pick_tiles(region, region_dir, N_TILES, SEED)
    rows = []
    for meta, x, y, zoom in picked:
        LOGGER.info("tile %s ...", meta.tile_id)
        variants = _build_query_variants(region_dir, meta, x, y, zoom)
        for variant_name, variant in variants.items():
            row = run_variant(region, region_dir, encoder, matcher_name, matcher_handle, match_fn,
                               never_accept, meta.tile_id, variant_name, variant)
            LOGGER.info(
                "  %-32s rank=%s inliers=%d/%d ratio=%.2f top1=%s gate=%s",
                variant_name, row["retrieval_rank_of_true_tile"], row["inlier_count"],
                row["match_count"], row["inlier_ratio"], row["top1_after_rerank"], row["gate_pass"],
            )
            rows.append(row)
    return rows


def _load_results() -> list[dict]:
    if not RESULTS_PATH.exists():
        return []
    return json.loads(RESULTS_PATH.read_text())


def _save_results(results: list[dict]) -> None:
    RESULTS_PATH.parent.mkdir(parents=True, exist_ok=True)
    RESULTS_PATH.write_text(json.dumps(results, indent=2))


def main(argv=None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--matcher", choices=sorted(matcher_registry.BUILDERS), required=True)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args(argv)

    existing = _load_results()
    if not args.force and any(r.get("matcher") == args.matcher for r in existing):
        LOGGER.info("%s already recorded (pass --force to re-run)", args.matcher)
        return 0

    rows = run_matcher(args.matcher)
    existing = [r for r in existing if r.get("matcher") != args.matcher] + rows
    existing.sort(key=lambda r: (r["matcher"], r["tile"], r["variant"]))
    _save_results(existing)
    print(json.dumps({"matcher": args.matcher, "n_rows": len(rows)}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
