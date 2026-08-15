"""Real-footage recording + offline replay (TRACKING-V3-PLAN wave V0).

Every scenario in `sequences.py` is synthetic -- known by construction because
"there is no camera in this repo" (`README.md`, `BASELINE.md`). That is a
real, durable limitation: no synthetic renderer can prove the tracker holds
up against a real detector's real confidence distribution, real jitter, or a
real gimbal's `CameraPose`. This module is the other half of wave V0's
charter -- a path from a LIVE stream to a file this harness can replay later,
so that when hardware tier H1 (a camera) is bought, closing that gap is a
day of capture, not a new subsystem.

**Pixel-free, deliberately, not merely allowed.** The wave brief asks for
"a recording... without pixels if that keeps the repo small (e.g. record
detections+pose)". This module goes further and makes pixel-free the ONLY
mode, for three reasons, not one:

1. **Repo size.** A committed recording is detections + `CameraPose` per
   frame -- a few hundred bytes each -- rather than a video file. A useful
   evaluation clip (hundreds of frames) stays well under a megabyte of JSON,
   diffable and reviewable like any other fixture in this repo.
2. **It stays honest about what a recording like this can and cannot prove.**
   FOLLOW's SOT engines (`lk`/`ncc`) and ASSOCIATE's `flow`/`histogram`
   compensators all need real pixels (`engines/base.py`'s own protocols).
   A recording with no pixels cannot exercise them, so `replay_recording`
   below has no `mode` parameter at all -- ASSOCIATE is the only mode it can
   honestly serve, not merely the one it defaults to -- and it forces
   `motion_engine_id`/`appearance_engine_id` to `"off"` explicitly rather
   than quietly feeding both compensators a `None` frame and letting
   whatever happens, happen. What a pixel-free recording CAN prove is the
   pure-identity core -- `predict`/`assign`/`history`/`reupdate`/`memory`/
   `pose_gmc` -- against REAL detector confidence and REAL `CameraPose`,
   which is exactly TRACKING-V3-PLAN §5.2's L1 RELAY tier, and exactly the
   layer waves V2-V6 add to.
3. **No camera exists here to record pixels FROM anyway** (see above) -- a
   half-built pixel path would be untested by construction, the same
   apparatus-failure shape TRACKING-V2-PLAN §5b's five defects all took.

`metrics.summarize_recording` is the other half of "no ground truth exists
for real footage": it reports the COST/structural axis (detector passes,
track lifetimes, coasting fraction) that needs no ground truth, explicitly
NOT an accuracy number -- see its own docstring.

Pure stdlib at module scope, same discipline as every other file here:
`Settings`/`TrackerRegistry`/`StreamTrackingSession` are all pure stdlib
already (`replay.py`'s own docstring), and this module adds only `json`
and `pathlib` on top.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Optional
from typing import Sequence as TypingSequence

from cv_service.config import Settings
from cv_service.tracking.engines.base import CameraPose
from cv_service.tracking.params import MODE_ASSOCIATE, TrackingRequest
from cv_service.tracking.registry import TrackerRegistry, build_default_registry
from cv_service.tracking.session import FrameOutcome, StreamTrackingSession

from tools.trackeval.replay import _SyntheticDetection, _coast_ids_this_frame

# Bumped only on an incompatible on-disk layout change -- `read_recording`
# refuses anything else rather than guessing at a schema that moved under it.
RECORDING_FORMAT_VERSION = 1

_WIRE_TOKEN = "trackeval-recording"
# `TrackingRequest` needs a mode; ASSOCIATE is the only one `replay_recording`
# ever actually resolves to (see its own docstring), so this is the one
# constructed here rather than threaded through every caller.
_REPLAY_MODE = MODE_ASSOCIATE


@dataclass(frozen=True)
class RecordedDetection:
    """One detector box, the plain wire-shaped facts a recording keeps --
    same fields `replay.py`'s `_SyntheticDetection` carries, so both sides of
    this harness speak one vocabulary for "a detection"."""

    label: str
    confidence: float
    x: float
    y: float
    width: float
    height: float


@dataclass(frozen=True)
class RecordedFrame:
    """One frame's detections + the `CameraPose` at capture -- no pixels.

    `CameraPose`'s six scalar fields are stored flat rather than nesting a
    `CameraPose` object, so `Recording`'s on-disk JSON has no engine-specific
    type to version alongside the file format -- `camera_pose()` rebuilds the
    real object only at replay time, the one place it is actually needed.
    """

    index: int
    timestamp_millis: int
    detections: tuple[RecordedDetection, ...] = ()
    yaw_degrees: float = 0.0
    pitch_degrees: float = 0.0
    roll_degrees: float = 0.0
    hfov_degrees: float = 0.0
    vfov_degrees: float = 0.0
    pose_timestamp_millis: int = 0

    def camera_pose(self) -> CameraPose:
        return CameraPose(
            yaw_degrees=self.yaw_degrees,
            pitch_degrees=self.pitch_degrees,
            roll_degrees=self.roll_degrees,
            hfov_degrees=self.hfov_degrees,
            vfov_degrees=self.vfov_degrees,
            timestamp_millis=self.pose_timestamp_millis,
        )


@dataclass(frozen=True)
class Recording:
    """A whole captured clip -- the pixel-free counterpart of `sequences.
    Sequence`. `width`/`height` are the SOURCE frame's dimensions (needed to
    interpret a normalized box in pixels later, e.g. `metrics.py`'s coast-px
    columns), not this recording's own storage size."""

    name: str
    fps: float
    width: int
    height: int
    frames: tuple[RecordedFrame, ...]


