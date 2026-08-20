"""Reference-pack zip landing for `Geolocation.BuildReferenceIndex` (docs/VISUAL-GEO-PLAN.md
§3.1, Wave 2a). Copies `cv_service/training/dataset.py#land_dataset`'s pattern almost verbatim:
land into a sibling temp dir under the data root, validate, atomically replace -- see that
module's docstring for the full zip-slip rationale. Stdlib-only (`os`, `re`, `shutil`, `json`,
`uuid`, `zipfile`, `pathlib`) -- importable without the `geo` extra, same discipline as
`cv_service/training/dataset.py`. Never imports `cv_pb2`.

Landed layout, exactly the frozen `ReferencePackChunk` shape (§3.1's doc comment): a region
directory `<CV_GEO_DATA_DIR>/<region_id>/` carrying `region.json` at its root and
`tiles/<z>_<x>_<y>.jpg`. `cv_service/geo/index.py`'s built artifacts (`descriptors.npy`,
`tiles.json`, `index.json`) are written into this SAME directory afterward, as siblings -- the
landed pack and the built index share one region directory, not two, because Wave 3a's
`verify.py` (LoFTR geometric verification) needs the raw tile JPEG bytes alongside the
descriptors, not just the descriptors.
"""

from __future__ import annotations

import json
import logging
import os
import re
import shutil
import uuid
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

LOGGER = logging.getLogger("cv_service.geo.pack")

REGION_JSON_NAME = "region.json"
TILES_DIRNAME = "tiles"

# `<z>_<x>_<y>.jpg` -- must describe the same shape as
# `cv_service/geo/index.py#TILE_FILENAME_RE` (kept as a separate copy on purpose: this one guards
# zip-entry *paths* before extraction, index.py's parses an *already-landed* file's name -- same
# shape, different concern/timing, same discipline `cv_service/training/dataset.py`'s own
# `is_safe_zip_entry` uses for `images/`/`labels/`).
_TILE_ENTRY_RE = re.compile(r"^\d+_\d+_\d+\.jpg$")


def sanitize_region_id(region_id: str) -> str:
    """Filesystem-safe basename for `region_id` -- same rule as
    `cv_service.training.dataset.sanitize_dataset_id`."""
    safe = "".join(c if (c.isalnum() or c in "-_.") else "_" for c in region_id)
    return safe.strip("._") or "region"


def is_safe_region_id(region_id: str) -> bool:
    """`BuildReferenceIndex`/`DeleteRegion`'s accept test: anything `sanitize_region_id` would
    rewrite (blank, a path separator, `..`, ...) is rejected outright rather than silently
    accepted under a different name -- same contract as
    `cv_service.training.dataset.is_safe_dataset_id`."""
    return bool(region_id) and sanitize_region_id(region_id) == region_id


def is_safe_pack_entry(name: str) -> bool:
    """Zip-slip guard for one archive member's path: accepts only `region.json` (exactly) or
    `tiles/<z>_<x>_<y>.jpg` (one path segment under `tiles/`) -- rejects an absolute path, a `..`
    segment, or any other prefix, mirroring `cv_service.training.dataset.is_safe_zip_entry`.
    """
    if not name:
        return False
    normalized = name.replace("\\", "/")
    if normalized.startswith("/") or ":" in normalized:
        return False
    parts = normalized.split("/")
    if ".." in parts or any(part == "" for part in parts[:-1]):
        return False
    if normalized == REGION_JSON_NAME:
        return True
    if normalized.startswith(f"{TILES_DIRNAME}/") and len(parts) == 2:
        return bool(_TILE_ENTRY_RE.match(parts[1]))
    return False


@dataclass(frozen=True)
class PackOutcome:
    """Plain (wire-agnostic) result of landing an uploaded reference pack archive.

    `cv_service/grpc/servicers.py` is the only place that turns this into a
    `cv_pb2.ReferenceIndexProgress` -- this module never imports `cv_pb2` (see module docstring).
    """

    ok: bool
    message: str
    region_id: str = ""
    bytes_received: int = 0
    tile_count: int = 0


