"""§12.13 regression matrix — re-run everything against the FIXED production code.

Empirical gate for the §12.13 shipped-code fixes (docs/VISUAL-GEO-PLAN.md):

  A. re-CALIBRATE all five on-disk region tile sets (kyiv-maidan + boryspil-fields +
     kyiv-nw-forest + btn-road-x + vas-road) through the fixed production
     `cv_service.geo.orchestrator.build_region_index` (fixed `calibrate.sweep_thresholds`);
  B. re-run the linear-terrain + homogeneous-terrain QUERY SETS (same seeds/construction as the
     original spikes) through the REAL fixed `GeolocationServicer.LocalizeStream` (real
     eigenplaces encoder + real LoFTR + real MAGSAC + the §12.13 promotion policy);
  C. maidan §12.11 smoke: the demo samples (sample-2 in particular) must still produce their
     refined fixes through the same production path;
  D. replay every RECORDED raw pose fit (linear-terrain sliding probes + queries,
     homogeneous-terrain queries, both homography-pose runs) through the OLD vs NEW promotion
     rule (offline — directed probes can't be re-steered through localize()).

Reads regions from the prior spike results dirs + demo/data (COPIES them — originals untouched).
Writes spikes/geo/results/regression-1213-<ts>/. Spike-only; production cv_service/ is exercised,
never modified.

Usage (from cv-service/, venv active):
  python -m spikes.geo.regression_1213
"""

from __future__ import annotations

import json
import shutil
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path

import cv2
import numpy as np

from spikes.geo.geomath import haversine_m

SPIKE_ROOT = Path(__file__).resolve().parent
CV_ROOT = SPIKE_ROOT.parent.parent
RESULTS = SPIKE_ROOT / "results"

LT_RUN = RESULTS / "linear-terrain-20260808T081337Z"
HT_RUN = RESULTS / "homogeneous-terrain-20260808T002058Z"
HP_RUNS = [RESULTS / "homography-pose-20260807T200452Z", RESULTS / "homography-pose-20260807T205449Z"]

REGION_SOURCES = {
    "kyiv-maidan": CV_ROOT / "demo" / "data" / "kyiv-maidan",
    "boryspil-fields": HT_RUN / "regions" / "boryspil-fields",
    "kyiv-nw-forest": HT_RUN / "regions" / "kyiv-nw-forest",
    "btn-road-x": LT_RUN / "regions" / "btn-road-x",
    "vas-road": LT_RUN / "regions" / "vas-road",
}

# The previously persisted/recorded calibrations (the "before" column).
BEFORE_CALIBRATION = {
    "kyiv-maidan": {"accept_similarity": 0.87, "accept_margin": 0.0, "holdout_recall_at_1": 0.0,
                    "holdout_median_error_meters": 194.5},
    "boryspil-fields": {"accept_similarity": 1.0, "accept_margin": 0.2, "holdout_recall_at_1": 0.0,
                        "holdout_median_error_meters": 333.3},
    "kyiv-nw-forest": {"accept_similarity": 1.0, "accept_margin": 0.2, "holdout_recall_at_1": 0.0,
                       "holdout_median_error_meters": 702.2},
    "btn-road-x": {"accept_similarity": 0.44, "accept_margin": 0.01, "holdout_recall_at_1": 0.0,
                   "holdout_median_error_meters": 278.2},
    "vas-road": {"accept_similarity": 1.0, "accept_margin": 0.2, "holdout_recall_at_1": 0.0,
                 "holdout_median_error_meters": 438.2},
}


def stage_regions(regions_dir: Path) -> None:
    regions_dir.mkdir(parents=True, exist_ok=True)
    for rid, src in REGION_SOURCES.items():
        dst = regions_dir / rid
        if dst.exists():
            shutil.rmtree(dst)
        (dst / "tiles").mkdir(parents=True)
        shutil.copyfile(src / "region.json", dst / "region.json")
        for tile in sorted((src / "tiles").glob("*.jpg")):
            shutil.copyfile(tile, dst / "tiles" / tile.name)
        print(f"[stage] {rid}: {len(list((dst / 'tiles').glob('*.jpg')))} tiles from {src}")


