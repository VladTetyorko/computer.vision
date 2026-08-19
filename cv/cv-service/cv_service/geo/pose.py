"""Homography pose extraction for verified geolocation fixes (docs/VISUAL-GEO-PLAN.md §6
Wave 6a, gated on §12.11's spike evidence -- gate PASSED 2026-08-07).

Production port of `spikes/geo/homography_pose.py`'s `fit_homography_pose` + helpers: given the
LoFTR keypoint correspondences `cv_service/geo/verify.py` now keeps (it used to throw them away),
fit a robust MAGSAC homography query-image -> winning-reference-tile in the tile's 256px
Web-Mercator PIXEL frame and extract:

- a **refined sub-tile position** (query image center through H -> fractional tile coords ->
  lat/lon via `cv_service.geo.index._tile_nw_corner`, the exact `tile2deg` inverse) -- §12.11
  measured this taking SITL-nadir median error 92.5m (tile-center quantization) to 4.8m under
  the shipped gate;
- **yaw / compass heading** (image up-vector through H's local Jacobian at the image center) --
  0.4-0.5 deg median on both yaw testbeds;
- the **ground-footprint quad** (4 query corners through H) with sanity flags
  (finite/convex/orientation-preserving/plausible-area -- §12.11: sanity-passing fixes were good
  84% vs 18% without);
- the **MAGSAC inlier count** -- the promotion gate as of Wave 6a (§12.11: precision 1.0 at >=8
  inliers vs 0.83 for raw match count; `Settings.geo_verify_inlier_floor`).

Geometry notes (deliberate, stated rather than hidden -- ported from the spike's own docstring):

- The homography is fitted in the winning tile's 256px Web-Mercator pixel frame, not a local ENU
  meter frame. Web Mercator is conformal, so ANGLES in tile-pixel space are true compass angles
  (yaw needs no ENU), and positions/footprint corners go through the exact fractional-tile ->
  lat/lon inverse, so no Mercator scale error enters the reported lat/lon either. Only metric
  AREA needs a meter conversion, done locally (equirectangular at the quad's own latitude).
- Keypoints are expected in each image's NATIVE pixel frame. When `verify.py` conditioned the
  query (de-rotation + GSD rescale, §12.11's load-bearing discovery), it maps keypoints back
  through the inverse conditioning transform BEFORE they reach this module -- which folds the
  heading/scale prior back into the fitted H, so the yaw extracted here is already the
  compass-corrected absolute yaw (mathematically the composition H_native = H_conditioned . T
  -- the same "residual + heading prior" arithmetic the spike applied explicitly, only expressed
  through the transform instead of added afterwards) and the footprint is the TRUE camera
  footprint (native frame corners), not the conditioned crop's.
- Yaw convention: tile pixel space has +x = east, +y = south. The image up-vector (0, -1) mapped
  through H's local Jacobian at the image center gives a tile-space direction (dx, dy); compass
  heading = atan2(east, north) = atan2(dx, -dy), degrees mod 360.

Pure `cv2`/`numpy` + stdlib -- importable and testable WITHOUT torch/kornia (unlike `verify.py`);
never imports `cv_pb2` (`cv_service/grpc/servicers.py` is the sole wire translation point, the
same discipline every `cv_service.geo` module follows).
"""

from __future__ import annotations

import math
import time
from dataclasses import dataclass, field
from typing import Optional

import cv2
import numpy as np

from cv_service.geo.index import _tile_nw_corner

TILE_PIXELS = 256  # the frozen reference-tile edge (§3.1's ReferencePackChunk layout)
RANSAC_REPROJ_PX = 3.0  # MAGSAC reprojection threshold, in tile-pixel (destination) space
MIN_MATCHES_FOR_FIT = 4  # findHomography's hard minimum
DEFAULT_INLIER_FLOOR = 8  # §12.11's precision-1.0 point; mirrors Settings.geo_verify_inlier_floor
# Footprint-area plausibility bounds, m^2 (spike values, unchanged). A zoom-17 tile at the
# latitudes this was measured on is roughly 200x200m = 4e4 m^2; a sane query footprint is
# somewhere between a 10x10m close-up and a ~1km^2 wide view. Outside that, the homography is
# extrapolating garbage.
AREA_MIN_M2 = 100.0
AREA_MAX_M2 = 1.0e6


