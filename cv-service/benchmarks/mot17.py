"""MOT17 loader: `seqinfo.ini` / `gt.txt` / `det.txt` -> `Mot17Sequence`.

`docs/conclusions/TRACKING-BENCHMARKS.md` §5.3 (format), the survey this
package implements. `cv-service/benchmarks/data/mot17/` holds MOT17 TRAIN
annotations only (no images -- see `benchmarks/__init__.py`'s own scope
note), seven scenes (`02 04 05 09 10 11 13`) x three detectors
(`DPM FRCNN SDP`), integrity-checked against the published MOT17 spec
(`seqLength`/`imWidth`/`imHeight`/`frameRate` per sequence) before this
package was written.

**The one quirk every caller of this module must go through `load_sequence`
(or `gt_source_dir`) to get right, not rediscover by hand:** the mirror
this data came from deduplicated `gt.txt`/`seqinfo.ini` into the `-FRCNN`
directory only, because ground truth is a property of the SCENE, not the
detector -- three detector variants of the same scene share one true
answer. `MOT17-13-DPM/` therefore has a `det/det.txt` but no `gt/` and no
`seqinfo.ini` at all; reading either from `MOT17-13-DPM/` directly raises
`FileNotFoundError` on purpose (see `fetch_mot17.sh`'s own note on this,
and its docstring for the exact behaviour of the mirror this data was
originally fetched from). `gt_source_dir`/`load_sequence` below are the
ONE place this redirection happens.

**Coordinate normalization is per-sequence, never a shared constant.**
MOT17's seven scenes are not all the same resolution (1920x1080 is common
but not universal), so `normalize_box` always takes the CALLING sequence's
own `imWidth`/`imHeight` -- there is no module-level `FRAME_WIDTH` the way
`tools/trackeval/sequences.py` has for its synthetic scenarios, because one
would silently corrupt every sequence that isn't 1920x1080.

**Timestamps are per-sequence too, for the same reason.** `frameRate`
genuinely varies across these seven scenes (14/25/30 fps) -- assuming 30
uniformly would corrupt every downstream velocity and ORU-gap computation
that reads `frame_timestamp_millis`'s output, which is why that function
takes `frame_rate` as an explicit argument rather than reading a constant.

Pure stdlib: `configparser` (`seqinfo.ini`) and plain CSV-shaped text
parsing (`gt.txt`/`det.txt`) only -- no `numpy`/`cv2` anywhere in this
module, matching `tools/trackeval/sequences.py`'s own "lazy numpy, only
where actually rendering pixels" discipline, except this module never
renders anything at all (there is nothing to render -- no images).
"""

from __future__ import annotations

import configparser
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from cv_service.tracking.engines.base import Box

# The seven MOT17 train scenes and three detector variants this data
# holds -- see the module docstring. Not the full MOT17 roster (train has
# no scene 01/03/06/07/08/12/14; those belong to MOT17's TEST split, which
# ships no ground truth at all and is out of scope for an accuracy
# benchmark by definition).
SCENES: tuple[str, ...] = ("02", "04", "05", "09", "10", "11", "13")
DETECTORS: tuple[str, ...] = ("DPM", "FRCNN", "SDP")

# The one detector variant's directory that actually carries gt.txt/seqinfo.ini
# -- see the module docstring's "one quirk" section.
GT_HOST_DETECTOR = "FRCNN"

# MOT17 gt.txt: frame,id,x,y,w,h,flag,class,visibility (9 columns, fixed).
_GT_COLUMN_COUNT = 9
# MOT17 det.txt: frame,-1,x,y,w,h,conf[,x3d,y3d,z3d]. FRCNN/SDP ship 7
# columns; DPM ships 10 (three trailing, always-"-1" 3D columns this
# benchmark never reads) -- a minimum, not an exact count, so both shapes
# parse without treating the well-known DPM variant as malformed.
_DET_MIN_COLUMN_COUNT = 7


