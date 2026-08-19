"""Perspective rectification fast path for oblique queries (docs/VISUAL-GEO-PLAN.md §13.4,
Slice R).

§12.11's honest negative: oblique frames stay broken under similarity-only conditioning
(SITL-oblique ~450m/42° yaw error) because rotation+scale conditioning cannot remove
PERSPECTIVE. This module closes that gap with zero new model dependencies, in three pure
steps:

1. `extract_geometry` — metadata-first camera geometry (§13.2 tier 0): EXIF focal length
   (`FocalLengthIn35mmFilm` preferred -> focal_px via the 35mm-diagonal convention), DJI XMP
   gimbal pitch/roll/yaw (plain XML inside JPEG APP1 segments, parsed without new deps), and a
   best-effort Apple MakerNote `AccelerationVector` (gravity) parse. Every field is Optional
   and carries per-field provenance (`"exif"`/`"xmp"`/`"exif-makernote"`/`"manual"`/
   `"assumed"`/`"absent"`) so callers can report honestly what was measured vs guessed.
   Messenger re-encodes strip metadata — absence is the NORMAL case and must degrade to
   exactly today's behavior (the §13.1 "always fall back gracefully" rule).

2. `horizon_crop` — discard pixels above the max-usable ground radius. Exact rule: a pixel
   row is kept iff its ray's depression below the horizontal is >= MIN_DEPRESSION_DEGREES
   (15°, the shallow end of §13.4's "usable roughly at depression >= 15-20°" band — chosen
   over 20° because MAGSAC absorbs residual error at the margin and the SITL-oblique render's
   own top edge sits at 75° from nadir = exactly 15° depression). Equivalently ground range
   <= altitude * tan(75°) ~= 3.7x height — the same guard family as the footprint sanity
   bounds in `pose.py`. Roll is deliberately ignored by the CROP (it only shifts the usable
   line by ~f*tan(roll)*sin(...) px); the WARP handles roll exactly.

3. `ipm_warp` — inverse-perspective-map the (cropped) image onto the ground plane: plane
   homography from K (focal_px) and the gravity rotation (pitch/roll), rendered at the target
   GSD (e.g. the reference tiles' meters-per-pixel), optionally de-rotated to north-up by a
   heading prior. This GENERALIZES `verify.condition_query`'s similarity conditioning
   (rotation+scale) to full perspective: after `ipm_warp`, a separate `condition_query` pass
   is redundant — the warp already applied heading (when given) and GSD. It only needs to be
   approximately right to enter LoFTR's convergence basin; MAGSAC absorbs the residual
   (§13.4). The exact 3x3 `H` (input px -> warped px) is returned so callers can compose the
   final LoFTR/MAGSAC homography back to ORIGINAL pixels (H_final = H_loftr . H_ipm; in
   practice: map the warped-frame keypoints through `warped_to_input` BEFORE
   `pose.fit_homography_pose`, which fits the composed homography directly and keeps
   `pose.py`'s yaw/footprint semantics in the original frame).

Conventions (stated once, used everywhere):
- `pitch_deg` is degrees FROM NADIR: 0 = straight down, 90 = horizon-level forward. This
  matches `spikes/geo/sitl_render.py`'s `--pitch-degrees`. DJI XMP gimbal pitch (-90 =
  nadir, 0 = horizon) is converted on extraction (`pitch_from_nadir = gimbal + 90`).
- `roll_deg` positive = camera rotated clockwise looking along the boresight.
- Ground frame during the warp: X = camera-right, Y = camera-forward (projected on the
  ground), Z = up; output pixels put +forward (or +north when `heading_deg` is given) UP.

Pure `cv2`/`numpy`/stdlib — importable WITHOUT torch/kornia (same discipline as `pose.py`).
Pillow is used lazily and optionally for EXIF (it is present in the service venv); when it
is missing every EXIF-derived field simply reports provenance `"absent"`. Never imports
`cv_pb2` (`cv_service/grpc/servicers.py` stays the sole wire-translation point).
"""

from __future__ import annotations

import io
import logging
import math
import re
import struct
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional, Union

import cv2
import numpy as np

