"""Reference descriptor index for visual geolocation (docs/VISUAL-GEO-PLAN.md D1/§3.1, Wave 2a).
Production port of `spikes/geo/index.py`'s numpy cosine-similarity `ReferenceIndex` (§9 open
choice 1: plain numpy matmul, no FAISS -- exhaustive search over tens of thousands of 512-D rows
is single-digit milliseconds) plus its persistence: `descriptors.npy` + `tiles.json`, and this
module's own `index.json` (the plain, wire-agnostic counterpart of `cv_pb2.ReferenceIndexStats`)
-- all three under `<CV_GEO_DATA_DIR>/<region_id>/` (D1: cv-service's own filesystem, never
Postgres).

Never imports `cv_pb2` -- `cv_service/grpc/servicers.py` is the sole place that translates
`ReferenceIndexStats` into the wire message.

**Import-cost note**: only the `ReferenceIndex` class itself needs `numpy` (imported lazily,
inside its methods) -- every other symbol here (tile filename parsing, tile-center/haversine
math, `ReferenceIndexStats`, `read_index_json`/`write_index_json`) is pure stdlib. This lets
`GeolocationServicer.ListRegions`/`DeleteRegion` (which only ever read `index.json` metadata,
never a region's actual descriptor array) keep working even in an install without the `geo`
extra -- mirroring `TrainingServicer.ListModels`'s "management works even without a loaded
model" posture.
"""

from __future__ import annotations

import json
import logging
import math
import re
import time
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING, Optional

if TYPE_CHECKING:  # pragma: no cover - typing only, see module docstring for why numpy is lazy
    import numpy as np

LOGGER = logging.getLogger("cv_service.geo.index")

DESCRIPTORS_FILENAME = "descriptors.npy"
TILES_JSON_FILENAME = "tiles.json"
INDEX_JSON_FILENAME = "index.json"
# Slice A (§13.3 item 2): verify-ready grayscale tile cache -- one uncompressed npz per region,
# written by `cv_service/geo/orchestrator.py` at build time, read by `LocalizeStream`'s
# verification tile loader so the LoFTR path skips JPEG decode + gray conversion at query time.
VERIFY_TILES_FILENAME = "verify_tiles.npz"
# Mirrors `cv_service/geo/verify.py#LOFTR_RESIZE` (deliberately duplicated, NOT imported --
# verify.py drags in torch at module scope and this module must stay importable without it,
# see the import-cost note above). 256px reference tiles sit below it, so their cached scale
# is 1.0; the constant exists so a hypothetical >480px tile is cached at the exact size
# verification would have resized it to anyway.
VERIFY_GRAY_MAX_EDGE = 480

_EARTH_RADIUS_METERS = 6371000.0

# `<z>_<x>_<y>.jpg` -- the frozen reference-pack tile filename shape (`ReferencePackChunk`'s doc
# comment, §3.1). `cv_service/geo/pack.py` keeps its OWN copy of a matching pattern for zip-entry
# path-safety filtering (a different concern, checked before extraction) -- duplicated on purpose
# rather than cross-imported, see pack.py's module docstring.
TILE_FILENAME_RE = re.compile(r"^(\d+)_(\d+)_(\d+)\.jpg$")


def parse_tile_filename(name: str) -> Optional[tuple[int, int, int]]:
    """`"17_76648_44197.jpg"` -> `(17, 76648, 44197)` (zoom, x, y). `None` if `name` doesn't
    match the frozen shape -- callers skip rather than crash on a stray file."""
    match = TILE_FILENAME_RE.match(name)
    if not match:
        return None
    zoom, x, y = (int(g) for g in match.groups())
    return zoom, x, y


def _tile_nw_corner(x: float, y: float, zoom: int) -> tuple[float, float]:
    """Lat/lon of the north-west corner of slippy-map tile (x, y) at `zoom` -- ported from
    `spikes/geo/geomath.py#tile2deg`. Accepts fractional x/y on purpose (unused today -- §9 open
    choice 2's half-offset augmentation is deliberately NOT wired into Wave 2a's default build,
    see MODULE.md/§12.3 -- kept fractional-capable so a future augmentation pass can reuse this
    unchanged)."""
    n = 2.0**zoom
    lon_deg = x / n * 360.0 - 180.0
    lat_rad = math.atan(math.sinh(math.pi * (1 - 2 * y / n)))
    return math.degrees(lat_rad), lon_deg


