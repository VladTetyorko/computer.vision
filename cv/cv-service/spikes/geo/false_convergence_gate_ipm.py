"""cv-service/spikes/geo/false_convergence_gate_ipm.py

H0c re-measurement driver (VISUAL-GEO-V2-PLAN.md §9.8, task 4): a THIRD `SequenceLocalizer` pass
alongside `false_convergence_gate.py`'s existing "control" (raw similarity) and "geometric"
(condition-only re-rank) fields -- "geometric_ipm", built from the BEST Pexels IPM configuration
found by this wave's `--rectify ipm` geometry sweep (`run_bakeoff_v2.py`, `results/h0c/
bakeoff_v2.json`): xfeat, single-tile, pitch=70deg/altitude=60m/heading=155deg -- highest
`recall_at_1_100m` (0.5, 6/12 frames) of all 54 swept single+mosaic geometry points, edging out
the parked branch's own stated-prior heading (135deg) by 20deg.

A SEPARATE script rather than a modification of `false_convergence_gate.py` itself: that file is a
completed, already-measured H0 deliverable (`results/false_convergence_gate.json` is its own
committed evidence) -- this driver imports its constants/helpers (`KNOWN_WRONG_CELL`, `REGION_ID`,
`ENCODER_ID`, `K`) and mirrors its `run_pass` shape exactly, but writes to a NEW path (`results/
h0c/false_convergence_gate_ipm.json`) so neither H0's numbers nor its script are disturbed by this
wave's addition.

Correctness (per task 4): the GROUND-TRUTH cell is `17/76649/44196` (Maidan Nezalezhnosti,
50.4502431N/30.5240622E at z17 -- `geomath.deg2tile`, cross-checked against the literal id every
other H0/H0b/H0c Pexels finding already uses). A converged estimate counts as CORRECT when its
distance from that cell's center is within `harvested/calibrate.py#holdout_correct_radius_m`
(quantization-aware -- ~1.2 tile-steps at kyiv-maidan's z17/50.45degN, `HOLDOUT_NEIGHBOR_SLACK`),
the same yardstick `rank_shift.py`/H0b use elsewhere in this wave, not an arbitrarily chosen flat
distance.

Same filter mechanics as `false_convergence_gate.py` (no odometry -- documented near-static hover,
`predict(0, 0)` every step); only the per-update measurement field differs.

Usage:
    source spikes/geo/env.sh
    .venv/bin/python -m spikes.geo.false_convergence_gate_ipm
"""
from __future__ import annotations

import json
import logging
from pathlib import Path

import cv2
import numpy as np

from spikes.geo import matchers as matcher_registry
from spikes.geo import rectify_pipeline
from spikes.geo.geomath import deg2tile
from spikes.geo.harvested.calibrate import holdout_correct_radius_m
from spikes.geo.harvested.encoder import build_encoder
from spikes.geo.harvested.localize import resolve_regions
from spikes.geo.harvested.sequence import STATUS_CONVERGED, SequenceLocalizer
from spikes.geo.false_convergence_gate import ENCODER_ID, K, KNOWN_WRONG_CELL, MANIFEST_PATH, REGION_ID
from spikes.geo.manifest import load_manifest
from spikes.geo.run_bakeoff import REGIONS_ROOT, haversine_m

LOGGER = logging.getLogger("spikes.geo.false_convergence_gate_ipm")
SPIKE_ROOT = Path(__file__).resolve().parent
RESULTS_PATH = SPIKE_ROOT / "results" / "h0c" / "false_convergence_gate_ipm.json"
MATCHER_NAME = "xfeat"
BEST_PITCH_DEG = 70.0
BEST_ALTITUDE_M = 60.0
BEST_HEADING_DEG = 155.0
BEST_USE_MOSAIC = False
GROUND_TRUTH_LAT = 50.4502431
GROUND_TRUTH_LON = 30.5240622


