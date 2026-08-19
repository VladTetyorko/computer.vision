"""cv-service/spikes/geo/bakeoff_matchers.py

Exploratory spike, NOT product code -- "let's try all of them on open sources and see what
we can have." §12.8 of docs/VISUAL-GEO-PLAN.md surveyed candidate geometric-verification
matchers (literature only, nothing measured against our own data). This script actually runs
each candidate against the two REAL Wave-0 test sets already used for the shipped LoFTR
numbers (§12.6/§12.7), through the identical retrieval->rerank pipeline `loftr_verify.py`
established, so every candidate's number -- including a fresh LoFTR run -- comes from the same
harness/environment.

Candidates (build_matcher() + match_score(matcher, image_a_bgr, image_b_bgr) -> (count,
mean_confidence), same contract as loftr_verify.py):
  - loftr           kornia KF.LoFTR(pretrained="outdoor") -- re-run of the shipped baseline.
  - disk_lightglue  kornia-native KF.DISK + KF.LightGlueMatcher("disk"). The transformers hub
                    checkpoint (ETH-CVG/lightglue_disk) needed `trust_remote_code` for its
                    custom "disk" config type, which current transformers (5.x, post-PR #45122
                    "Remove remote code execution") refuses to load -- kornia's own DISK +
                    LightGlueMatcher avoids that entirely and is the same DISK (cvlab-epfl,
                    Apache-2.0) + LightGlue (cvg, Apache-2.0) combination.
  - efficientloftr  HuggingFace `transformers` AutoModelForKeypointMatching, checkpoint
                    zju-community/efficientloftr (Apache 2.0, the official port).
  - roma            romatch.roma_outdoor -- full RoMa v1 (DINOv2 ViT-L backbone). CPU-only,
                    amp_dtype forced to float32 (fp16 autocast is a CUDA-only path upstream).
  - tiny_roma       romatch.tiny_roma_v1_outdoor -- XFeat-based lightweight fallback, only run
                    if full RoMa proves impractically slow/heavy on this box (explicit
                    go-ahead in the task brief).

Two real test sets (identical framing to §12.6, reusing already-committed real data, no
re-downloading):
  - kyiv     4 real Google Maps screenshots (cv-service/demo/samples/) vs. real Esri reference
             tiles for the Maidan Nezalezhnosti cluster already in tile_cache/17/.
  - auair    40 real AU-AIR drone photos (spikes/geo/results/auair-manifest/manifest_subset40
             .jsonl) vs. real Esri reference tiles for the Aarhus/Denmark cluster already in
             tile_cache/17/. (The original §12.6 run used a 32-frame subsample of the same
             real AU-AIR source; this run uses the 40-row manifest now available -- same
             dataset and reference tiles, a slightly different exact query subset, not a new
             data source.)

Reference indices are built directly from the already-cached tiles (no network tile fetch --
`tiles.py`'s fetch_bbox is deliberately NOT used here) so a bakeoff run never depends on Esri
being reachable and never touches tiles outside the two clusters this repo already fetched.

Usage:
    python -m spikes.geo.bakeoff_matchers --candidates loftr,disk_lightglue,efficientloftr,roma \
        --out-dir spikes/geo/results/bakeoff-matchers-<UTC-timestamp>
"""
from __future__ import annotations

import argparse
import json
import logging
import time
import traceback
from dataclasses import dataclass, field
from pathlib import Path
from statistics import mean, median
from typing import Callable, Optional

import cv2
import numpy as np
import torch

from spikes.geo.encoders import Encoder, build_encoder, load_image
from spikes.geo.geomath import tile_center
from spikes.geo.spike_index import ReferenceIndex, ReferenceItem, SearchResult, encode_items, haversine_to_truth
from spikes.geo.manifest import QueryFrame, load_manifest

LOGGER = logging.getLogger("spikes.geo.bakeoff_matchers")