@dataclass(frozen=True)
class LiveFrame:
    """One frame's worth of data exactly as a live capture loop would hand it
    to `record_stream` -- deliberately decoupled from any actual gRPC/
    session wiring, since this repo has no camera to drive one with
    (hardware tier H1 is unbought, `README.md`). A caller with a real
    `PulledDetectionSession`/`DetectionFrameCodec` in hand builds one of
    these per frame it receives; everything from here down is exercised by
    this module's own tests using a synthetic source, and is unchanged
    whichever source built the `LiveFrame`.
    """

    index: int
    timestamp_millis: int
    detections: TypingSequence[RecordedDetection] = ()
    pose: CameraPose = CameraPose()


def record_stream(name: str, fps: float, width: int, height: int, frames: TypingSequence[LiveFrame]) -> Recording:
    """Assemble a `Recording` from already-captured `LiveFrame`s -- the
    "recorder" half of this module. Pure data assembly, no I/O; pair with
    `write_recording` to actually persist it."""
    return Recording(
        name=name,
        fps=fps,
        width=width,
        height=height,
        frames=tuple(
            RecordedFrame(
                index=live.index,
                timestamp_millis=live.timestamp_millis,
                detections=tuple(live.detections),
                yaw_degrees=live.pose.yaw_degrees,
                pitch_degrees=live.pose.pitch_degrees,
                roll_degrees=live.pose.roll_degrees,
                hfov_degrees=live.pose.hfov_degrees,
                vfov_degrees=live.pose.vfov_degrees,
                pose_timestamp_millis=live.pose.timestamp_millis,
            )
            for live in frames
        ),
    )


def write_recording(recording: Recording, path: "Path | str") -> None:
    """Persist as indented JSON -- diffable and reviewable in a PR, same
    reasoning `BASELINE.md` being a checked-in markdown table already
    follows for this package's other saved outputs."""
    payload = {
        "format_version": RECORDING_FORMAT_VERSION,
        "name": recording.name,
        "fps": recording.fps,
        "width": recording.width,
        "height": recording.height,
        "frames": [
            {
                "index": frame.index,
                "timestamp_millis": frame.timestamp_millis,
                "detections": [
                    {
                        "label": detection.label,
                        "confidence": detection.confidence,
                        "x": detection.x,
                        "y": detection.y,
                        "width": detection.width,
                        "height": detection.height,
                    }
                    for detection in frame.detections
                ],
                "pose": {
                    "yaw_degrees": frame.yaw_degrees,
                    "pitch_degrees": frame.pitch_degrees,
                    "roll_degrees": frame.roll_degrees,
                    "hfov_degrees": frame.hfov_degrees,
                    "vfov_degrees": frame.vfov_degrees,
                    "timestamp_millis": frame.pose_timestamp_millis,
                },
            }
            for frame in recording.frames
        ],
    }
    Path(path).write_text(json.dumps(payload, indent=2, sort_keys=True))