def tile_center(x: int, y: int, zoom: int) -> tuple[float, float]:
    """Lat/lon of the CENTER of slippy-map tile (x, y) at `zoom` -- ported from
    `spikes/geo/geomath.py#tile_center`; what `GeoCandidate.latitude`/`longitude` (§3.1) report
    for a matched tile."""
    lat_nw, lon_nw = _tile_nw_corner(x, y, zoom)
    lat_se, lon_se = _tile_nw_corner(x + 1, y + 1, zoom)
    return (lat_nw + lat_se) / 2.0, (lon_nw + lon_se) / 2.0


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Great-circle distance in meters -- ported from `spikes/geo/geomath.py#haversine_m`."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlambda / 2) ** 2
    return 2 * _EARTH_RADIUS_METERS * math.asin(min(1.0, math.sqrt(a)))


@dataclass(frozen=True)
class ReferenceTileMeta:
    """One indexed tile's geo tag -- the plain, wire-agnostic counterpart of `GeoCandidate`
    (§3.1) minus the similarity score, which only exists at search time.

    `distinctiveness` (Slice A, §13.3 item 1): the tile's leave-one-out self-margin against the
    rest of the region's descriptors (`cv_service/geo/calibrate.py#leave_one_out_distinctiveness`
    -- see its docstring for the definition and the honesty note on why it is NOT the holdout
    margin). `None` on regions built before the field existed -- consumers MUST treat `None` as
    neutral (verification candidate ordering falls back to pure retrieval order)."""

    tile_id: str  # "<z>/<x>/<y>"
    lat: float
    lon: float
    distinctiveness: Optional[float] = None


@dataclass(frozen=True)
class SearchResult:
    tile: ReferenceTileMeta
    similarity: float


@dataclass(frozen=True)
class ReferenceIndexStats:
    """Plain, wire-agnostic counterpart of `cv_pb2.ReferenceIndexStats` (§3.1) -- persisted to
    `index.json` (`write_index_json`/`read_index_json`); `cv_service/grpc/servicers.py` is the
    only place that translates this into the wire message."""

    tile_count: int
    descriptor_count: int
    descriptor_dim: int
    encoder_id: str
    accept_similarity: float
    accept_margin: float
    holdout_recall_at_1: float
    holdout_median_error_meters: float
    index_bytes: int


@dataclass(frozen=True)
class RegionIndexMeta:
    stats: ReferenceIndexStats
    built_at_millis: int


class ReferenceIndex:
    """Descriptors are assumed already L2-normalized (every `Encoder` guarantees this, see
    `cv_service/geo/encoder.py`) so cosine similarity is a plain dot product.

    `numpy` is imported lazily inside each method that actually needs it -- see module docstring
    for why the rest of this file stays importable without it.
    """

    def __init__(self, tiles: list[ReferenceTileMeta], descriptors: "np.ndarray") -> None:
        import numpy as np

        if len(tiles) != descriptors.shape[0]:
            raise ValueError(f"{len(tiles)} tiles but {descriptors.shape[0]} descriptor rows")
        self.tiles = tiles
        self.descriptors = descriptors
        self._lats = np.array([t.lat for t in tiles], dtype=np.float64)
        self._lons = np.array([t.lon for t in tiles], dtype=np.float64)

    def __len__(self) -> int:
        return len(self.tiles)

    def _candidate_mask(self, prior_lat: float, prior_lon: float, prior_radius_m: float) -> "np.ndarray":
        import numpy as np

        phi1 = np.radians(prior_lat)
        phi2 = np.radians(self._lats)
        dphi = np.radians(self._lats - prior_lat)
        dlambda = np.radians(self._lons - prior_lon)
        a = np.sin(dphi / 2) ** 2 + np.cos(phi1) * np.cos(phi2) * np.sin(dlambda / 2) ** 2
        distances = 2 * _EARTH_RADIUS_METERS * np.arcsin(np.minimum(1.0, np.sqrt(a)))
        return distances <= prior_radius_m

    def search(
        self,
        query_descriptor: "np.ndarray",
        top_k: int = 5,
        prior: Optional[tuple[float, float, float]] = None,
    ) -> tuple[list[SearchResult], float]:
        """`prior`: (lat, lon, radius_meters) restricts candidates to that geographic disc first
        (D5's recursive-prior bootstrap-then-track design, consumed starting Wave 3). Returns
        (ranked best-first results, search_ms)."""
        import numpy as np

        start = time.perf_counter()
        if len(self.tiles) == 0:
            return [], (time.perf_counter() - start) * 1000.0
        if prior is not None:
            plat, plon, pradius = prior
            candidate_idx = np.nonzero(self._candidate_mask(plat, plon, pradius))[0]
        else:
            candidate_idx = np.arange(len(self.tiles))
        if candidate_idx.size == 0:
            return [], (time.perf_counter() - start) * 1000.0
        sims = self.descriptors[candidate_idx] @ query_descriptor
        k = min(top_k, sims.shape[0])
        top_local = np.argpartition(-sims, k - 1)[:k]
        top_local = top_local[np.argsort(-sims[top_local])]
        top_global = candidate_idx[top_local]
        results = [
            SearchResult(tile=self.tiles[g], similarity=float(s)) for g, s in zip(top_global, sims[top_local])
        ]
        return results, (time.perf_counter() - start) * 1000.0

    def save(self, region_dir: Path) -> None:
        """Persist `descriptors.npy` + `tiles.json` -- the two files `load()` reads back.

        Dtype-preserving: whatever dtype the in-memory descriptors carry is what lands on disk
        (`cv_service/geo/orchestrator.py` decides the stored precision -- fp16 as of Slice A,
        §13.3 item 6, measured max sim shift 1.2e-4 / margin shift 6.1e-5 across all six real
        regions, ~100x below the 0.01 calibration grain, zero top-1 changes). A tile's
        `distinctiveness` is written only when set, so pre-Slice-A readers of `tiles.json`
        see the exact same three keys they always did."""
        import numpy as np

        np.save(region_dir / DESCRIPTORS_FILENAME, self.descriptors)
        payload = []
        for t in self.tiles:
            row: dict = {"tileId": t.tile_id, "lat": t.lat, "lon": t.lon}
            if t.distinctiveness is not None:
                row["distinctiveness"] = t.distinctiveness
            payload.append(row)
        (region_dir / TILES_JSON_FILENAME).write_text(json.dumps(payload))

    @classmethod
    def load(cls, region_dir: Path) -> "ReferenceIndex":
        import numpy as np

        descriptors = np.load(region_dir / DESCRIPTORS_FILENAME)
        payload = json.loads((region_dir / TILES_JSON_FILENAME).read_text())
        tiles = [
            ReferenceTileMeta(
                tile_id=t["tileId"],
                lat=t["lat"],
                lon=t["lon"],
                # Absent on regions built before Slice A -- loads as None (neutral ordering).
                distinctiveness=t.get("distinctiveness"),
            )
            for t in payload
        ]
        return cls(tiles, descriptors)