LOGGER = logging.getLogger("cv_service.geo.rectify")

# --- constants ----------------------------------------------------------------------------------

MIN_DEPRESSION_DEGREES = 15.0  # usable-ground rule, see module docstring (§13.4's 15-20° band)
MIN_USABLE_PX = 32  # below this the crop/warp has degenerated into noise (verify.py's idiom)
MAX_OUTPUT_PX = 4096  # hard cap on either warped edge — beyond this the far field is useless
FULL_FRAME_DIAGONAL_MM = 43.26661  # 35mm film frame diagonal (36x24) — the f35 equivalence base
_EARTH_RADIUS_METERS = 6_371_000.0  # matches verify.py/index.py (not imported: torch-free rule)
TILE_PIXELS = 256

# EXIF tag ids (stdlib-level constants so the parse code reads clearly)
_TAG_EXIF_IFD = 0x8769
_TAG_MAKER_NOTE = 0x927C
_TAG_FOCAL_LENGTH = 0x920A
_TAG_FOCAL_35MM = 0xA405
_APPLE_TAG_ACCELERATION_VECTOR = 0x0008

PROV_ABSENT = "absent"
PROV_EXIF = "exif"
PROV_XMP = "xmp"
PROV_MAKERNOTE = "exif-makernote"
PROV_MANUAL = "manual"
PROV_ASSUMED = "assumed"


def reference_gsd_m_per_px(lat: float, zoom: int) -> float:
    """Ground sampling distance of a 256px slippy tile at `lat`/`zoom` — the same formula as
    `verify.reference_meters_per_pixel`, duplicated here (3 lines) because `verify` imports
    torch and this module must stay importable without it."""
    return (
        math.cos(math.radians(lat)) * 2.0 * math.pi * _EARTH_RADIUS_METERS / (TILE_PIXELS * 2**zoom)
    )


def focal_px_from_fov(width_px: int, horizontal_fov_deg: float) -> float:
    """Pinhole focal length in pixels from a horizontal FOV across `width_px` — the assumed-FOV
    fallback (`track.DEFAULT_ASSUMED_HORIZONTAL_FOV_DEGREES` is the conventional default)."""
    return (width_px / 2.0) / math.tan(math.radians(horizontal_fov_deg / 2.0))


# --- geometry extraction ------------------------------------------------------------------------


@dataclass(frozen=True)
class CameraGeometry:
    """Everything the rectifier can learn about the capture geometry, all Optional, with
    per-field provenance in `provenance` (keys: focal_px, pitch_deg, roll_deg, yaw_deg)."""

    focal_px: Optional[float] = None
    focal_mm: Optional[float] = None  # raw EXIF FocalLength, informational
    focal_35mm: Optional[float] = None  # raw EXIF FocalLengthIn35mmFilm, informational
    pitch_deg: Optional[float] = None  # degrees from nadir (0 = straight down)
    roll_deg: Optional[float] = None
    yaw_deg: Optional[float] = None  # compass heading of camera-forward, when known
    image_width: int = 0
    image_height: int = 0
    provenance: dict = field(default_factory=dict)

    def with_manual(
        self,
        *,
        pitch_deg: Optional[float] = None,
        roll_deg: Optional[float] = None,
        focal_px: Optional[float] = None,
    ) -> "CameraGeometry":
        """Overlay operator-supplied values (they win over metadata — the operator saw the
        photo; metadata sometimes describes a gimbal that was still slewing)."""
        prov = dict(self.provenance)
        updates: dict = {}
        if pitch_deg is not None:
            updates["pitch_deg"] = pitch_deg
            prov["pitch_deg"] = PROV_MANUAL
        if roll_deg is not None:
            updates["roll_deg"] = roll_deg
            prov["roll_deg"] = PROV_MANUAL
        if focal_px is not None:
            updates["focal_px"] = focal_px
            prov["focal_px"] = PROV_MANUAL
        import dataclasses as _dc

        return _dc.replace(self, provenance=prov, **updates)


def _read_bytes(source: Union[bytes, bytearray, str, Path]) -> Optional[bytes]:
    if isinstance(source, (bytes, bytearray)):
        return bytes(source)
    try:
        return Path(source).read_bytes()
    except OSError as exc:
        LOGGER.warning("rectify: could not read %s: %s", source, exc)
        return None


