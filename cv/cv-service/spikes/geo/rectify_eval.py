"""Slice R measurement gate: does §13.4 IPM rectification lift the two known-failing oblique
testbeds? (docs/VISUAL-GEO-PLAN.md §13.4; run before wiring rectification into production.)

Two experiments, run against `cv_service/geo/rectify.py` (the actual production module, not a
reimplementation):

(a) The two REAL Chavdar 38b window photos (§12.11 addendum; GT tile 17/76689/44230,
    Pixel 9 Pro XL). First reports what their metadata ACTUALLY contains via
    `extract_geometry`, then runs [no rectification | manual-pitch IPM sweep x altitude x
    heading] x LoFTR+MAGSAC against the GT tile and its 8 neighbors. Known baseline to beat:
    5-6 inliers max (below every floor). Heading is unknown (GPSImgDirection=NaN), so a
    coarse heading sweep runs against the GT tile only, and the best few (pitch, alt,
    heading) combos get the full 9-tile treatment.

(b) The SITL-oblique set (71 frames, render pitch 45 deg / fov 60 / 512px —
    run_sitl_eval.sh + sitl_render.py), full retrieval pipeline (cached zoom-17 tile index,
    EigenPlaces, top-2 LoFTR verify, MAGSAC pose — run_homography_pose.py's harness shape),
    conditions: `norm` (similarity conditioning, the shipped Wave-6a math — §12.11 measured
    ~450m look-at error / 42 deg yaw) vs `ipm` (horizon-crop + full perspective warp to
    north-up tile GSD; keypoints mapped back through H_ipm^-1 before the pose fit, so the
    pose homography refers to input pixels and yaw is absolute).

Writes spikes/geo/results/rectify-eval-<ts>/{report.md, summary.json, raw/**}.

Usage (from cv-service/, venv activated):
    python -m spikes.geo.rectify_eval [--parts a,b] [--subsample-sitl N]
"""

from __future__ import annotations

import argparse
import json
import logging
import math
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

import cv2
import numpy as np

from spikes.geo.harvested import rectify as prod_rectify
from spikes.geo.harvested.pose import fit_homography_pose
from spikes.geo.harvested.verify import loftr_match_keypoints
from spikes.geo.encoders import build_encoder, load_image
from spikes.geo.geomath import haversine_m, tile_center
from spikes.geo.loftr_verify import build_matcher
from spikes.geo.manifest import load_manifest
from spikes.geo.run_homography_pose import (
    OBLIQUE_PITCH_DEG,
    RENDER_FOV_DEG,
    SITL_RUN,
    build_cached_index,
    load_telemetry_alt,
    advance_latlon,
    normalize_query,
)

LOGGER = logging.getLogger("spikes.geo.rectify_eval")

SPIKE_DIR = Path(__file__).resolve().parent
RESULTS_DIR = SPIKE_DIR / "results"
ZOOM = 17

# --- part (a) inputs ---
PHOTOS = {
    "photo1-oblique": Path(
        "/home/vladte/.claude/uploads/74cd8651-9131-4d29-b113-960cb51123bb/01e9fc45-7199.jpg"
    ),
    "photo2-steep": Path(
        "/home/vladte/.claude/uploads/74cd8651-9131-4d29-b113-960cb51123bb/0dbe0524-7203.jpg"
    ),
}
GT_TILE = (17, 76689, 44230)
POZNIAKY_TILES = SPIKE_DIR.parent.parent / "demo" / "data" / "kyiv-pozniaky" / "tiles"
PHOTO_MAX_EDGE = 1600  # full 4080px warps are wasted work — LoFTR sees 480px anyway
PITCH_SWEEP = [20.0, 30.0, 40.0, 50.0, 60.0]
ALT_SWEEP = [25.0, 40.0]  # plausible window heights at a Pozniaky high-rise, meters
HEADING_SWEEP = [0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0]
FULL_EVAL_TOP_COMBOS = 3  # best heading-sweep combos that get the 9-tile treatment

SITL_FOCAL_PX = prod_rectify.focal_px_from_fov(512, RENDER_FOV_DEG)


def _json_default(obj):
    if isinstance(obj, (np.bool_,)):
        return bool(obj)
    if isinstance(obj, np.integer):
        return int(obj)
    if isinstance(obj, np.floating):
        return float(obj)
    raise TypeError(f"not JSON serializable: {type(obj)}")


