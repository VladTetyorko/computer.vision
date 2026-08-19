"""cv-service/spikes/geo/mosaic.py

H0b shared helper: a candidate reference tile's 3x3 neighbourhood, stitched into one mosaic image
straight off a region's own `tiles/` directory (no network -- every H0 region already fetched its
full bbox). Used by both `calibrate_instrument.py` (H0b task 1, self-match case iv/v) and
`mosaic_rerank.py` (H0b task 2, mosaic-candidate re-rank) so both scripts share one mosaic
georeference convention: `sitl_render.Mosaic.origin_x`/`origin_y` are in TILE-PIXEL space
(`x_lo * 256`), so `origin_x / 256` is exactly the top-left tile's own `x` (integer, when the
mosaic is a full 3x3 block) -- the `(tile_x, tile_y)` origin `pose.fit_homography_pose` needs.

Deliberately requires a FULL 3x3 (9/9 neighbour tiles present) -- a partial mosaic would still
have `sitl_render.build_mosaic`'s own bounding-box shrink to whatever tiles exist, which would
silently move the origin tile away from `(cx-1, cy-1)` and corrupt the pose-fit's georeference.
Callers treat an incomplete neighbourhood (an edge-of-region candidate) as "unavailable", the same
convention `rerank.score_candidate` already uses for a missing single tile image.
"""
from __future__ import annotations

from pathlib import Path
from typing import Optional

from spikes.geo import sitl_render
from spikes.geo.geomath import tile_center
from spikes.geo.tiles import Tile

TILE_PIXELS = 256


def neighbourhood_tiles(region_dir: Path, cx: int, cy: int, zoom: int) -> list[Tile]:
    """The (up to) 3x3 neighbourhood around tile (cx, cy), loaded from `region_dir/tiles/*.jpg`
    on disk -- whichever neighbours actually exist (edge-of-region tiles have fewer than 9)."""
    tiles: list[Tile] = []
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            x, y = cx + dx, cy + dy
            path = region_dir / "tiles" / f"{zoom}_{x}_{y}.jpg"
            if not path.is_file():
                continue
            lat, lon = tile_center(x, y, zoom)
            tiles.append(Tile(zoom=zoom, x=x, y=y, lat=lat, lon=lon, path=path))
    return tiles


def has_full_neighbourhood(region_dir: Path, cx: int, cy: int, zoom: int) -> bool:
    return len(neighbourhood_tiles(region_dir, cx, cy, zoom)) == 9


def build_tile_mosaic(region_dir: Path, cx: int, cy: int, zoom: int) -> Optional["sitl_render.Mosaic"]:
    """3x3 mosaic centred on tile (cx, cy) -- `None` unless all 9 neighbours are present on disk
    (see module docstring for why a partial mosaic is refused rather than black-padded here)."""
    tiles = neighbourhood_tiles(region_dir, cx, cy, zoom)
    if len(tiles) != 9:
        return None
    return sitl_render.build_mosaic(tiles, zoom, TILE_PIXELS)


def origin_tile_xy(mosaic: "sitl_render.Mosaic") -> tuple[int, int]:
    """The mosaic's own top-left tile (x, y) -- the `(tile_x, tile_y)` origin
    `pose.fit_homography_pose` needs to interpret mosaic-pixel coordinates."""
    return int(round(mosaic.origin_x / TILE_PIXELS)), int(round(mosaic.origin_y / TILE_PIXELS))