def parse_tile_id(tile_id: str) -> Optional[tuple[int, int, int]]:
    """`"17/76648/44197"` -> `(17, 76648, 44197)` (zoom, x, y) -- the `<z>/<x>/<y>` shape
    `cv_service.geo.index.ReferenceTileMeta.tile_id` carries. `None` on anything malformed --
    callers skip pose extraction rather than crash on a stray id (forgiving contract, same
    posture as `index.parse_tile_filename`)."""
    parts = tile_id.split("/")
    if len(parts) != 3:
        return None
    try:
        zoom, x, y = (int(p) for p in parts)
    except ValueError:
        return None
    if zoom < 0 or x < 0 or y < 0:
        return None
    return zoom, x, y


def tilepx_to_latlon(px: float, py: float, tile_x: int, tile_y: int, zoom: int) -> tuple[float, float]:
    """Tile-pixel coordinates (256px frame of tile (x, y)) -> lat/lon, via the exact fractional
    tile inverse (`index._tile_nw_corner` accepts fractional x/y on purpose -- its own docstring
    kept that capability for exactly this). Plain-`float` results even for numpy-scalar inputs
    (the spike leaked `np.float64`/`np.bool_` into its JSON rows and needed a serializer shim --
    production keeps its wire types clean at the source instead)."""
    lat, lon = _tile_nw_corner(float(tile_x + px / TILE_PIXELS), float(tile_y + py / TILE_PIXELS), zoom)
    return float(lat), float(lon)


def homography_jacobian(H: np.ndarray, x: float, y: float) -> Optional[np.ndarray]:
    """2x2 Jacobian of the projective map at (x, y) -- the honest 'linear part' of H at a point
    (for a projective transform the linear part varies across the image). `None` when the point
    sits on H's line at infinity (w ~ 0)."""
    w = H[2, 0] * x + H[2, 1] * y + H[2, 2]
    if abs(w) < 1e-12:
        return None
    X = (H[0, 0] * x + H[0, 1] * y + H[0, 2]) / w
    Y = (H[1, 0] * x + H[1, 1] * y + H[1, 2]) / w
    return np.array(
        [
            [(H[0, 0] - X * H[2, 0]) / w, (H[0, 1] - X * H[2, 1]) / w],
            [(H[1, 0] - Y * H[2, 0]) / w, (H[1, 1] - Y * H[2, 1]) / w],
        ]
    )


def quad_is_convex(corners_px: np.ndarray) -> bool:
    """All cross products of consecutive edges share one sign (and none degenerate)."""
    signs = []
    for i in range(4):
        a = corners_px[(i + 1) % 4] - corners_px[i]
        b = corners_px[(i + 2) % 4] - corners_px[(i + 1) % 4]
        cross = a[0] * b[1] - a[1] * b[0]
        if abs(cross) < 1e-9:
            return False
        signs.append(cross > 0)
    return all(signs) or not any(signs)


def quad_area_m2(corners_latlon: list[tuple[float, float]]) -> float:
    """Shoelace area over a local equirectangular meter projection at the quad's own latitude --
    exact enough at footprint scale, and it removes Mercator inflation."""
    lat0, lon0 = corners_latlon[0]
    cos_lat = max(math.cos(math.radians(lat0)), 1e-6)
    pts = [
        ((lon - lon0) * 111_320.0 * cos_lat, (lat - lat0) * 111_320.0)
        for lat, lon in corners_latlon
    ]
    area = 0.0
    for i in range(4):
        x1, y1 = pts[i]
        x2, y2 = pts[(i + 1) % 4]
        area += x1 * y2 - x2 * y1
    return abs(area) / 2.0


@dataclass(frozen=True)
class PoseResult:
    """Outcome of one homography fit. `ok=False` (with `reason`) when no model could be fitted at
    all; `ok=True` carries position/yaw/footprint plus the per-flag `sanity` dict -- callers gate
    on `inlier_count` (promotion) and `sanity_ok` (whether the refined position/yaw/footprint are
    trustworthy enough to REPLACE the tile-center fix, §12.11's 84%-vs-18% finding)."""

    ok: bool
    reason: str = ""
    lat: Optional[float] = None
    lon: Optional[float] = None
    yaw_deg: Optional[float] = None
    footprint_latlon: Optional[list[tuple[float, float]]] = None  # TL, TR, BR, BL of the image
    footprint_area_m2: Optional[float] = None
    sanity: dict = field(default_factory=dict)
    sanity_ok: bool = False
    inlier_count: int = 0
    inlier_ratio: float = 0.0
    fit_ms: float = 0.0