def sequence_name(scene: str, detector: str) -> str:
    """`MOT17-<scene>-<detector>`, e.g. `MOT17-09-FRCNN` -- the on-disk
    directory naming convention every path in this module builds from."""
    return f"MOT17-{scene}-{detector}"


def _validate_scene(scene: str) -> None:
    if scene not in SCENES:
        raise ValueError(f"unknown MOT17 scene {scene!r}; expected one of {SCENES}")


def _validate_detector(detector: str) -> None:
    if detector not in DETECTORS:
        raise ValueError(f"unknown MOT17 detector {detector!r}; expected one of {DETECTORS}")


def sequence_dir(data_dir: Path, scene: str, detector: str) -> Path:
    """Where `det/det.txt` for `(scene, detector)` actually lives -- every
    one of the 21 on-disk directories has this."""
    _validate_scene(scene)
    _validate_detector(detector)
    return data_dir / sequence_name(scene, detector)


def gt_source_dir(data_dir: Path, scene: str, detector: str) -> Path:
    """Where `gt/gt.txt` and `seqinfo.ini` for `(scene, detector)` actually
    live -- ALWAYS the `-FRCNN` sibling, regardless of `detector`. See the
    module docstring's "one quirk" section: this is the one function (with
    `load_sequence`, which calls it) every caller must go through instead
    of guessing `sequence_dir` also holds ground truth.
    """
    _validate_scene(scene)
    return data_dir / sequence_name(scene, GT_HOST_DETECTOR)


@dataclass(frozen=True)
class SeqInfo:
    """`seqinfo.ini`'s `[Sequence]` section, the fields this benchmark uses."""

    name: str
    frame_rate: float
    seq_length: int
    width: int
    height: int


@dataclass(frozen=True)
class GtRow:
    """One `gt.txt` row, columns intact and UNFILTERED -- `ignore` is
    carried, not yet acted on; `ignore_matching.py` is what turns this into
    scoring/detection-filtering decisions, this module only parses.
    """

    frame: int  # 1-based, MOT convention -- see `frame_timestamp_millis`
    track_id: int
    x: float  # pixel, top-left origin
    y: float
    width: float
    height: float
    ignore: bool  # True <=> the 7th ("consider") column is 0 -- Blocker 2
    class_id: int
    visibility: float  # 0..1, fraction of the box actually visible


@dataclass(frozen=True)
class DetRow:
    """One `det.txt` row -- a real detector's real output, pixel
    coordinates. No `id` field (MOT det.txt always carries `-1` there:
    detections are unidentified by construction, association is the
    tracker's job, not the detector's)."""

    frame: int  # 1-based, MOT convention
    x: float
    y: float
    width: float
    height: float
    confidence: float


@dataclass(frozen=True)
class Mot17Sequence:
    """One (scene, detector) composition's raw, loaded data -- both
    ignored and considered GT rows, every det.txt row, unfiltered by
    confidence. Filtering (ignore regions, confidence threshold) is a
    RUN-time decision, not a load-time one, so this dataclass stays a
    faithful, complete parse of what is actually on disk."""

    scene: str
    detector: str
    name: str  # MOT17-<scene>-<detector>
    gt_source: str  # the directory actually read for gt/seqinfo -- may differ from `name`
    info: SeqInfo
    gt_rows: tuple[GtRow, ...]
    det_rows: tuple[DetRow, ...]


def load_seqinfo(path: Path) -> SeqInfo:
    """Parse `seqinfo.ini`. Raises `FileNotFoundError` if `path` does not
    exist (e.g. a caller reached into a non-FRCNN directory directly,
    skipping `gt_source_dir` -- see the module docstring), `ValueError` if
    the `[Sequence]` section is missing a field this benchmark needs.
    """
    if not path.is_file():
        raise FileNotFoundError(
            f"seqinfo.ini not found at {path} -- gt.txt/seqinfo.ini live ONLY in the "
            f"-{GT_HOST_DETECTOR} sibling directory for every scene (see mot17.py's "
            f"module docstring); did you mean to call gt_source_dir()/load_sequence() "
            f"instead of pointing directly at a DPM/SDP directory?"
        )
    parser = configparser.ConfigParser()
    parser.read(path)
    if not parser.has_section("Sequence"):
        raise ValueError(f"{path}: missing [Sequence] section")
    section = parser["Sequence"]
    try:
        return SeqInfo(
            name=section["name"],
            frame_rate=float(section["frameRate"]),
            seq_length=int(section["seqLength"]),
            width=int(section["imWidth"]),
            height=int(section["imHeight"]),
        )
    except KeyError as exc:
        raise ValueError(f"{path}: missing required seqinfo.ini key {exc}") from exc
    except ValueError as exc:
        raise ValueError(f"{path}: malformed seqinfo.ini value: {exc}") from exc


