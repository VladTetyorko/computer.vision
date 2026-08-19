"""Pre-labeled query frames: `{"path": ..., "lat": ..., "lon": ..., "heading":
...}` per line (docs/VISUAL-GEO-PLAN.md §5's "manifest.jsonl" input). Used
directly by users who already have extracted frames with known ground
truth, and by synth.py to describe the smoke test's synthetic frames.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

LOGGER = logging.getLogger("spikes.geo.manifest")


@dataclass(frozen=True)
class QueryFrame:
    query_id: str
    image_path: Path
    lat: float
    lon: float
    heading: Optional[float] = None
    timestamp_ms: Optional[int] = None
    # H0 addition (VISUAL-GEO-V2-PLAN.md §5 H0 deliverable 4, "rectify-first re-rank ... on a
    # SITL analytic track"): AGL altitude in metres, when known -- `condition_query`'s second
    # required prior alongside `heading` ("both together, never one", §4.2). Optional because
    # real extracted-video manifests (e.g. the Pexels clip) structurally have no telemetry.
    altitude_meters: Optional[float] = None


def load_manifest(path: Path) -> list[QueryFrame]:
    frames: list[QueryFrame] = []
    for line_number, raw_line in enumerate(path.read_text().splitlines(), start=1):
        line = raw_line.strip()
        if not line:
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"{path}:{line_number}: invalid JSON: {exc}") from exc
        try:
            image_path = Path(record["path"])
            lat = float(record["lat"])
            lon = float(record["lon"])
        except (KeyError, TypeError, ValueError) as exc:
            raise ValueError(f"{path}:{line_number}: expected {{path, lat, lon}}: {exc}") from exc
        if not image_path.is_absolute():
            image_path = (path.parent / image_path).resolve()
        heading = record.get("heading")
        altitude_meters = record.get("altitude_meters")
        frames.append(
            QueryFrame(
                query_id=f"manifest-{line_number}",
                image_path=image_path,
                lat=lat,
                lon=lon,
                heading=float(heading) if heading is not None else None,
                timestamp_ms=record.get("timestamp_ms"),
                altitude_meters=float(altitude_meters) if altitude_meters is not None else None,
            )
        )
    return frames


def write_manifest(path: Path, frames: list[QueryFrame]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w") as handle:
        for frame in frames:
            record = {"path": str(frame.image_path), "lat": frame.lat, "lon": frame.lon}
            if frame.heading is not None:
                record["heading"] = frame.heading
            if frame.timestamp_ms is not None:
                record["timestamp_ms"] = frame.timestamp_ms
            if frame.altitude_meters is not None:
                record["altitude_meters"] = frame.altitude_meters
            handle.write(json.dumps(record) + "\n")