REPO_ROOT = Path(__file__).resolve().parents[2]  # cv-service/
TILE_CACHE = Path(__file__).resolve().parent / "tile_cache"
TILE_ZOOM = 17
KYIV_TILE_X_RANGE = range(76646, 76653)   # Maidan Nezalezhnosti cluster
AUAIR_TILE_X_RANGE = range(69242, 69250)  # Aarhus/Denmark cluster
KYIV_MANIFEST_PATH = REPO_ROOT / "demo" / "samples" / "manifest.json"
AUAIR_MANIFEST_PATH = REPO_ROOT / "spikes" / "geo" / "results" / "auair-manifest" / "manifest_subset40.jsonl"

CONFIDENCE_THRESHOLD = 0.5  # same cutoff loftr_verify.py uses -- kept identical across every candidate
RESIZE = 480  # longest side, px -- loftr_verify.py's own working resolution, reused as the default
TOP_K = 5
ENCODER_NAME = "eigenplaces"


# --------------------------------------------------------------------------- test-set loading


def load_kyiv_manifest() -> list[QueryFrame]:
    """demo/samples/manifest.json uses its own {"file", "true_lat", "true_lon", "label"} schema
    (not manifest.py's {"path","lat","lon"} jsonl schema) -- converted in memory here rather
    than writing a converted copy into demo/, which is real product-adjacent fixture data this
    spike must not touch."""
    data = json.loads(KYIV_MANIFEST_PATH.read_text())
    frames = []
    for i, row in enumerate(data, start=1):
        frames.append(
            QueryFrame(
                query_id=f"kyiv-{i}",
                image_path=KYIV_MANIFEST_PATH.parent / row["file"],
                lat=float(row["true_lat"]),
                lon=float(row["true_lon"]),
            )
        )
    return frames


def load_auair_manifest() -> list[QueryFrame]:
    return load_manifest(AUAIR_MANIFEST_PATH)


def build_reference_index_from_cache(x_range: range, encoder_name: str) -> tuple[ReferenceIndex, Encoder]:
    """Builds the reference index directly from already-cached tiles under tile_cache/17/ --
    no network tile fetch, no dependency on Esri being reachable, and no risk of pulling in
    tiles from outside the intended cluster (tile_cache/17/ holds both Kyiv and Denmark tiles
    side by side, keyed only by x -- see the module docstring)."""
    items: list[ReferenceItem] = []
    zoom_dir = TILE_CACHE / str(TILE_ZOOM)
    for x_dir in sorted(zoom_dir.iterdir()):
        if not x_dir.is_dir():
            continue
        x = int(x_dir.name)
        if x not in x_range:
            continue
        for tile_path in sorted(x_dir.glob("*.jpg")):
            y = int(tile_path.stem)
            lat, lon = tile_center(x, y, TILE_ZOOM)
            items.append(ReferenceItem(ref_id=f"{TILE_ZOOM}/{x}/{y}", lat=lat, lon=lon, image_path=tile_path))
    if not items:
        raise RuntimeError(f"no cached tiles found for x in {x_range} under {zoom_dir}")
    encoder = build_encoder(encoder_name)
    descriptors, stats = encode_items(items, encoder)
    LOGGER.info("indexed %d reference tiles from cache (%.1fms/tile, encoder=%s)", len(items), stats.mean_ms, encoder_name)
    return ReferenceIndex(items, descriptors), encoder


# --------------------------------------------------------------------------- shared preprocessing


def _resize_longest_side_bgr(image_bgr: np.ndarray, target: int = RESIZE) -> np.ndarray:
    h, w = image_bgr.shape[:2]
    scale = target / max(h, w)
    if scale < 1.0:
        return cv2.resize(image_bgr, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_AREA)
    return image_bgr


def _to_pil_rgb(image_bgr: np.ndarray):
    from PIL import Image

    return Image.fromarray(cv2.cvtColor(image_bgr, cv2.COLOR_BGR2RGB))


# --------------------------------------------------------------------------- candidate: LoFTR (kornia) -- re-run of the shipped baseline


def build_loftr():
    from spikes.geo.loftr_verify import build_matcher as _build

    return _build()


def loftr_match_score(matcher, image_a_bgr: np.ndarray, image_b_bgr: np.ndarray) -> tuple[int, float]:
    from spikes.geo.loftr_verify import loftr_match_score as _score

    return _score(matcher, image_a_bgr, image_b_bgr)