def load_gt(path: Path) -> tuple[GtRow, ...]:
    """Parse `gt.txt`. Raises `FileNotFoundError` if `path` does not exist,
    `ValueError` on any row that does not have exactly `_GT_COLUMN_COUNT`
    comma-separated numeric fields -- a loader that silently skipped or
    coerced a malformed row would corrupt a benchmark meant to be an
    adversary, not a guess.
    """
    if not path.is_file():
        raise FileNotFoundError(f"gt.txt not found at {path}")
    rows: list[GtRow] = []
    with path.open("r", encoding="ascii") as handle:
        for line_number, raw_line in enumerate(handle, start=1):
            line = raw_line.strip()
            if not line:
                continue
            fields = line.split(",")
            if len(fields) != _GT_COLUMN_COUNT:
                raise ValueError(
                    f"{path}:{line_number}: expected {_GT_COLUMN_COUNT} comma-separated "
                    f"fields (frame,id,x,y,w,h,flag,class,visibility), got {len(fields)}: {line!r}"
                )
            try:
                rows.append(
                    GtRow(
                        frame=int(fields[0]),
                        track_id=int(fields[1]),
                        x=float(fields[2]),
                        y=float(fields[3]),
                        width=float(fields[4]),
                        height=float(fields[5]),
                        ignore=(int(fields[6]) == 0),
                        class_id=int(fields[7]),
                        visibility=float(fields[8]),
                    )
                )
            except ValueError as exc:
                raise ValueError(f"{path}:{line_number}: malformed gt.txt row {line!r}: {exc}") from exc
    return tuple(rows)


def load_det(path: Path) -> tuple[DetRow, ...]:
    """Parse `det.txt`. Raises `FileNotFoundError` if `path` does not
    exist, `ValueError` on any row with fewer than `_DET_MIN_COLUMN_COUNT`
    fields or a non-numeric core field -- same "raise, never guess" contract
    as `load_gt`. Trailing columns beyond confidence (DPM's three `-1` 3D
    placeholders) are accepted and ignored, not treated as malformed.
    """
    if not path.is_file():
        raise FileNotFoundError(f"det.txt not found at {path}")
    rows: list[DetRow] = []
    with path.open("r", encoding="ascii") as handle:
        for line_number, raw_line in enumerate(handle, start=1):
            line = raw_line.strip()
            if not line:
                continue
            fields = line.split(",")
            if len(fields) < _DET_MIN_COLUMN_COUNT:
                raise ValueError(
                    f"{path}:{line_number}: expected at least {_DET_MIN_COLUMN_COUNT} "
                    f"comma-separated fields (frame,-1,x,y,w,h,conf,...), got "
                    f"{len(fields)}: {line!r}"
                )
            try:
                rows.append(
                    DetRow(
                        frame=int(fields[0]),
                        x=float(fields[2]),
                        y=float(fields[3]),
                        width=float(fields[4]),
                        height=float(fields[5]),
                        confidence=float(fields[6]),
                    )
                )
            except ValueError as exc:
                raise ValueError(f"{path}:{line_number}: malformed det.txt row {line!r}: {exc}") from exc
    return tuple(rows)