def _jpeg_app_segments(data: bytes) -> list[bytes]:
    """Payloads of every APP0-APP15 segment before SOS — a minimal, forgiving JPEG segment
    walk (XMP lives in APP1; malformed files just yield fewer segments, never raise)."""
    segments: list[bytes] = []
    if len(data) < 4 or data[0:2] != b"\xff\xd8":
        return segments
    i = 2
    while i + 4 <= len(data):
        if data[i] != 0xFF:
            break
        marker = data[i + 1]
        if marker == 0xDA or marker == 0xD9:  # SOS / EOI — metadata segments are all before
            break
        if 0xD0 <= marker <= 0xD7 or marker == 0x01:  # standalone markers, no length
            i += 2
            continue
        length = struct.unpack(">H", data[i + 2 : i + 4])[0]
        if length < 2 or i + 2 + length > len(data):
            break
        if 0xE0 <= marker <= 0xEF:
            segments.append(data[i + 4 : i + 2 + length])
        i += 2 + length
    return segments


_XMP_HEADER = b"http://ns.adobe.com/xap/1.0/\x00"
# DJI writes gimbal pose as XML attributes or elements under the drone-dji namespace; both
# forms appear in the wild. Values like "-89.90", "+5.00".
_DJI_FIELDS = {
    "GimbalPitchDegree": "pitch",
    "GimbalRollDegree": "roll",
    "GimbalYawDegree": "yaw",
}


def _parse_dji_xmp(segments: list[bytes]) -> dict[str, float]:
    """DJI gimbal pitch/roll/yaw out of XMP APP1 payloads. XMP is plain XML — a targeted
    regex per field (attribute or element form) is deliberate: no XML dependency, and DJI's
    packets are machine-written (no exotic escaping of a signed decimal)."""
    found: dict[str, float] = {}
    for payload in segments:
        if not payload.startswith(_XMP_HEADER):
            continue
        text = payload[len(_XMP_HEADER) :]
        for xml_name, key in _DJI_FIELDS.items():
            if key in found:
                continue
            pattern = (
                rb"(?:drone-dji:)?" + xml_name.encode()
                + rb'(?:="([+-]?\d+(?:\.\d+)?)"|>([+-]?\d+(?:\.\d+)?)<)'
            )
            match = re.search(pattern, text)
            if match:
                raw = match.group(1) or match.group(2)
                try:
                    found[key] = float(raw)
                except ValueError:
                    pass
    return found


def _parse_apple_acceleration(maker_note: bytes) -> Optional[tuple[float, float, float]]:
    """Best-effort Apple MakerNote `AccelerationVector` (tag 0x0008, 3 SRATIONALs) parse.

    Format: b"Apple iOS\\x00\\x00\\x01MM" (or II) header, then a bare TIFF IFD at offset 14
    whose value offsets are relative to the START of the MakerNote blob (Apple's documented
    quirk, same as exiftool implements). Any structural surprise -> None, never raises.
    HONESTY NOTE: implemented from the known exiftool-documented layout but UNVERIFIED
    against a real iPhone capture in this repo (neither seed photo is an iPhone —
    docs/VISUAL-GEO-PLAN.md §13.4 status note); the synthetic-blob unit test exercises the
    parse mechanics only."""
    try:
        if not maker_note.startswith(b"Apple iOS\x00\x00\x01"):
            return None
        endian = maker_note[12:14]
        if endian == b"MM":
            fmt = ">"
        elif endian == b"II":
            fmt = "<"
        else:
            return None
        (count,) = struct.unpack_from(fmt + "H", maker_note, 14)
        for i in range(count):
            entry = 16 + 12 * i
            tag, ftype, n = struct.unpack_from(fmt + "HHI", maker_note, entry)
            if tag != _APPLE_TAG_ACCELERATION_VECTOR:
                continue
            if ftype != 10 or n != 3:  # 10 = SRATIONAL
                return None
            (offset,) = struct.unpack_from(fmt + "I", maker_note, entry + 8)
            values = []
            for j in range(3):
                num, den = struct.unpack_from(fmt + "ii", maker_note, offset + 8 * j)
                if den == 0:
                    return None
                values.append(num / den)
            return (values[0], values[1], values[2])
        return None
    except (struct.error, IndexError):
        return None


