"""cv-service/spikes/geo/loftr_verify.py

Geometric-verification prototype for the deferred "stage 2" seam (docs/VISUAL-GEO-PLAN.md §0/§4's
`shouldVerify`, `GeoFrameRequest.request_verification`) -- re-rank top-k retrieval candidates by
dense local-feature matching instead of trusting the encoder's raw cosine-similarity order alone.
Uses LoFTR (github.com/zju3dv/LoFTR, permissive license, via `kornia` -- see the §12.4 prior-art
survey in the plan doc for why this was picked over LightGlue+SuperPoint, whose pretrained weights
are Magic Leap noncommercial-research-only).

**Query images**: real drone footage is still not available. Per an explicit go-ahead, this uses a
handful of REAL Google Maps satellite screenshots (Independence Square, Kyiv -- the same area every
other Wave 0 run has used) as mock query "photos from above, known position" -- captured
interactively via a few manual-style browser navigations to specific coordinates (not bulk tile
scraping), UI chrome cropped out, ground truth = the exact lat/lon navigated to. This is a genuine
cross-source test: Google's imagery vs. this harness's Esri-tile reference index, different vendor,
different capture date -- closer to the real "drone camera vs. satellite reference" domain gap than
matching Esri against itself the way the original smoke test did.

Usage:
    python3 -m spikes.geo.loftr_verify --manifest spikes/geo/results/gmaps-query/manifest.jsonl \
        --bbox 50.4480,30.5210,50.4525,30.5265 --zoom 17 --encoder eigenplaces --top-k 5
"""
from __future__ import annotations

import argparse
import json
import logging
from pathlib import Path
from typing import Optional

import cv2
import numpy as np
import torch

from spikes.geo.encoders import build_encoder, load_image
from spikes.geo.spike_index import ReferenceIndex, ReferenceItem, SearchResult, encode_items, haversine_to_truth
from spikes.geo.manifest import load_manifest
from spikes.geo.tiles import TileFetchSettings, fetch_bbox

LOGGER = logging.getLogger("spikes.geo.loftr_verify")
LOFTR_RESIZE = 480  # longest side, px -- LoFTR's own recommended working resolution range
CONFIDENCE_THRESHOLD = 0.5  # kornia LoFTR's own suggested default cutoff for "confident" matches


def build_matcher():
    import kornia.feature as KF
    return KF.LoFTR(pretrained="outdoor").eval()


def _prep_gray_tensor(image_bgr: np.ndarray) -> torch.Tensor:
    gray = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2GRAY)
    h, w = gray.shape
    scale = LOFTR_RESIZE / max(h, w)
    if scale < 1.0:
        gray = cv2.resize(gray, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_AREA)
    tensor = torch.from_numpy(gray).float()[None, None] / 255.0
    return tensor


def loftr_match_score(matcher, image_a_bgr: np.ndarray, image_b_bgr: np.ndarray) -> tuple[int, float]:
    """(confident_match_count, mean_confidence) between two images, LoFTR dense matching."""
    tensor_a = _prep_gray_tensor(image_a_bgr)
    tensor_b = _prep_gray_tensor(image_b_bgr)
    with torch.no_grad():
        out = matcher({"image0": tensor_a, "image1": tensor_b})
    confidence = out["confidence"].cpu().numpy() if len(out["confidence"]) else np.array([])
    confident = confidence[confidence >= CONFIDENCE_THRESHOLD]
    return int(confident.size), float(confident.mean()) if confident.size else 0.0


def rerank_with_loftr(
    matcher, query_image: np.ndarray, candidates: list[SearchResult],
) -> list[tuple[SearchResult, int, float]]:
    """Re-scores each candidate by LoFTR confident-match count against the query image, sorted
    best-first. Ties broken by mean confidence, then by the original retrieval rank (stable sort)."""
    scored = []
    for candidate in candidates:
        count, mean_conf = loftr_match_score(matcher, query_image, candidate.ref.load())
        scored.append((candidate, count, mean_conf))
    scored.sort(key=lambda t: (t[1], t[2]), reverse=True)
    return scored