# --------------------------------------------------------------------------- candidate: DISK + LightGlue (kornia-native)

_DISK_MAX_KEYPOINTS = 2048


def build_disk_lightglue():
    import kornia.feature as KF

    disk = KF.DISK.from_pretrained("depth").eval()
    matcher = KF.LightGlueMatcher("disk").eval()
    return disk, matcher


def disk_lightglue_match_score(matcher_pair, image_a_bgr: np.ndarray, image_b_bgr: np.ndarray) -> tuple[int, float]:
    import kornia.feature as KF

    disk, lightglue = matcher_pair

    def _prep(image_bgr: np.ndarray) -> torch.Tensor:
        rgb = cv2.cvtColor(_resize_longest_side_bgr(image_bgr), cv2.COLOR_BGR2RGB)
        return torch.from_numpy(rgb).float().permute(2, 0, 1)[None] / 255.0

    tensor_a, tensor_b = _prep(image_a_bgr), _prep(image_b_bgr)
    with torch.no_grad():
        feats_a = disk(tensor_a, n=_DISK_MAX_KEYPOINTS, pad_if_not_divisible=True)[0]
        feats_b = disk(tensor_b, n=_DISK_MAX_KEYPOINTS, pad_if_not_divisible=True)[0]
        if feats_a.n < 2 or feats_b.n < 2:
            return 0, 0.0
        lafs_a = KF.laf_from_center_scale_ori(feats_a.keypoints[None])
        lafs_b = KF.laf_from_center_scale_ori(feats_b.keypoints[None])
        hw1 = (tensor_a.shape[-2], tensor_a.shape[-1])
        hw2 = (tensor_b.shape[-2], tensor_b.shape[-1])
        scores, matches = lightglue(feats_a.descriptors, feats_b.descriptors, lafs_a, lafs_b, hw1, hw2)
    scores_np = scores.reshape(-1).cpu().numpy() if len(scores) else np.array([])
    confident = scores_np[scores_np >= CONFIDENCE_THRESHOLD]
    return int(confident.size), float(confident.mean()) if confident.size else 0.0


# --------------------------------------------------------------------------- candidate: EfficientLoFTR (transformers)


def build_efficientloftr():
    from transformers import AutoImageProcessor, AutoModelForKeypointMatching

    processor = AutoImageProcessor.from_pretrained("zju-community/efficientloftr")
    model = AutoModelForKeypointMatching.from_pretrained("zju-community/efficientloftr").eval()
    return processor, model


def efficientloftr_match_score(matcher_pair, image_a_bgr: np.ndarray, image_b_bgr: np.ndarray) -> tuple[int, float]:
    processor, model = matcher_pair
    img_a = _to_pil_rgb(_resize_longest_side_bgr(image_a_bgr))
    img_b = _to_pil_rgb(_resize_longest_side_bgr(image_b_bgr))
    images = [img_a, img_b]
    inputs = processor(images, return_tensors="pt")
    with torch.no_grad():
        outputs = model(**inputs)
    image_sizes = [[(img.height, img.width) for img in images]]
    results = processor.post_process_keypoint_matching(outputs, image_sizes, threshold=CONFIDENCE_THRESHOLD)
    scores = results[0]["matching_scores"]
    count = int(len(scores))
    mean_conf = float(scores.mean()) if count else 0.0
    return count, mean_conf


# --------------------------------------------------------------------------- candidate: RoMa (full, DINOv2 backbone)

# NOTE on match counting: an earlier version of this used matcher.sample(warp, certainty),
# RoMa's own recommended pre-RANSAC sampling step -- but RegressionMatcher.sample's
# "threshold_balanced" mode (the default) CLAMPS every certainty above its own sample_thresh
# (0.05) to 1.0 before sampling, so every sampled point reports confidence=1.0 regardless of
# how confident the match actually was (measured directly: mean_conf=1.000 on a real pair,
# which is the clamp artifact, not a real signal). Using the RAW dense certainty map RoMa's
# own match() already returns (post-sigmoid, genuine per-pixel probability, see its source)
# instead avoids that artifact.