def neighbors(tile: tuple[int, int, int]) -> list[tuple[int, int, int]]:
    z, x, y = tile
    return [(z, x + dx, y + dy) for dy in (-1, 0, 1) for dx in (-1, 0, 1)]


def load_tile_image(tile: tuple[int, int, int]) -> Optional[np.ndarray]:
    z, x, y = tile
    return cv2.imread(str(POZNIAKY_TILES / f"{z}_{x}_{y}.jpg"), cv2.IMREAD_COLOR)


def match_and_pose(matcher, query: np.ndarray, tile: tuple[int, int, int],
                   tile_image: np.ndarray, to_input=None,
                   input_size: Optional[tuple[int, int]] = None) -> dict:
    """One LoFTR + MAGSAC evaluation of `query` against one reference tile. When `to_input`
    is given (the rectifier's warped_to_input), keypoints are mapped back and the pose is
    fitted in the input frame of size `input_size` (w, h)."""
    kp = loftr_match_keypoints(matcher, query, tile_image)
    row = {"tile": f"{tile[0]}/{tile[1]}/{tile[2]}", "matches": kp.count,
           "mean_conf": kp.mean_confidence, "inliers": 0, "sanity_ok": False}
    kp_query = kp.kp_query
    if to_input is not None and kp_query.size:
        kp_query = to_input(kp_query)
    if input_size is None:
        input_size = (query.shape[1], query.shape[0])
    if kp.count >= 4:
        pose = fit_homography_pose(kp_query, kp.kp_tile, input_size[0], input_size[1],
                                   tile[1], tile[2], tile[0])
        row.update({
            "inliers": pose.inlier_count,
            "sanity_ok": bool(pose.ok and pose.sanity_ok),
            "pose_ok": pose.ok,
            "lat": pose.lat, "lon": pose.lon, "yaw_deg": pose.yaw_deg,
            "footprint_area_m2": pose.footprint_area_m2,
        })
    return row


# ---------------------------------------------------------------------------
# part (a): real Chavdar photos
# ---------------------------------------------------------------------------


