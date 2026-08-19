"""cv-service/spikes/geo/run_mosaic_bakeoff.py

H0b deliverable (2): the mosaic-candidate driver, `run_bakeoff.py`'s own shape (one
(dataset, matcher, rectified, k) configuration per invocation, resumable, appends to
`results/h0b/mosaic.json`) but re-ranking against `mosaic_rerank.rerank_mosaic` (a 3x3 tile
mosaic per candidate) instead of `rerank.rerank` (the single 256px tile) -- so every column lines
up directly against H0's own `results/bakeoff.json` rows for the same (dataset, matcher, k).

Usage:
    source spikes/geo/env.sh
    .venv/bin/python -m spikes.geo.run_mosaic_bakeoff --dataset pexels --matcher xfeat --unrectified --k 10
    .venv/bin/python -m spikes.geo.run_mosaic_bakeoff --dataset sitl-oblique45 --matcher lightglue_disk --rectified --k 10
"""
from __future__ import annotations

import argparse
import json
import logging
import time
from pathlib import Path
from typing import Optional

import cv2
import numpy as np

from spikes.geo import matchers as matcher_registry
from spikes.geo import mosaic_rerank
from spikes.geo.harvested.calibrate import is_never_accept
from spikes.geo.harvested.encoder import build_encoder
from spikes.geo.harvested.localize import localize, resolve_regions
from spikes.geo.manifest import load_manifest
from spikes.geo.run_bakeoff import DATASETS, ENCODER_ID, FALSE_FIX_DISTANCE_M, RECALL_DISTANCE_M, REFUSED_RECTIFIED_NO_TELEMETRY, REGIONS_ROOT, _config_key as _base_config_key, haversine_m

LOGGER = logging.getLogger("spikes.geo.run_mosaic_bakeoff")
SPIKE_ROOT = Path(__file__).resolve().parent
RESULTS_PATH = SPIKE_ROOT / "results" / "h0b" / "mosaic.json"


def _config_key(dataset: str, matcher_name: str, rectified: bool, k: int) -> str:
    return "mosaic|" + _base_config_key(dataset, matcher_name, rectified, k)


