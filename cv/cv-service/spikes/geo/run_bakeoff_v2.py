"""cv-service/spikes/geo/run_bakeoff_v2.py

H0c re-measurement driver (VISUAL-GEO-V2-PLAN.md §9.8, tasks 2+3): `run_bakeoff.py`'s twin,
generalized from the old binary `--rectified`/`--unrectified` flag to `--rectify
{none,condition,ipm}` (`rectify_pipeline.py`'s three modes -- see that module's docstring for what
each one actually does) and extended with the geometry knobs IPM needs.

Per-frame geometry: `sitl-nadir`/`sitl-oblique45`'s manifest carries REAL per-frame heading +
altitude (telemetry) but no pitch field (`manifest.py#QueryFrame` -- `sitl_render.py` always
renders a whole dataset at one fixed pitch), so `--pitch-deg` is a required CLI constant for those
two datasets under `condition`/`ipm`, applied uniformly, while heading/altitude come from each
frame's own manifest row unless `--heading-deg`/`--altitude-m` override them (for the geometry
sweep). `pexels` has NO telemetry at all (H0's own finding, `run_bakeoff.py`'s own
`REFUSED_RECTIFIED_NO_TELEMETRY`) -- under `condition`/`ipm` its heading/altitude/pitch MUST come
from `--heading-deg`/`--altitude-m`/`--pitch-deg`, and every summary this driver writes for pexels
sets `"geometry_is_stated_prior": true` so nothing downstream mistakes a prior for a measurement.

Retrieval rank of the "true tile" (same nearest-indexed-tile convention `rank_shift.py` established
-- H0c defect 1 means the exact cell is usually indexed now, but the fallback stays for honesty on
regions/frames where it still isn't) is measured via a FULL, untruncated index search
(`rectify_pipeline.run_pass`'s own `retrieval_rank_of_true_tile`) alongside the usual
recall/false-fix/distance bake-off metrics, plus the after-re-rank rank within the top-k -- so one
run produces both this task's "rank shift" and "top-1/gate/error" columns without a second driver.

Writes to `results/h0c/bakeoff_v2.json` -- `run_bakeoff.py`'s own `results/bakeoff.json` (H0's
original numbers) is untouched by this module.

Usage (foreground, resumable, one config per invocation -- `--force` to redo):
    source spikes/geo/env.sh
    .venv/bin/python -m spikes.geo.run_bakeoff_v2 --dataset sitl-oblique45 --matcher xfeat \\
        --rectify ipm --k 10 --pitch-deg 45
    .venv/bin/python -m spikes.geo.run_bakeoff_v2 --dataset pexels --matcher xfeat --rectify ipm \\
        --k 10 --pitch-deg 70 --altitude-m 60 --heading-deg 135
    .venv/bin/python -m spikes.geo.run_bakeoff_v2 --dataset pexels --matcher loftr --rectify none --k 10
"""
from __future__ import annotations

import argparse
import json
import logging
import statistics as st
import time
from pathlib import Path
from typing import Optional

import cv2
import numpy as np

from spikes.geo import matchers as matcher_registry
from spikes.geo import rectify_pipeline
from spikes.geo.geomath import deg2tile, haversine_m
from spikes.geo.harvested.calibrate import is_never_accept
from spikes.geo.harvested.encoder import build_encoder
from spikes.geo.harvested.localize import resolve_regions
from spikes.geo.manifest import load_manifest
from spikes.geo.run_bakeoff import DATASETS, ENCODER_ID, REGIONS_ROOT

LOGGER = logging.getLogger("spikes.geo.run_bakeoff_v2")
SPIKE_ROOT = Path(__file__).resolve().parent
RESULTS_PATH = SPIKE_ROOT / "results" / "h0c" / "bakeoff_v2.json"
ZOOM = 17
RECALL_DISTANCE_M = 100.0
FALSE_FIX_DISTANCE_M = 300.0
REFUSED_NO_GEOMETRY = (
    "refused: rectify mode needs heading+altitude(+pitch for ipm) and this dataset/frame has none "
    "-- pass --heading-deg/--altitude-m/--pitch-deg explicitly (a stated prior for pexels) rather "
    "than silently defaulting, which would misreport a guess as a measurement"
)


def _config_key(dataset: str, matcher_name: str, rectify_mode: str, k: int, pitch_deg: float, heading_override: Optional[float], altitude_override: Optional[float], use_mosaic: bool) -> str:
    geom = f"p{pitch_deg:g}"
    if heading_override is not None:
        geom += f"_h{heading_override:g}"
    if altitude_override is not None:
        geom += f"_a{altitude_override:g}"
    if use_mosaic:
        geom += "_mosaic"
    return f"{dataset}|{matcher_name}|{rectify_mode}|k{k}|{geom}"


