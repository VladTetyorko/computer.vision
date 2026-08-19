"""cv-service/spikes/geo/rank_shift.py

H0b deliverable (3): for a given (dataset, matcher, k), report the rank of the CORRECT tile
within the top-k candidate set BEFORE re-rank (its position in the retrieval-similarity order,
`localize()`'s own `loc.candidates`) and AFTER re-rank (its position in `rerank.rerank()`'s
`result.scored`, sorted by inlier count descending) -- one row per frame, plus the median.

"Correct tile" = the NEAREST INDEXED tile to the frame's own ground-truth (lat, lon) -- NOT the
literal `geomath.deg2tile(lat, lon)` cell. H0b measured (this script's first run, kept as a
finding rather than silently patched over) that kyiv-maidan's every pexels/sitl-oblique45 frame
shares one ground-truth coordinate (Maidan Nezalezhnosti), whose exact z17 cell (17/76649/44196)
is ALWAYS one of the region's own `DEFAULT_HOLDOUT_FRACTION=0.10` calibration holdout tiles
(`harvested/calibrate.py`) -- absent from the 45/49-tile search index by construction, so its
id-exact rank is undefined for literally every frame in every kyiv-maidan dataset (0/25 `in_topk`
at both k=10 and k=20, before AND after re-rank -- ruled out as a rank_shift.py bug by checking
`tiles.json` directly). `harvested/calibrate.py`'s own `holdout_correct_radius_m` treats a held-out
tile's best ACHIEVABLE top-1 as its nearest indexed neighbour (~194.5m away at z17/Kyiv latitudes)
-- so this script adopts the same yardstick: `nearest_indexed_tile_id` (picked by haversine
distance among `RegionIndex.index.tiles`, which are exactly the indexed 45) is the target ranked
before/after re-rank; `exact_cell_tile_id` and `exact_cell_indexed` are recorded alongside for
transparency. A `None` rank means even the nearest-indexed tile fell outside the top-k retrieval
window -- never offered to the re-rank stage to fix.

Usage:
    source spikes/geo/env.sh
    .venv/bin/python -m spikes.geo.rank_shift --dataset pexels --matcher xfeat --unrectified --k 10
    .venv/bin/python -m spikes.geo.rank_shift --dataset sitl-oblique45 --matcher loftr --rectified --k 20
"""
from __future__ import annotations

import argparse
import json
import logging
import statistics as st
from pathlib import Path
from typing import Optional

import cv2
import numpy as np

from spikes.geo import matchers as matcher_registry
from spikes.geo import rerank as rerank_mod
from spikes.geo.geomath import deg2tile, haversine_m
from spikes.geo.harvested.calibrate import is_never_accept
from spikes.geo.harvested.encoder import build_encoder
from spikes.geo.harvested.localize import localize, resolve_regions
from spikes.geo.harvested.verify import load_region_tile_image
from spikes.geo.manifest import load_manifest
from spikes.geo.run_bakeoff import DATASETS, ENCODER_ID, REFUSED_RECTIFIED_NO_TELEMETRY, REGIONS_ROOT
from spikes.geo.run_bakeoff import _config_key as _base_config_key

LOGGER = logging.getLogger("spikes.geo.rank_shift")
SPIKE_ROOT = Path(__file__).resolve().parent
RESULTS_PATH = SPIKE_ROOT / "results" / "h0b" / "rank_shift.json"
ZOOM = 17


def _rank_of(tile_id: str, ordered_ids: list[str]) -> Optional[int]:
    try:
        return ordered_ids.index(tile_id) + 1
    except ValueError:
        return None


def _config_key(dataset: str, matcher_name: str, rectified: bool, k: int) -> str:
    return "rankshift|" + _base_config_key(dataset, matcher_name, rectified, k)


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

    regions = resolve_regions(REGIONS_ROOT, spec["region_id"])
    if not regions:
        return {"config": key, "dataset": dataset, "matcher": matcher_name, "rectified": rectified, "k": k, "status": "not_run", "reason": f"region {spec['region_id']} not built"}
    never_accept = is_never_accept(regions[0].stats.accept_similarity, regions[0].stats.holdout_recall_at_1)
    encoder = build_encoder(ENCODER_ID)
    indexed_tiles = regions[0].index.tiles  # exactly the searchable 45/49 -- see module docstring

    def load_tile(candidate) -> Optional[np.ndarray]:
        return load_region_tile_image(REGIONS_ROOT / candidate.region_id, candidate.tile_id)

    def nearest_indexed(lat: float, lon: float) -> str:
        return min(indexed_tiles, key=lambda t: haversine_m(lat, lon, t.lat, t.lon)).tile_id

    per_frame = []
    for frame in frames:
        image = cv2.imread(str(frame.image_path), cv2.IMREAD_COLOR)
        if image is None:
            LOGGER.warning("%s: unreadable frame %s, skipping", key, frame.image_path)
            continue
        tx, ty = deg2tile(frame.lat, frame.lon, ZOOM)
        exact_cell_tile_id = f"{ZOOM}/{tx}/{ty}"
        exact_cell_indexed = any(t.tile_id == exact_cell_tile_id for t in indexed_tiles)
        true_tile_id = exact_cell_tile_id if exact_cell_indexed else nearest_indexed(frame.lat, frame.lon)

        loc = localize(image, encoder=encoder, regions=regions, max_candidates=k)
        before_ids = [c.tile_id for c in loc.candidates]
        rank_before = _rank_of(true_tile_id, before_ids)
        if not loc.candidates:
            per_frame.append({
                "query_id": frame.query_id, "true_tile": true_tile_id,
                "exact_cell_tile_id": exact_cell_tile_id, "exact_cell_indexed": exact_cell_indexed,
                "rank_before": None, "rank_after": None, "note": "no retrieval candidates",
            })
            continue

        heading = frame.heading if rectified else None
        altitude = frame.altitude_meters if rectified else None
        result = rerank_mod.rerank(
            matcher_handle, image, loc.candidates, load_tile, match_keypoints_fn,
            top_k=k, heading_degrees=heading, altitude_meters=altitude,
            cell_is_never_accept=never_accept,
        )
        after_ids = [cs.candidate.tile_id for cs in result.scored]
        rank_after = _rank_of(true_tile_id, after_ids)

        per_frame.append({
            "query_id": frame.query_id,
            "true_tile": true_tile_id,
            "exact_cell_tile_id": exact_cell_tile_id,
            "exact_cell_indexed": exact_cell_indexed,
            "in_topk": true_tile_id in before_ids,
            "rank_before": rank_before,
            "rank_after": rank_after,
            "shift": (rank_before - rank_after) if (rank_before is not None and rank_after is not None) else None,
        })

    ranks_before = [f["rank_before"] for f in per_frame if f.get("rank_before") is not None]
    ranks_after = [f["rank_after"] for f in per_frame if f.get("rank_after") is not None]
    summary = {
        "config": key,
        "dataset": dataset,
        "matcher": matcher_name,
        "rectified": rectified,
        "k": k,
        "status": "ok",
        "n_frames": len(frames),
        "n_exact_cell_indexed": sum(1 for f in per_frame if f.get("exact_cell_indexed")),
        "n_in_topk": sum(1 for f in per_frame if f.get("in_topk")),
        "median_rank_before": st.median(ranks_before) if ranks_before else None,
        "median_rank_after": st.median(ranks_after) if ranks_after else None,
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
