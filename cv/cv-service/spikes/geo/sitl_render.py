"""SITL-simulated query-frame synthesis (docs/VISUAL-GEO-PLAN.md §5 Wave 0
extension -- see spikes/geo/README.md's "SITL-simulated eval" section for
the full writeup of what this is and, importantly, what it is NOT).

Input: a telemetry CSV in infra/sitl/log_telemetry.py's format --
`timestamp_ms,lat,lon,alt_m,heading_deg,groundspeed_mps,sysid` -- captured
from a REAL ArduCopter SITL flight (genuine flight dynamics: GUIDED takeoff,
CIRCLE loiter, real heading/altitude/groundspeed changes).

What this module does NOT have: a camera. SITL pushes flight telemetry, not
video. So instead of real pixels, each sampled telemetry row is turned into
a synthetic query frame by cropping/warping the SAME real Kyiv satellite
tiles this repo's harness already fetches (tiles.py) -- centered on that
row's real lat/lon, sized from real altitude, rotated/warped by real
heading. This is meaningfully harder than the existing --smoke-test (which
crops the identical, already-tile-aligned imagery with only mild synthetic
perturbation): here the crop center is essentially never tile-aligned, the
rotation sweeps a full 360 degrees as the vehicle circles, and (oblique
mode) the camera geometry is a genuine forward-and-down pinhole projection,
not a same-plane re-crop. But the query pixels are still literally sourced
from the same imagery as the reference index -- no sensor noise, no
lighting/season/temporal gap. See run_sitl_eval.sh and the README for the
full honesty banner this feeds into the report.

Two render modes, selected by --pitch-degrees:
  - Nadir (pitch=0, default): straight-down square crop, sized from
    altitude via `footprint_m = 2 * altitude_m * tan(fov_degrees / 2)`,
    rotated so screen-up = the vehicle's current heading (direction of
    travel) -- the simplest defensible convention for a forward-facing FPV
    camera radioing back "what's ahead is up". Mainly exercises rotation
    and crop-boundary robustness, NOT cross-view generalization (a nadir
    crop of a nadir tile is still a same-viewpoint match).
  - Oblique (pitch>0, e.g. 40-45): a forward-and-down pinhole camera view
    of the same ground patch, built as a homography
    (cv2.getPerspectiveTransform + cv2.warpPerspective) from a trapezoidal
    ground footprint (near edge close/narrow, far edge distant/wide) to a
    rectangular output frame. THIS is the config that actually tests
    cross-view (oblique-drone vs nadir-satellite) generalization on real
    imagery -- the open question §5 configuration 'c' asks about.

Camera model (oblique mode): `pitch_degrees` is the camera boresight's tilt
away from nadir (straight down), toward the horizon, in the direction of
travel. pitch=0 => nadir (straight down, boresight = (0,0,-1)). pitch=90 =>
boresight horizontal, looking at the horizon in the direction of travel.
A symmetric square FOV (`--fov-degrees`, both horizontal and vertical) is
assumed -- a simplification appropriate for a spike, not a real sensor's
non-square FOV. With this convention, "top of frame" happens to be the
forward/direction-of-travel side of the ground footprint at every pitch
(verified algebraically: the image's "up" axis, cross(boresight, right),
reduces to the pure-forward direction at pitch=0) -- i.e. it agrees with
nadir mode's own "up = direction of travel" choice at the pitch=0 boundary.

Output: `<out-dir>/frames/frame_%04d.jpg` + `<out-dir>/manifest.jsonl`,
written via manifest.py's own `write_manifest` -- exactly the
`{"path":..., "lat":..., "lon":..., "heading":...}` schema run_spike.py's
`--manifest` mode already consumes, so this plugs in with zero harness
changes.
"""

from __future__ import annotations

import argparse
import csv
import logging
import math
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import cv2
import numpy as np

from spikes.geo.geomath import EARTH_RADIUS_METERS, TILE_PIXELS, bbox_from_points, tiles_for_bbox
from spikes.geo.manifest import QueryFrame, write_manifest
from spikes.geo.tiles import DEFAULT_ZOOM, Tile, TileFetchSettings, fetch_bbox

LOGGER = logging.getLogger("spikes.geo.sitl_render")

