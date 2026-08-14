"""`benchmarks.mot17` -- the loader, on tiny hand-built fixtures.

Deliberately independent of `cv-service/benchmarks/data/mot17/` (gitignored,
not guaranteed present in every environment this suite runs in) -- every
test here writes its own minimal `seqinfo.ini`/`gt.txt`/`det.txt` into
`tmp_path`, matching the real MOT17 on-disk shape exactly (verified against
the real downloaded data while this module was written).
"""

from __future__ import annotations

from pathlib import Path

import pytest

from benchmarks.mot17 import (
    GT_HOST_DETECTOR,
    frame_numbers,
    frame_timestamp_millis,
    gt_source_dir,
    load_det,
    load_gt,
    load_seqinfo,
    load_sequence,
    normalize_box,
    sequence_dir,
)

_SEQINFO = """[Sequence]
name={name}
imDir=img1
frameRate={frame_rate}
seqLength={seq_length}
imWidth={width}
imHeight={height}
imExt=.jpg
"""


def _write_seqinfo(path: Path, *, name: str, frame_rate: int, seq_length: int, width: int, height: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        _SEQINFO.format(name=name, frame_rate=frame_rate, seq_length=seq_length, width=width, height=height)
    )


def _build_scene(data_dir: Path, scene: str, *, frame_rate: int = 30, width: int = 1920, height: int = 1080) -> None:
    """One minimal, real-shaped (scene, FRCNN) directory: 2 frames, one
    considered pedestrian (flag=1) and one ignore-region row (flag=0) per
    frame, plus a DPM sibling directory that carries ONLY det.txt -- the
    exact quirk `load_sequence`/`gt_source_dir` exist to route around."""
    frcnn_dir = data_dir / f"MOT17-{scene}-{GT_HOST_DETECTOR}"
    _write_seqinfo(
        frcnn_dir / "seqinfo.ini",
        name=f"MOT17-{scene}-{GT_HOST_DETECTOR}",
        frame_rate=frame_rate,
        seq_length=2,
        width=width,
        height=height,
    )
    (frcnn_dir / "gt").mkdir(parents=True, exist_ok=True)
    (frcnn_dir / "gt" / "gt.txt").write_text(
        "1,1,100,200,50,100,1,1,1.0\n"
        "1,2,900,800,40,40,0,8,0.3\n"  # ignore region (flag=0)
        "2,1,110,205,50,100,1,1,0.9\n"
    )
    (frcnn_dir / "det").mkdir(parents=True, exist_ok=True)
    (frcnn_dir / "det" / "det.txt").write_text(
        "1,-1,100,200,50,100,0.95\n"
        "2,-1,110,205,50,100,0.90\n"
    )

    dpm_dir = data_dir / f"MOT17-{scene}-DPM"
    (dpm_dir / "det").mkdir(parents=True, exist_ok=True)
    (dpm_dir / "det" / "det.txt").write_text(
        "1,-1,100.0,200.0,50.0,100.0,1.5,-1,-1,-1\n"  # DPM's real 10-column shape
    )


def test_gt_source_dir_always_points_at_frcnn(tmp_path: Path) -> None:
    assert gt_source_dir(tmp_path, "09", "DPM") == tmp_path / "MOT17-09-FRCNN"
    assert gt_source_dir(tmp_path, "09", "SDP") == tmp_path / "MOT17-09-FRCNN"
    assert gt_source_dir(tmp_path, "09", "FRCNN") == tmp_path / "MOT17-09-FRCNN"


def test_load_sequence_reads_dpm_det_but_frcnn_gt(tmp_path: Path) -> None:
    _build_scene(tmp_path, "09")
    sequence = load_sequence(tmp_path, "09", "DPM")
    assert sequence.name == "MOT17-09-DPM"
    assert sequence.gt_source == "MOT17-09-FRCNN"
    assert len(sequence.gt_rows) == 3  # read from the FRCNN sibling
    assert len(sequence.det_rows) == 1  # read from DPM's own directory
    # The DPM det.txt's own 10-column row parsed correctly (extra trailing
    # -1,-1,-1 accepted, not treated as malformed).
    assert sequence.det_rows[0].confidence == pytest.approx(1.5)


