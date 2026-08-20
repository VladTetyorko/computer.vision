"""cv-service/spikes/geo/false_convergence_gate.py

H0 deliverable (5): prototype §4.4 Change 1 ("the likelihood is the re-ranked geometric score,
not the raw embedding similarity") and show it refuses the real 771m-wrong Pexels/Maidan cell that
`harvested/sequence.py`'s filter -- fed the OLD raw-similarity field, exactly as it runs today --
converges on.

`harvested/sequence.py` is left untouched (a verbatim harvest of production `cv_service/geo/
sequence.py`, not a spike surface); this script drives its real, unmodified `SequenceLocalizer`
TWICE over the same 12 real Pexels frames with two different per-update measurement fields:

  - **control**: the field production computes today -- full per-tile cosine similarity
    (`region.index.descriptors @ query_descriptor`), exactly `LocalizeResult.descriptor`'s own
    documented downstream use (`localize.py#LocalizeResult.descriptor`'s docstring).
  - **geometric** (this wave's proposed fix): re-rank (`rerank.py`, xfeat backend -- the fastest
    bake-off matcher, adequate for this per-frame gate prototype) the top-k retrieval candidates,
    then build the SAME-SHAPE field from `inlier_ratio` (0..1, directly comparable to raw cosine
    similarity's range -- this wave's own invented mapping, same idiom as `localize.py
    #compute_confidence`'s own "this wave's own invention" precedent) at each scored candidate's
    row, `0.0` everywhere else (never geometrically evaluated this update == no evidence, not
    "confirmed absent").

Both runs use the SAME filter mechanics (`predict`/`update`, spread/persistence/diversity gates
already in `sequence.py`) -- only the measurement field differs. A frame-by-frame CONVERGED
verdict + its distance from the known 771m-wrong cell (and from true ground truth, 50.4502431N/
30.5240622E) is reported for both, so the difference is directly attributable to Change 1's
substitution, not a different filter.

No motion: the Pexels clip is a documented near-static hover (VISUAL-GEO-V2-PLAN.md, citing the
parked branch's own §12.14 finding) -- `predict(0, 0)` every step (diffusion-only), matching the
real footage's own motion profile rather than inventing displacement that was never there.

Usage:
    source spikes/geo/env.sh
    .venv/bin/python -m spikes.geo.false_convergence_gate
"""
from __future__ import annotations

import json
import logging
from pathlib import Path

import cv2
import numpy as np

from spikes.geo import matchers as matcher_registry
from spikes.geo import rerank as rerank_mod
from spikes.geo.harvested.encoder import build_encoder
from spikes.geo.harvested.localize import resolve_regions, localize
from spikes.geo.harvested.sequence import STATUS_CONVERGED, SequenceLocalizer
from spikes.geo.harvested.verify import load_region_tile_image
from spikes.geo.manifest import load_manifest
from spikes.geo.run_bakeoff import REGIONS_ROOT, haversine_m

LOGGER = logging.getLogger("spikes.geo.false_convergence_gate")
SPIKE_ROOT = Path(__file__).resolve().parent
RESULTS_PATH = SPIKE_ROOT / "results" / "false_convergence_gate.json"
MANIFEST_PATH = SPIKE_ROOT / "fixtures" / "maidan-video-frames" / "manifest.jsonl"
REGION_ID = "kyiv-maidan"
ENCODER_ID = "eigenplaces_r18_512"
K = 10
MATCHER_NAME = "xfeat"
# The known false-convergence cell (VISUAL-GEO-V2-PLAN.md, 00-existing-state.md §2/§12.14): the
# branch's own consistent top-1 wrong winner, similarity 0.43-0.48, ~775m off.
KNOWN_WRONG_CELL = "17/76646/44193"