DEFAULT_FOV_DEGREES = 60.0
DEFAULT_SAMPLE_EVERY_SECONDS = 2.0  # matches vision.geo.keyframe-interval default (§3.5)
DEFAULT_FRAME_SIZE = 512
DEFAULT_MIN_MARGIN_METERS = 150.0
MAX_RAY_ANGLE_FROM_NADIR_DEGREES = 85.0  # safety clamp -- see _corner_offset_meters


# --------------------------------------------------------------------------
# Telemetry CSV (infra/sitl/log_telemetry.py's schema -- distinct from, and
# richer than, video_input.py's `timestamp_ms,lat,lon,heading` schema: this
# one also carries altitude, which the footprint math requires).
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class TelemetrySample:
    timestamp_ms: int
    lat: float
    lon: float
    alt_m: float
    heading_deg: float


def load_sitl_csv(path: Path) -> list[TelemetrySample]:
    samples: list[TelemetrySample] = []
    skipped = 0
    with path.open(newline="") as handle:
        reader = csv.DictReader(handle)
        required = {"timestamp_ms", "lat", "lon", "alt_m", "heading_deg"}
        missing = required - set(f.strip() for f in (reader.fieldnames or []))
        if missing:
            raise ValueError(
                f"{path}: telemetry CSV missing required column(s) {sorted(missing)} -- "
                "expected infra/sitl/log_telemetry.py's header "
                "`timestamp_ms,lat,lon,alt_m,heading_deg,groundspeed_mps,sysid`"
            )
        for line_number, record in enumerate(reader, start=2):
            try:
                alt_raw = (record.get("alt_m") or "").strip()
                heading_raw = (record.get("heading_deg") or "").strip()
                if not alt_raw or not heading_raw:
                    raise ValueError("blank alt_m/heading_deg")
                samples.append(
                    TelemetrySample(
                        timestamp_ms=int(float(record["timestamp_ms"])),
                        lat=float(record["lat"]),
                        lon=float(record["lon"]),
                        alt_m=float(alt_raw),
                        heading_deg=float(heading_raw),
                    )
                )
            except (KeyError, TypeError, ValueError) as exc:
                LOGGER.warning("%s:%d: skipping row without a usable alt/heading: %s", path, line_number, exc)
                skipped += 1
    samples.sort(key=lambda s: s.timestamp_ms)
    if skipped:
        LOGGER.info("%s: skipped %d row(s) missing alt_m/heading_deg (e.g. before VFR_HUD's first message)", path, skipped)
    return samples


def thin_by_time(samples: list[TelemetrySample], every_seconds: float) -> list[TelemetrySample]:
    """Greedy time-based thinning: keep the first sample, then the next one
    at least `every_seconds` later, etc. -- turns a dense ~2Hz capture into
    one query frame roughly every `every_seconds`, matching a realistic
    keyframe cadence instead of rendering (and matching) hundreds of
    near-duplicate frames."""
    if not samples:
        return []
    interval_ms = every_seconds * 1000.0
    kept = [samples[0]]
    for sample in samples[1:]:
        if sample.timestamp_ms - kept[-1].timestamp_ms >= interval_ms:
            kept.append(sample)
    return kept


# --------------------------------------------------------------------------
# Pixel-precise Web Mercator math -- the continuous counterpart of
# geomath.deg2tile (which truncates to an integer tile index). Needed here
# because a query frame's center is essentially never tile-aligned.
# --------------------------------------------------------------------------


def _deg_to_global_pixel(lat_deg: float, lon_deg: float, zoom: int, tile_px: int) -> tuple[float, float]:
    lat_rad = math.radians(lat_deg)
    n = 2.0**zoom
    xf = (lon_deg + 180.0) / 360.0 * n
    yf = (1.0 - math.asinh(math.tan(lat_rad)) / math.pi) / 2.0 * n
    return xf * tile_px, yf * tile_px


def _meters_per_pixel(lat_deg: float, zoom: int, tile_px: int) -> float:
    lat_rad = math.radians(lat_deg)
    return (math.cos(lat_rad) * 2.0 * math.pi * EARTH_RADIUS_METERS) / (tile_px * (2.0**zoom))