def load_sequence(data_dir: Path, scene: str, detector: str) -> Mot17Sequence:
    """Load one full `(scene, detector)` composition: `seqinfo.ini`/`gt.txt`
    from the `-FRCNN` sibling (`gt_source_dir`), `det.txt` from `(scene,
    detector)`'s own directory (`sequence_dir`) -- the one function that
    gets the quirk right so no other module in this package has to.
    """
    _validate_scene(scene)
    _validate_detector(detector)
    gt_dir = gt_source_dir(data_dir, scene, detector)
    info = load_seqinfo(gt_dir / "seqinfo.ini")
    gt_rows = load_gt(gt_dir / "gt" / "gt.txt")
    det_rows = load_det(sequence_dir(data_dir, scene, detector) / "det" / "det.txt")
    return Mot17Sequence(
        scene=scene,
        detector=detector,
        name=sequence_name(scene, detector),
        gt_source=sequence_name(scene, GT_HOST_DETECTOR),
        info=info,
        gt_rows=gt_rows,
        det_rows=det_rows,
    )


def normalize_box(x: float, y: float, width: float, height: float, image_width: int, image_height: int) -> Box:
    """Pixel box -> `Box`, normalized `[0, 1]`, top-left origin -- the units
    `cv_service.tracking` speaks everywhere (`Box`'s own docstring). Divides
    by THIS call's own `image_width`/`image_height`; never a module
    constant -- see the module docstring on why that would silently corrupt
    any sequence that isn't the same resolution as whichever one a constant
    happened to be measured from.

    Not clamped to `[0, 1]`: `Box` itself does not enforce that range (only
    its `.valid` property reasons about overlap with the unit square), and
    a MOT annotation box that extends slightly past a frame edge is real
    data, not an error -- clamping it here would silently discard exactly
    the geometry a coordinate-normalization test needs to round-trip.
    """
    return Box(
        x=x / image_width,
        y=y / image_height,
        width=width / image_width,
        height=height / image_height,
    )


def frame_timestamp_millis(frame_index: int, frame_rate: float) -> float:
    """0-based `frame_index` -> millis at THIS sequence's own `frame_rate`
    -- mirrors `tools/trackeval/replay.run_replay`'s own
    `frame.index * (1000 / sequence.fps)` convention exactly, so
    `StreamTrackingSession`'s cadence/threshold math (all of it keyed off
    `now_millis`, never off wall-clock replay speed) runs correctly no
    matter how fast this process actually executes.

    Takes `frame_rate` as an explicit argument, never a shared constant --
    MOT17's seven scenes are 14/25/30 fps, not uniformly 30; see the module
    docstring.
    """
    return frame_index * (1000.0 / frame_rate)


def gt_rows_by_frame(sequence: Mot17Sequence) -> "dict[int, tuple[GtRow, ...]]":
    """`{1-based frame number: every gt.txt row on it}` -- grouping GT rows
    by frame is a pure re-shape every consumer (scoring, ignore-region
    lookup) needs, so it lives here once instead of once per caller."""
    grouped: "dict[int, list[GtRow]]" = {}
    for row in sequence.gt_rows:
        grouped.setdefault(row.frame, []).append(row)
    return {frame: tuple(rows) for frame, rows in grouped.items()}


def det_rows_by_frame(sequence: Mot17Sequence) -> "dict[int, tuple[DetRow, ...]]":
    """`{1-based frame number: every det.txt row on it}` -- same grouping
    reasoning as `gt_rows_by_frame`, for detections instead of ground
    truth."""
    grouped: "dict[int, list[DetRow]]" = {}
    for row in sequence.det_rows:
        grouped.setdefault(row.frame, []).append(row)
    return {frame: tuple(rows) for frame, rows in grouped.items()}


def frame_numbers(info: SeqInfo) -> range:
    """The full 1-based frame range `seqinfo.ini` declares (`1..seq_length`
    inclusive) -- the loop bound every driver over this sequence should
    use, rather than inferring it from whichever frames happen to have a
    `gt.txt`/`det.txt` row (a frame with zero detections is real, not
    missing data, and must still be replayed so `now_millis` stays
    continuous).
    """
    return range(1, info.seq_length + 1)
