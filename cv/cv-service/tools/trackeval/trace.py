"""Read a saved `CvTrace` JSON into a `recording.Recording` (CV-ORCHESTRATION
wave W5b, decision E23).

W5 shipped "Save trace" (`station/vision-web`'s `/manage/cv` inspector), but
the file it downloads could not replay through this harness: `FrameLedger`
carried a detection *count*, not boxes, and `ObjectState.detectorBox` only
ever exists for a MATCHED object, so every box the associator rejected was
already gone by the time the trace was saved. Wave W5b closes that gap at
the source -- `FrameLedger.detections` now carries `detect.full`'s own raw,
pre-association boxes for the frame, but only when the request traced (see
`cv_service/orchestration/ledger.py`'s `detections` field and
`cv_service/tracking/session.py#process`'s `trace` kwarg).

**Why the detector's raw boxes, not the tracker's own output.** A `CvTrace`
also carries `world[]` -- the CURRENT `WorldObject` fold, the tracker's own
already-associated result. Replaying THAT through the tracker would prove
nothing (it is what the tracker already decided); `frame[].detections` is
the real, pre-association fixture -- the same real-detector-noise source
`recording.py`'s real-footage path exists for (its own module docstring),
this time read from a saved trace instead of a live capture loop.

**What a trace replay cannot do.** A `CvTrace` carries no `CameraPose` at
all -- `FrameLedger` has no yaw/pitch/roll/hfov/vfov field on the wire, so
every `RecordedFrame` this reader builds keeps `recording.RecordedFrame`'s
own zero-default pose. Replay therefore runs WITHOUT ego-motion
compensation (`pose_gmc`) -- see `README.md`'s "Replaying a saved trace" for
the disclosure this module's CLI caller surfaces.

Pure stdlib, same discipline as `recording.py` itself.
"""

from __future__ import annotations

import json
from pathlib import Path
from statistics import median
from typing import Any, Mapping

from tools.trackeval.recording import RecordedDetection, RecordedFrame, Recording


def read_trace(path: "Path | str") -> Recording:
    """Load a saved `CvTrace` JSON (`dto.CvTraceResponse`'s wire shape,
    `station/vision-web`'s "Save trace" download) and return its `frame[]`
    as a pixel-free `Recording`.

    `frame[]` is sorted by `sequence` first -- a trace's own JSON order is
    not guaranteed to be capture order -- and `index` is then this sorted
    position, matching `recording.RecordedFrame.index`'s own "position in
    replay order" contract. `timestamp_millis` is each frame's
    `capturedAtMillis` unchanged. `width`/`height` come from the FIRST frame
    (by `sequence`) `frameWidth`/`frameHeight` -- every frame in one traced
    session shares one source resolution, so the first is as good as any,
    and reading only one avoids silently accepting a trace that changed
    resolution mid-session.

    Raises `ValueError`:
    - when the trace has fewer than 2 frames (no consecutive gap to compute
      `fps` from);
    - when the first frame carries no `frameWidth`/`frameHeight` (`0` or
      absent) -- a trace saved before wave W5b never carried a frame's pixel
      size at all, so it cannot replay through this reader.

    A non-positive median interval (two ledger entries sharing one
    millisecond-resolution timestamp, or a clock that went backward) is not
    an error here: `fps` becomes `0.0`, the same "not known" sentinel
    `recording.RecordingReplayResult.fps`/`metrics.summarize_recording`
    already treat as the unknown-duration case (`duration_seconds = ...
    if fps > 0 else 0.0`) rather than a fabricated positive number.
    """
    payload = json.loads(Path(path).read_text())
    frames_wire = sorted(payload.get("frame", ()), key=lambda entry: entry["sequence"])
    if len(frames_wire) < 2:
        raise ValueError(
            f"trace at {path} has {len(frames_wire)} frame(s); at least 2 are needed to compute fps from "
            "consecutive timestamp deltas"
        )

    first = frames_wire[0]
    width = first.get("frameWidth", 0)
    height = first.get("frameHeight", 0)
    if not width or not height:
        raise ValueError(
            f"trace at {path}: first frame (sequence {first['sequence']}) carries no frameWidth/frameHeight -- "
            "a trace saved before CV-ORCHESTRATION wave W5b never carried a frame's pixel size, so it cannot "
            "replay through tools/trackeval"
        )

    timestamps = [entry["capturedAtMillis"] for entry in frames_wire]
    deltas = [later - earlier for earlier, later in zip(timestamps, timestamps[1:])]
    median_delta_millis = median(deltas)
    fps = 1000.0 / median_delta_millis if median_delta_millis > 0 else 0.0

    frames = tuple(
        RecordedFrame(
            index=index,
            timestamp_millis=entry["capturedAtMillis"],
            detections=tuple(_detection(item) for item in entry.get("detections", ())),
            # Camera pose is left at RecordedFrame's own zero default --
            # see the module docstring's "What a trace replay cannot do".
        )
        for index, entry in enumerate(frames_wire)
    )

    return Recording(name=Path(path).stem, fps=fps, width=width, height=height, frames=frames)


def _detection(wire: Mapping[str, Any]) -> RecordedDetection:
    """One wire `TracedDetection` (`{label, confidence, box: {x, y, width,
    height}}`) to a `RecordedDetection` -- the same flat shape
    `recording.py`'s own real-footage path already uses."""
    box = wire["box"]
    return RecordedDetection(
        label=wire["label"],
        confidence=wire["confidence"],
        x=box["x"],
        y=box["y"],
        width=box["width"],
        height=box["height"],
    )