# --------------------------------------------------------------------------
# Reference mosaic: stitch fetched tiles into one array so crops/warps can
# be pixel-addressed continuously across tile boundaries.
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class Mosaic:
    image: np.ndarray  # BGR uint8
    origin_x: float  # global pixel x of image[:, 0]
    origin_y: float  # global pixel y of image[0, :]
    zoom: int
    tile_px: int


def build_mosaic(tiles: list[Tile], zoom: int, tile_px: int) -> Mosaic:
    if not tiles:
        raise ValueError("no reference tiles to build a mosaic from")
    xs = [t.x for t in tiles]
    ys = [t.y for t in tiles]
    x_lo, x_hi = min(xs), max(xs)
    y_lo, y_hi = min(ys), max(ys)
    width = (x_hi - x_lo + 1) * tile_px
    height = (y_hi - y_lo + 1) * tile_px
    canvas = np.zeros((height, width, 3), dtype=np.uint8)
    by_xy = {(t.x, t.y): t for t in tiles}
    missing = 0
    for y in range(y_lo, y_hi + 1):
        for x in range(x_lo, x_hi + 1):
            tile = by_xy.get((x, y))
            if tile is None:
                missing += 1
                continue
            image = cv2.imread(str(tile.path), cv2.IMREAD_COLOR)
            if image is None or image.shape[0] != tile_px or image.shape[1] != tile_px:
                missing += 1
                continue
            ox, oy = (x - x_lo) * tile_px, (y - y_lo) * tile_px
            canvas[oy : oy + tile_px, ox : ox + tile_px] = image
    if missing:
        LOGGER.warning(
            "mosaic: %d/%d tile slots missing or unreadable (rendered as black) -- "
            "widen --bbox-margin-meters or check network if this fraction is large",
            missing, (x_hi - x_lo + 1) * (y_hi - y_lo + 1),
        )
    return Mosaic(image=canvas, origin_x=x_lo * tile_px, origin_y=y_lo * tile_px, zoom=zoom, tile_px=tile_px)


def _pad_to_cover(image: np.ndarray, points_xy: list[tuple[float, float]], slack: int = 4) -> tuple[np.ndarray, list[tuple[float, float]]]:
    """Pads `image` (reflect) so every point in `points_xy` lands inside
    bounds, returns (padded_image, points shifted to match). A non-trivial
    pad here means --bbox-margin-meters undershot the true footprint for
    this pitch/fov/altitude combination -- logged, not fatal."""
    h, w = image.shape[:2]
    xs = [p[0] for p in points_xy]
    ys = [p[1] for p in points_xy]
    pad_left = max(0, int(math.ceil(-min(xs))) + slack) if min(xs) < 0 else 0
    pad_top = max(0, int(math.ceil(-min(ys))) + slack) if min(ys) < 0 else 0
    pad_right = max(0, int(math.ceil(max(xs) - w)) + slack) if max(xs) > w else 0
    pad_bottom = max(0, int(math.ceil(max(ys) - h)) + slack) if max(ys) > h else 0
    if not (pad_left or pad_top or pad_right or pad_bottom):
        return image, points_xy
    LOGGER.debug("padding mosaic L=%d T=%d R=%d B=%d to cover an out-of-bounds crop", pad_left, pad_top, pad_right, pad_bottom)
    padded = cv2.copyMakeBorder(image, pad_top, pad_bottom, pad_left, pad_right, cv2.BORDER_REFLECT101)
    shifted = [(x + pad_left, y + pad_top) for x, y in points_xy]
    return padded, shifted


# --------------------------------------------------------------------------
# Nadir rendering: plain square crop + heading rotation.
# --------------------------------------------------------------------------


