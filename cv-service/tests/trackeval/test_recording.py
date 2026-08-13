"""`tools.trackeval.recording` -- real-footage recording + offline replay.

Building the synthetic ground truth used to FEED a fake "live" recording
needs `numpy` (`sequences.py`'s own rendering discipline), even though
`recording.py` itself is pure stdlib; `pytest.importorskip` below mirrors
`test_replay.py`'s own reasoning for why.
"""

from __future__ import annotations

import pytest

pytest.importorskip("numpy")

from cv_service.tracking.engines.base import CameraPose
from cv_service.tracking.session import FrameOutcome

from tools.trackeval import metrics as metrics_module
from tools.trackeval.recording import (
    RECORDING_FORMAT_VERSION,
    LiveFrame,
    Recording,
    RecordedDetection,
    RecordedFrame,
    read_recording,
    record_stream,
    replay_recording,
    write_recording,
)
from tools.trackeval.replay import DetectorNoiseConfig, SyntheticDetector
from tools.trackeval.sequences import SCENARIOS


def _live_frames(scenario: str, *, seed: int = 0, jitter: float = 0.01) -> tuple[list[LiveFrame], float, int, int]:
    """A fake "live capture" -- the real SyntheticDetector over one of this
    package's own scenarios, converted to `LiveFrame`s exactly as a real
    caller with a live `PulledDetectionSession` would. `pose` sweeps `yaw`
    a little every frame so the CameraPose round-trip has something to
    actually check."""
    sequence = SCENARIOS[scenario](seed=seed)
    detector = SyntheticDetector(DetectorNoiseConfig(seed=seed, position_jitter=jitter))
    live_frames = []
    for frame in sequence.frames:
        detections = detector.detect(frame.ground_truth)
        live_frames.append(
            LiveFrame(
                index=frame.index,
                timestamp_millis=int(frame.index * (1000.0 / sequence.fps)),
                detections=[
                    RecordedDetection(d.label, d.confidence, d.x, d.y, d.width, d.height) for d in detections
                ],
                pose=CameraPose(yaw_degrees=0.3 * frame.index, hfov_degrees=62.0),
            )
        )
    return live_frames, sequence.fps, sequence.width, sequence.height


def test_record_stream_assembles_one_frame_per_live_frame() -> None:
    live_frames, fps, width, height = _live_frames("linear")
    recording = record_stream("linear-smoke", fps, width, height, live_frames)

    assert recording.name == "linear-smoke"
    assert recording.fps == fps
    assert recording.width == width
    assert recording.height == height
    assert len(recording.frames) == len(live_frames)
    assert recording.frames[0].camera_pose() == CameraPose(yaw_degrees=0.0, hfov_degrees=62.0)


def test_write_then_read_round_trips_exactly(tmp_path) -> None:
    live_frames, fps, width, height = _live_frames("crossing")
    recording = record_stream("crossing-smoke", fps, width, height, live_frames)

    path = tmp_path / "recording.json"
    write_recording(recording, path)
    assert path.exists()
    loaded = read_recording(path)

    assert loaded == recording


def test_write_recording_stamps_the_current_format_version(tmp_path) -> None:
    import json

    live_frames, fps, width, height = _live_frames("linear")
    recording = record_stream("linear-smoke", fps, width, height, live_frames)
    path = tmp_path / "recording.json"
    write_recording(recording, path)

    payload = json.loads(path.read_text())
    assert payload["format_version"] == RECORDING_FORMAT_VERSION


def test_read_recording_rejects_an_unknown_format_version(tmp_path) -> None:
    path = tmp_path / "bad.json"
    path.write_text('{"format_version": 999, "name": "x", "fps": 10.0, "width": 1, "height": 1, "frames": []}')
    with pytest.raises(ValueError):
        read_recording(path)