def _gravity_to_pitch_roll(accel: tuple[float, float, float]) -> Optional[tuple[float, float]]:
    """(pitch_from_nadir, roll) from an Apple AccelerationVector, under exiftool's documented
    axis convention (+X left, +Y down, +Z into the phone, units of g, at rest ~= the gravity
    direction): the BACK camera looks along +Z, so pitch-from-nadir is the angle between +Z
    and gravity; roll is gravity's direction within the image plane (portrait: image right =
    -X, image down = +Y). Same honesty caveat as `_parse_apple_acceleration`."""
    x, y, z = accel
    norm = math.sqrt(x * x + y * y + z * z)
    if norm < 1e-6:
        return None
    x, y, z = x / norm, y / norm, z / norm
    pitch = math.degrees(math.acos(max(-1.0, min(1.0, z))))
    roll = math.degrees(math.atan2(-x, y)) if (abs(x) > 1e-9 or abs(y) > 1e-9) else 0.0
    return pitch, roll


def extract_geometry(source: Union[bytes, bytearray, str, Path]) -> CameraGeometry:
    """Metadata-first camera geometry (§13.4). Every failure path degrades a FIELD to
    provenance "absent", never the whole call — a stripped/re-encoded image yields an
    all-absent CameraGeometry and the caller runs exactly today's unrectified path."""
    data = _read_bytes(source)
    provenance = {k: PROV_ABSENT for k in ("focal_px", "pitch_deg", "roll_deg", "yaw_deg")}
    if data is None:
        return CameraGeometry(provenance=provenance)

    width = height = 0
    focal_mm = focal_35 = focal_px = None
    pitch = roll = yaw = None

    # --- EXIF via Pillow (lazy, optional) ---
    exif_ifd = {}
    try:
        from PIL import Image  # optional dependency for this module — see module docstring

        with Image.open(io.BytesIO(data)) as image:
            width, height = image.size
            exif = image.getexif()
            exif_ifd = dict(exif.get_ifd(_TAG_EXIF_IFD)) if exif else {}
    except ImportError:
        LOGGER.info("rectify: Pillow unavailable — EXIF fields reported absent")
    except Exception as exc:  # any decode surprise: metadata is best-effort by contract
        LOGGER.warning("rectify: EXIF read failed (%s) — fields reported absent", exc)

    if width == 0:
        decoded = cv2.imdecode(np.frombuffer(data, dtype=np.uint8), cv2.IMREAD_COLOR)
        if decoded is not None:
            height, width = decoded.shape[:2]

    raw_focal = exif_ifd.get(_TAG_FOCAL_LENGTH)
    raw_f35 = exif_ifd.get(_TAG_FOCAL_35MM)
    try:
        focal_mm = float(raw_focal) if raw_focal else None
    except (TypeError, ValueError):
        focal_mm = None
    try:
        focal_35 = float(raw_f35) if raw_f35 else None
    except (TypeError, ValueError):
        focal_35 = None
    if focal_35 and width and height:
        # f35 is defined against the 43.27mm full-frame diagonal; scale by the image diagonal.
        diag_px = math.hypot(width, height)
        focal_px = focal_35 / FULL_FRAME_DIAGONAL_MM * diag_px
        provenance["focal_px"] = PROV_EXIF
    # FocalLength (mm) alone is useless without the physical sensor size, which EXIF rarely
    # carries — documented limitation, reported via focal_mm for diagnostics only.

    # --- DJI XMP gimbal ---
    dji = _parse_dji_xmp(_jpeg_app_segments(data))
    if "pitch" in dji:
        pitch = dji["pitch"] + 90.0  # DJI: -90 = nadir, 0 = horizon -> degrees-from-nadir
        provenance["pitch_deg"] = PROV_XMP
    if "roll" in dji:
        roll = dji["roll"]
        provenance["roll_deg"] = PROV_XMP
    if "yaw" in dji:
        yaw = dji["yaw"] % 360.0
        provenance["yaw_deg"] = PROV_XMP

    # --- Apple MakerNote gravity (only when XMP gave nothing) ---
    if pitch is None:
        maker = exif_ifd.get(_TAG_MAKER_NOTE)
        if isinstance(maker, bytes):
            accel = _parse_apple_acceleration(maker)
            if accel is not None:
                converted = _gravity_to_pitch_roll(accel)
                if converted is not None:
                    pitch, roll = converted
                    provenance["pitch_deg"] = PROV_MAKERNOTE
                    provenance["roll_deg"] = PROV_MAKERNOTE

    return CameraGeometry(
        focal_px=focal_px,
        focal_mm=focal_mm,
        focal_35mm=focal_35,
        pitch_deg=pitch,
        roll_deg=roll,
        yaw_deg=yaw,
        image_width=width,
        image_height=height,
        provenance=provenance,
    )