def render_nadir(mosaic: Mosaic, lat: float, lon: float, heading_deg: float, altitude_m: float, fov_degrees: float, output_size: int) -> Optional[np.ndarray]:
    mpp = _meters_per_pixel(lat, mosaic.zoom, mosaic.tile_px)
    footprint_m = 2.0 * max(altitude_m, 1.0) * math.tan(math.radians(fov_degrees / 2.0))
    footprint_px = footprint_m / mpp
    if footprint_px < 4:
        LOGGER.warning("nadir footprint at (%.6f,%.6f) alt=%.1fm is only %.1fpx -- too small, skipping", lat, lon, altitude_m, footprint_px)
        return None

    gx, gy = _deg_to_global_pixel(lat, lon, mosaic.zoom, mosaic.tile_px)
    cx, cy = gx - mosaic.origin_x, gy - mosaic.origin_y

    half_diag = footprint_px * math.sqrt(2.0) / 2.0 + 2
    corners = [(cx - half_diag, cy - half_diag), (cx + half_diag, cy + half_diag)]
    padded, shifted = _pad_to_cover(mosaic.image, corners)
    (x0, y0), (x1, y1) = shifted
    x0i, y0i, x1i, y1i = int(round(x0)), int(round(y0)), int(round(x1)), int(round(y1))
    patch = padded[y0i:y1i, x0i:x1i]
    if patch.size == 0:
        return None

    center = (patch.shape[1] / 2.0, patch.shape[0] / 2.0)
    # Rotate by -heading: an unrotated crop already has "up" = north
    # (bearing 0). To make "up" = the direction of travel (bearing =
    # heading), the image must turn by -heading degrees under OpenCV's
    # counterclockwise-positive convention (verified against
    # heading=0 -> no rotation needed, heading=90/east -> -90 turns the
    # crop's east-pointing content to the top).
    rot = cv2.getRotationMatrix2D(center, -heading_deg, 1.0)
    rotated = cv2.warpAffine(patch, rot, (patch.shape[1], patch.shape[0]), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REFLECT101)

    half_fp = footprint_px / 2.0
    cxp, cyp = rotated.shape[1] / 2.0, rotated.shape[0] / 2.0
    x0c, x1c = int(round(cxp - half_fp)), int(round(cxp + half_fp))
    y0c, y1c = int(round(cyp - half_fp)), int(round(cyp + half_fp))
    x0c, y0c = max(0, x0c), max(0, y0c)
    x1c, y1c = min(rotated.shape[1], x1c), min(rotated.shape[0], y1c)
    cropped = rotated[y0c:y1c, x0c:x1c]
    if cropped.size == 0:
        return None
    return cv2.resize(cropped, (output_size, output_size), interpolation=cv2.INTER_AREA)


# --------------------------------------------------------------------------
# Oblique rendering: pinhole-camera ground footprint -> homography.
# --------------------------------------------------------------------------


def _corner_offset_meters(altitude_m: float, pitch_degrees: float, fov_degrees: float, sign_u: int, sign_r: int) -> tuple[float, float]:
    """One ground-footprint corner, in camera-local (forward_m, right_m)
    meters relative to the point directly below the camera. Full 3D pinhole
    ray-cast (not a small-angle approximation): boresight tilted
    `pitch_degrees` from nadir toward the direction of travel, FOV corner
    ray = boresight + tan(fov/2) along each of the camera's right/up-image
    axes, intersected with the ground plane. `sign_u=+1` = top of frame
    (farther, forward side when pitched down), `sign_u=-1` = bottom
    (nearer). `sign_r=+1` = right of frame, `sign_r=-1` = left.

    Rays within MAX_RAY_ANGLE_FROM_NADIR_DEGREES of the horizon are clamped
    to that angle (preserving their horizontal direction) rather than
    allowed to blow up toward infinite range -- a real camera pointed near
    the horizon sees the ground at extreme, near-useless range too; this
    just keeps the synthesized footprint finite and the output frame
    non-degenerate.
    """
    theta = math.radians(pitch_degrees)
    boresight = (math.sin(theta), 0.0, -math.cos(theta))  # (F, R, U)
    right_axis = (0.0, 1.0, 0.0)
    up_image = (math.cos(theta), 0.0, math.sin(theta))  # cross(boresight, right_axis)

    tan_half = math.tan(math.radians(fov_degrees / 2.0))
    dF = boresight[0] + tan_half * sign_r * right_axis[0] + tan_half * sign_u * up_image[0]
    dR = boresight[1] + tan_half * sign_r * right_axis[1] + tan_half * sign_u * up_image[1]
    dU = boresight[2] + tan_half * sign_r * right_axis[2] + tan_half * sign_u * up_image[2]

    norm = math.sqrt(dF * dF + dR * dR + dU * dU)
    cos_from_down = -dU / norm
    cos_from_down = max(-1.0, min(1.0, cos_from_down))
    angle_from_down = math.acos(cos_from_down)
    max_angle = math.radians(MAX_RAY_ANGLE_FROM_NADIR_DEGREES)
    if angle_from_down > max_angle:
        horiz_norm = math.sqrt(dF * dF + dR * dR)
        if horiz_norm < 1e-9:
            dF, dR, dU = 0.0, 0.0, -1.0
        else:
            scale = math.sin(max_angle) / horiz_norm
            dF, dR, dU = dF * scale, dR * scale, -math.cos(max_angle)

    t = -altitude_m / dU  # dU guaranteed < 0 after the clamp above
    return t * dF, t * dR