def recalibrate(regions_dir: Path, encoder) -> dict:
    from cv_service.geo.orchestrator import build_region_index

    out = {}
    for rid in REGION_SOURCES:
        stats = build_region_index(
            regions_dir / rid, encoder,
            on_phase=lambda *_: None, is_cancelled=lambda: False,
        )
        out[rid] = {
            "accept_similarity": stats.accept_similarity,
            "accept_margin": stats.accept_margin,
            "holdout_recall_at_1": stats.holdout_recall_at_1,
            "holdout_median_error_meters": stats.holdout_median_error_meters,
        }
        print(f"[calibrate] {rid}: {out[rid]}")
    return out


# ---------------------------------------------------------------- production-path streaming

def _bgr24_request(cv_pb2, image: np.ndarray, region_id: str, sequence: int):
    h, w = image.shape[:2]
    return cv_pb2.GeoFrameRequest(
        stream_id=f"reg-{region_id}", sequence=sequence, timestamp_millis=sequence,
        width=w, height=h, encoding=cv_pb2.ImageEncoding.IMAGE_ENCODING_BGR24,
        data=image.tobytes(), region_id=region_id, request_verification=True,
    )


class _Ctx:
    def is_active(self):  # pragma: no cover - spike shim
        return True

    def add_callback(self, cb):
        pass

    def abort(self, code, details):
        raise RuntimeError(f"abort: {code} {details}")


def stream_queries(servicer, cv_pb2, region_id: str, queries) -> list[dict]:
    """queries: list of (query_id, gt_lat, gt_lon, image). One LocalizeStream call per query --
    a single call's `_StreamReader` is latest-wins (deliberately drops frames when fed faster
    than it processes, same as DetectStream), so a pre-built synchronous batch would be sampled,
    not measured. One-frame calls keep every query measured while still exercising the exact
    production per-frame pipeline."""
    responses = []
    for i, (_qid, _lat, _lon, image) in enumerate(queries):
        request = _bgr24_request(cv_pb2, image, region_id, i + 1)
        result = list(servicer.LocalizeStream(iter([request]), _Ctx()))
        assert len(result) == 1
        responses.append(result[0])
    rows = []
    for (qid, gt_lat, gt_lon, _image), resp in zip(queries, responses):
        status = cv_pb2.GeoFixStatus.Name(resp.status)
        fix_err = None
        if resp.HasField("fix"):
            fix_err = haversine_m(gt_lat, gt_lon, resp.fix.latitude, resp.fix.longitude)
        rows.append({
            "query_id": qid, "status": status, "verified": bool(resp.verified),
            "match_count": int(resp.verification_match_count),
            "inlier_count": int(resp.verification_inlier_count),
            "fix_error_m": fix_err, "message": resp.message,
        })
    return rows


def summarize_rows(rows: list[dict]) -> dict:
    fixes = [r for r in rows if r["status"] == "GEO_FIX"]
    return {
        "queries": len(rows),
        "low_texture": sum(1 for r in rows if r["status"] == "GEO_LOW_TEXTURE"),
        "no_fix": sum(1 for r in rows if r["status"] == "GEO_NO_FIX"),
        "fixes": len(fixes),
        "verified_fixes": sum(1 for r in fixes if r["verified"]),
        "fix_errors_m": sorted(round(r["fix_error_m"], 1) for r in fixes if r["fix_error_m"] is not None),
        "false_fixes_gt100m": sum(1 for r in fixes if (r["fix_error_m"] or 0) > 100.0),
        "false_fixes_gt300m": sum(1 for r in fixes if (r["fix_error_m"] or 0) > 300.0),
    }


# ------------------------------------------------------------------------- recorded replay