def run_photos(matcher, raw_dir: Path) -> dict:
    gt_lat, gt_lon = tile_center(GT_TILE[1], GT_TILE[2], ZOOM)
    target_gsd = prod_rectify.reference_gsd_m_per_px(gt_lat, ZOOM)
    tiles = neighbors(GT_TILE)
    tile_images = {t: load_tile_image(t) for t in tiles}
    results: dict = {}

    for name, path in PHOTOS.items():
        LOGGER.info("=== photo %s ===", name)
        geometry = prod_rectify.extract_geometry(path)
        LOGGER.info("metadata: focal_px=%s pitch=%s roll=%s yaw=%s provenance=%s",
                    geometry.focal_px, geometry.pitch_deg, geometry.roll_deg,
                    geometry.yaw_deg, geometry.provenance)
        image = cv2.imread(str(path), cv2.IMREAD_COLOR)
        scale = PHOTO_MAX_EDGE / max(image.shape[:2])
        focal_px = geometry.focal_px * scale if geometry.focal_px else None
        image = cv2.resize(image, (int(image.shape[1] * scale), int(image.shape[0] * scale)),
                           interpolation=cv2.INTER_AREA)

        photo: dict = {
            "metadata": {
                "focal_px_native": geometry.focal_px, "focal_mm": geometry.focal_mm,
                "focal_35mm": geometry.focal_35mm, "pitch_deg": geometry.pitch_deg,
                "roll_deg": geometry.roll_deg, "yaw_deg": geometry.yaw_deg,
                "provenance": geometry.provenance,
                "image_wh": [geometry.image_width, geometry.image_height],
            },
            "conditions": {},
        }

        # Condition 1: no rectification (the known baseline), all 9 tiles.
        base_rows = [match_and_pose(matcher, image, t, tile_images[t])
                     for t in tiles if tile_images[t] is not None]
        photo["conditions"]["none"] = base_rows
        best_base = max(base_rows, key=lambda r: r["inliers"])
        LOGGER.info("none: best tile %s matches=%d inliers=%d",
                    best_base["tile"], best_base["matches"], best_base["inliers"])

        if focal_px is None:
            photo["note"] = "no focal metadata -- IPM sweep skipped"
            results[name] = photo
            continue

        # Stage 1: coarse (pitch, alt, heading) sweep against the GT tile ONLY.
        gt_image = tile_images[GT_TILE]
        sweep_rows = []
        for pitch in PITCH_SWEEP:
            for alt in ALT_SWEEP:
                for heading in HEADING_SWEEP:
                    rect = prod_rectify.rectify(
                        image, pitch_deg=pitch, focal_px=focal_px, altitude_m=alt,
                        heading_deg=heading, target_gsd_m_per_px=target_gsd)
                    if rect is None:
                        continue
                    row = match_and_pose(
                        matcher, rect.warped, GT_TILE, gt_image,
                        to_input=rect.warped_to_input,
                        input_size=(rect.cropped_width, rect.cropped_height))
                    row.update({"pitch": pitch, "alt": alt, "heading": heading,
                                "crop_top": rect.crop_top,
                                "warped_wh": [rect.warped.shape[1], rect.warped.shape[0]]})
                    sweep_rows.append(row)
        sweep_rows.sort(key=lambda r: (r["inliers"], r["matches"]), reverse=True)
        photo["conditions"]["ipm_gt_tile_sweep"] = sweep_rows
        for row in sweep_rows[:5]:
            LOGGER.info("sweep: p=%.0f a=%.0f h=%.0f matches=%d inliers=%d",
                        row["pitch"], row["alt"], row["heading"], row["matches"], row["inliers"])

        # Stage 2: full 9-tile eval at the best combos.
        photo["conditions"]["ipm_full"] = []
        for combo in sweep_rows[:FULL_EVAL_TOP_COMBOS]:
            rect = prod_rectify.rectify(
                image, pitch_deg=combo["pitch"], focal_px=focal_px,
                altitude_m=combo["alt"], heading_deg=combo["heading"],
                target_gsd_m_per_px=target_gsd)
            rows = [match_and_pose(matcher, rect.warped, t, tile_images[t],
                                   to_input=rect.warped_to_input,
                                   input_size=(rect.cropped_width, rect.cropped_height))
                    for t in tiles if tile_images[t] is not None]
            rows.sort(key=lambda r: r["inliers"], reverse=True)
            gt_id = f"{GT_TILE[0]}/{GT_TILE[1]}/{GT_TILE[2]}"
            photo["conditions"]["ipm_full"].append({
                "pitch": combo["pitch"], "alt": combo["alt"], "heading": combo["heading"],
                "gt_tile_leads_inliers": rows[0]["tile"] == gt_id if rows else False,
                "rows": rows,
            })
            LOGGER.info("full p=%.0f a=%.0f h=%.0f: leader %s inliers=%d (gt leads: %s)",
                        combo["pitch"], combo["alt"], combo["heading"],
                        rows[0]["tile"], rows[0]["inliers"], rows[0]["tile"] == gt_id)

        results[name] = photo
        (raw_dir / f"{name}.json").write_text(
            json.dumps(photo, indent=2, default=_json_default))
    return results


# ---------------------------------------------------------------------------
# part (b): SITL-oblique full pipeline
# ---------------------------------------------------------------------------


def _stats(values: list[float]) -> dict:
    if not values:
        return {"n": 0}
    arr = np.array(values, dtype=np.float64)
    return {"n": int(arr.size), "median": float(np.median(arr)), "mean": float(arr.mean()),
            "p90": float(np.percentile(arr, 90)), "max": float(arr.max())}


def _circular_err(a: Optional[float], b: Optional[float]) -> Optional[float]:
    if a is None or b is None:
        return None
    d = abs(a - b) % 360.0
    return min(d, 360.0 - d)