# --- horizon crop -------------------------------------------------------------------------------


def horizon_crop(
    image: "np.ndarray",
    pitch_deg: float,
    *,
    focal_px: float,
    min_depression_deg: float = MIN_DEPRESSION_DEGREES,
) -> Optional[tuple["np.ndarray", int]]:
    """Crop away rows whose rays graze the ground shallower than `min_depression_deg` below
    the horizontal (see module docstring for the exact rule). Returns `(cropped, top_offset)`
    — `cropped = image[top_offset:]` (a view, zero-copy) — or `None` when fewer than
    MIN_USABLE_PX usable rows remain (the whole frame is sky/near-horizon).

    Row math (roll assumed small — the warp handles roll exactly): the boresight's depression
    is `90 - pitch`; a row at y has ray depression `(90 - pitch) + atan((y - cy)/f)` (y grows
    downward = more toward nadir), so the first usable row is
    `cy + f * tan(min_depression - (90 - pitch))`."""
    h = image.shape[0]
    cy = h / 2.0
    boresight_depression = 90.0 - pitch_deg
    cut_angle = math.radians(min_depression_deg - boresight_depression)
    if cut_angle >= math.radians(89.9):  # camera pitched so far up nothing can be usable
        return None
    top = int(math.ceil(cy + focal_px * math.tan(cut_angle)))
    top = max(0, top)
    if h - top < MIN_USABLE_PX:
        return None
    return image[top:], top


# --- inverse perspective map --------------------------------------------------------------------


def _rotation_ground_from_camera(pitch_deg: float, roll_deg: float) -> "np.ndarray":
    """Columns = camera axes (x=right, y=image-down, z=optical axis) expressed in the ground
    frame (X=right, Y=forward, Z=up). pitch from nadir tilts the boresight toward +Y;
    positive roll rotates the camera clockwise looking along the boresight."""
    theta = math.radians(pitch_deg)
    tilt = np.array(
        [
            [1.0, 0.0, 0.0],
            [0.0, -math.cos(theta), math.sin(theta)],
            [0.0, -math.sin(theta), -math.cos(theta)],
        ]
    )
    phi = math.radians(roll_deg)
    roll = np.array(
        [
            [math.cos(phi), -math.sin(phi), 0.0],
            [math.sin(phi), math.cos(phi), 0.0],
            [0.0, 0.0, 1.0],
        ]
    )
    return tilt @ roll


@dataclass(frozen=True)
class IpmResult:
    """`warped` is the ground-plane rendering at `gsd_m_per_px`; `H` is the exact 3x3
    projective map INPUT px -> WARPED px (what §13.4 calls H_ipm). `warped_to_input` maps
    warped-frame keypoints back so the final pose homography can be fitted directly in the
    input frame (composing H_ipm^-1 into the final pose)."""

    warped: "np.ndarray"
    H: "np.ndarray"  # [3,3], input px -> warped px
    gsd_m_per_px: float

    def warped_to_input(self, points: "np.ndarray") -> "np.ndarray":
        pts = np.asarray(points, dtype=np.float64).reshape(-1, 1, 2)
        if pts.size == 0:
            return np.zeros((0, 2), dtype=np.float64)
        return cv2.perspectiveTransform(pts, np.linalg.inv(self.H)).reshape(-1, 2)


