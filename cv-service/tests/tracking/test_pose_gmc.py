"""`pose` ego-motion compensation: the projection, and every refusal path.

The sign conventions get their own tests because a sign error here is
invisible in isolation -- a transform that compensates backwards looks
exactly as plausible as one that compensates forwards, and it would make
association strictly worse than no compensation at all while every unit
looked healthy.
"""

from __future__ import annotations

import math

import pytest

from cv_service.tracking.engines.base import Box, CameraPose
from cv_service.tracking.engines.pose_gmc import ENGINE_ID, PoseMotionCompensator, create

HFOV = 62.0
VFOV = 36.0


def pose(**overrides) -> CameraPose:
    fields = {"hfov_degrees": HFOV, "vfov_degrees": VFOV}
    fields.update(overrides)
    return CameraPose(**fields)


@pytest.fixture()
def compensator() -> PoseMotionCompensator:
    engine = create()
    engine.estimate(None, pose())  # establish the reference pose
    return engine


def test_engine_id_matches_the_registry_name():
    assert create().engine_id == ENGINE_ID == "pose"


def test_a_pose_without_a_field_of_view_is_unavailable():
    # Without an FOV an attitude delta cannot become a pixel shift, and the
    # whole message is gated on it -- which is what makes an absent
    # CameraPose a normal state rather than an error.
    assert not create().available(CameraPose())
    assert not create().available(CameraPose(yaw_degrees=10.0))
    assert create().available(pose())


def test_the_first_pose_of_a_stream_compensates_nothing():
    assert create().estimate(None, pose()).identity


def test_yawing_right_moves_the_scene_left(compensator):
    warped = compensator.estimate(None, pose(yaw_degrees=3.0)).apply_box(Box(0.45, 0.45, 0.1, 0.1))
    assert warped.x < 0.45


def test_pitching_up_moves_the_scene_down(compensator):
    # Normalized y runs downward, so "down the image" is an increase.
    warped = compensator.estimate(None, pose(pitch_degrees=3.0)).apply_box(Box(0.45, 0.45, 0.1, 0.1))
    assert warped.y > 0.45


def test_the_shift_matches_the_first_order_projection(compensator):
    # du = -d_yaw / tan(hfov/2), halved for the [0,1] box convention.
    expected = math.radians(3.0) / math.tan(math.radians(HFOV) / 2.0) / 2.0
    assert compensator.estimate(None, pose(yaw_degrees=3.0)).c == pytest.approx(-expected)


def test_a_three_degree_yaw_moves_a_box_further_than_its_own_width(compensator):
    # The review's claim, pinned as a number: at 62 deg HFOV a 3 deg step --
    # one frame of a routine 30 deg/s slew at 10 fps -- shifts the image by
    # ~4.4% of frame width, so any box narrower than that has ZERO overlap
    # with itself and is reborn under a new id without compensation.
    box = Box(0.45, 0.48, 0.04, 0.04)
    warped = compensator.estimate(None, pose(yaw_degrees=3.0)).apply_box(box)
    assert box.iou(warped) == 0.0


def test_yaw_wraparound_is_the_short_way_round(compensator):
    engine = create()
    engine.estimate(None, pose(yaw_degrees=359.0))
    crossing = engine.estimate(None, pose(yaw_degrees=1.0))

    straight = create()
    straight.estimate(None, pose(yaw_degrees=0.0))
    reference = straight.estimate(None, pose(yaw_degrees=2.0))

    assert crossing.c == pytest.approx(reference.c)


def test_an_implausible_attitude_step_is_refused_rather_than_applied(compensator):
    # A 90 degree jump between two frames is a telemetry glitch or a
    # reconnect; compensating for it would fling every box off the frame.
    assert compensator.estimate(None, pose(yaw_degrees=90.0)).identity


def test_a_step_below_telemetry_noise_returns_identity_exactly(compensator):
    # Exactly IDENTITY, not merely close to it -- `Transform.identity` is an
    # exact comparison and everything downstream relies on that.
    assert compensator.estimate(None, pose(yaw_degrees=0.001)).identity


def test_a_stale_pose_restarts_the_reference_instead_of_stepping(compensator):
    engine = create()
    engine.estimate(None, pose(yaw_degrees=0.0, timestamp_millis=1_000))
    assert engine.estimate(None, pose(yaw_degrees=5.0, timestamp_millis=9_000)).identity


def test_losing_the_pose_restarts_the_reference(compensator):
    assert compensator.estimate(None, CameraPose()).identity
    # ...and the next known pose is a first pose again, not a step from before the gap.
    assert compensator.estimate(None, pose(yaw_degrees=30.0)).identity


def test_roll_rotates_about_the_frame_centre(compensator):
    rolled = compensator.estimate(None, pose(roll_degrees=10.0))
    centre = rolled.apply_point(0.5, 0.5)
    assert centre == pytest.approx((0.5, 0.5))


def test_roll_is_a_rotation_in_metric_space_and_not_a_shear(compensator):
    # The aspect correction's whole job. Normalized [0,1] coordinates are
    # anisotropic -- 0.1 of width and 0.1 of height are different physical
    # distances -- so "is it a rotation?" is only meaningful once the axes
    # are put back on the same scale. Without the correction this distance
    # changes and boxes deform instead of turning.
    rolled = compensator.estimate(None, pose(roll_degrees=10.0))
    aspect = math.tan(math.radians(HFOV) / 2.0) / math.tan(math.radians(VFOV) / 2.0)

    def metric(point):
        return (point[0] * aspect, point[1])

    left, right = (0.30, 0.40), (0.70, 0.65)
    before = math.dist(metric(left), metric(right))
    after = math.dist(metric(rolled.apply_point(*left)), metric(rolled.apply_point(*right)))
    assert after == pytest.approx(before)


def test_reset_drops_the_reference_pose(compensator):
    compensator.reset()
    assert compensator.estimate(None, pose(yaw_degrees=3.0)).identity


def test_the_module_needs_no_cv_extra():
    # Deliberately on the pure-stdlib side of the package rule: this is
    # trigonometry, not image processing, which is what gives a build with no
    # OpenCV an ego-motion compensator at all.
    import sys

    import cv_service.tracking.engines.pose_gmc as module

    assert not any(
        name in sys.modules and getattr(module, name, None) is not None
        for name in ("cv2", "numpy")
    )
    assert not hasattr(module, "cv2")
    assert not hasattr(module, "np")