def test_load_seqinfo_missing_file_raises_with_a_helpful_hint(tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError, match="gt_source_dir"):
        load_seqinfo(tmp_path / "MOT17-09-DPM" / "seqinfo.ini")


def test_load_gt_raises_on_wrong_column_count(tmp_path: Path) -> None:
    bad = tmp_path / "gt.txt"
    bad.write_text("1,1,100,200,50,100,1,1\n")  # missing the visibility column
    with pytest.raises(ValueError, match="expected 9"):
        load_gt(bad)


def test_load_gt_raises_on_non_numeric_field(tmp_path: Path) -> None:
    bad = tmp_path / "gt.txt"
    bad.write_text("1,1,oops,200,50,100,1,1,1.0\n")
    with pytest.raises(ValueError, match="malformed gt.txt row"):
        load_gt(bad)


def test_load_det_raises_on_too_few_columns(tmp_path: Path) -> None:
    bad = tmp_path / "det.txt"
    bad.write_text("1,-1,100,200,50\n")  # only 5 fields, needs >= 7
    with pytest.raises(ValueError, match="expected at least 7"):
        load_det(bad)


def test_load_det_accepts_dpm_and_frcnn_shapes(tmp_path: Path) -> None:
    mixed = tmp_path / "det.txt"
    mixed.write_text(
        "1,-1,10,20,30,40,0.9\n"  # FRCNN/SDP: exactly 7 columns
        "2,-1,10,20,30,40,1.2,-1,-1,-1\n"  # DPM: 10 columns
    )
    rows = load_det(mixed)
    assert len(rows) == 2
    assert rows[0].confidence == pytest.approx(0.9)
    assert rows[1].confidence == pytest.approx(1.2)


def test_normalize_box_round_trips_pixel_coordinates() -> None:
    width, height = 1920, 1080
    px_x, px_y, px_w, px_h = 260.0, 450.0, 102.0, 262.0
    box = normalize_box(px_x, px_y, px_w, px_h, width, height)
    assert box.x * width == pytest.approx(px_x)
    assert box.y * height == pytest.approx(px_y)
    assert box.width * width == pytest.approx(px_w)
    assert box.height * height == pytest.approx(px_h)


def test_frame_timestamp_millis_uses_this_sequences_own_frame_rate() -> None:
    # 14 fps: frame 0 -> 0ms, frame 14 -> exactly 1000ms (one full second in),
    # never 30fps's 466.67ms for the same frame index.
    assert frame_timestamp_millis(0, frame_rate=14.0) == pytest.approx(0.0)
    assert frame_timestamp_millis(14, frame_rate=14.0) == pytest.approx(1000.0)
    assert frame_timestamp_millis(1, frame_rate=14.0) == pytest.approx(1000.0 / 14.0)
    # Same frame index, a 30fps sequence lands somewhere else entirely --
    # proof that assuming 30fps uniformly would corrupt this number.
    assert frame_timestamp_millis(14, frame_rate=30.0) != pytest.approx(1000.0)


def test_a_real_14fps_sequence_produces_14fps_timestamps(tmp_path: Path) -> None:
    """MOT17-13 is the 14fps outlier of the seven real sequences -- this
    fixture reproduces just its frameRate, not its content."""
    _build_scene(tmp_path, "13", frame_rate=14)
    sequence = load_sequence(tmp_path, "13", "FRCNN")
    assert sequence.info.frame_rate == pytest.approx(14.0)
    timestamps = [frame_timestamp_millis(n - 1, sequence.info.frame_rate) for n in frame_numbers(sequence.info)]
    # Frame 1 -> 0ms; frame 2 -> ~71.43ms (1000/14), never 33.33ms (1000/30).
    assert timestamps[0] == pytest.approx(0.0)
    assert timestamps[1] == pytest.approx(1000.0 / 14.0)
    assert sequence_dir(tmp_path, "13", "FRCNN").exists()