def ipm_warp(
    image: "np.ndarray",
    *,
    pitch_deg: float,
    roll_deg: float,
    focal_px: float,
    altitude_m: float,
    target_gsd_m_per_px: float,
    heading_deg: Optional[float] = None,
    principal_point: Optional[tuple[float, float]] = None,
    min_depression_deg: float = MIN_DEPRESSION_DEGREES,
    max_output_px: int = MAX_OUTPUT_PX,
) -> Optional[IpmResult]:
    """Plane homography from K and the gravity rotation, rendered at `target_gsd_m_per_px`
    (§13.4 — the full-perspective generalization of `verify.condition_query`). The caller is
    expected to `horizon_crop` first; ground points beyond `altitude * tan(90 - min_depression)`
    are clamped out of the output bounds regardless, and boundary points ABOVE the horizon
    (which mirror through the plane homography) are rejected by projective-depth sign, so an
    uncropped call cannot blow the output up toward the horizon. When `heading_deg` (compass
    heading of camera-forward) is given the output is rendered north-up, replacing
    `condition_query`'s de-rotation.

    `principal_point` is (cx, cy) in THIS image's pixel frame — pass it whenever the image is
    a crop of a larger original (after `horizon_crop`: `(w/2, original_h/2 - crop_top)`);
    default = the image center.

    `None` when the geometry degenerates (no usable ground, output below MIN_USABLE_PX, or a
    non-finite homography). `altitude_m` sets the metric scale of the plane; when it is a
    guess, position/scale are off proportionally but LoFTR+MAGSAC tolerate modest scale error
    (§12.11) — pass the best prior available, not nothing."""
    if altitude_m <= 0 or focal_px <= 0 or target_gsd_m_per_px <= 0:
        return None
    h_px, w_px = image.shape[:2]
    if min(h_px, w_px) < 4:
        return None

    cx, cy = principal_point if principal_point is not None else (w_px / 2.0, h_px / 2.0)
    K = np.array([[focal_px, 0.0, cx], [0.0, focal_px, cy], [0.0, 0.0, 1.0]])
    R_gc = _rotation_ground_from_camera(pitch_deg, roll_deg)
    R_cg = R_gc.T
    C = np.array([0.0, 0.0, altitude_m])
    # Ground plane (X, Y, 0) -> input px: x = K [r1 r2 | -R_cg C] (X, Y, 1)^T
    H_g2i = K @ np.column_stack([R_cg[:, 0], R_cg[:, 1], -R_cg @ C])
    if abs(np.linalg.det(H_g2i)) < 1e-12:
        return None
    H_i2g = np.linalg.inv(H_g2i)

    # Ground coords of the input image boundary, clamped to the usable radius.
    max_range = altitude_m * math.tan(math.radians(90.0 - min_depression_deg))
    border = []
    for u in (0.0, w_px / 2.0, w_px - 1.0):
        for v in (0.0, h_px / 2.0, h_px - 1.0):
            border.append((u, v))
    # Manual projective map so the sign of the depth component is observable: a pixel above
    # the horizon has w of the OPPOSITE sign to the boresight's and its (mirrored, finite)
    # ground image must be rejected, not clamped.
    homog = (H_i2g @ np.array([[u, v, 1.0] for u, v in border]).T).T  # [N, 3]
    w_center = float((H_i2g @ np.array([cx, cy, 1.0]))[2])
    if abs(w_center) < 1e-12:  # boresight exactly on the horizon — no usable geometry
        return None
    ground: list[tuple[float, float]] = []
    for gx_h, gy_h, w in homog:
        if not np.isfinite((gx_h, gy_h, w)).all() or w * w_center <= 0:
            continue
        gx, gy = gx_h / w, gy_h / w
        radius = math.hypot(gx, gy)
        if radius > max_range:
            if radius < 1e-9:
                continue
            gx, gy = gx * max_range / radius, gy * max_range / radius
        ground.append((gx, gy))
    if len(ground) < 3:
        return None

    # Optional heading: ground (right, forward) -> (east, north).
    if heading_deg is not None:
        psi = math.radians(heading_deg)
        M = np.array(
            [
                [math.cos(psi), math.sin(psi), 0.0],
                [-math.sin(psi), math.cos(psi), 0.0],
                [0.0, 0.0, 1.0],
            ]
        )
        ground = [
            (
                gx * math.cos(psi) + gy * math.sin(psi),
                -gx * math.sin(psi) + gy * math.cos(psi),
            )
            for gx, gy in ground
        ]
    else:
        M = np.eye(3)

    gs = target_gsd_m_per_px
    xs = [g[0] for g in ground]
    ys = [g[1] for g in ground]
    out_w = int(math.ceil((max(xs) - min(xs)) / gs))
    out_h = int(math.ceil((max(ys) - min(ys)) / gs))
    if min(out_w, out_h) < MIN_USABLE_PX:
        return None
    out_w, out_h = min(out_w, max_output_px), min(out_h, max_output_px)
    # Output px: u = (E - min_x)/gsd, v = (max_y - N)/gsd  (north/forward up).
    S = np.array(
        [[1.0 / gs, 0.0, -min(xs) / gs], [0.0, -1.0 / gs, max(ys) / gs], [0.0, 0.0, 1.0]]
    )
    H = S @ M @ H_i2g
    if not np.isfinite(H).all():
        return None
    warped = cv2.warpPerspective(image, H, (out_w, out_h), flags=cv2.INTER_LINEAR)
    return IpmResult(warped=warped, H=H, gsd_m_per_px=gs)


