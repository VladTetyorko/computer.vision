"""`tools.trackeval.trace` -- reading a saved `CvTrace` JSON into a
`recording.Recording` (CV-ORCHESTRATION wave W5b, decision E23).

Pure stdlib, same as `trace.py` itself: unlike `test_recording.py` (which
needs `numpy` only to build its synthetic "live" source via `sequences.py`),
every fixture here is a hand-written/committed JSON file, so no
`pytest.importorskip` is needed.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from cv_service.tracking.engines.base import CameraPose

from tools.trackeval.recording import RecordedDetection, replay_recording
from tools.trackeval.trace import read_trace

FIXTURES_DIR = Path(__file__).parent / "fixtures"
MIN_TRACE_PATH = FIXTURES_DIR / "min-trace.json"

# Copied from station/vision-web/src/app/core/api/__fixtures__/cv-trace.wire.json's
# "full" example (station/vision-api's CvTraceResponseWireContractTest, wave
# W5b.3) rather than read from that path directly: the real fixture wraps its
# content in a {"full": ..., "minimal": ...} test-harness envelope that
# read_trace does not (and should not) know how to unwrap, so parsing it
# directly here would be testing this test's own unwrapping code, not
# read_trace. This file is that "full" value, verbatim. Regenerate with:
#   python3 -c "import json; d = json.load(open(
#       'station/vision-web/src/app/core/api/__fixtures__/cv-trace.wire.json'));
#       json.dump(d['full'], open(
#       'cv/cv-service/tests/trackeval/fixtures/cv-trace.wire.full.json', 'w'),
#       indent=2, sort_keys=True)"
# run from the repo root, whenever that upstream fixture changes shape.
JAVA_FIXTURE_PATH = FIXTURES_DIR / "cv-trace.wire.full.json"


def test_read_trace_returns_frames_sorted_by_sequence_not_file_order() -> None:
    # min-trace.json lists sequence 12 first, then 10, then 11 -- a trace's
    # own JSON order is not guaranteed to be capture order.
    recording = read_trace(MIN_TRACE_PATH)

    assert recording.name == "min-trace"
    assert [frame.index for frame in recording.frames] == [0, 1, 2]
    assert [frame.timestamp_millis for frame in recording.frames] == [
        1700000000000,
        1700000000100,
        1700000000200,
    ]


def test_read_trace_reads_width_height_from_the_first_frame_by_sequence() -> None:
    recording = read_trace(MIN_TRACE_PATH)
    assert recording.width == 640
    assert recording.height == 480


def test_read_trace_computes_fps_as_the_median_consecutive_delta() -> None:
    # Sorted deltas: 100ms, 100ms -> median 100ms -> 10 fps.
    recording = read_trace(MIN_TRACE_PATH)
    assert recording.fps == pytest.approx(10.0)


def test_read_trace_maps_detections_onto_recorded_detections() -> None:
    recording = read_trace(MIN_TRACE_PATH)

    assert recording.frames[0].detections == (
        RecordedDetection("person", 0.88, 0.10, 0.15, 0.12, 0.22),
        RecordedDetection("car", 0.63, 0.55, 0.60, 0.20, 0.15),
    )
    assert recording.frames[1].detections == ()
    assert recording.frames[2].detections == (RecordedDetection("person", 0.71, 0.30, 0.35, 0.10, 0.20),)


def test_read_trace_leaves_camera_pose_at_zero() -> None:
    """No CameraPose exists on the wire at all -- replay runs without
    ego-motion compensation, disclosed in README.md's "Replaying a saved
    trace"."""
    recording = read_trace(MIN_TRACE_PATH)
    for frame in recording.frames:
        assert frame.camera_pose() == CameraPose()


def test_replay_recording_runs_a_read_trace_and_yields_at_least_one_track() -> None:
    recording = read_trace(MIN_TRACE_PATH)
    result = replay_recording(recording)

    assert len(result.outcomes) == len(recording.frames)
    ever_tracked = any(tracked.track is not None for outcome in result.outcomes for tracked in (outcome.boxes or []))
    assert ever_tracked


def test_read_trace_rejects_fewer_than_two_frames(tmp_path: Path) -> None:
    path = tmp_path / "one-frame.json"
    path.write_text(
        json.dumps(
            {
                "frame": [
                    {"sequence": 0, "capturedAtMillis": 0, "detections": [], "frameWidth": 100, "frameHeight": 100},
                ],
            }
        )
    )
    with pytest.raises(ValueError, match="at least 2"):
        read_trace(path)


def test_read_trace_rejects_a_pre_w5b_trace_with_no_frame_size(tmp_path: Path) -> None:
    path = tmp_path / "pre-w5b.json"
    path.write_text(
        json.dumps(
            {
                "frame": [
                    {"sequence": 0, "capturedAtMillis": 0, "detections": []},
                    {"sequence": 1, "capturedAtMillis": 100, "detections": []},
                ],
            }
        )
    )
    with pytest.raises(ValueError, match="cannot replay"):
        read_trace(path)


def test_read_trace_treats_a_zero_median_delta_as_unknown_fps_not_an_error(tmp_path: Path) -> None:
    """The real Java-generated wire-contract fixture (`JAVA_FIXTURE_PATH`
    above) happens to share one fixed `capturedAtMillis` across both of its
    frame examples -- a wire-contract-test artifact, not a real capture --
    so this reader must not treat a non-positive median delta as an error."""
    path = tmp_path / "same-timestamp.json"
    path.write_text(
        json.dumps(
            {
                "frame": [
                    {
                        "sequence": 0,
                        "capturedAtMillis": 1000,
                        "detections": [],
                        "frameWidth": 100,
                        "frameHeight": 100,
                    },
                    {
                        "sequence": 1,
                        "capturedAtMillis": 1000,
                        "detections": [],
                        "frameWidth": 100,
                        "frameHeight": 100,
                    },
                ],
            }
        )
    )
    recording = read_trace(path)
    assert recording.fps == 0.0


def test_read_trace_parses_the_java_generated_wire_contract_fixture() -> None:
    """`station/vision-api`'s `CvTraceResponseWireContractTest` (wave W5b.3)
    is the other producer of a `CvTrace`-shaped JSON in this repo -- proving
    `read_trace` accepts what that side actually emits, not only this
    package's own hand-written fixture."""
    recording = read_trace(JAVA_FIXTURE_PATH)

    assert len(recording.frames) == 2
    assert recording.width == 1920
    assert recording.height == 1080
    assert len(recording.frames[0].detections) == 2
    assert recording.frames[1].detections == ()

    result = replay_recording(recording)
    assert len(result.outcomes) == 2