def write_index_json(
    region_dir: Path, stats: ReferenceIndexStats, built_at_millis: Optional[int] = None
) -> RegionIndexMeta:
    resolved_built_at = built_at_millis if built_at_millis is not None else int(time.time() * 1000)
    payload = {
        "tileCount": stats.tile_count,
        "descriptorCount": stats.descriptor_count,
        "descriptorDim": stats.descriptor_dim,
        "encoderId": stats.encoder_id,
        "acceptSimilarity": stats.accept_similarity,
        "acceptMargin": stats.accept_margin,
        "holdoutRecallAt1": stats.holdout_recall_at_1,
        "holdoutMedianErrorMeters": stats.holdout_median_error_meters,
        "indexBytes": stats.index_bytes,
        "builtAtMillis": resolved_built_at,
    }
    (region_dir / INDEX_JSON_FILENAME).write_text(json.dumps(payload))
    return RegionIndexMeta(stats=stats, built_at_millis=resolved_built_at)


def read_index_json(region_dir: Path) -> Optional[RegionIndexMeta]:
    """Best-effort read of a built region's `index.json` -- `None` if missing/corrupt (a landed
    but not-yet-(re)built region, or a corrupt file). Forgiving contract, same posture as
    `cv_service.training.marker.read_active_model`."""
    path = region_dir / INDEX_JSON_FILENAME
    if not path.is_file():
        return None
    try:
        payload = json.loads(path.read_text())
        stats = ReferenceIndexStats(
            tile_count=int(payload["tileCount"]),
            descriptor_count=int(payload["descriptorCount"]),
            descriptor_dim=int(payload["descriptorDim"]),
            encoder_id=str(payload["encoderId"]),
            accept_similarity=float(payload["acceptSimilarity"]),
            accept_margin=float(payload["acceptMargin"]),
            holdout_recall_at_1=float(payload["holdoutRecallAt1"]),
            holdout_median_error_meters=float(payload["holdoutMedianErrorMeters"]),
            index_bytes=int(payload["indexBytes"]),
        )
        return RegionIndexMeta(stats=stats, built_at_millis=int(payload["builtAtMillis"]))
    except (json.JSONDecodeError, KeyError, TypeError, ValueError, OSError) as exc:
        LOGGER.warning(
            "region %s has a corrupt %s (%s); treating as not-yet-built",
            region_dir.name,
            INDEX_JSON_FILENAME,
            exc,
        )
        return None