def _ground_corners_meters(altitude_m: float, pitch_degrees: float, fov_degrees: float) -> list[tuple[float, float]]:
    """4 corners in (forward_m, right_m), order: TL, TR, BR, BL --
    matching a dst image's (0,0), (W-1,0), (W-1,H-1), (0,H-1)."""
    signs = [(1, -1), (1, 1), (-1, 1), (-1, -1)]
    return [_corner_offset_meters(altitude_m, pitch_degrees, fov_degrees, su, sr) for su, sr in signs]


def estimate_margin_meters(altitude_m: float, pitch_degrees: float, fov_degrees: float, cushion: float = 1.3) -> float:
    """Conservative scalar bbox margin covering the widest ground-footprint
    corner for a given altitude/pitch/fov -- used to size the reference-tile
    fetch so a render never has to pad-extrapolate (via _pad_to_cover)
    beyond a thin reflect border."""
    corners = _ground_corners_meters(altitude_m, pitch_degrees, fov_degrees)
    farthest = max(math.hypot(f, r) for f, r in corners)
    return max(DEFAULT_MIN_MARGIN_METERS, farthest * cushion)


def render_oblique(mosaic: Mosaic, lat: float, lon: float, heading_deg: float, altitude_m: float, pitch_degrees: float, fov_degrees: float, output_size: int) -> Optional[np.ndarray]:
    mpp = _meters_per_pixel(lat, mosaic.zoom, mosaic.tile_px)
    gx, gy = _deg_to_global_pixel(lat, lon, mosaic.zoom, mosaic.tile_px)
    cx, cy = gx - mosaic.origin_x, gy - mosaic.origin_y

    heading_rad = math.radians(heading_deg)
    forward_east, forward_north = math.sin(heading_rad), math.cos(heading_rad)
    right_east, right_north = math.cos(heading_rad), -math.sin(heading_rad)

    corners_local = _ground_corners_meters(altitude_m, pitch_degrees, fov_degrees)
    src_pts: list[tuple[float, float]] = []
    for forward_m, right_m in corners_local:
        east_m = forward_m * forward_east + right_m * right_east
        north_m = forward_m * forward_north + right_m * right_north
        dx_px = east_m / mpp
        dy_px = -north_m / mpp  # pixel y increases south; north offset moves -y
        src_pts.append((cx + dx_px, cy + dy_px))

    padded, shifted = _pad_to_cover(mosaic.image, src_pts)
    src = np.float32(shifted)
    dst = np.float32([[0, 0], [output_size - 1, 0], [output_size - 1, output_size - 1], [0, output_size - 1]])
    matrix = cv2.getPerspectiveTransform(src, dst)
    warped = cv2.warpPerspective(padded, matrix, (output_size, output_size), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REFLECT101)
    return warped


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m spikes.geo.sitl_render",
        description="Synthesize query frames from a SITL flight track + real satellite tiles (docs/VISUAL-GEO-PLAN.md §5).",
    )
    parser.add_argument("--csv", type=Path, required=True, help="telemetry CSV from infra/sitl/log_telemetry.py")
    parser.add_argument("--out-dir", type=Path, required=True, help="output dir: frames/ + manifest.jsonl written here")
    parser.add_argument("--zoom", type=int, default=DEFAULT_ZOOM)
    parser.add_argument("--pitch-degrees", type=float, default=0.0, help="0 = nadir (default), >0 = oblique (e.g. 40-45)")
    parser.add_argument("--fov-degrees", type=float, default=DEFAULT_FOV_DEGREES)
    parser.add_argument("--sample-every-seconds", type=float, default=DEFAULT_SAMPLE_EVERY_SECONDS)
    parser.add_argument("--frame-size", type=int, default=DEFAULT_FRAME_SIZE, help="output frame is frame-size x frame-size")
    parser.add_argument("--bbox-margin-meters", type=float, default=None, help="reference-tile fetch margin; auto-estimated from altitude/pitch/fov if omitted")
    parser.add_argument("--cache-dir", type=Path, default=None)
    parser.add_argument("--max-tiles", type=int, default=None)
    parser.add_argument("-v", "--verbose", action="store_true")
    return parser