def fit_homography_pose(
    kp_query: np.ndarray,
    kp_tile: np.ndarray,
    query_width: int,
    query_height: int,
    tile_x: int,
    tile_y: int,
    zoom: int,
) -> PoseResult:
    """MAGSAC homography query-native-px -> tile-px, then position / yaw / footprint.

    `kp_query`/`kp_tile` are [N, 2] float correspondences in each image's NATIVE pixel frame
    (`verify.py` guarantees this, including undoing its own conditioning transform). Timing
    (`fit_ms`) covers everything AFTER the LoFTR pass -- §12.11 measured 0.5-0.8ms median,
    effectively free on top of the match cost already paid."""
    start = time.perf_counter()
    count = int(kp_query.shape[0])
    if count < MIN_MATCHES_FOR_FIT:
        return PoseResult(
            ok=False,
            reason=f"only {count} matches (< {MIN_MATCHES_FOR_FIT})",
            fit_ms=(time.perf_counter() - start) * 1000.0,
        )

    src = np.asarray(kp_query, dtype=np.float64).reshape(-1, 1, 2)
    dst = np.asarray(kp_tile, dtype=np.float64).reshape(-1, 1, 2)
    H, mask = cv2.findHomography(src, dst, cv2.USAC_MAGSAC, RANSAC_REPROJ_PX)
    if H is None or mask is None:
        return PoseResult(
            ok=False,
            reason="findHomography returned no model",
            fit_ms=(time.perf_counter() - start) * 1000.0,
        )
    inlier_count = int(mask.sum())
    inlier_ratio = inlier_count / count

    center = np.array([[query_width / 2.0, query_height / 2.0]], dtype=np.float64)
    corners = np.array(
        [[0.0, 0.0], [query_width, 0.0], [query_width, query_height], [0.0, query_height]],
        dtype=np.float64,
    )
    projected = cv2.perspectiveTransform(np.vstack([center, corners]).reshape(-1, 1, 2), H).reshape(-1, 2)
    center_px, corners_px = projected[0], projected[1:]

    if not bool(np.isfinite(projected).all()):
        return PoseResult(
            ok=False,
            reason="non-finite projection (near-degenerate H)",
            inlier_count=inlier_count,
            inlier_ratio=inlier_ratio,
            sanity={"corners_finite": False},
            fit_ms=(time.perf_counter() - start) * 1000.0,
        )

    lat, lon = tilepx_to_latlon(center_px[0], center_px[1], tile_x, tile_y, zoom)

    # Yaw: image up-vector (0, -1) through H's local Jacobian at the image center.
    yaw_deg = None
    jacobian = homography_jacobian(H, float(center[0, 0]), float(center[0, 1]))
    det_positive = False
    if jacobian is not None:
        det_positive = bool(np.linalg.det(jacobian) > 0)
        up = jacobian @ np.array([0.0, -1.0])
        east, north = up[0], -up[1]  # tile px: +x = east, +y = south
        if abs(east) > 1e-12 or abs(north) > 1e-12:
            yaw_deg = math.degrees(math.atan2(east, north)) % 360.0
            if yaw_deg >= 360.0:  # float rounding on a tiny negative angle can yield exactly 360.0
                yaw_deg = 0.0

    footprint_latlon = [
        tilepx_to_latlon(px, py, tile_x, tile_y, zoom) for px, py in corners_px
    ]
    area_m2 = float(quad_area_m2(footprint_latlon))
    sanity = {
        "corners_finite": True,
        "convex": bool(quad_is_convex(corners_px)),
        "orientation_preserving": det_positive,
        "area_plausible": bool(AREA_MIN_M2 <= area_m2 <= AREA_MAX_M2),
    }
    return PoseResult(
        ok=True,
        lat=lat,
        lon=lon,
        yaw_deg=yaw_deg,
        footprint_latlon=footprint_latlon,
        footprint_area_m2=area_m2,
        sanity=sanity,
        sanity_ok=all(sanity.values()),
        inlier_count=inlier_count,
        inlier_ratio=inlier_ratio,
        fit_ms=(time.perf_counter() - start) * 1000.0,
    )