def build_roma():
    from romatch import roma_outdoor

    torch.set_float32_matmul_precision("highest")  # required by RoMa, see its own README
    # use_custom_corr=True (RoMa's own default) requires a compiled `local_corr` CUDA/C++
    # extension from the optional `romatch[fused-local-corr]` extra, which is not installed
    # (and not meaningfully CPU-buildable -- it's a fused kernel aimed at GPU inference).
    # use_custom_corr=False routes to RoMa's own pure-PyTorch fallback
    # (`shitty_native_torch_local_corr`, their own name for it in local_correlation.py) --
    # slower than a real CUDA kernel would be, but the only path that runs at all on this
    # CPU-only box, and still real RoMa (same weights, same architecture).
    return roma_outdoor(device="cpu", amp_dtype=torch.float32, use_custom_corr=False)


def roma_match_score(matcher, image_a_bgr: np.ndarray, image_b_bgr: np.ndarray) -> tuple[int, float]:
    img_a, img_b = _to_pil_rgb(image_a_bgr), _to_pil_rgb(image_b_bgr)
    _warp, certainty = matcher.match(img_a, img_b, device="cpu")
    conf_np = certainty.detach().cpu().numpy().reshape(-1)  # raw dense per-pixel probability, no clamping
    confident = conf_np[conf_np >= CONFIDENCE_THRESHOLD]
    return int(confident.size), float(confident.mean()) if confident.size else 0.0


# --------------------------------------------------------------------------- candidate: tiny RoMa (XFeat-based fallback)


def build_tiny_roma():
    import torch as _torch

    from romatch import tiny_roma_v1_outdoor

    # tiny_roma_v1_outdoor()'s own default XFeat load omits trust_repo=True, which hangs
    # waiting on stdin in a non-interactive shell (same class of issue encoders.py's
    # VprHubEncoder already documents for torch.hub + gmberton repos) -- pre-build XFeat
    # ourselves with trust_repo=True (this is throwaway spike code run by the operator
    # themselves against the paper authors' own repo, not a service accepting untrusted
    # input) and pass it in via tiny_roma_v1_outdoor's own xfeat= parameter.
    xfeat = _torch.hub.load("verlab/accelerated_features", "XFeat", pretrained=True, top_k=4096, trust_repo=True).net
    return tiny_roma_v1_outdoor(device="cpu", xfeat=xfeat)


def tiny_roma_match_score(matcher, image_a_bgr: np.ndarray, image_b_bgr: np.ndarray) -> tuple[int, float]:
    # Same raw-certainty approach as roma_match_score -- .sample()'s "threshold_balanced"
    # clamp artifact applies here too (tiny.py's TinyRoMa.sample is the same logic).
    img_a, img_b = _to_pil_rgb(image_a_bgr), _to_pil_rgb(image_b_bgr)
    _warp, certainty = matcher.match(img_a, img_b)
    conf_np = certainty.detach().cpu().numpy().reshape(-1) if hasattr(certainty, "detach") else np.asarray(certainty).reshape(-1)
    confident = conf_np[conf_np >= CONFIDENCE_THRESHOLD]
    return int(confident.size), float(confident.mean()) if confident.size else 0.0


# --------------------------------------------------------------------------- registry


@dataclass(frozen=True)
class Candidate:
    key: str
    label: str
    build_matcher: Callable[[], object]
    match_score: Callable[[object, np.ndarray, np.ndarray], tuple[int, float]]
    license: str
    notes: str