def new_rule_promotes(never_accept_region: bool, sanity_ok: bool, inliers: int, floor: int) -> bool:
    return (not never_accept_region) and sanity_ok and inliers >= floor


def replay_recorded(after_calibration: dict, promotion_floor: int) -> dict:
    from cv_service.geo.calibrate import is_never_accept

    def region_never_accept(rid: str) -> bool:
        cal = after_calibration[rid]
        return is_never_accept(cal["accept_similarity"], cal["holdout_recall_at_1"])

    rows = []
    lt = json.loads((LT_RUN / "results.json").read_text())
    for rid, v in lt.items():
        for kind in ("queries", "probes"):
            for q in v[kind]:
                if not q.get("pose_ok"):
                    continue
                err = q.get("pose_refined_error_m")
                base = q.get("top1_error_m")
                eff = err if q.get("pose_sanity_ok") and err not in (None, -1.0) else base
                rows.append({
                    "source": f"lt/{rid}/{kind}", "id": q["query_id"], "region": rid,
                    "inliers": q["pose_inliers"], "sanity_ok": bool(q.get("pose_sanity_ok")),
                    "err_m": eff, "retrieval_accepted": bool(q.get("accepted")),
                })
    ht = json.loads((HT_RUN / "results.json").read_text())
    for rid, v in ht.items():
        for q in v["queries"]:
            if not q.get("pose_ok"):
                continue
            err = q.get("pose_refined_error_m")
            base = q.get("top1_error_m")
            eff = err if q.get("pose_sanity_ok") and err not in (None, -1.0) else base
            rows.append({
                "source": f"ht/{rid}", "id": q["query_id"], "region": rid,
                "inliers": q["pose_inliers"], "sanity_ok": bool(q.get("pose_sanity_ok")),
                "err_m": eff, "retrieval_accepted": bool(q.get("accepted")),
            })
    # homography-pose raw runs: SITL/gmaps flew over the maidan region; retrieval accept status
    # was not recorded -> treated as promotion-relevant (worst case). AU-AIR's region is not on
    # disk -> conservatively treated as a NON-never-accept region (worst case for the floor).
    for run in HP_RUNS:
        for f in sorted(run.glob("raw/*/*.json")):
            d = json.loads(f.read_text())
            pose = d.get("pose") or {}
            if not pose.get("ok"):
                continue
            err = d.get("refined_err_m")
            eff = err if pose.get("sanity_ok") and err is not None else d.get("baseline_err_m")
            rows.append({
                "source": f"hp/{d['dataset']}", "id": d["query"],
                "region": "kyiv-maidan" if d["dataset"].startswith(("sitl", "gmaps")) else None,
                "inliers": pose["inlier_count"], "sanity_ok": bool(pose.get("sanity_ok")),
                "err_m": eff, "retrieval_accepted": None,
            })

    replay = []
    for r in rows:
        if r["retrieval_accepted"] is True:
            continue  # refinement path -- promotion rules don't apply
        old = r["inliers"] >= 8  # the OLD shipped promotion rule: inlier floor alone
        na = region_never_accept(r["region"]) if r["region"] else False
        new = new_rule_promotes(na, r["sanity_ok"], r["inliers"], promotion_floor)
        replay.append({**r, "old_promotes": old, "new_promotes": new, "region_never_accept": na})

    def bucket(pred):
        return [x for x in replay if pred(x)]

    false_old = bucket(lambda x: x["old_promotes"] and (x["err_m"] or 0) > 100.0)
    false_new = bucket(lambda x: x["new_promotes"] and (x["err_m"] or 0) > 100.0)
    strong_correct = bucket(lambda x: x["inliers"] >= 67 and (x["err_m"] or 1e9) <= 100.0 and x["sanity_ok"])
    strong_lost = [x for x in strong_correct if not x["new_promotes"]]
    return {
        "records_replayed": len(replay),
        "old_false_promotions": [
            {k: x[k] for k in ("source", "id", "inliers", "sanity_ok", "err_m")} for x in false_old
        ],
        "new_false_promotions": [
            {k: x[k] for k in ("source", "id", "inliers", "sanity_ok", "err_m")} for x in false_new
        ],
        "strong_correct_67plus": len(strong_correct),
        "strong_correct_67plus_lost": [
            {k: x[k] for k in ("source", "id", "inliers", "err_m", "region_never_accept")}
            for x in strong_lost
        ],
        "correct_promotions_old": sum(
            1 for x in replay if x["old_promotes"] and (x["err_m"] or 1e9) <= 100.0
        ),
        "correct_promotions_new": sum(
            1 for x in replay if x["new_promotes"] and (x["err_m"] or 1e9) <= 100.0
        ),
    }


