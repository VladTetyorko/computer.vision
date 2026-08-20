"""cv-service/spikes/geo/run_bakeoff.py

H0 deliverable (3)+(4): the matcher bake-off driver -- one (dataset, matcher, rectified, k)
configuration per invocation (execution-discipline rule: every run finishes in the foreground,
appends to `results/bakeoff.json`, and is safely re-runnable/resumable -- `--force` to re-measure
a config that's already recorded).

Datasets (each backed by a manifest.jsonl of {path, lat, lon, heading?, altitude_meters?}):
  - `pexels`        12 real Maidan-Nezalezhnosti frames, ground truth 50.4502431N/30.5240622E,
                     NO telemetry (the branch's own finding -- "no telemetry existed on that
                     footage at all"). `--rectified` on this dataset is refused, not faked: §4.2's
                     conditioning needs a real heading+altitude prior, and inventing one would
                     report a number that looks like a measurement but isn't (see REFUSED_RECTIFIED_NO_TELEMETRY).
  - `sitl-nadir`     13 frames rendered from a closed-form ArduCopter CIRCLE-mode track (real
                     telemetry, see analytical_circle_telemetry.py's own "NOT SITL output, but a
                     real documented flight mode's own trace" caveat) against real Esri z17 tiles,
                     nadir (pitch=0).
  - `sitl-oblique45` same track, pitch=45deg oblique -- the case rectify-first is FOR.

Region: `kyiv-maidan` (built by `build_regions.py`) for all three -- the SITL track is centered on
the same region on purpose, so pexels/SITL numbers sit on the same reference index.

Usage:
    source spikes/geo/env.sh
    .venv/bin/python -m spikes.geo.run_bakeoff --dataset pexels --matcher xfeat --unrectified --k 10
    .venv/bin/python -m spikes.geo.run_bakeoff --dataset sitl-oblique45 --matcher lightglue_disk --rectified --k 10
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
from spikes.geo import rerank as rerank_mod
from spikes.geo.harvested.calibrate import is_never_accept
from spikes.geo.harvested.encoder import build_encoder
from spikes.geo.harvested.localize import resolve_regions, localize
from spikes.geo.harvested.verify import load_region_tile_image
from spikes.geo.manifest import QueryFrame, load_manifest

LOGGER = logging.getLogger("spikes.geo.run_bakeoff")
SPIKE_ROOT = Path(__file__).resolve().parent
REGIONS_ROOT = SPIKE_ROOT / "regions"
RESULTS_PATH = SPIKE_ROOT / "results" / "bakeoff.json"
ENCODER_ID = "eigenplaces_r18_512"
EARTH_RADIUS_M = 6_371_000.0
RECALL_DISTANCE_M = 100.0
FALSE_FIX_DISTANCE_M = 300.0
REFUSED_RECTIFIED_NO_TELEMETRY = "refused: dataset carries no heading/altitude telemetry -- conditioning needs both, faking one would misreport a measurement as real"

DATASETS = {
    "pexels": {
        "manifest": SPIKE_ROOT / "fixtures" / "maidan-video-frames" / "manifest.jsonl",
        "region_id": "kyiv-maidan",
    },
    "sitl-nadir": {
        "manifest": SPIKE_ROOT / "results" / "sitl" / "nadir" / "manifest.jsonl",
        "region_id": "kyiv-maidan",
    },
    "sitl-oblique45": {
        "manifest": SPIKE_ROOT / "results" / "sitl" / "oblique45" / "manifest.jsonl",
        "region_id": "kyiv-maidan",
    },
}


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = np.radians(lat1), np.radians(lat2)
    dphi = np.radians(lat2 - lat1)
    dlambda = np.radians(lon2 - lon1)
    a = np.sin(dphi / 2) ** 2 + np.cos(p1) * np.cos(p2) * np.sin(dlambda / 2) ** 2
    return float(2 * EARTH_RADIUS_M * np.arcsin(np.sqrt(a)))


def _load_results() -> list[dict]:
    if not RESULTS_PATH.exists():
        return []
    return json.loads(RESULTS_PATH.read_text())


def _save_results(results: list[dict]) -> None:
    RESULTS_PATH.parent.mkdir(parents=True, exist_ok=True)
    RESULTS_PATH.write_text(json.dumps(results, indent=2))


def _config_key(dataset: str, matcher_name: str, rectified: bool, k: int) -> str:
    return f"{dataset}|{matcher_name}|{'rectified' if rectified else 'unrectified'}|k{k}"


def run_config(dataset: str, matcher_name: str, rectified: bool, k: int) -> dict:
    key = _config_key(dataset, matcher_name, rectified, k)
    spec = DATASETS[dataset]
    frames = load_manifest(spec["manifest"])

    if rectified and dataset == "pexels":
        return {"config": key, "dataset": dataset, "matcher": matcher_name, "rectified": rectified, "k": k, "status": "not_run", "reason": REFUSED_RECTIFIED_NO_TELEMETRY}

    try:
        matcher_handle = matcher_registry.build(matcher_name)
    except Exception as exc:  # noqa: BLE001 -- record, never crash the driver (execution discipline)
        LOGGER.warning("matcher %s unavailable: %s", matcher_name, exc)
        return {"config": key, "dataset": dataset, "matcher": matcher_name, "rectified": rectified, "k": k, "status": "not_run", "reason": f"{type(exc).__name__}: {exc}"}
    match_keypoints_fn = matcher_registry.MATCH_FNS[matcher_name]

    region_dir = REGIONS_ROOT / spec["region_id"]
    regions = resolve_regions(REGIONS_ROOT, spec["region_id"])
    if not regions:
        return {"config": key, "dataset": dataset, "matcher": matcher_name, "rectified": rectified, "k": k, "status": "not_run", "reason": f"region {spec['region_id']} not built -- run build_regions.py first"}
    never_accept = is_never_accept(regions[0].stats.accept_similarity, regions[0].stats.holdout_recall_at_1)
    encoder = build_encoder(ENCODER_ID)

    def load_tile(candidate) -> Optional[np.ndarray]:
        return load_region_tile_image(REGIONS_ROOT / candidate.region_id, candidate.tile_id)

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
        result = rerank_mod.rerank(
            matcher_handle, image, loc.candidates, load_tile, match_keypoints_fn,
            top_k=k, heading_degrees=heading, altitude_meters=altitude,
            cell_is_never_accept=never_accept,
        )
        elapsed_ms = (time.perf_counter() - t0) * 1000.0
        n_scored = len(result.scored) or 1
        per_pair_ms = elapsed_ms / n_scored

        winner = result.winner
        distance_m = None
        est_lat = est_lon = None
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
            "per_pair_ms": round(per_pair_ms, 1),
            "n_scored": n_scored,
        })

    total_s = time.perf_counter() - t_start
    matched = [f for f in per_frame if f.get("distance_m") is not None]
    accepted = [f for f in matched if f.get("accepted")]
    recall_at_1 = (sum(1 for f in matched if f["distance_m"] <= RECALL_DISTANCE_M) / len(frames)) if frames else 0.0
    # None (not 0.0) with zero accepted frames -- "0% false-fix rate" would misreport an undefined
    # ratio as a measured perfect score. Zero acceptances is itself the headline in that case.
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