def run_pass(region, encoder, matcher_handle, match_keypoints_fn, frames, geometric: bool) -> list[dict]:
    tile_ids = [t.tile_id for t in region.index.tiles]
    localizer = SequenceLocalizer.from_tile_ids(tile_ids, seed=20260819)

    def load_tile(candidate):
        return load_region_tile_image(REGIONS_ROOT / candidate.region_id, candidate.tile_id)

    rows = []
    for frame in frames:
        image = cv2.imread(str(frame.image_path), cv2.IMREAD_COLOR)
        if image is None:
            continue
        loc = localize(image, encoder=encoder, regions=[region], max_candidates=K)
        if loc.descriptor is None:
            continue

        if geometric:
            field = np.zeros(len(tile_ids), dtype=np.float64)
            if loc.candidates:
                result = rerank_mod.rerank(
                    matcher_handle, image, loc.candidates, load_tile, match_keypoints_fn, top_k=K
                )
                tile_row = {t.tile_id: i for i, t in enumerate(region.index.tiles)}
                for cs in result.scored:
                    row = tile_row.get(cs.candidate.tile_id)
                    if row is not None:
                        field[row] = cs.inlier_ratio
        else:
            field = region.index.descriptors.astype(np.float64) @ loc.descriptor.astype(np.float64)

        localizer.predict(0.0, 0.0)  # documented near-static hover, no odometry
        estimate = localizer.update(field)

        winning_tile = tile_ids[int(np.argmax(field))] if field.size else None
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
        })
    return rows


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    frames = load_manifest(MANIFEST_PATH)
    regions = resolve_regions(REGIONS_ROOT, REGION_ID)
    if not regions:
        raise SystemExit(f"region {REGION_ID} not built -- run build_regions.py first")
    region = regions[0]
    encoder = build_encoder(ENCODER_ID)
    matcher_handle = matcher_registry.build(MATCHER_NAME)
    match_keypoints_fn = matcher_registry.MATCH_FNS[MATCHER_NAME]

    # Known-wrong-cell coordinates, for the distance-from-that-cell column.
    known_wrong = next((t for t in region.index.tiles if t.tile_id == KNOWN_WRONG_CELL), None)

    LOGGER.info("control pass (raw similarity field, today's production behavior) ...")
    control_rows = run_pass(region, encoder, matcher_handle, match_keypoints_fn, frames, geometric=False)
    LOGGER.info("geometric pass (re-ranked inlier-ratio field, §4.4 Change 1 proposed) ...")
    geometric_rows = run_pass(region, encoder, matcher_handle, match_keypoints_fn, frames, geometric=True)

    for rows in (control_rows, geometric_rows):
        for row in rows:
            if known_wrong is not None:
                row["distance_from_known_wrong_cell_m"] = haversine_m(
                    row["lat"], row["lon"], known_wrong.lat, known_wrong.lon
                )

    def summarize(rows: list[dict]) -> dict:
        converged_rows = [r for r in rows if r["converged"]]
        converged_on_wrong_cell = [
            r for r in converged_rows
            if r["distance_from_known_wrong_cell_m"] is not None and r["distance_from_known_wrong_cell_m"] < 50.0
        ]
        return {
            "n_updates": len(rows),
            "n_converged": len(converged_rows),
            "n_converged_on_known_wrong_cell": len(converged_on_wrong_cell),
            "min_distance_from_truth_m_at_convergence": (
                min((r["distance_from_truth_m"] for r in converged_rows), default=None)
            ),
        }

    summary = {
        "known_wrong_cell": KNOWN_WRONG_CELL,
        "control_raw_similarity": {"summary": summarize(control_rows), "rows": control_rows},
        "geometric_rerank": {"summary": summarize(geometric_rows), "rows": geometric_rows},
    }
    RESULTS_PATH.parent.mkdir(parents=True, exist_ok=True)
    RESULTS_PATH.write_text(json.dumps(summary, indent=2))

    print(json.dumps({
        "known_wrong_cell": KNOWN_WRONG_CELL,
        "control_raw_similarity_summary": summary["control_raw_similarity"]["summary"],
        "geometric_rerank_summary": summary["geometric_rerank"]["summary"],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
