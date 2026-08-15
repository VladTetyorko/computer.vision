"""`benchmarks.driver` -- Blocker 1 (real detections, never ground truth)
and the structural pixel-off guarantees, on a tiny in-memory fixture.

No file IO: `Mot17Sequence` is built directly, so this suite never depends
on `cv-service/benchmarks/data/mot17/` being present. `cost`+L1 needs no
`numpy`/`cv2` (`cv_service/tracking/assign.py` is pure stdlib, `registry.py`'s
own `_ASSOCIATOR_MIN_LEVEL` lists `cost` at L1) -- this suite therefore
never needs `pytest.importorskip`, unlike `tests/trackeval/test_replay.py`.
"""

from __future__ import annotations

import pytest

from benchmarks.driver import CompositionConfig, Mot17Detection, build_settings, run_mot17_replay
from benchmarks.mot17 import DetRow, GtRow, Mot17Sequence, SeqInfo
from cv_service.config import Settings


def _fixture_sequence() -> Mot17Sequence:
    """Two frames, one object. Ground truth sits at pixel (100, 100);
    det.txt -- the ONLY thing the session may ever see -- sits at pixel
    (500, 500), deliberately far away with no overlap. Anything the session
    emits near (500, 500) proves it came from det.txt; anything near
    (100, 100) would prove ground truth leaked in."""
    info = SeqInfo(name="TEST-00-FRCNN", frame_rate=10.0, seq_length=2, width=1000, height=1000)
    gt_rows = (
        GtRow(frame=1, track_id=1, x=100.0, y=100.0, width=50.0, height=50.0, ignore=False, class_id=1, visibility=1.0),
        GtRow(frame=2, track_id=1, x=100.0, y=100.0, width=50.0, height=50.0, ignore=False, class_id=1, visibility=1.0),
    )
    det_rows = (
        DetRow(frame=1, x=500.0, y=500.0, width=50.0, height=50.0, confidence=0.9),
        DetRow(frame=2, x=500.0, y=500.0, width=50.0, height=50.0, confidence=0.9),
    )
    return Mot17Sequence(
        scene="00",
        detector="FRCNN",
        name="TEST-00-FRCNN",
        gt_source="TEST-00-FRCNN",
        info=info,
        gt_rows=gt_rows,
        det_rows=det_rows,
    )


def _config(**overrides) -> CompositionConfig:
    base = dict(engine_id="cost", capability_level=1, reupdate_enabled=False, detection_threshold=0.0)
    base.update(overrides)
    return CompositionConfig(**base)


def test_emitted_boxes_come_from_det_txt_not_gt_txt() -> None:
    sequence = _fixture_sequence()
    result, stats = run_mot17_replay(sequence, _config())
    assert len(result.outcomes) == 2
    first = result.outcomes[0]
    assert first.boxes, "expected at least one emitted box on the first frame"
    box = first.boxes[0].box
    # det.txt's own position (500/1000 = 0.5), never gt.txt's (100/1000 = 0.1).
    assert box.x == pytest.approx(0.5, abs=0.01)
    assert box.x != pytest.approx(0.1, abs=0.01)


def test_dets_per_frame_reflects_what_actually_reached_the_session() -> None:
    sequence = _fixture_sequence()
    result, stats = run_mot17_replay(sequence, _config())
    assert stats.frame_count == 2
    assert stats.dets_retained_total == 2  # one detection per frame, nothing filtered
    assert stats.dets_per_frame == pytest.approx(1.0)


def test_association_fps_is_positive_and_process_wall_millis_recorded() -> None:
    sequence = _fixture_sequence()
    result, stats = run_mot17_replay(sequence, _config())
    assert stats.association_fps > 0.0
    assert stats.mean_process_wall_millis >= 0.0


def test_ignored_gt_never_opens_a_scored_window() -> None:
    """An ignore-flagged object must never appear in scored_gt_ids, even
    though it exists in gt_rows -- the scorer-side half of Blocker 2,
    exercised here through the real driver rather than ignore_matching in
    isolation (see test_ignore_matching.py for that)."""
    sequence = _fixture_sequence()
    ignored_row = GtRow(
        frame=1, track_id=99, x=10.0, y=10.0, width=5.0, height=5.0, ignore=True, class_id=8, visibility=1.0
    )
    sequence = Mot17Sequence(
        scene=sequence.scene,
        detector=sequence.detector,
        name=sequence.name,
        gt_source=sequence.gt_source,
        info=sequence.info,
        gt_rows=sequence.gt_rows + (ignored_row,),
        det_rows=sequence.det_rows,
    )
    result, _stats = run_mot17_replay(sequence, _config())
    assert 99 not in result.scored_gt_ids
    assert 1 in result.scored_gt_ids


def test_build_settings_forces_pixel_dependent_engines_off() -> None:
    base = Settings.from_env()
    resolved = build_settings(_config(capability_level=3), base=base)
    assert resolved.track_motion_engine == "off"
    assert resolved.track_appearance_engine == "off"
    assert resolved.track_roi_enabled is False
    assert resolved.track_capability_level == 3


def test_build_settings_oru_off_forces_zero_gap() -> None:
    base = Settings.from_env()
    off = build_settings(_config(reupdate_enabled=False), base=base)
    assert off.track_reupdate_max_gap_millis == 0


def test_build_settings_oru_on_keeps_the_deployment_default() -> None:
    from dataclasses import replace

    base = replace(Settings.from_env(), track_reupdate_max_gap_millis=12345)
    on = build_settings(_config(reupdate_enabled=True), base=base)
    assert on.track_reupdate_max_gap_millis == 12345


def test_mot17_detection_box_matches_its_flat_fields() -> None:
    detection = Mot17Detection(label="pedestrian", confidence=0.9, x=0.1, y=0.2, width=0.05, height=0.05)
    assert detection.box.x == detection.x
    assert detection.box.width == detection.width