CANDIDATES: dict[str, Candidate] = {
    c.key: c
    for c in [
        Candidate("loftr", "LoFTR (kornia, outdoor)", build_loftr, loftr_match_score,
                  "permissive (kornia)", "shipped baseline, re-run in this harness for apples-to-apples"),
        Candidate("disk_lightglue", "DISK + LightGlue (kornia-native)", build_disk_lightglue, disk_lightglue_match_score,
                  "Apache-2.0 (DISK) + Apache-2.0 (LightGlue)",
                  f"max {_DISK_MAX_KEYPOINTS} DISK keypoints/image; transformers hub checkpoint "
                  "(ETH-CVG/lightglue_disk) needed trust_remote_code for its custom 'disk' config "
                  "type, refused by transformers>=5.x post-PR#45122 -- used kornia's native "
                  "KF.DISK + KF.LightGlueMatcher instead, same underlying weights/license"),
        Candidate("efficientloftr", "EfficientLoFTR (transformers, zju-community)", build_efficientloftr, efficientloftr_match_score,
                  "Apache-2.0", "official HF port, AutoModelForKeypointMatching"),
        Candidate("roma", "RoMa v1 (full, DINOv2 ViT-L backbone)", build_roma, roma_match_score,
                  "MIT (+ DINOv2 Apache-2.0)",
                  "match-count = raw dense per-pixel certainty map thresholded at confidence>=0.5 "
                  "(one candidate match per output pixel -- RoMa is a genuinely dense matcher), "
                  "NOT comparable in raw magnitude to the sparse/semi-dense candidates' counts; "
                  "use_custom_corr=False (pure-PyTorch fallback, the fused CUDA kernel extra isn't "
                  "installed/CPU-buildable) -- measured ~86-115s/pair on this box, ~40-50x LoFTR's "
                  "~2s/pair, so the full test-set sweep below uses tiny_roma instead (see its row)"),
        Candidate("tiny_roma", "tiny_roma_v1 (XFeat-based, RoMa repo)", build_tiny_roma, tiny_roma_match_score,
                  "MIT", "lightweight fallback -- only meaningful as a fallback if full RoMa is impractical here"),
    ]
}


# --------------------------------------------------------------------------- evaluation loop


@dataclass
class QueryResult:
    query_id: str
    true_lat: float
    true_lon: float
    plain_top1_ref: str
    plain_sim: float
    plain_err_m: float
    verified_top1_ref: str
    verified_matches: int
    verified_conf: float
    verified_err_m: float
    changed_pick: bool
    pair_ms: list[float] = field(default_factory=list)  # one entry per candidate re-ranked (top_k pairs)


def run_test_set(
    test_name: str,
    candidate: Candidate,
    matcher,
    index: ReferenceIndex,
    encoder: Encoder,
    queries: list[QueryFrame],
    top_k: int = TOP_K,
) -> list[QueryResult]:
    results: list[QueryResult] = []
    for query in queries:
        image = load_image(query.image_path)
        if image is None:
            LOGGER.warning("[%s/%s] could not decode %s, skipping", test_name, candidate.key, query.image_path)
            continue
        descriptor = encoder.encode(image)
        candidates_sr, _search_ms = index.search(descriptor, top_k=top_k)
        if not candidates_sr:
            continue

        plain_top1 = candidates_sr[0]
        plain_err = haversine_to_truth(plain_top1, query.lat, query.lon)

        scored = []
        pair_ms = []
        for c in candidates_sr:
            t0 = time.perf_counter()
            count, mean_conf = candidate.match_score(matcher, image, c.ref.load())
            pair_ms.append((time.perf_counter() - t0) * 1000.0)
            scored.append((c, count, mean_conf))
        scored.sort(key=lambda t: (t[1], t[2]), reverse=True)
        verified_top1, top1_matches, top1_conf = scored[0]
        verified_err = haversine_to_truth(verified_top1, query.lat, query.lon)

        results.append(
            QueryResult(
                query_id=query.query_id,
                true_lat=query.lat,
                true_lon=query.lon,
                plain_top1_ref=plain_top1.ref.ref_id,
                plain_sim=plain_top1.similarity,
                plain_err_m=plain_err,
                verified_top1_ref=verified_top1.ref.ref_id,
                verified_matches=top1_matches,
                verified_conf=top1_conf,
                verified_err_m=verified_err,
                changed_pick=plain_top1.ref.ref_id != verified_top1.ref.ref_id,
                pair_ms=pair_ms,
            )
        )
        LOGGER.info(
            "[%s/%s] %s: plain=%.1fm -> verified=%.1fm (%d matches, %.0fms/pair) %s",
            test_name, candidate.key, query.query_id, plain_err, verified_err, top1_matches,
            mean(pair_ms) if pair_ms else 0.0, "[CHANGED]" if results[-1].changed_pick else "",
        )
    return results