def test_recorded_detection_survives_the_json_round_trip(tmp_path) -> None:
    """Every field, not just the box -- confidence in particular, since it
    is what `assign.py`'s two-stage matcher gates on."""
    recording = Recording(
        name="one-frame",
        fps=10.0,
        width=320,
        height=240,
        frames=(
            RecordedFrame(
                index=0,
                timestamp_millis=0,
                detections=(RecordedDetection("object", 0.73, 0.1, 0.2, 0.3, 0.4),),
                yaw_degrees=12.5,
                pitch_degrees=-3.0,
                roll_degrees=1.0,
                hfov_degrees=58.0,
                vfov_degrees=42.0,
                pose_timestamp_millis=123,
            ),
        ),
    )
    path = tmp_path / "rec.json"
    write_recording(recording, path)
    loaded = read_recording(path)

    assert loaded.frames[0].detections[0] == RecordedDetection("object", 0.73, 0.1, 0.2, 0.3, 0.4)
    assert loaded.frames[0].camera_pose() == CameraPose(
        yaw_degrees=12.5, pitch_degrees=-3.0, roll_degrees=1.0, hfov_degrees=58.0, vfov_degrees=42.0, timestamp_millis=123
    )


def test_replay_recording_runs_the_real_session_without_raising() -> None:
    live_frames, fps, width, height = _live_frames("occlusion")
    recording = record_stream("occlusion-smoke", fps, width, height, live_frames)

    result = replay_recording(recording)

    assert len(result.outcomes) == len(recording.frames)
    assert all(isinstance(outcome, FrameOutcome) for outcome in result.outcomes)
    assert result.width == width
    assert result.height == height
    assert len(result.coast_track_ids) == len(result.outcomes)


def test_replay_recording_is_deterministic() -> None:
    live_frames, fps, width, height = _live_frames("crowd_recall")
    recording = record_stream("crowd_recall-smoke", fps, width, height, live_frames)

    first = replay_recording(recording)
    second = replay_recording(recording)

    first_ids = [
        sorted(tracked.track.track_id for tracked in (outcome.boxes or []) if tracked.track is not None)
        for outcome in first.outcomes
    ]
    second_ids = [
        sorted(tracked.track.track_id for tracked in (outcome.boxes or []) if tracked.track is not None)
        for outcome in second.outcomes
    ]
    assert first_ids == second_ids


def test_replay_recording_actually_tracks_a_confirmed_object() -> None:
    """Not just "runs without raising" -- a clean, always-visible object with
    real detections must actually pick up a track id at some point, or the
    whole pixel-free path is silently inert."""
    live_frames, fps, width, height = _live_frames("linear", jitter=0.0)
    recording = record_stream("linear-clean", fps, width, height, live_frames)

    result = replay_recording(recording)

    ever_tracked = any(
        tracked.track is not None for outcome in result.outcomes for tracked in (outcome.boxes or [])
    )
    assert ever_tracked


def test_summarize_recording_reports_no_coasting_for_a_fully_confirmed_replay() -> None:
    """Every frame in `linear` gets a fresh, matching detection -- nothing
    should ever be coasting, so the fraction must be exactly 0.0, not
    `None` (some track WAS emitted) and not > 0."""
    live_frames, fps, width, height = _live_frames("linear", jitter=0.0)
    recording = record_stream("linear-clean", fps, width, height, live_frames)
    result = replay_recording(recording)

    summary = metrics_module.summarize_recording(
        result.outcomes, fps=result.fps, coast_track_ids=result.coast_track_ids, name=result.name
    )

    assert summary.total_frames == len(recording.frames)
    assert summary.distinct_track_ids >= 1
    assert summary.coast_frame_fraction == pytest.approx(0.0)


def test_summarize_recording_needs_no_ground_truth() -> None:
    """The whole point of this function: it must work from `outcomes` alone,
    with nothing resembling `sequences.GroundTruthObject` anywhere nearby."""
    live_frames, fps, width, height = _live_frames("dropout")
    recording = record_stream("dropout-smoke", fps, width, height, live_frames)
    result = replay_recording(recording)

    summary = metrics_module.summarize_recording(result.outcomes, fps=result.fps)

    assert summary.total_frames == len(recording.frames)
    assert summary.coast_frame_fraction is None  # no coast_track_ids supplied this time
