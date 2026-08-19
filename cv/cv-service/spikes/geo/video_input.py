"""Real-footage input: extract keyframes from a video at a configurable
interval, parse a telemetry CSV, align each frame to its nearest-in-time
telemetry row -- the ground truth for recall/error metrics. Zero manual
frame-labeling (docs/VISUAL-GEO-PLAN.md §5's "point me at a video + a log,
get a report").

Expected telemetry CSV header: `timestamp_ms,lat,lon,heading` (`heading`
optional -- a missing/blank column is fine, rows just carry `heading=None`).
`timestamp_ms` is milliseconds since an arbitrary but CONSISTENT epoch for
the whole file -- it only has to agree with the video's own frame timestamps
(both measured from "recording start" is the common case; if your logger
uses wall-clock epoch ms instead, shift one column so they line up before
running the spike).
"""

from __future__ import annotations

import bisect
import csv
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import cv2

from spikes.geo.manifest import QueryFrame

LOGGER = logging.getLogger("spikes.geo.video_input")

DEFAULT_KEYFRAME_INTERVAL_SECONDS = 2.0  # matches vision.geo.keyframe-interval default (§3.5)
DEFAULT_MAX_ALIGN_GAP_MS = 2_000


@dataclass(frozen=True)
class TelemetryRow:
    timestamp_ms: int
    lat: float
    lon: float
    heading: Optional[float]


def parse_telemetry_csv(path: Path) -> list[TelemetryRow]:
    rows: list[TelemetryRow] = []
    with path.open(newline="") as handle:
        reader = csv.DictReader(handle)
        required = {"timestamp_ms", "lat", "lon"}
        missing = required - set(field.strip() for field in (reader.fieldnames or []))
        if missing:
            raise ValueError(
                f"{path}: telemetry CSV missing required column(s) {sorted(missing)} -- "
                "expected header `timestamp_ms,lat,lon,heading` (heading optional)"
            )
        for line_number, record in enumerate(reader, start=2):
            try:
                timestamp_ms = int(float(record["timestamp_ms"]))
                lat = float(record["lat"])
                lon = float(record["lon"])
            except (KeyError, TypeError, ValueError) as exc:
                LOGGER.warning("%s:%d: skipping malformed telemetry row: %s", path, line_number, exc)
                continue
            heading_raw = (record.get("heading") or "").strip()
            heading = float(heading_raw) if heading_raw else None
            rows.append(TelemetryRow(timestamp_ms=timestamp_ms, lat=lat, lon=lon, heading=heading))
    rows.sort(key=lambda row: row.timestamp_ms)
    return rows


def extract_frames(video_path: Path, out_dir: Path, interval_seconds: float) -> list[tuple[int, Path]]:
    """Samples one frame every `interval_seconds` of video time. Returns
    `(timestamp_ms, image_path)` in playback order. `timestamp_ms` is video-
    relative (starts at ~0), matching `TelemetryRow.timestamp_ms`'s
    "consistent epoch" contract above."""
    out_dir.mkdir(parents=True, exist_ok=True)
    capture = cv2.VideoCapture(str(video_path))
    if not capture.isOpened():
        raise ValueError(f"could not open video {video_path} (unsupported codec, or file not found)")

    fps = capture.get(cv2.CAP_PROP_FPS) or 0.0
    frame_step = max(1, round(fps * interval_seconds)) if fps > 0 else 1
    if fps <= 0:
        LOGGER.warning("%s: could not read FPS from container, sampling every frame instead", video_path)

    extracted: list[tuple[int, Path]] = []
    frame_index = 0
    saved_index = 0
    try:
        while True:
            ok, frame = capture.read()
            if not ok:
                break
            if frame_index % frame_step == 0:
                timestamp_ms = int(capture.get(cv2.CAP_PROP_POS_MSEC))
                if timestamp_ms <= 0 and fps > 0:
                    timestamp_ms = int(frame_index / fps * 1000)
                image_path = out_dir / f"frame_{saved_index:05d}.jpg"
                cv2.imwrite(str(image_path), frame)
                extracted.append((timestamp_ms, image_path))
                saved_index += 1
            frame_index += 1
    finally:
        capture.release()

    LOGGER.info("extracted %d keyframes from %s (fps=%.2f, every %d frames)", len(extracted), video_path, fps, frame_step)
    return extracted


def align_frames_to_telemetry(
    frames: list[tuple[int, Path]],
    telemetry: list[TelemetryRow],
    max_gap_ms: int = DEFAULT_MAX_ALIGN_GAP_MS,
) -> list[QueryFrame]:
    """Nearest-in-time telemetry row per frame; a frame with no telemetry
    row within `max_gap_ms` is dropped (logged, not raised) rather than
    given a fabricated ground truth."""
    if not telemetry:
        raise ValueError("telemetry log is empty -- nothing to align frames against")
    timestamps = [row.timestamp_ms for row in telemetry]
    aligned: list[QueryFrame] = []
    dropped = 0
    for frame_index, (timestamp_ms, image_path) in enumerate(frames):
        position = bisect.bisect_left(timestamps, timestamp_ms)
        candidates = [i for i in (position - 1, position) if 0 <= i < len(telemetry)]
        if not candidates:
            dropped += 1
            continue
        nearest = min(candidates, key=lambda i: abs(telemetry[i].timestamp_ms - timestamp_ms))
        gap = abs(telemetry[nearest].timestamp_ms - timestamp_ms)
        if gap > max_gap_ms:
            dropped += 1
            continue
        row = telemetry[nearest]
        aligned.append(
            QueryFrame(
                query_id=f"video-{frame_index:05d}",
                image_path=image_path,
                lat=row.lat,
                lon=row.lon,
                heading=row.heading,
                timestamp_ms=timestamp_ms,
            )
        )
    if dropped:
        LOGGER.warning("dropped %d/%d frames with no telemetry row within %dms", dropped, len(frames), max_gap_ms)
    return aligned