def summarize(results: list[QueryResult]) -> dict:
    if not results:
        return {"n": 0}
    plain_errs = [r.plain_err_m for r in results]
    verified_errs = [r.verified_err_m for r in results]
    all_pair_ms = [ms for r in results for ms in r.pair_ms]
    match_counts = [r.verified_matches for r in results]
    return {
        "n": len(results),
        "plain_mean_err_m": mean(plain_errs),
        "verified_mean_err_m": mean(verified_errs),
        "plain_recall_100m": sum(1 for e in plain_errs if e <= 100.0) / len(results),
        "verified_recall_100m": sum(1 for e in verified_errs if e <= 100.0) / len(results),
        "changed_pick_count": sum(1 for r in results if r.changed_pick),
        "match_count_mean": mean(match_counts),
        "match_count_median": median(match_counts),
        "match_count_min": min(match_counts),
        "match_count_max": max(match_counts),
        "pair_ms_mean": mean(all_pair_ms) if all_pair_ms else 0.0,
        "pair_ms_median": median(all_pair_ms) if all_pair_ms else 0.0,
        "pair_ms_p90": sorted(all_pair_ms)[int(0.9 * (len(all_pair_ms) - 1))] if all_pair_ms else 0.0,
        "pair_count": len(all_pair_ms),
    }


# --------------------------------------------------------------------------- report