def land_pack(zip_path: Path, data_dir: Path, region_id: str, bytes_received: int) -> PackOutcome:
    """Validate + extract `zip_path` into a sibling temp dir under `data_dir`, then atomically
    replace `<data_dir>/<region_id>/` (remove-then-`os.replace`). Every failure from here on is a
    *reported* `ok=False` outcome -- never raised -- matching `Geolocation.BuildReferenceIndex`'s
    "content problems are reported as a terminal FAILED event, not aborted" contract (§3.1),
    same posture `cv_service.training.dataset.land_dataset` already established for
    `UploadDataset`.
    """
    temp_dir = data_dir / f".pack-{region_id}-{uuid.uuid4().hex}"
    try:
        with zipfile.ZipFile(zip_path) as zf:
            bad_entry = zf.testzip()
            if bad_entry is not None:
                return PackOutcome(
                    ok=False,
                    region_id=region_id,
                    bytes_received=bytes_received,
                    message=f"corrupt reference pack archive (bad entry: {bad_entry!r})",
                )
            members = [member for member in zf.infolist() if not member.is_dir()]
            for member in members:
                if not is_safe_pack_entry(member.filename):
                    return PackOutcome(
                        ok=False,
                        region_id=region_id,
                        bytes_received=bytes_received,
                        message=f"reference pack archive contains an unsafe entry: {member.filename!r}",
                    )
            temp_dir.mkdir()
            zf.extractall(path=temp_dir, members=members)
    except (zipfile.BadZipFile, OSError, EOFError) as exc:
        shutil.rmtree(temp_dir, ignore_errors=True)
        return PackOutcome(
            ok=False,
            region_id=region_id,
            bytes_received=bytes_received,
            message=f"corrupt or unreadable reference pack archive: {exc}",
        )

    region_json_path = temp_dir / REGION_JSON_NAME
    tiles_dir = temp_dir / TILES_DIRNAME
    if not region_json_path.is_file():
        shutil.rmtree(temp_dir, ignore_errors=True)
        return PackOutcome(
            ok=False,
            region_id=region_id,
            bytes_received=bytes_received,
            message=f"reference pack archive is missing {REGION_JSON_NAME!r}",
        )
    try:
        json.loads(region_json_path.read_text())
    except (json.JSONDecodeError, OSError) as exc:
        shutil.rmtree(temp_dir, ignore_errors=True)
        return PackOutcome(
            ok=False,
            region_id=region_id,
            bytes_received=bytes_received,
            message=f"{REGION_JSON_NAME} is not valid JSON: {exc}",
        )
    if not tiles_dir.is_dir():
        shutil.rmtree(temp_dir, ignore_errors=True)
        return PackOutcome(
            ok=False,
            region_id=region_id,
            bytes_received=bytes_received,
            message=f"reference pack archive is missing {TILES_DIRNAME}/",
        )
    tile_count = sum(1 for path in tiles_dir.iterdir() if path.is_file())
    if tile_count == 0:
        shutil.rmtree(temp_dir, ignore_errors=True)
        return PackOutcome(
            ok=False,
            region_id=region_id,
            bytes_received=bytes_received,
            message="reference pack archive contains no tiles",
        )

    final_dir = data_dir / region_id
    if final_dir.exists():
        shutil.rmtree(final_dir)
    os.replace(temp_dir, final_dir)

    return PackOutcome(
        ok=True,
        region_id=region_id,
        bytes_received=bytes_received,
        tile_count=tile_count,
        message=f"reference pack {region_id!r} landed ({tile_count} tiles)",
    )


def read_region_meta(region_dir: Path) -> Optional[dict]:
    """Best-effort read of a landed `region.json` -- `None` if missing/corrupt (caller treats
    that as "not a valid/built region"), same forgiving contract as
    `cv_service.training.marker.read_active_model`."""
    path = region_dir / REGION_JSON_NAME
    if not path.is_file():
        return None
    try:
        return json.loads(path.read_text())
    except (json.JSONDecodeError, OSError) as exc:
        LOGGER.warning("region %s has a corrupt %s (%s)", region_dir.name, REGION_JSON_NAME, exc)
        return None