def run(args: argparse.Namespace) -> int:
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    samples = load_sitl_csv(args.csv)
    if not samples:
        raise SystemExit(f"{args.csv}: no usable telemetry rows (need lat/lon/alt_m/heading_deg on every row)")
    thinned = thin_by_time(samples, args.sample_every_seconds)
    LOGGER.info("%d telemetry rows -> %d sampled every ~%.1fs", len(samples), len(thinned), args.sample_every_seconds)

    max_alt = max(s.alt_m for s in thinned)
    margin = args.bbox_margin_meters
    if margin is None:
        margin = estimate_margin_meters(max_alt, args.pitch_degrees, args.fov_degrees)
        LOGGER.info("auto-estimated --bbox-margin-meters=%.0f from max altitude=%.1fm, pitch=%.0f, fov=%.0f", margin, max_alt, args.pitch_degrees, args.fov_degrees)

    points = [(s.lat, s.lon) for s in thinned]
    bbox = bbox_from_points(points, margin)
    LOGGER.info("reference bbox (flight track + %.0fm margin): %s", margin, bbox)

    settings = TileFetchSettings.from_env()
    overrides = {}
    if args.cache_dir is not None:
        overrides["cache_dir"] = args.cache_dir
    if args.max_tiles is not None:
        overrides["max_tiles"] = args.max_tiles
    if overrides:
        settings = TileFetchSettings(**{**settings.__dict__, **overrides})

    tiles = fetch_bbox(*bbox, args.zoom, settings, halo=0)
    core_xy = set(tiles_for_bbox(*bbox, args.zoom))
    tiles = [t for t in tiles if (t.x, t.y) in core_xy]
    if not tiles:
        raise SystemExit(f"no reference tiles fetched for bbox={bbox} at zoom={args.zoom} -- check network/bbox")
    LOGGER.info("fetched %d reference tiles at zoom %d", len(tiles), args.zoom)

    mosaic = build_mosaic(tiles, args.zoom, TILE_PIXELS)

    frames_dir = args.out_dir / "frames"
    frames_dir.mkdir(parents=True, exist_ok=True)
    query_frames: list[QueryFrame] = []
    mode = "oblique" if args.pitch_degrees > 0 else "nadir"
    skipped = 0
    for index, sample in enumerate(thinned):
        if mode == "nadir":
            image = render_nadir(mosaic, sample.lat, sample.lon, sample.heading_deg, sample.alt_m, args.fov_degrees, args.frame_size)
        else:
            image = render_oblique(mosaic, sample.lat, sample.lon, sample.heading_deg, sample.alt_m, args.pitch_degrees, args.fov_degrees, args.frame_size)
        if image is None:
            skipped += 1
            continue
        frame_path = (frames_dir / f"frame_{index:04d}.jpg").resolve()
        cv2.imwrite(str(frame_path), image)
        query_frames.append(
            QueryFrame(
                query_id=f"sitl-{index:04d}", image_path=frame_path,
                lat=sample.lat, lon=sample.lon, heading=sample.heading_deg,
                timestamp_ms=sample.timestamp_ms, altitude_meters=sample.alt_m,
            )
        )

    if skipped:
        LOGGER.warning("%d/%d sampled rows produced no frame (degenerate crop/footprint)", skipped, len(thinned))
    if not query_frames:
        raise SystemExit("no query frames rendered -- nothing to write")

    manifest_path = args.out_dir / "manifest.jsonl"
    write_manifest(manifest_path, query_frames)
    LOGGER.info("wrote %d frames -> %s, manifest -> %s (mode=%s)", len(query_frames), frames_dir, manifest_path, mode)
    return 0


def main(argv: Optional[list[str]] = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
