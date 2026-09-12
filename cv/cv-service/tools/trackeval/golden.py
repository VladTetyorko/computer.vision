"""Per-frame `FrameOutcome` golden dumps -- the zero-behavioural-delta gate.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` P7/E15 and wave W0's acceptance:
the contributor refactor ships only if every scenario x mode replays to a
BYTE-IDENTICAL sequence of frame outcomes. `BASELINE.md` (scored by
`metrics.py`) is necessary but nowhere near sufficient for that -- it is
fifteen aggregate numbers per row, so a refactor that moved an id from one
box to another, changed a label election by one frame, or emitted the same
boxes in a different order could leave every one of them untouched. This
module records the RAW per-frame outcome instead, so any such difference
fails the gate rather than being averaged away.

**Why the dump is captured during the replay, not from `ReplayResult`
afterwards.** `TrackedBox.track` holds a REFERENCE to the book's own mutable
`Track`, so every past frame's box aliases whatever that track looks like
NOW -- the exact trap `ReplayResult.coast_track_ids`/`track_velocities`/
`track_labels` already exist to work around. `run_replay`'s `observer` hook
calls into here the instant `process()` returns, before the next frame can
mutate anything.

**What is deliberately NOT dumped: `tracker_millis` and `motion_millis`.**
Both are `time.perf_counter()` wall clock, the same two quantities
`tests/trackeval/test_baseline_consistency.py` skips (`_SKIPPED_COLUMNS`)
and for the same reason -- they are machine noise, not behaviour, and
asserting them would make the gate fail on a busy laptop rather than on a
real delta. Every OTHER field of `FrameOutcome` is dumped, including
`inference_millis`, which LOOKS timing-shaped but is exact here: this
harness's `SyntheticDetector` reports `0` millis for every pass (`replay.
run_replay`'s own `detect` closure), so the field only ever carries the
integer sum this session computed.

Floats are written with `repr()`, which round-trips exactly in CPython --
a `:.3f` would hide precisely the sub-display-quantum drift a refactor of
the association/prediction arithmetic is most likely to introduce.
"""

from __future__ import annotations

from pathlib import Path
from typing import Optional

from cv_service.tracking.session import FrameOutcome, TrackedBox

# One dump file per scenario x mode, named so a failure's file path names
# both halves of the run that produced it with no lookup table.
_FILE_SUFFIX = ".txt"


def golden_path(directory: Path, scenario: str, mode: str) -> Path:
    """`<directory>/<scenario>.<mode>.txt` -- the one dump for this run."""
    return directory / f"{scenario}.{mode}{_FILE_SUFFIX}"


def _f(value: float) -> str:
    """A float that reads back exactly -- see the module docstring."""
    return repr(float(value))


def _box_fields(prefix: str, box: object) -> str:
    return (
        f"{prefix}x={_f(getattr(box, 'x'))} {prefix}y={_f(getattr(box, 'y'))} "
        f"{prefix}w={_f(getattr(box, 'width'))} {prefix}h={_f(getattr(box, 'height'))}"
    )


def _track_text(tracked: TrackedBox) -> str:
    """This frame's own view of the box's track, or `-` for untracked.

    Every field here is read at dump time, i.e. inside the observer call
    immediately after `process()` returned -- see the module docstring on
    why reading them later would report the LAST frame's values for every
    frame the track ever appeared in.
    """
    track = tracked.track
    if track is None:
        return "track=-"
    return (
        f"track=#{track.track_id} state={track.state} source={track.source} "
        f"age={track.age_frames} hits={track.hits} misses={track.misses} "
        f"elected={track.elected_label!r} raw_label={track.label!r} "
        f"vx={_f(track.velocity_x)} vy={_f(track.velocity_y)} "
        f"reupdated={int(track.reupdated)} "
        f"first_seen={_f(track.first_seen)} last_seen={_f(track.last_seen)} "
        f"last_confirmed={_f(track.last_confirmed)} "
        f"has_descriptor={int(track.descriptor is not None)} "
        f"history_len={len(track.history)} "
        + _box_fields("t", track.box)
    )


def outcome_lines(frame_index: int, outcome: FrameOutcome) -> list[str]:
    """One `frame` line plus one `box` line per emitted box.

    `boxes=echo` is the `FrameOutcome.boxes is None` degradation (no model
    resolved) -- spelled as its own token rather than as `0` so the dump
    never conflates "this frame produced nothing" with "this frame was not
    tracked at all".
    """
    header = (
        f"frame {frame_index:04d} "
        f"ran={int(outcome.detector_ran)} reason={outcome.detector_reason} "
        f"engine={outcome.engine_id!r} locked={outcome.locked_track_id} "
        f"motion_engine={outcome.motion_engine_id!r} roi={int(outcome.detector_roi)} "
        f"inference_ms={outcome.inference_millis} "
        f"level={outcome.capability_level_served} "
        f"level_reason={outcome.capability_level_reason!r} "
        f"reupdate_ms={outcome.reupdate_millis} "
        f"reupdated_tracks={outcome.reupdated_tracks} "
        f"lag_ms={outcome.detection_lag_millis} "
    )
    if outcome.boxes is None:
        return [header + "boxes=echo"]

    lines = [header + f"boxes={len(outcome.boxes)}"]
    for index, tracked in enumerate(outcome.boxes):
        lines.append(
            f"  box {index:02d} label={tracked.label!r} conf={_f(tracked.confidence)} "
            + _box_fields("", tracked.box)
            + f" idconf={_f(tracked.identity_confidence)} dormant={tracked.dormant_millis} "
            + _track_text(tracked)
        )
    return lines


class GoldenRecorder:
    """`run_replay(observer=...)`'s sink: accumulates this run's dump text."""

    def __init__(self) -> None:
        self._lines: list[str] = []

    def __call__(self, frame_index: int, outcome: FrameOutcome) -> None:
        self._lines.extend(outcome_lines(frame_index, outcome))

    def text(self) -> str:
        """The dump, newline-terminated so the file ends cleanly."""
        return "\n".join(self._lines) + "\n"


def write(directory: Path, scenario: str, mode: str, text: str) -> Path:
    """Persist one run's dump, creating `directory` if needed."""
    directory.mkdir(parents=True, exist_ok=True)
    path = golden_path(directory, scenario, mode)
    path.write_text(text, encoding="utf-8")
    return path


def read(directory: Path, scenario: str, mode: str) -> Optional[str]:
    """The recorded dump for this run, or `None` when none exists yet."""
    path = golden_path(directory, scenario, mode)
    if not path.is_file():
        return None
    return path.read_text(encoding="utf-8")