def run_sitl(matcher, raw_dir: Path, subsample: int) -> dict:
    encoder = build_encoder("eigenplaces")
    index = build_cached_index(encoder)
    queries = load_manifest(SITL_RUN / "oblique" / "manifest.jsonl")
    if subsample > 1:
        queries = queries[::subsample]
    alt_by_ts = load_telemetry_alt(SITL_RUN / "telemetry.csv")
    target_gsd_ref = None  # per-query from its own latitude

    per_condition: dict[str, list[dict]] = {"norm": [], "ipm": []}
    out_dir = raw_dir / "sitl"
    out_dir.mkdir(parents=True, exist_ok=True)

    for query in queries:
        image = load_image(query.image_path)
        alt = alt_by_ts.get(query.timestamp_ms) if query.timestamp_ms is not None else None
        if image is None or alt is None or query.heading is None:
            continue
        # look-at ground truth: frame center is alt*tan(pitch) ahead of the vehicle
        dist = alt * math.tan(math.radians(OBLIQUE_PITCH_DEG))
        la_lat, la_lon = advance_latlon(query.lat, query.lon, query.heading, dist)
        target_gsd = prod_rectify.reference_gsd_m_per_px(query.lat, ZOOM)

        conditions: dict[str, tuple[np.ndarray, object, tuple[int, int], Optional[float]]] = {}
        # norm: shipped similarity conditioning (de-rotate + GSD rescale, pitch-aware slant)
        normalized = normalize_query(image, query.heading, alt, query.lat, OBLIQUE_PITCH_DEG)
        if normalized is not None:
            conditions["norm"] = (normalized, None, (normalized.shape[1], normalized.shape[0]),
                                  query.heading)
        # ipm: full perspective rectification (crop + warp to north-up tile GSD)
        rect = prod_rectify.rectify(
            image, pitch_deg=OBLIQUE_PITCH_DEG, focal_px=SITL_FOCAL_PX, altitude_m=alt,
            heading_deg=query.heading, target_gsd_m_per_px=target_gsd)
        if rect is not None:
            conditions["ipm"] = (rect.warped, rect.warped_to_input,
                                 (rect.cropped_width, rect.cropped_height), None)

        for cond, (cond_image, to_input, input_size, heading_prior) in conditions.items():
            descriptor = encoder.encode(cond_image)
            candidates, _ = index.search(descriptor, top_k=2)
            if not candidates:
                continue
            scored = []
            for candidate in candidates:
                tile_ref = candidate.ref
                z, x, y = (int(p) for p in tile_ref.ref_id.split("/"))
                row = match_and_pose(matcher, cond_image, (z, x, y), tile_ref.load(),
                                     to_input=to_input, input_size=input_size)
                row["similarity"] = candidate.similarity
                scored.append(row)
            scored.sort(key=lambda r: (r["matches"], r["mean_conf"]), reverse=True)
            winner = scored[0]
            wz, wx, wy = (int(p) for p in winner["tile"].split("/"))
            tile_lat, tile_lon = tile_center(wx, wy, ZOOM)
            baseline_lookat_err = haversine_m(la_lat, la_lon, tile_lat, tile_lon)
            refined_lookat_err = (
                haversine_m(la_lat, la_lon, winner["lat"], winner["lon"])
                if winner.get("pose_ok") else None)
            yaw_pred = winner.get("yaw_deg")
            if yaw_pred is not None and heading_prior is not None:
                yaw_pred = (yaw_pred + heading_prior) % 360.0
            row = {
                "query": query.image_path.name, "condition": cond,
                "matches": winner["matches"], "inliers": winner["inliers"],
                "sanity_ok": winner["sanity_ok"],
                "baseline_lookat_err_m": baseline_lookat_err,
                "refined_lookat_err_m": refined_lookat_err,
                "yaw_err_deg": _circular_err(yaw_pred, query.heading),
                "gate_pass": winner["inliers"] >= 8 and winner["sanity_ok"],
            }
            per_condition[cond].append(row)
            (out_dir / f"{query.image_path.stem}-{cond}.json").write_text(
                json.dumps(row, indent=2, default=_json_default))
        LOGGER.info("%s: %s", query.image_path.name, {
            c: (rows[-1]["matches"], rows[-1]["inliers"],
                None if rows[-1]["refined_lookat_err_m"] is None
                else round(rows[-1]["refined_lookat_err_m"], 1))
            for c, rows in per_condition.items()
            if rows and rows[-1]["query"] == query.image_path.name})

    summary = {}
    for cond, rows in per_condition.items():
        accepted = [r for r in rows if r["gate_pass"]]
        summary[cond] = {
            "queries": len(rows),
            "matches": _stats([r["matches"] for r in rows]),
            "inliers": _stats([r["inliers"] for r in rows]),
            "baseline_lookat_err_m": _stats([r["baseline_lookat_err_m"] for r in rows]),
            "refined_lookat_err_m": _stats(
                [r["refined_lookat_err_m"] for r in rows if r["refined_lookat_err_m"] is not None]),
            "yaw_err_deg": _stats([r["yaw_err_deg"] for r in rows if r["yaw_err_deg"] is not None]),
            "gate_pass": len(accepted),
            "gate_pass_refined_err_m": _stats(
                [r["refined_lookat_err_m"] for r in accepted
                 if r["refined_lookat_err_m"] is not None]),
            "gate_pass_yaw_err_deg": _stats(
                [r["yaw_err_deg"] for r in accepted if r["yaw_err_deg"] is not None]),
        }
    return {"per_condition": summary, "rows": per_condition}


# ---------------------------------------------------------------------------