def read_recording(path: "Path | str") -> Recording:
    """Inverse of `write_recording`. Raises `ValueError` on a format this
    module does not know how to read, rather than guessing."""
    payload = json.loads(Path(path).read_text())
    version = payload.get("format_version")
    if version != RECORDING_FORMAT_VERSION:
        raise ValueError(
            f"unsupported recording format_version {version!r} (expected {RECORDING_FORMAT_VERSION}): {path}"
        )
    frames = tuple(
        RecordedFrame(
            index=entry["index"],
            timestamp_millis=entry["timestamp_millis"],
            detections=tuple(
                RecordedDetection(
                    label=detection["label"],
                    confidence=detection["confidence"],
                    x=detection["x"],
                    y=detection["y"],
                    width=detection["width"],
                    height=detection["height"],
                )
                for detection in entry.get("detections", ())
            ),
            **{
                field: entry.get("pose", {}).get(field, default)
                for field, default in (
                    ("yaw_degrees", 0.0),
                    ("pitch_degrees", 0.0),
                    ("roll_degrees", 0.0),
                    ("hfov_degrees", 0.0),
                    ("vfov_degrees", 0.0),
                )
            },
            pose_timestamp_millis=entry.get("pose", {}).get("timestamp_millis", 0),
        )
        for entry in payload["frames"]
    )
    return Recording(name=payload["name"], fps=payload["fps"], width=payload["width"], height=payload["height"], frames=frames)


@dataclass(frozen=True)
class RecordingReplayResult:
    """The structural-only counterpart of `replay.ReplayResult`: no ground
    truth exists for real footage, so there is no `ground_truth_by_frame`/
    `scored_gt_ids` here and `metrics.compute` does not apply -- see
    `metrics.summarize_recording` for what CAN still be measured."""

    name: str
    mode: str
    engine_id: str
    fps: float
    width: int
    height: int
    outcomes: tuple[FrameOutcome, ...]
    coast_track_ids: tuple[frozenset[int], ...]


def replay_recording(
    recording: Recording,
    *,
    engine_id: str = "",
    settings: Optional[Settings] = None,
    registry: Optional[TrackerRegistry] = None,
) -> RecordingReplayResult:
    """Feed `recording` through one real `StreamTrackingSession`, exactly the
    way `replay.run_replay` feeds a synthetic `Sequence` through it --
    ASSOCIATE only, `motion_engine_id`/`appearance_engine_id` forced to
    `"off"` (see the module docstring: a pixel-free recording cannot serve
    either), and `frame()` returns `None` since nothing on this path is
    ever supposed to call it once both are off.
    """
    settings = settings or Settings.from_env()
    registry = registry or build_default_registry(settings, probe=True)
    session = StreamTrackingSession(settings=settings, registry_provider=lambda: registry)
    session.apply_config(
        TrackingRequest(mode=_REPLAY_MODE, engine_id=engine_id, motion_engine_id="off", appearance_engine_id="off"),
        wire_token=_WIRE_TOKEN,
    )

    outcomes: list[FrameOutcome] = []
    coast_track_ids: list[frozenset[int]] = []
    for frame in recording.frames:
        detections = [
            _SyntheticDetection(d.label, d.confidence, d.x, d.y, d.width, d.height) for d in frame.detections
        ]

        def detect(roi=None, detections=detections) -> "tuple[list, int]":
            if roi is not None:
                # No live detector behind this replay to ask a second
                # question of -- a genuine no-op, same posture `_roi_rescue`
                # itself takes for a degenerate crop (P5).
                return [], 0
            return detections, 0

        def load_frame() -> None:
            return None  # pixel-free -- see the module docstring

        outcome = session.process(
            now_millis=float(frame.timestamp_millis),
            detect=detect,
            frame=load_frame,
            pose=frame.camera_pose(),
        )
        outcomes.append(outcome)
        coast_track_ids.append(_coast_ids_this_frame(outcome))

    engine_id_served = next((outcome.engine_id for outcome in reversed(outcomes) if outcome.engine_id), "")
    return RecordingReplayResult(
        name=recording.name,
        mode=_REPLAY_MODE,
        engine_id=engine_id_served,
        fps=recording.fps,
        width=recording.width,
        height=recording.height,
        outcomes=tuple(outcomes),
        coast_track_ids=tuple(coast_track_ids),
    )
