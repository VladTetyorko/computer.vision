"""The end-to-end claim for ego-motion compensation, as an outcome not a unit.

`tests/tracking/test_flow_gmc.py` proves the compensator estimates the right
transform, and `tests/tracking/test_pose_gmc.py` proves the same for
attitude. Neither would have caught what this catches: for a while the
estimator was accurate and the SESSION applied one frame's worth of it to a
prediction spanning thirty, so the feature was a no-op precisely when it
mattered and every unit test stayed green.

So this test asserts the only thing an operator would recognise -- WHICH
OBJECT the locked id ends up on -- and it asserts it in both directions,
because "compensation on works" is only half a claim. If the uncompensated
arm ever starts passing too, the scenario has stopped discriminating and this
test is lying rather than protecting.
"""

from __future__ import annotations

import dataclasses

import pytest

from cv_service.config import Settings
from cv_service.tracking.params import MODE_FOLLOW
from tools.trackeval import metrics, replay
from tools.trackeval.sequences import PAN_STEP_GAP_FRAMES, PAN_STEP_STATIC_FRAMES, pan_step

TARGET_GT_ID = 1
DISTRACTOR_GT_ID = 2
MATCH_IOU = 0.5
FIRST_FRAME_AFTER_THE_BAR = PAN_STEP_STATIC_FRAMES + PAN_STEP_GAP_FRAMES


def follow_counts(engine: str) -> dict[int, int]:
    """Frames after the bar clears on which the locked id sits on each object."""
    sequence = pan_step()
    settings = dataclasses.replace(Settings(), track_motion_engine=engine)
    result = replay.run_replay(sequence, mode=MODE_FOLLOW, settings=settings)
    counts = {TARGET_GT_ID: 0, DISTRACTOR_GT_ID: 0}
    for frame, outcome in zip(sequence.frames, result.outcomes):
        if frame.index < FIRST_FRAME_AFTER_THE_BAR:
            continue
        for _track_id, box in metrics._tracked_boxes(outcome):
            best_iou, best_gt = 0.0, None
            for obj in frame.ground_truth:
                if not obj.visible:
                    continue
                overlap = box.iou(obj.box)
                if overlap > best_iou:
                    best_iou, best_gt = overlap, obj.gt_id
            if best_gt is not None and best_iou >= MATCH_IOU:
                counts[best_gt] += 1
    return counts


@pytest.fixture(scope="module")
def compensated() -> dict[int, int]:
    return follow_counts("flow")


@pytest.fixture(scope="module")
def uncompensated() -> dict[int, int]:
    return follow_counts("off")


def test_without_compensation_the_locked_id_follows_the_wrong_object(uncompensated):
    # The failure this whole wave exists to remove, and the reason the
    # scenario has a distractor at all: the stale prediction points where the
    # target USED to be, the distractor is now standing there, and the
    # re-anchor takes it. The operator is not told -- the box is confident
    # and it is on the wrong vehicle.
    assert uncompensated[DISTRACTOR_GT_ID] > uncompensated[TARGET_GT_ID]
    assert uncompensated[TARGET_GT_ID] == 0


def test_with_compensation_the_locked_id_follows_the_target(compensated):
    assert compensated[TARGET_GT_ID] > 0
    assert compensated[DISTRACTOR_GT_ID] == 0


def test_compensation_recovers_the_target_under_its_own_id(compensated):
    sequence = pan_step()
    settings = dataclasses.replace(Settings(), track_motion_engine="flow")
    scored = metrics.compute(replay.run_replay(sequence, mode=MODE_FOLLOW, settings=settings))
    assert scored.recovery_rate == pytest.approx(1.0)
    assert scored.idsw == 0