def _nearest_indexed_tile_id(region, lat: float, lon: float) -> str:
    return min(region.index.tiles, key=lambda t: haversine_m(lat, lon, t.lat, t.lon)).tile_id


def run_config(
    dataset: str, matcher_name: str, rectify_mode: str, k: int, *,
    pitch_deg: float, heading_override: Optional[float], altitude_override: Optional[float],
    use_mosaic: bool,
) -> dict:
    key = _config_key(dataset, matcher_name, rectify_mode, k, pitch_deg, heading_override, altitude_override, use_mosaic)
    spec = DATASETS[dataset]
    frames = load_manifest(spec["manifest"])
    geometry_is_stated_prior = dataset == "pexels" and rectify_mode != "none"

    def base(status: str, reason: str) -> dict:
        return {"config": key, "dataset": dataset, "matcher": matcher_name, "rectify": rectify_mode, "k": k, "status": status, "reason": reason}

    if rectify_mode != "none" and dataset == "pexels" and (heading_override is None or altitude_override is None):
        return base("not_run", REFUSED_NO_GEOMETRY)

    try:
        matcher_handle = matcher_registry.build(matcher_name)
    except Exception as exc:  # noqa: BLE001 -- record, never crash the driver
        LOGGER.warning("matcher %s unavailable: %s", matcher_name, exc)
        return base("not_run", f"{type(exc).__name__}: {exc}")
    match_fn = matcher_registry.MATCH_FNS[matcher_name]

    region_dir = REGIONS_ROOT / spec["region_id"]
    regions = resolve_regions(REGIONS_ROOT, spec["region_id"])
    if not regions:
        return base("not_run", f"region {spec['region_id']} not built -- run build_regions.py first")
    region = regions[0]
    never_accept = is_never_accept(region.stats.accept_similarity, region.stats.holdout_recall_at_1)
    encoder = build_encoder(ENCODER_ID)

    per_frame = []
    t_start = time.perf_counter()
    for frame in frames:
        image = cv2.imread(str(frame.image_path), cv2.IMREAD_COLOR)
        if image is None:
            LOGGER.warning("%s: unreadable frame %s, skipping", key, frame.image_path)
            continue

        tx, ty = deg2tile(frame.lat, frame.lon, ZOOM)
        exact_cell_tile_id = f"{ZOOM}/{tx}/{ty}"
        exact_cell_indexed = any(t.tile_id == exact_cell_tile_id for t in region.index.tiles)
        true_tile_id = exact_cell_tile_id if exact_cell_indexed else _nearest_indexed_tile_id(region, frame.lat, frame.lon)

        heading = heading_override if heading_override is not None else frame.heading
        altitude = altitude_override if altitude_override is not None else frame.altitude_meters
        if rectify_mode != "none" and (heading is None or altitude is None):
            per_frame.append({"query_id": frame.query_id, "note": "no heading/altitude available for this frame", "true_tile": true_tile_id})
            continue

        p = rectify_pipeline.run_pass(
            image, encoder=encoder, region=region, region_dir=region_dir,
            matcher_handle=matcher_handle, match_fn=match_fn, rectify_mode=rectify_mode, k=k,
            true_tile_id=true_tile_id, heading_deg=heading, altitude_m=altitude, pitch_deg=pitch_deg,
            use_mosaic=use_mosaic, cell_is_never_accept=never_accept,
        )

        rank_after = None
        winner = None
        if p.rerank is not None:
            rank_after = next((i + 1 for i, cs in enumerate(p.rerank.scored) if cs.candidate.tile_id == true_tile_id), None)
            winner = p.rerank.winner

        distance_m = None
        if winner is not None:
            if winner.pose.ok and winner.pose.lat is not None:
                est_lat, est_lon = winner.pose.lat, winner.pose.lon
            else:
                est_lat, est_lon = winner.candidate.lat, winner.candidate.lon
            distance_m = haversine_m(frame.lat, frame.lon, est_lat, est_lon)

        per_frame.append({
            "query_id": frame.query_id,
            "true_tile": true_tile_id,
            "exact_cell_indexed": exact_cell_indexed,
            "ok": p.ok,
            "refusal": p.refusal,
            "retrieval_rank_before": p.retrieval_rank_of_true_tile,
            "retrieval_rank_after": rank_after,
            "top1_after_rerank": bool(winner and winner.candidate.tile_id == true_tile_id),
            "distance_m": distance_m,
            "inlier_count": winner.inlier_count if winner else 0,
            "inlier_ratio": winner.inlier_ratio if winner else None,
            "match_count": winner.match_count if winner else 0,
            "reprojection_rms_px": winner.reprojection_rms_px if winner else None,
            "accepted": p.refusal is None and winner is not None,
            "elapsed_ms": round(p.elapsed_ms, 1),
            "rectify_meta": p.rectify_meta,
        })

    total_s = time.perf_counter() - t_start
    matched = [f for f in per_frame if f.get("distance_m") is not None]
    accepted = [f for f in matched if f.get("accepted")]
    recall_at_1 = (sum(1 for f in matched if f["distance_m"] <= RECALL_DISTANCE_M) / len(frames)) if frames else 0.0
    false_fix_rate = (sum(1 for f in accepted if f["distance_m"] > FALSE_FIX_DISTANCE_M) / len(accepted)) if accepted else None
    median_distance_m = float(np.median([f["distance_m"] for f in matched])) if matched else None
    ranks_before = [f["retrieval_rank_before"] for f in per_frame if f.get("retrieval_rank_before") is not None]
    ranks_after = [f["retrieval_rank_after"] for f in per_frame if f.get("retrieval_rank_after") is not None]
    elapsed_values = [f["elapsed_ms"] for f in per_frame if "elapsed_ms" in f]

    summary = {
        "config": key,
        "dataset": dataset,
        "matcher": matcher_name,
        "rectify": rectify_mode,
        "k": k,
        "pitch_deg": pitch_deg,
        "heading_override_deg": heading_override,
        "altitude_override_m": altitude_override,
        "use_mosaic": use_mosaic,
        "geometry_is_stated_prior": geometry_is_stated_prior,
        "status": "ok",
        "n_frames": len(frames),
        "n_matched": len(matched),
        "n_accepted": len(accepted),
        "n_exact_cell_indexed": sum(1 for f in per_frame if f.get("exact_cell_indexed")),
        "n_retrieval_in_topk_before": sum(1 for f in per_frame if f.get("retrieval_rank_before") is not None and f["retrieval_rank_before"] <= k),
        "median_retrieval_rank_before": st.median(ranks_before) if ranks_before else None,
        "median_retrieval_rank_after": st.median(ranks_after) if ranks_after else None,
        "recall_at_1_100m": recall_at_1,
        "false_fix_rate": false_fix_rate,
        "median_distance_m": median_distance_m,
        "mean_elapsed_ms_per_frame": round(sum(elapsed_values) / len(elapsed_values), 1) if elapsed_values else None,
        "total_wall_seconds": round(total_s, 1),
        "per_frame": per_frame,
    }
    return summary


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
    parser.add_argument("--dataset", choices=sorted(DATASETS), required=True)
    parser.add_argument("--matcher", choices=sorted(matcher_registry.BUILDERS), required=True)
    parser.add_argument("--rectify", choices=rectify_pipeline.RECTIFY_MODES, required=True)
    parser.add_argument("--k", type=int, required=True)
    parser.add_argument("--pitch-deg", type=float, default=0.0)
    parser.add_argument("--heading-deg", type=float, default=None, help="override every frame's manifest heading (required for pexels)")
    parser.add_argument("--altitude-m", type=float, default=None, help="override every frame's manifest altitude (required for pexels)")
    parser.add_argument("--mosaic", action="store_true", help="ipm mode: match against each candidate's 3x3 mosaic instead of its single tile")
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args(argv)

    key = _config_key(args.dataset, args.matcher, args.rectify, args.k, args.pitch_deg, args.heading_deg, args.altitude_m, args.mosaic)
    results = _load_results()
    if not args.force and any(r["config"] == key for r in results):
        LOGGER.info("%s already recorded (pass --force to re-run)", key)
        existing = next(r for r in results if r["config"] == key)
        print(json.dumps({k: v for k, v in existing.items() if k != "per_frame"}, indent=2))
        return 0

    LOGGER.info("running %s ...", key)
    summary = run_config(
        args.dataset, args.matcher, args.rectify, args.k, pitch_deg=args.pitch_deg,
        heading_override=args.heading_deg, altitude_override=args.altitude_m, use_mosaic=args.mosaic,
    )
    results = [r for r in results if r["config"] != key] + [summary]
    results.sort(key=lambda r: r["config"])
    _save_results(results)
    print(json.dumps({k: v for k, v in summary.items() if k != "per_frame"}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
