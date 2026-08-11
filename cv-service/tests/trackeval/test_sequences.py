"""`tools.trackeval.sequences` -- synthetic ground truth, known by construction.

Needs `numpy` (the `cv` extra) to actually render a frame; scenario
functions lazily import it exactly once, inside `_render_frame`, so
importing the module itself never requires it (mirrors
`cv_service/tracking/`'s own lazy-import discipline for pixel code --
`pytest.importorskip` below is what makes THIS file skip rather than fail
on a checkout without the extra).
"""

from __future__ import annotations

import pytest

pytest.importorskip("numpy")

from tools.trackeval.sequences import SCENARIOS


@pytest.mark.parametrize("name", sorted(SCENARIOS))
def test_every_scenario_generates_without_raising(name: str) -> None:
    sequence = SCENARIOS[name](seed=0)
    assert sequence.frames
    assert sequence.name == name
    assert sequence.fps > 0
    first_frame = sequence.frames[0].image
    assert first_frame.shape == (sequence.height, sequence.width, 3)


@pytest.mark.parametrize("name", sorted(SCENARIOS))
def test_determinism_same_seed_same_sequence(name: str) -> None:
    first = SCENARIOS[name](seed=7)
    second = SCENARIOS[name](seed=7)
    assert len(first.frames) == len(second.frames)
    for frame_a, frame_b in zip(first.frames, second.frames):
        assert frame_a.ground_truth == frame_b.ground_truth
        assert (frame_a.image == frame_b.image).all()


def test_seed_changes_placement_jitter() -> None:
    first = SCENARIOS["linear"](seed=1)
    second = SCENARIOS["linear"](seed=2)
    first_y = [obj.box.y for obj in first.frames[0].ground_truth]
    second_y = [obj.box.y for obj in second.frames[0].ground_truth]
    assert first_y != second_y


@pytest.mark.parametrize("name", sorted(SCENARIOS))
def test_every_declared_object_is_visible_at_least_once(name: str) -> None:
    """A generator that declares a `gt_id` which is never actually seen is a
    bug -- there would be nothing for a detector, real or synthetic, to ever
    report, and the object could never contribute to any metric."""
    sequence = SCENARIOS[name](seed=0)
    seen_visible: set[int] = set()
    for frame in sequence.frames:
        for obj in frame.ground_truth:
            if obj.visible:
                seen_visible.add(obj.gt_id)
    assert sequence.gt_ids == seen_visible
    assert sequence.primary_gt_id in seen_visible


@pytest.mark.parametrize("name", sorted(SCENARIOS))
def test_primary_gt_id_is_visible_on_the_first_frame(name: str) -> None:
    """`replay.py`'s FOLLOW lock clicks the primary object's box on the
    first frame it is visible -- every scenario places it there on frame 0
    so a FOLLOW replay always starts locked rather than waiting."""
    sequence = SCENARIOS[name](seed=0)
    first_frame_ids = {obj.gt_id for obj in sequence.frames[0].ground_truth if obj.visible}
    assert sequence.primary_gt_id in first_frame_ids


def test_occlusion_has_an_internal_visibility_gap_for_the_primary_object() -> None:
    sequence = SCENARIOS["occlusion"](seed=0)
    visibility = [
        any(obj.gt_id == sequence.primary_gt_id and obj.visible for obj in frame.ground_truth)
        for frame in sequence.frames
    ]
    third = max(1, len(visibility) // 3)
    assert True in visibility[:third], "primary object should start out visible"
    assert False in visibility, "occlusion scenario must actually occlude the object"
    assert True in visibility[-third:], "primary object should re-emerge before the clip ends"


def test_crossing_objects_actually_overlap_at_some_frame() -> None:
    sequence = SCENARIOS["crossing"](seed=0)
    best_iou = 0.0
    for frame in sequence.frames:
        boxes = [obj.box for obj in frame.ground_truth]
        assert len(boxes) == 2
        best_iou = max(best_iou, boxes[0].iou(boxes[1]))
    assert best_iou > 0.3, "the two objects must genuinely overlap, not just pass near each other"


def test_pan_scene_has_a_large_frame_to_frame_shift() -> None:
    """The whole point of `pan` -- consecutive frames must NOT look like a
    small object nudge, they must look like the camera moved."""
    sequence = SCENARIOS["pan"](seed=0)
    max_shift = 0.0
    previous_x: dict[int, float] = {}
    for frame in sequence.frames:
        for obj in frame.ground_truth:
            if not obj.visible:
                continue
            if obj.gt_id in previous_x:
                max_shift = max(max_shift, abs(obj.box.x - previous_x[obj.gt_id]))
            previous_x[obj.gt_id] = obj.box.x
    assert max_shift > 0.02, "pan must produce a real camera-induced jump, not sub-pixel noise"


def test_dropout_ground_truth_is_always_visible() -> None:
    """`dropout`'s failure mode lives entirely in `replay.py`'s detector
    noise -- the ground truth itself must never claim the object is hidden,
    or the scenario would silently turn into a second `occlusion`."""
    sequence = SCENARIOS["dropout"](seed=0)
    assert all(obj.visible for frame in sequence.frames for obj in frame.ground_truth)