# --------------------------------------------------------------------------------- main

def main() -> int:
    from cv_service.config import Settings
    from cv_service.geo.encoder import build_encoder
    from cv_service.geo import verify as geo_verify
    from cv_service.grpc.servicers import GeolocationServicer, cv_pb2

    out_dir = RESULTS / f"regression-1213-{time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())}"
    regions_dir = out_dir / "regions"
    stage_regions(regions_dir)

    encoder = build_encoder("eigenplaces_r18_512")
    after_calibration = recalibrate(regions_dir, encoder)

    servicer = GeolocationServicer(
        data_dir=regions_dir,
        encoder_factory=lambda _eid, _dev: encoder,
        verify_matcher_factory=geo_verify.build_matcher,
    )

    # -- B: spike query sets through the fixed production path -------------------------
    from spikes.geo import homogeneous_terrain as ht_spike
    from spikes.geo import linear_terrain as lt_spike

    query_summaries, query_rows = {}, {}
    for rid, builder in (
        ("boryspil-fields", ht_spike.build_queries),
        ("kyiv-nw-forest", ht_spike.build_queries),
        ("btn-road-x", lt_spike.build_queries),
        ("vas-road", lt_spike.build_queries),
    ):
        built = builder(rid, regions_dir / rid)
        queries = [(rec.query_id, rec.gt_lat, rec.gt_lon, img) for rec, img in built]
        rows = stream_queries(servicer, cv_pb2, rid, queries)
        query_rows[rid] = rows
        query_summaries[rid] = summarize_rows(rows)
        print(f"[queries] {rid}: {json.dumps(query_summaries[rid])}")

    # -- C: maidan demo-sample smoke (§12.11 sample-2 refined fix) ----------------------
    samples_dir = CV_ROOT / "demo" / "samples"
    manifest = json.loads((samples_dir / "manifest.json").read_text())
    sample_queries = []
    for entry in manifest:
        img = cv2.imread(str(samples_dir / entry["file"]), cv2.IMREAD_COLOR)
        sample_queries.append((entry["file"], entry["true_lat"], entry["true_lon"], img))
    sample_rows = stream_queries(servicer, cv_pb2, "kyiv-maidan", sample_queries)
    for r in sample_rows:
        print(f"[maidan-sample] {r['query_id']}: {r['status']} verified={r['verified']} "
              f"inliers={r['inlier_count']} err={r['fix_error_m']}")

    # -- D: recorded-evidence replay through the old vs new promotion rule --------------
    promotion_floor = Settings.from_env().geo_verify_promotion_inlier_floor
    replay = replay_recorded(after_calibration, promotion_floor)
    print(f"[replay] {json.dumps(replay, indent=1)}")

    payload = {
        "promotion_floor": promotion_floor,
        "calibration_before": BEFORE_CALIBRATION,
        "calibration_after": after_calibration,
        "query_summaries": query_summaries,
        "query_rows": query_rows,
        "maidan_samples": sample_rows,
        "recorded_replay": replay,
    }
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "results.json").write_text(json.dumps(payload, indent=1))
    print(f"results -> {out_dir / 'results.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
