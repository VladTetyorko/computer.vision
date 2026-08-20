"""cv-service/spikes/geo/build_regions.py

H0 deliverable (2): rebuild the Pozniaky + Maidan reference regions from LIVE Esri z17 tiles
(VISUAL-GEO-V2-PLAN.md §5 H0) -- through the exact production path (`spikes/geo/harvested/
{orchestrator,index,calibrate,pack,encoder}.py`, harvested verbatim from `cv_service/geo/`), not a
shortcut. Bboxes are the parked branch's own (`git show feat/visual-geo:cv-service/demo/
build_region_pozniaky.py` and `build_region.py` -- VISUAL-GEO-V2-PLAN.md's own pointer, "find them
in the branch's ... spike scripts"):

  kyiv-pozniaky: z17, x 76681..76694, y 44220..44237 (14x18=252 tiles) -- bbox 50.3775,30.6133 /
                 50.4069,30.6488 (two OSM anchors + ~1km margin; branch's own script).
  kyiv-maidan:   z17, x 76646..76652, y 44193..44199 (7x7=49 tiles) -- the SAME tile rectangle the
                 branch's `demo/build_region.py` used (and every §12.6/§12.14 number in the
                 research pack was measured against): NOT re-centered on the monument. The branch
                 separately found (`docs/VISUAL-GEO-PLAN.md` §12.15) that a monument-centered
                 rebuild self-calibrates to NEVER_ACCEPT at both 500m/800m half-widths -- rebuilding
                 the offset original is what reproduces the branch's own measured numbers (0/12
                 top-1, the 771m false-convergence cell) rather than a different, harder region.

Usage (from cv-service/, env sourced):
    source spikes/geo/env.sh
    .venv/bin/python -m spikes.geo.build_regions --region pozniaky
    .venv/bin/python -m spikes.geo.build_regions --region maidan
"""
from __future__ import annotations

import argparse
import json
import logging
import shutil
import time
from pathlib import Path

from spikes.geo.harvested import orchestrator
from spikes.geo.harvested.calibrate import is_never_accept
from spikes.geo.harvested.encoder import build_encoder
from spikes.geo.tiles import TileFetchSettings, fetch_tiles

LOGGER = logging.getLogger("spikes.geo.build_regions")
SPIKE_ROOT = Path(__file__).resolve().parent
REGIONS_ROOT = SPIKE_ROOT / "regions"  # gitignored -- runtime artefact, see .gitignore
ZOOM = 17
ENCODER_ID = "eigenplaces_r18_512"  # O11's shipped default -- this cycle changes ranking, not recall

REGION_SPECS = {
    "pozniaky": {
        "region_id": "kyiv-pozniaky",
        "x_range": range(76681, 76695),  # 76681..76694 inclusive
        "y_range": range(44220, 44238),  # 44220..44237 inclusive
        "bbox": (50.3775, 30.6133, 50.4069, 30.6488),  # south, west, north, east
    },
    "maidan": {
        "region_id": "kyiv-maidan",
        "x_range": range(76646, 76653),  # 76646..76652 inclusive
        "y_range": range(44193, 44200),  # 44193..44199 inclusive
        "bbox": None,  # derived from the tile rectangle itself below
    },
}


def build_region(key: str, *, force: bool = False) -> dict:
    spec = REGION_SPECS[key]
    region_dir = REGIONS_ROOT / spec["region_id"]
    tiles_dir = region_dir / "tiles"
    if region_dir.exists():
        if not force:
            LOGGER.info("%s already built at %s (pass --force to rebuild)", spec["region_id"], region_dir)
        else:
            shutil.rmtree(region_dir)
    tiles_dir.mkdir(parents=True, exist_ok=True)

    xy_list = [(x, y) for x in spec["x_range"] for y in spec["y_range"]]
    settings = TileFetchSettings(cache_dir=SPIKE_ROOT / "tile_cache", max_tiles=max(4096, len(xy_list) + 10))
    t0 = time.perf_counter()
    tiles = fetch_tiles(xy_list, ZOOM, settings)
    fetch_s = time.perf_counter() - t0
    LOGGER.info("%s: fetched %d/%d tiles in %.1fs", spec["region_id"], len(tiles), len(xy_list), fetch_s)
    if not tiles:
        raise RuntimeError(f"no tiles fetched for {spec['region_id']} -- Esri unreachable?")

    for tile in tiles:
        dest = tiles_dir / f"{tile.zoom}_{tile.x}_{tile.y}.jpg"
        if not dest.exists():
            shutil.copyfile(tile.path, dest)

    lats = [t.lat for t in tiles]
    lons = [t.lon for t in tiles]
    region_json = {
        "regionId": spec["region_id"],
        "zoom": ZOOM,
        "tileCount": len(tiles),
        "north": max(lats), "south": min(lats), "east": max(lons), "west": min(lons),
        "builtFromLiveEsri": True,
        "sourceXRange": [spec["x_range"].start, spec["x_range"].stop - 1],
        "sourceYRange": [spec["y_range"].start, spec["y_range"].stop - 1],
    }
    (region_dir / "region.json").write_text(json.dumps(region_json, indent=2))

    encoder = build_encoder(ENCODER_ID)

    def on_phase(phase: str, done: int, total: int, message: str = "") -> None:
        LOGGER.info("%s: %s %d/%d %s", spec["region_id"], phase, done, total, message)

    t0 = time.perf_counter()
    stats = orchestrator.build_region_index(region_dir, encoder, on_phase=on_phase, is_cancelled=lambda: False)
    build_s = time.perf_counter() - t0
    never_accept = is_never_accept(stats.accept_similarity, stats.holdout_recall_at_1)
    LOGGER.info(
        "%s: built index in %.1fs -- tiles=%d accept_similarity=%.3f accept_margin=%.3f "
        "holdout_recall@1=%.3f holdout_median_err_m=%.1f never_accept=%s",
        spec["region_id"], build_s, stats.tile_count, stats.accept_similarity, stats.accept_margin,
        stats.holdout_recall_at_1, stats.holdout_median_error_meters, never_accept,
    )
    summary = {
        "regionId": spec["region_id"],
        "zoom": ZOOM,
        "tileCount": stats.tile_count,
        "descriptorCount": stats.descriptor_count,
        "encoderId": stats.encoder_id,
        "acceptSimilarity": stats.accept_similarity,
        "acceptMargin": stats.accept_margin,
        "holdoutRecallAt1": stats.holdout_recall_at_1,
        "holdoutMedianErrorMeters": stats.holdout_median_error_meters,
        "isNeverAccept": bool(never_accept),
        "fetchSeconds": round(fetch_s, 1),
        "buildSeconds": round(build_s, 1),
        "bbox": {"north": region_json["north"], "south": region_json["south"], "east": region_json["east"], "west": region_json["west"]},
    }
    (region_dir / "build_summary.json").write_text(json.dumps(summary, indent=2))
    return summary


def main(argv=None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--region", choices=sorted(REGION_SPECS), required=True)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args(argv)
    summary = build_region(args.region, force=args.force)
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