# --- verify-ready tile cache (Slice A, §13.3 item 2) --------------------------------------------
#
# Verification (LoFTR) matches the query against a candidate tile's GRAYSCALE image resized to at
# most `VERIFY_GRAY_MAX_EDGE` px on the longest side (`verify.py#prep_gray_tensor`'s exact prep).
# The gray form is deterministic per tile, so it is computed ONCE at region build time -- where
# the tile JPEG is already decoded for encoding anyway -- and persisted here, instead of paying
# JPEG decode + cvtColor per verification at query time. Absent cache (any pre-Slice-A region)
# falls back to the on-the-fly disk path, unchanged.
#
# Layout: one uncompressed npz per region -- `tile_ids` (array of str), `scales` (float32,
# resized_px = native_px * scale), and one `gray_<row>` uint8 array per tile (per-tile keys, not
# one stacked array, so mixed tile sizes stay representable and `np.load` reads only the members
# actually asked for). Needs cv2/numpy -- both imported lazily, same discipline as
# `ReferenceIndex` (this file must stay importable without the `geo` extra).


def prep_verify_gray(image_bgr) -> tuple:
    """Grayscale + resize-to-`VERIFY_GRAY_MAX_EDGE` -- `verify.py#prep_gray_tensor`'s image-side
    math (everything before the torch tensor wrap), duplicated here deliberately so the build
    path never imports torch. Returns `(gray_uint8, scale)`."""
    import cv2

    gray = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2GRAY)
    h, w = gray.shape
    scale = VERIFY_GRAY_MAX_EDGE / max(h, w)
    if scale < 1.0:
        gray = cv2.resize(gray, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_AREA)
    else:
        scale = 1.0
    return gray, scale


def write_verify_tiles(region_dir: Path, tile_ids: list, grays: list, scales: list) -> int:
    """Persist the region's verify-ready gray tiles; returns the written file's size in bytes.
    Parallel lists, one entry per indexed tile (same order as `tiles.json`)."""
    import numpy as np

    if not (len(tile_ids) == len(grays) == len(scales)):
        raise ValueError(
            f"verify-tile cache arity mismatch: {len(tile_ids)} ids, {len(grays)} grays, "
            f"{len(scales)} scales"
        )
    arrays = {f"gray_{row}": gray for row, gray in enumerate(grays)}
    arrays["tile_ids"] = np.array(tile_ids, dtype=str)
    arrays["scales"] = np.array(scales, dtype=np.float32)
    path = region_dir / VERIFY_TILES_FILENAME
    np.savez(path, **arrays)
    return path.stat().st_size


class VerifyTileCache:
    """Read side of the cache: `get(tile_id) -> (gray_uint8, scale) | None`. Holds the npz
    lazily-loaded members; misses (a tile id the cache doesn't carry) return None so the caller
    falls back to the on-the-fly disk path."""

    def __init__(self, npz, row_by_tile_id: dict) -> None:
        self._npz = npz
        self._row_by_tile_id = row_by_tile_id

    def __len__(self) -> int:
        return len(self._row_by_tile_id)

    def get(self, tile_id: str):
        row = self._row_by_tile_id.get(tile_id)
        if row is None:
            return None
        try:
            gray = self._npz[f"gray_{row}"]
            scale = float(self._npz["scales"][row])
        except (KeyError, IndexError, ValueError, OSError) as exc:
            LOGGER.warning("verify-tile cache row %d (%s) unreadable (%s)", row, tile_id, exc)
            return None
        return gray, scale


def load_verify_tiles(region_dir: Path) -> Optional[VerifyTileCache]:
    """Best-effort cache open -- `None` if the file is missing/corrupt (pre-Slice-A region, or a
    damaged cache): the caller's fallback is the on-the-fly decode path, never an error. Same
    forgiving posture as `read_index_json`."""
    path = region_dir / VERIFY_TILES_FILENAME
    if not path.is_file():
        return None
    import numpy as np

    try:
        npz = np.load(path)
        tile_ids = [str(t) for t in npz["tile_ids"]]
    except (KeyError, ValueError, OSError, EOFError) as exc:
        LOGGER.warning(
            "region %s has a corrupt %s (%s); verification falls back to on-the-fly tile decode",
            region_dir.name,
            VERIFY_TILES_FILENAME,
            exc,
        )
        return None
    return VerifyTileCache(npz, {tile_id: row for row, tile_id in enumerate(tile_ids)})