def write_report(out_dir: Path, photos: Optional[dict], sitl: Optional[dict]) -> None:
    lines = ["# Rectification fast-path eval (Slice R, §13.4)", "",
             f"Generated {datetime.now(timezone.utc).isoformat()}", ""]
    if photos:
        lines.append("## (a) Real Chavdar 38b photos (GT tile 17/76689/44230)")
        for name, photo in photos.items():
            m = photo["metadata"]
            lines += ["", f"### {name}", "",
                      f"- metadata: focal_px={m['focal_px_native'] and round(m['focal_px_native'])} "
                      f"(f={m['focal_mm']}mm, f35={m['focal_35mm']}mm), pitch={m['pitch_deg']}, "
                      f"roll={m['roll_deg']}, yaw={m['yaw_deg']}",
                      f"- provenance: {m['provenance']}", ""]
            best_base = max(photo["conditions"]["none"], key=lambda r: r["inliers"])
            lines.append(f"- no rectification: best tile {best_base['tile']} "
                         f"matches={best_base['matches']} inliers={best_base['inliers']}")
            if "ipm_gt_tile_sweep" in photo["conditions"]:
                lines.append("- IPM sweep vs GT tile (top 5 of "
                             f"{len(photo['conditions']['ipm_gt_tile_sweep'])}):")
                lines.append("  | pitch | alt | heading | matches | inliers | sanity |")
                lines.append("  |---|---|---|---|---|---|")
                for row in photo["conditions"]["ipm_gt_tile_sweep"][:5]:
                    lines.append(f"  | {row['pitch']:.0f} | {row['alt']:.0f} | "
                                 f"{row['heading']:.0f} | {row['matches']} | {row['inliers']} "
                                 f"| {row['sanity_ok']} |")
                for full in photo["conditions"].get("ipm_full", []):
                    leader = full["rows"][0]
                    lines.append(
                        f"- 9-tile eval p={full['pitch']:.0f} a={full['alt']:.0f} "
                        f"h={full['heading']:.0f}: leader {leader['tile']} "
                        f"inliers={leader['inliers']} (GT leads: {full['gt_tile_leads_inliers']})")
    if sitl:
        lines += ["", "## (b) SITL-oblique full pipeline (pitch 45, 71 frames w/ GT)", "",
                  "| condition | n | matches med | inliers med | look-at refined err med (m) | "
                  "yaw err med (deg) | gate pass | gated err med (m) | gated yaw med (deg) |",
                  "|---|---|---|---|---|---|---|---|---|"]
        for cond, s in sitl["per_condition"].items():
            def med(d):
                return f"{d['median']:.1f}" if d.get("n") else "-"
            lines.append(
                f"| {cond} | {s['queries']} | {med(s['matches'])} | {med(s['inliers'])} | "
                f"{med(s['refined_lookat_err_m'])} | {med(s['yaw_err_deg'])} | "
                f"{s['gate_pass']} | {med(s['gate_pass_refined_err_m'])} | "
                f"{med(s['gate_pass_yaw_err_deg'])} |")
        lines.append("")
        lines.append("Baseline to beat (§12.11): ~450m look-at refined error / ~42 deg yaw "
                     "under similarity-only conditioning.")
    (out_dir / "report.md").write_text("\n".join(lines) + "\n")


def main(argv: Optional[list[str]] = None) -> int:
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--parts", type=str, default="a,b")
    parser.add_argument("--subsample-sitl", type=int, default=1)
    args = parser.parse_args(argv)

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = RESULTS_DIR / f"rectify-eval-{stamp}"
    raw_dir = out_dir / "raw"
    raw_dir.mkdir(parents=True, exist_ok=True)

    start = time.perf_counter()
    matcher = build_matcher()
    photos = sitl = None
    parts = {p.strip() for p in args.parts.split(",")}
    if "a" in parts:
        photos = run_photos(matcher, raw_dir)
    if "b" in parts:
        sitl = run_sitl(matcher, raw_dir, args.subsample_sitl)

    summary = {"generated_utc": stamp, "wall_s": time.perf_counter() - start,
               "photos": photos, "sitl": sitl and sitl["per_condition"]}
    (out_dir / "summary.json").write_text(json.dumps(summary, indent=2, default=_json_default))
    write_report(out_dir, photos, sitl)
    LOGGER.info("wrote %s (wall %.0fs)", out_dir, time.perf_counter() - start)
    print(json.dumps(summary.get("sitl") or {}, indent=2, default=_json_default))
    return 0


if __name__ == "__main__":
    import sys

    sys.exit(main())