def render_report(out_dir: Path, run_results: dict) -> str:
    lines = [
        "# Matcher bakeoff -- real candidates on real data",
        "",
        "Exploratory spike (docs/VISUAL-GEO-PLAN.md's directive: \"let's try all of them on open "
        "sources and see what we can have\"), NOT product code. Every number below was measured in "
        "this run, on this CPU-only (no CUDA) box, through the identical retrieval->rerank harness "
        "`loftr_verify.py` established -- LoFTR's own row here is a fresh re-run, not the §12.6 numbers "
        "copied over, so every candidate is apples-to-apples.",
        "",
        "**Baseline to beat (§12.6/§12.7, already measured, not re-derived):** Kyiv near-nadir "
        "plain retrieval 284.8m -> LoFTR-verified 69.7m (100-273 matches every time); AU-AIR "
        "plain 101.7m/recall@100m=0.66 -> unconditional LoFTR-verified 188.6m/0.47 (worse) -> "
        "gated at match-count>=12, recovered to 93.7m at 0.66 recall (this gate ships as "
        "`cv_service/geo/verify.py`'s `DEFAULT_MATCH_FLOOR=12`).",
        "",
        "## Comparison table",
        "",
        "| Candidate | Test set | n | Plain mean err | Verified mean err | Recall@100m (plain -> verified) | "
        "Match count (mean / median / range) | ms/pair (mean / median / p90) | License |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for candidate_key, per_test in run_results.items():
        candidate = CANDIDATES[candidate_key]
        for test_name, payload in per_test.items():
            if payload.get("status") != "ok":
                lines.append(
                    f"| {candidate.label} | {test_name} | -- | -- | -- | -- | -- | -- | "
                    f"**{payload.get('status', 'unknown').upper()}: {payload.get('error', '')}** |"
                )
                continue
            s = payload["summary"]
            lines.append(
                f"| {candidate.label} | {test_name} | {s['n']} | {s['plain_mean_err_m']:.1f}m | "
                f"{s['verified_mean_err_m']:.1f}m | {s['plain_recall_100m']:.2f} -> {s['verified_recall_100m']:.2f} | "
                f"{s['match_count_mean']:.1f} / {s['match_count_median']:.0f} / [{s['match_count_min']}-{s['match_count_max']}] | "
                f"{s['pair_ms_mean']:.0f} / {s['pair_ms_median']:.0f} / {s['pair_ms_p90']:.0f} | {candidate.license} |"
            )
    lines += ["", "## Per-candidate notes", ""]
    for candidate_key, per_test in run_results.items():
        candidate = CANDIDATES[candidate_key]
        lines.append(f"### {candidate.label}")
        lines.append("")
        lines.append(f"License: {candidate.license}. {candidate.notes}")
        lines.append("")
        for test_name, payload in per_test.items():
            if payload.get("status") != "ok":
                lines.append(f"- **{test_name}**: {payload.get('status')} -- {payload.get('error', '')}")
            else:
                s = payload["summary"]
                lines.append(
                    f"- **{test_name}**: n={s['n']}, plain {s['plain_mean_err_m']:.1f}m -> "
                    f"verified {s['verified_mean_err_m']:.1f}m, recall@100m "
                    f"{s['plain_recall_100m']:.2f}->{s['verified_recall_100m']:.2f}, "
                    f"{s['changed_pick_count']}/{s['n']} picks changed, "
                    f"{s['pair_ms_mean']:.0f}ms/pair mean over {s['pair_count']} pairs"
                )
        lines.append("")
    report = "\n".join(lines)
    (out_dir / "report.md").write_text(report)
    return report


# --------------------------------------------------------------------------- main


def main(argv: Optional[list[str]] = None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--candidates", type=str, default=",".join(CANDIDATES.keys()))
    parser.add_argument("--test-sets", type=str, default="kyiv,auair")
    parser.add_argument("--top-k", type=int, default=TOP_K)
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args(argv)

    args.out_dir.mkdir(parents=True, exist_ok=True)
    candidate_keys = [k.strip() for k in args.candidates.split(",") if k.strip()]
    test_set_names = [t.strip() for t in args.test_sets.split(",") if t.strip()]

    LOGGER.info("building reference indices from cache (eigenplaces encoder)...")
    test_sets = {}
    if "kyiv" in test_set_names:
        index, encoder = build_reference_index_from_cache(KYIV_TILE_X_RANGE, ENCODER_NAME)
        test_sets["kyiv"] = (index, encoder, load_kyiv_manifest())
    if "auair" in test_set_names:
        index, encoder = build_reference_index_from_cache(AUAIR_TILE_X_RANGE, ENCODER_NAME)
        test_sets["auair"] = (index, encoder, load_auair_manifest())

    run_results: dict[str, dict] = {}
    for key in candidate_keys:
        candidate = CANDIDATES[key]
        LOGGER.info("=== candidate: %s ===", candidate.label)
        run_results[key] = {}
        try:
            t0 = time.perf_counter()
            matcher = candidate.build_matcher()
            build_s = time.perf_counter() - t0
            LOGGER.info("%s: build_matcher took %.1fs", key, build_s)
        except Exception as exc:  # noqa: BLE001 - honesty requirement: report failure, don't abort the whole run
            LOGGER.error("%s: build_matcher FAILED: %s", key, exc)
            traceback.print_exc()
            for test_name in test_sets:
                run_results[key][test_name] = {"status": "failed_to_build", "error": str(exc)}
            continue

        for test_name, (index, encoder, queries) in test_sets.items():
            try:
                t0 = time.perf_counter()
                results = run_test_set(test_name, candidate, matcher, index, encoder, queries, top_k=args.top_k)
                elapsed = time.perf_counter() - t0
                summary = summarize(results)
                LOGGER.info("%s/%s: done in %.1fs, %s", key, test_name, elapsed, summary)
                run_results[key][test_name] = {"status": "ok", "summary": summary}
                raw_path = args.out_dir / f"{key}__{test_name}.json"
                raw_path.write_text(json.dumps([r.__dict__ for r in results], indent=2))
            except Exception as exc:  # noqa: BLE001
                LOGGER.error("%s/%s FAILED: %s", key, test_name, exc)
                traceback.print_exc()
                run_results[key][test_name] = {"status": "failed", "error": str(exc)}

        # free the matcher before building the next one -- some of these are large (RoMa/DINOv2)
        del matcher

    report = render_report(args.out_dir, run_results)
    print("\n" + report)
    (args.out_dir / "run_results_raw.json").write_text(
        json.dumps(
            {k: {t: {kk: vv for kk, vv in p.items()} for t, p in v.items()} for k, v in run_results.items()},
            indent=2,
        )
    )
    print(f"\nwrote {args.out_dir}")
    return 0


if __name__ == "__main__":
    import sys

    sys.exit(main())