def run_ipm_pass(region, region_dir, encoder, matcher_handle, match_fn, frames) -> list[dict]:
    tile_ids = [t.tile_id for t in region.index.tiles]
    tile_row = {tid: i for i, tid in enumerate(tile_ids)}
    localizer = SequenceLocalizer.from_tile_ids(tile_ids, seed=20260819)

    rows = []
    for frame in frames:
        image = cv2.imread(str(frame.image_path), cv2.IMREAD_COLOR)
        if image is None:
            continue

        p = rectify_pipeline.run_pass(
            image, encoder=encoder, region=region, region_dir=region_dir,
            matcher_handle=matcher_handle, match_fn=match_fn, rectify_mode="ipm", k=K,
            heading_deg=BEST_HEADING_DEG, altitude_m=BEST_ALTITUDE_M, pitch_deg=BEST_PITCH_DEG,
            use_mosaic=BEST_USE_MOSAIC,
        )

        field = np.zeros(len(tile_ids), dtype=np.float64)
        if p.rerank is not None:
            for cs in p.rerank.scored:
                row = tile_row.get(cs.candidate.tile_id)
                if row is not None:
                    field[row] = cs.inlier_ratio

        localizer.predict(0.0, 0.0)  # documented near-static hover, no odometry
        estimate = localizer.update(field)

        winning_tile = tile_ids[int(np.argmax(field))] if field.size and field.max() > 0 else None
        rows.append({
            "query_id": frame.query_id,
            "status": estimate.status,
            "converged": estimate.status == STATUS_CONVERGED,
            "lat": estimate.lat,
            "lon": estimate.lon,
            "spread_m": estimate.spread_m,
            "top_cell_share": estimate.top_cell_share,
            "update_count": estimate.update_count,
            "distance_from_truth_m": haversine_m(frame.lat, frame.lon, estimate.lat, estimate.lon),
            "distance_from_known_wrong_cell_m": None,
            "field_argmax_tile": winning_tile,
            "field_argmax_is_known_wrong_cell": winning_tile == KNOWN_WRONG_CELL,
            "rectify_refusal": p.refusal,
        })
    return rows


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    frames = load_manifest(MANIFEST_PATH)
    region_dir = REGIONS_ROOT / REGION_ID
    regions = resolve_regions(REGIONS_ROOT, REGION_ID)
    if not regions:
        raise SystemExit(f"region {REGION_ID} not built -- run build_regions.py first")
    region = regions[0]
    encoder = build_encoder(ENCODER_ID)
    matcher_handle = matcher_registry.build(MATCHER_NAME)
    match_fn = matcher_registry.MATCH_FNS[MATCHER_NAME]

    known_wrong = next((t for t in region.index.tiles if t.tile_id == KNOWN_WRONG_CELL), None)
    gt_zoom_x, gt_zoom_y = deg2tile(GROUND_TRUTH_LAT, GROUND_TRUTH_LON, 17)
    gt_cell = f"17/{gt_zoom_x}/{gt_zoom_y}"
    correct_radius_m = holdout_correct_radius_m(gt_cell)

    LOGGER.info(
        "geometric_ipm pass (pitch=%.0f alt=%.0f heading=%.0f mosaic=%s, xfeat) ...",
        BEST_PITCH_DEG, BEST_ALTITUDE_M, BEST_HEADING_DEG, BEST_USE_MOSAIC,
    )
    rows = run_ipm_pass(region, region_dir, encoder, matcher_handle, match_fn, frames)

    for row in rows:
        if known_wrong is not None:
            row["distance_from_known_wrong_cell_m"] = haversine_m(row["lat"], row["lon"], known_wrong.lat, known_wrong.lon)
        row["distance_from_gt_cell_center_m"] = haversine_m(row["lat"], row["lon"], GROUND_TRUTH_LAT, GROUND_TRUTH_LON)
        row["correct_at_convergence"] = bool(row["converged"] and row["distance_from_gt_cell_center_m"] <= correct_radius_m)

    converged_rows = [r for r in rows if r["converged"]]
    converged_on_wrong_cell = [
        r for r in converged_rows
        if r["distance_from_known_wrong_cell_m"] is not None and r["distance_from_known_wrong_cell_m"] < 50.0
    ]
    converged_correct = [r for r in converged_rows if r["correct_at_convergence"]]
    summary = {
        "ground_truth_cell": gt_cell,
        "correct_radius_m": correct_radius_m,
        "geometry": {"matcher": MATCHER_NAME, "pitch_deg": BEST_PITCH_DEG, "altitude_m": BEST_ALTITUDE_M, "heading_deg": BEST_HEADING_DEG, "use_mosaic": BEST_USE_MOSAIC, "geometry_is_stated_prior": True},
        "known_wrong_cell": KNOWN_WRONG_CELL,
        "n_updates": len(rows),
        "n_converged": len(converged_rows),
        "n_converged_on_known_wrong_cell": len(converged_on_wrong_cell),
        "n_converged_correct": len(converged_correct),
        "min_distance_from_truth_m_at_convergence": min((r["distance_from_truth_m"] for r in converged_rows), default=None),
    }

    output = {"summary": summary, "rows": rows}
    RESULTS_PATH.parent.mkdir(parents=True, exist_ok=True)
    RESULTS_PATH.write_text(json.dumps(output, indent=2))
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