def build_reference_index(bbox: tuple[float, float, float, float], zoom: int, encoder_name: str, cache_dir: Path) -> ReferenceIndex:
    lat1, lon1, lat2, lon2 = bbox
    settings = TileFetchSettings(cache_dir=cache_dir)
    tiles = fetch_bbox(lat1, lon1, lat2, lon2, zoom, settings)
    LOGGER.info("fetched %d reference tiles at zoom %d", len(tiles), zoom)
    items = [ReferenceItem(ref_id=t.tile_id, lat=t.lat, lon=t.lon, image_path=t.path) for t in tiles]
    encoder = build_encoder(encoder_name)
    descriptors, stats = encode_items(items, encoder)
    LOGGER.info("indexed %d reference descriptors (%.1fms/tile)", len(items), stats.mean_ms)
    return ReferenceIndex(items, descriptors), encoder


def main(argv: Optional[list[str]] = None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--bbox", type=str, required=True, help="lat1,lon1,lat2,lon2")
    parser.add_argument("--zoom", type=int, default=17)
    parser.add_argument("--encoder", type=str, default="eigenplaces")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--cache-dir", type=Path, default=Path(__file__).resolve().parent / "tile_cache")
    parser.add_argument("--out", type=Path, default=None)
    args = parser.parse_args(argv)

    lat1, lon1, lat2, lon2 = (float(x) for x in args.bbox.split(","))
    index, encoder = build_reference_index((lat1, lon1, lat2, lon2), args.zoom, args.encoder, args.cache_dir)

    LOGGER.info("loading LoFTR matcher (first run downloads pretrained weights)")
    matcher = build_matcher()

    queries = load_manifest(args.manifest)
    rows = []
    for query in queries:
        image = load_image(query.image_path)
        if image is None:
            LOGGER.warning("could not decode %s, skipping", query.image_path)
            continue
        descriptor = encoder.encode(image)
        candidates, _search_ms = index.search(descriptor, top_k=args.top_k)
        if not candidates:
            LOGGER.warning("no candidates for %s", query.image_path)
            continue

        plain_top1 = candidates[0]
        plain_err = haversine_to_truth(plain_top1, query.lat, query.lon)

        reranked = rerank_with_loftr(matcher, image, candidates)
        loftr_top1, top1_matches, top1_conf = reranked[0]
        loftr_err = haversine_to_truth(loftr_top1, query.lat, query.lon)

        row = {
            "query": str(query.image_path.name), "true_lat": query.lat, "true_lon": query.lon,
            "plain_top1_tile": plain_top1.ref.ref_id, "plain_top1_sim": plain_top1.similarity,
            "plain_err_m": plain_err,
            "loftr_top1_tile": loftr_top1.ref.ref_id, "loftr_top1_matches": top1_matches,
            "loftr_top1_conf": top1_conf, "loftr_err_m": loftr_err,
            "changed_pick": plain_top1.ref.ref_id != loftr_top1.ref.ref_id,
            "candidates": [
                {"tile": c.ref.ref_id, "sim": c.similarity, "loftr_matches": n, "loftr_conf": mc,
                 "err_m": haversine_to_truth(c, query.lat, query.lon)}
                for c, n, mc in reranked
            ],
        }
        rows.append(row)
        LOGGER.info(
            "%s: plain_err=%.1fm (tile %s, sim=%.3f) -> loftr_err=%.1fm (tile %s, %d matches) %s",
            row["query"], plain_err, plain_top1.ref.ref_id, plain_top1.similarity,
            loftr_err, loftr_top1.ref.ref_id, top1_matches,
            "[CHANGED]" if row["changed_pick"] else "",
        )

    print(f"\n{'query':<14} {'plain_err_m':>12} {'loftr_err_m':>12} {'changed':>8} {'loftr_matches':>14}")
    for row in rows:
        print(f"{row['query']:<14} {row['plain_err_m']:>12.1f} {row['loftr_err_m']:>12.1f} "
              f"{str(row['changed_pick']):>8} {row['loftr_top1_matches']:>14}")
    if rows:
        plain_mean = sum(r["plain_err_m"] for r in rows) / len(rows)
        loftr_mean = sum(r["loftr_err_m"] for r in rows) / len(rows)
        print(f"\nmean plain retrieval error: {plain_mean:.1f}m")
        print(f"mean LoFTR-verified error:  {loftr_mean:.1f}m")
        print(f"winner: {'LoFTR verification' if loftr_mean < plain_mean else 'plain retrieval (LoFTR did not help)'}")

    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(rows, indent=2))
        print(f"\nwrote {args.out}")
    return 0


if __name__ == "__main__":
    import sys
    sys.exit(main())