def run_config(dataset: str, matcher_name: str, rectified: bool, k: int) -> dict:
    key = _config_key(dataset, matcher_name, rectified, k)
    spec = DATASETS[dataset]
    frames = load_manifest(spec["manifest"])

    if rectified and dataset == "pexels":
        return {"config": key, "dataset": dataset, "matcher": matcher_name, "rectified": rectified, "k": k, "status": "not_run", "reason": REFUSED_RECTIFIED_NO_TELEMETRY}

    try:
        matcher_handle = matcher_registry.build(matcher_name)
    except Exception as exc:  # noqa: BLE001 -- record, never crash the driver
        LOGGER.warning("matcher %s unavailable: %s", matcher_name, exc)
        return {"config": key, "dataset": dataset, "matcher": matcher_name, "rectified": rectified, "k": k, "status": "not_run", "reason": f"{type(exc).__name__}: {exc}"}
    match_keypoints_fn = matcher_registry.MATCH_FNS[matcher_name]

    region_dir = REGIONS_ROOT / spec["region_id"]
    regions = resolve_regions(REGIONS_ROOT, spec["region_id"])
    if not regions:
        return {"config": key, "dataset": dataset, "matcher": matcher_name, "rectified": rectified, "k": k, "status": "not_run", "reason": f"region {spec['region_id']} not built"}
    never_accept = is_never_accept(regions[0].stats.accept_similarity, regions[0].stats.holdout_recall_at_1)
    encoder = build_encoder(ENCODER_ID)

    per_frame = []
    t_start = time.perf_counter()
    for frame in frames:
        image = cv2.imread(str(frame.image_path), cv2.IMREAD_COLOR)
        if image is None:
            LOGGER.warning("%s: unreadable frame %s, skipping", key, frame.image_path)
            continue
        loc = localize(image, encoder=encoder, regions=regions, max_candidates=k)
        if not loc.candidates:
            per_frame.append({"query_id": frame.query_id, "status": loc.status, "distance_m": None, "note": "no retrieval candidates"})
            continue

        heading = frame.heading if rectified else None
        altitude = frame.altitude_meters if rectified else None
        t0 = time.perf_counter()
        result = mosaic_rerank.rerank_mosaic(
            matcher_handle, image, loc.candidates, region_dir, match_keypoints_fn,
            top_k=k, heading_degrees=heading, altitude_meters=altitude,
            cell_is_never_accept=never_accept,
        )
        elapsed_ms = (time.perf_counter() - t0) * 1000.0
        n_scored = len(result.scored) or 1
        per_pair_ms = elapsed_ms / n_scored

        winner = result.winner
        distance_m = None
        if winner is not None:
            if winner.pose.ok and winner.pose.lat is not None:
                est_lat, est_lon = winner.pose.lat, winner.pose.lon
            else:
                est_lat, est_lon = winner.candidate.lat, winner.candidate.lon
            distance_m = haversine_m(frame.lat, frame.lon, est_lat, est_lon)

        all_gates_pass = result.refusal is None
        per_frame.append({
            "query_id": frame.query_id,
            "distance_m": distance_m,
            "inlier_count": winner.inlier_count if winner else 0,
            "match_count": winner.match_count if winner else 0,
            "reprojection_rms_px": winner.reprojection_rms_px if winner else None,
            "rerank_margin": result.rerank_margin,
            "conditioned": result.conditioned,
            "refusal": result.refusal,
            "accepted": all_gates_pass,
            "n_incomplete_neighbourhood": result.gates.get("n_incomplete_neighbourhood"),
            "per_pair_ms": round(per_pair_ms, 1),
            "n_scored": n_scored,
        })

    total_s = time.perf_counter() - t_start
    matched = [f for f in per_frame if f.get("distance_m") is not None]
    accepted = [f for f in matched if f.get("accepted")]
    recall_at_1 = (sum(1 for f in matched if f["distance_m"] <= RECALL_DISTANCE_M) / len(frames)) if frames else 0.0
    false_fix_rate = (sum(1 for f in accepted if f["distance_m"] > FALSE_FIX_DISTANCE_M) / len(accepted)) if accepted else None
    per_pair_values = [f["per_pair_ms"] for f in per_frame if "per_pair_ms" in f]
    mean_per_pair_ms = sum(per_pair_values) / len(per_pair_values) if per_pair_values else None
    median_distance_m = float(np.median([f["distance_m"] for f in matched])) if matched else None

    summary = {
        "config": key,
        "dataset": dataset,
        "matcher": matcher_name,
        "rectified": rectified,
        "k": k,
        "status": "ok",
        "n_frames": len(frames),
        "n_matched": len(matched),
        "n_accepted": len(accepted),
        "recall_at_1_100m": recall_at_1,
        "false_fix_rate": false_fix_rate,
        "median_distance_m": median_distance_m,
        "mean_per_pair_ms": round(mean_per_pair_ms, 1) if mean_per_pair_ms is not None else None,
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
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--rectified", action="store_true")
    group.add_argument("--unrectified", action="store_true")
    parser.add_argument("--k", type=int, required=True)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args(argv)

    rectified = args.rectified
    key = _config_key(args.dataset, args.matcher, rectified, args.k)
    results = _load_results()
    if not args.force and any(r["config"] == key for r in results):
        LOGGER.info("%s already recorded (pass --force to re-run)", key)
        existing = next(r for r in results if r["config"] == key)
        print(json.dumps({k: v for k, v in existing.items() if k != "per_frame"}, indent=2))
        return 0

    LOGGER.info("running %s ...", key)
    summary = run_config(args.dataset, args.matcher, rectified, args.k)
    results = [r for r in results if r["config"] != key] + [summary]
    results.sort(key=lambda r: r["config"])
    _save_results(results)
    print(json.dumps({k: v for k, v in summary.items() if k != "per_frame"}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
