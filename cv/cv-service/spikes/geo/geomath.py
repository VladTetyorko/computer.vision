"""Standard slippy-map (Web Mercator) tile math + haversine distance.

Stdlib-only, no third-party deps -- shared by tiles.py, index.py, metrics.py,
synth.py. Kept as its own module because every other spike module needs at
least one of these two functions and none of them should re-derive the math.
"""

from __future__ import annotations

import math
import random

EARTH_RADIUS_METERS = 6371000.0
TILE_PIXELS = 256


def deg2tile(lat_deg: float, lon_deg: float, zoom: int) -> tuple[int, int]:
    lat_rad = math.radians(lat_deg)
    n = 2.0**zoom
    x = int((lon_deg + 180.0) / 360.0 * n)
    y = int((1.0 - math.asinh(math.tan(lat_rad)) / math.pi) / 2.0 * n)
    return x, y


def tile2deg(x: float, y: float, zoom: int) -> tuple[float, float]:
    """Latitude/longitude of the NORTH-WEST corner of tile (x, y) at `zoom`.

    `x`/`y` accept fractional values on purpose -- augment.py's half-offset
    crops evaluate this at e.g. `x + 0.5` to get the geographic point at a
    half-tile pixel offset, not just whole-tile corners."""
    n = 2.0**zoom
    lon_deg = x / n * 360.0 - 180.0
    lat_rad = math.atan(math.sinh(math.pi * (1 - 2 * y / n)))
    lat_deg = math.degrees(lat_rad)
    return lat_deg, lon_deg


def tile_center(x: int, y: int, zoom: int) -> tuple[float, float]:
    lat_nw, lon_nw = tile2deg(x, y, zoom)
    lat_se, lon_se = tile2deg(x + 1, y + 1, zoom)
    return (lat_nw + lat_se) / 2.0, (lon_nw + lon_se) / 2.0


def tiles_for_bbox(lat1: float, lon1: float, lat2: float, lon2: float, zoom: int) -> list[tuple[int, int]]:
    """All (x, y) tile coords covering a bbox, corners given in any order."""
    south, north = min(lat1, lat2), max(lat1, lat2)
    west, east = min(lon1, lon2), max(lon1, lon2)
    x_nw, y_nw = deg2tile(north, west, zoom)
    x_se, y_se = deg2tile(south, east, zoom)
    x_lo, x_hi = min(x_nw, x_se), max(x_nw, x_se)
    y_lo, y_hi = min(y_nw, y_se), max(y_nw, y_se)
    return [(x, y) for y in range(y_lo, y_hi + 1) for x in range(x_lo, x_hi + 1)]


def bbox_from_points(points: list[tuple[float, float]], margin_meters: float) -> tuple[float, float, float, float]:
    """Smallest (lat1, lon1, lat2, lon2) bbox covering `points`, padded by
    `margin_meters` on every side. Used to auto-derive a reference region
    from a manifest's or telemetry log's own lat/lon extent when the caller
    doesn't pass --bbox explicitly."""
    lats = [p[0] for p in points]
    lons = [p[1] for p in points]
    south, north = min(lats), max(lats)
    west, east = min(lons), max(lons)
    lat_margin = margin_meters / 111_320.0
    mid_lat = (south + north) / 2.0
    lon_margin = margin_meters / (111_320.0 * max(math.cos(math.radians(mid_lat)), 1e-6))
    return south - lat_margin, west - lon_margin, north + lat_margin, east + lon_margin


def jitter_point(lat: float, lon: float, max_radius_meters: float, rng: random.Random) -> tuple[float, float]:
    """Random point within `max_radius_meters` of (lat, lon) -- simulates an
    approximate prior fix (a previous position, not necessarily exact)
    rather than always centering the §5 500m prior disc dead-on truth.
    Equirectangular approximation, fine at the sub-km offsets this is used for."""
    if max_radius_meters <= 0:
        return lat, lon
    radius = rng.uniform(0, max_radius_meters)
    angle = rng.uniform(0, 2 * math.pi)
    north_m = radius * math.cos(angle)
    east_m = radius * math.sin(angle)
    dlat = north_m / 111_320.0
    dlon = east_m / (111_320.0 * max(math.cos(math.radians(lat)), 1e-6))
    return lat + dlat, lon + dlon


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlambda / 2) ** 2
    return 2 * EARTH_RADIUS_METERS * math.asin(min(1.0, math.sqrt(a)))