# --- convenience: crop + warp in one call -------------------------------------------------------


@dataclass(frozen=True)
class RectifyResult:
    """`horizon_crop` + `ipm_warp` composed. `crop_top` is the number of ORIGINAL rows removed
    above the usable line; `H` (and `warped_to_input`) refer to the CROPPED frame — add
    `(0, crop_top)` to reach full-original coordinates. Callers fitting a pose should use the
    cropped frame as the query frame (its pixels are the ground-visible part of the photo;
    the sky rows would project past H's line at infinity and poison the footprint sanity
    check)."""

    warped: "np.ndarray"
    H: "np.ndarray"
    crop_top: int
    cropped_width: int
    cropped_height: int
    gsd_m_per_px: float

    def warped_to_input(self, points: "np.ndarray") -> "np.ndarray":
        pts = np.asarray(points, dtype=np.float64).reshape(-1, 1, 2)
        if pts.size == 0:
            return np.zeros((0, 2), dtype=np.float64)
        return cv2.perspectiveTransform(pts, np.linalg.inv(self.H)).reshape(-1, 2)


def rectify(
    image: "np.ndarray",
    *,
    pitch_deg: float,
    focal_px: float,
    roll_deg: float = 0.0,
    heading_deg: Optional[float] = None,
    altitude_m: float,
    target_gsd_m_per_px: float,
    min_depression_deg: float = MIN_DEPRESSION_DEGREES,
) -> Optional[RectifyResult]:
    """Horizon-crop then IPM-warp; `None` when either step degenerates (caller falls back to
    the unrectified path — the §13.4 metadata-first rule's graceful floor)."""
    cropped_result = horizon_crop(
        image, pitch_deg, focal_px=focal_px, min_depression_deg=min_depression_deg
    )
    if cropped_result is None:
        return None
    cropped, top = cropped_result
    ipm = ipm_warp(
        cropped,
        pitch_deg=pitch_deg,
        roll_deg=roll_deg,
        focal_px=focal_px,
        altitude_m=altitude_m,
        target_gsd_m_per_px=target_gsd_m_per_px,
        heading_deg=heading_deg,
        # The crop moved the frame origin but not the optical axis: the principal point stays
        # at the ORIGINAL image center, expressed in cropped coordinates.
        principal_point=(image.shape[1] / 2.0, image.shape[0] / 2.0 - top),
        min_depression_deg=min_depression_deg,
    )
    if ipm is None:
        return None
    return RectifyResult(
        warped=ipm.warped,
        H=ipm.H,
        crop_top=top,
        cropped_width=cropped.shape[1],
        cropped_height=cropped.shape[0],
        gsd_m_per_px=ipm.gsd_m_per_px,
    )
