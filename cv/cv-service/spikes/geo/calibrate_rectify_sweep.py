"""cv-service/spikes/geo/calibrate_rectify_sweep.py

H0c re-measurement driver (VISUAL-GEO-V2-PLAN.md §9.8, task 1): H0b's `calibrate_instrument.py`
answered "does retrieval+re-rank recognise a query built from the same indexed pixels" for five
synthetic query kinds; its own "v_oblique_45deg" variant was the one case that reproduced the
harness's Defect 2 gap on purpose (its own docstring: matched with `condition_query`, which is
NADIR-ONLY, "expected to behave like an UNMITIGATED oblique query even though the pixels are
self-sourced"). This driver re-runs EXACTLY that one variant -- same tiles (same `SEED`, same
`_pick_tiles` selection), same oblique construction (`OBLIQUE_PITCH_DEGREES`/`_ALTITUDE_M`/
`_HEADING_DEG`, `sitl_render.render_oblique`) -- through all three `rectify_pipeline.py` modes
(`none`/`condition`/`ipm`) instead of `condition` alone, so H0b's own number (retrieval rank
median 28, 2/5 top-1) has a like-for-like `ipm` comparison on the identical query set.

`region_id`/tile pool: `kyiv-pozniaky` (H0b's own choice, not `kyiv-maidan` -- deliberately a
DIFFERENT region than the pexels/SITL bake-off, per H0b's own module docstring: the point of the
self-match instrument check is independence from any real domain gap, so it needs its own tile
pool, not H0's small 49-tile maidan index).

Writes to `results/h0c/calibration_sweep.json`, one row per (tile, matcher, rectify_mode) --
`results/h0b/calibration.json` (H0b's own five-variant sweep) is untouched.

Usage (foreground, resumable -- appends per matcher, `--force` to redo):
    source spikes/geo/env.sh
    .venv/bin/python -m spikes.geo.calibrate_rectify_sweep --matcher xfeat
    .venv/bin/python -m spikes.geo.calibrate_rectify_sweep --matcher lightglue_disk
    .venv/bin/python -m spikes.geo.calibrate_rectify_sweep --matcher loftr
"""
from __future__ import annotations

import argparse
import json
import logging
import time
from pathlib import Path

from spikes.geo import matchers as matcher_registry
from spikes.geo import mosaic as mosaic_mod
from spikes.geo import rectify_pipeline
from spikes.geo import sitl_render
from spikes.geo.calibrate_instrument import (
    FOV_DEGREES,
    K,
    N_TILES,
    OBLIQUE_ALTITUDE_M,
    OBLIQUE_HEADING_DEG,
    OBLIQUE_PITCH_DEGREES,
    REGION_ID,
    SEED,
    _pick_tiles,
)
from spikes.geo.geomath import haversine_m
from spikes.geo.harvested.calibrate import is_never_accept
from spikes.geo.harvested.encoder import build_encoder
from spikes.geo.harvested.localize import resolve_regions
from spikes.geo.run_bakeoff import REGIONS_ROOT, ENCODER_ID

LOGGER = logging.getLogger("spikes.geo.calibrate_rectify_sweep")
SPIKE_ROOT = Path(__file__).resolve().parent
RESULTS_PATH = SPIKE_ROOT / "results" / "h0c" / "calibration_sweep.json"
RECTIFY_MODES = rectify_pipeline.RECTIFY_MODES


def run_matcher(matcher_name: str) -> list[dict]:
    region_dir = REGIONS_ROOT / REGION_ID
    regions = resolve_regions(REGIONS_ROOT, REGION_ID)
    if not regions:
        return [{"matcher": matcher_name, "status": "not_run", "reason": f"region {REGION_ID} not built"}]
    region = regions[0]
    never_accept = is_never_accept(region.stats.accept_similarity, region.stats.holdout_recall_at_1)
    encoder = build_encoder(ENCODER_ID)

    try:
        matcher_handle = matcher_registry.build(matcher_name)
    except Exception as exc:  # noqa: BLE001
        LOGGER.warning("matcher %s unavailable: %s", matcher_name, exc)
        return [{"matcher": matcher_name, "status": "not_run", "reason": f"{type(exc).__name__}: {exc}"}]
    match_fn = matcher_registry.MATCH_FNS[matcher_name]

    picked = _pick_tiles(region, region_dir, N_TILES, SEED)
    rows = []
    for tile_meta, x, y, zoom in picked:
        mosaic = mosaic_mod.build_tile_mosaic(region_dir, x, y, zoom)
        if mosaic is None:
            rows.append({"matcher": matcher_name, "tile": tile_meta.tile_id, "status": "not_run", "reason": "mosaic unavailable"})
            continue
        oblique = sitl_render.render_oblique(
            mosaic, tile_meta.lat, tile_meta.lon, OBLIQUE_HEADING_DEG, OBLIQUE_ALTITUDE_M,
            OBLIQUE_PITCH_DEGREES, FOV_DEGREES, 512,
        )
        for rectify_mode in RECTIFY_MODES:
            t0 = time.perf_counter()
            p = rectify_pipeline.run_pass(
                oblique, encoder=encoder, region=region, region_dir=region_dir,
                matcher_handle=matcher_handle, match_fn=match_fn, rectify_mode=rectify_mode, k=K,
                true_tile_id=tile_meta.tile_id, heading_deg=OBLIQUE_HEADING_DEG,
                altitude_m=OBLIQUE_ALTITUDE_M, pitch_deg=OBLIQUE_PITCH_DEGREES,
                fov_degrees=FOV_DEGREES, cell_is_never_accept=never_accept,
            )
            elapsed_ms = (time.perf_counter() - t0) * 1000.0
            winner = p.rerank.winner if p.rerank else None
            error_m = None
            if winner is not None and winner.pose.ok and winner.pose.lat is not None:
                error_m = haversine_m(tile_meta.lat, tile_meta.lon, winner.pose.lat, winner.pose.lon)
            rows.append({
                "matcher": matcher_name,
                "tile": tile_meta.tile_id,
                "rectify": rectify_mode,
                "status": "ok",
                "ok": p.ok,
                "retrieval_rank_of_true_tile": p.retrieval_rank_of_true_tile,
                "n_indexed": p.n_indexed,
                "top1_after_rerank": bool(winner and winner.candidate.tile_id == tile_meta.tile_id),
                "inlier_count": winner.inlier_count if winner else 0,
                "inlier_ratio": winner.inlier_ratio if winner else None,
                "match_count": winner.match_count if winner else 0,
                "reprojection_rms_px": winner.reprojection_rms_px if winner else None,
                "refusal": p.refusal,
                "gate_pass": p.refusal is None and winner is not None,
                "position_error_m": error_m,
                "rectify_meta": p.rectify_meta,
                "elapsed_ms": round(elapsed_ms, 1),
            })
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

    results = _load_results()
    already = any(r.get("matcher") == args.matcher and r.get("status") == "ok" for r in results)
    if already and not args.force:
        LOGGER.info("matcher %s already recorded (pass --force to re-run)", args.matcher)
        print(json.dumps([r for r in results if r.get("matcher") == args.matcher], indent=2))
        return 0

    LOGGER.info("running matcher=%s ...", args.matcher)
    rows = run_matcher(args.matcher)
    results = [r for r in results if r.get("matcher") != args.matcher] + rows
    _save_results(results)
    print(json.dumps(rows, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
